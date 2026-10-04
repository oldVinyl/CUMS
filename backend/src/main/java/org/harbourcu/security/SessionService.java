package org.harbourcu.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    public static final String AUDIT_ACTOR_ATTRIBUTE = "cums.authenticatedActor";
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(7);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public record Tokens(String access, String refresh, String csrf,
                         Instant accessExpiresAt, Instant refreshExpiresAt) {}
    public record SessionIdentity(UUID sessionId, UUID userId) {}

    private record StoredSession(UUID id, UUID userId, Instant refreshExpiresAt,
                                 Instant lastSeenAt, Instant revokedAt, String userStatus) {}

    public SessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Tokens create(UUID userId) {
        Boolean active = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')
            """, Boolean.class, userId);
        if (!Boolean.TRUE.equals(active)) {
            throw new IllegalArgumentException("User is not active");
        }
        Tokens tokens = freshTokens();
        jdbc.update("""
            INSERT INTO auth_sessions
                (user_id, access_token_hash, refresh_token_hash, csrf_token_hash,
                 access_expires_at, refresh_expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, userId, digest(tokens.access()), digest(tokens.refresh()), digest(tokens.csrf()),
            dbTime(tokens.accessExpiresAt()), dbTime(tokens.refreshExpiresAt()));
        return tokens;
    }

    @Transactional
    public Optional<Tokens> rotate(String refreshToken) {
        byte[] hash = tokenDigest(refreshToken);
        if (hash == null) {
            return Optional.empty();
        }
        List<StoredSession> current = jdbc.query("""
            SELECT s.id, s.user_id, s.refresh_expires_at, s.last_seen_at,
                   s.revoked_at, u.status AS user_status
            FROM auth_sessions s JOIN users u ON u.id = s.user_id
            WHERE s.refresh_token_hash = ? FOR UPDATE OF s
            """, (row, ignored) -> new StoredSession(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class), row.getTimestamp("refresh_expires_at").toInstant(),
                row.getTimestamp("last_seen_at").toInstant(),
                row.getTimestamp("revoked_at") == null ? null : row.getTimestamp("revoked_at").toInstant(),
                row.getString("user_status")), hash);
        if (current.isEmpty()) {
            List<UUID> consumed = jdbc.query("""
                SELECT session_id FROM consumed_refresh_tokens WHERE token_hash = ?
                """, (row, ignored) -> row.getObject("session_id", UUID.class), hash);
            if (!consumed.isEmpty()) {
                jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL",
                    consumed.getFirst());
            }
            return Optional.empty();
        }
        StoredSession session = current.getFirst();
        Instant now = Instant.now();
        if (session.revokedAt() != null || !"ACTIVE".equals(session.userStatus())
            || !session.refreshExpiresAt().isAfter(now)
            || !session.lastSeenAt().plus(IDLE_TIMEOUT).isAfter(now)) {
            jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL",
                session.id());
            return Optional.empty();
        }
        Tokens next = freshTokens();
        jdbc.update("""
            INSERT INTO consumed_refresh_tokens (token_hash, session_id, csrf_token_hash)
            SELECT ?, id, csrf_token_hash FROM auth_sessions WHERE id = ?
            """, hash, session.id());
        jdbc.update("""
            UPDATE auth_sessions
            SET access_token_hash = ?, refresh_token_hash = ?, csrf_token_hash = ?,
                access_expires_at = ?, refresh_expires_at = ?, last_seen_at = now()
            WHERE id = ?
            """, digest(next.access()), digest(next.refresh()), digest(next.csrf()),
            dbTime(next.accessExpiresAt()), dbTime(next.refreshExpiresAt()), session.id());
        return Optional.of(next);
    }

    @Transactional
    public Optional<SessionIdentity> resolveAccess(String accessToken) {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) attributes.getRequest().removeAttribute(AUDIT_ACTOR_ATTRIBUTE);
        byte[] hash = tokenDigest(accessToken);
        if (hash == null) {
            return Optional.empty();
        }
        List<SessionIdentity> rows = jdbc.query("""
            SELECT s.id, s.user_id FROM auth_sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.access_token_hash = ? AND s.revoked_at IS NULL
              AND s.access_expires_at > now() AND s.refresh_expires_at > now()
              AND s.last_seen_at > now() - interval '30 minutes'
              AND u.status = 'ACTIVE'
            """, (row, ignored) -> new SessionIdentity(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class)), hash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SessionIdentity identity = rows.getFirst();
        jdbc.update("UPDATE auth_sessions SET last_seen_at = now() WHERE id = ?", identity.sessionId());
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) attributes.getRequest().setAttribute(AUDIT_ACTOR_ATTRIBUTE, identity.userId());
        return Optional.of(identity);
    }

    public boolean csrfMatches(UUID sessionId, String csrfToken) {
        byte[] candidate = tokenDigest(csrfToken);
        if (candidate == null) {
            return false;
        }
        List<byte[]> stored = jdbc.query("""
            SELECT csrf_token_hash FROM auth_sessions WHERE id = ? AND revoked_at IS NULL
            """, (row, ignored) -> row.getBytes("csrf_token_hash"), sessionId);
        return !stored.isEmpty() && MessageDigest.isEqual(candidate, stored.getFirst());
    }

    public boolean csrfMatchesRefresh(String refreshToken, String csrfToken) {
        byte[] refreshHash = tokenDigest(refreshToken);
        byte[] csrfHash = tokenDigest(csrfToken);
        if (refreshHash == null || csrfHash == null) {
            return false;
        }
        List<byte[]> stored = jdbc.query("""
            SELECT csrf_token_hash FROM auth_sessions
            WHERE refresh_token_hash = ? AND revoked_at IS NULL
              AND refresh_expires_at > now()
            UNION ALL
            SELECT csrf_token_hash FROM consumed_refresh_tokens WHERE token_hash = ?
            """, (row, ignored) -> row.getBytes("csrf_token_hash"), refreshHash, refreshHash);
        return stored.stream().anyMatch(hash -> hash != null && MessageDigest.isEqual(csrfHash, hash));
    }

    @Transactional
    public void revoke(UUID sessionId) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL",
            sessionId);
    }

    @Transactional
    public void revokeAll(UUID userId) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL",
            userId);
    }

    private Tokens freshTokens() {
        Instant now = Instant.now();
        return new Tokens(newToken(), newToken(), newToken(),
            now.plus(ACCESS_LIFETIME), now.plus(REFRESH_LIFETIME));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] tokenDigest(String token) {
        if (token == null) {
            return null;
        }
        try {
            if (Base64.getUrlDecoder().decode(token).length != 32) {
                return null;
            }
            return digest(token);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime dbTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
