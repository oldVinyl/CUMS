package org.harbourcu.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.harbourcu.common.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verifies a Google ID token and binds its stable subject to an existing user. */
@Service
@ConditionalOnProperty(name = "APP_GOOGLE_CLIENT_ID")
public class GoogleIdentityService {
    private final JdbcTemplate jdbc;
    private final JwtDecoder decoder;
    private final SessionService sessions;
    private final AuthFlowService flow;
    private final AuditService audit;
    private final String clientId;
    private final SecureRandom random = new SecureRandom();

    public GoogleIdentityService(JdbcTemplate jdbc, JwtDecoder googleJwtDecoder,
                                 SessionService sessions, AuthFlowService flow, AuditService audit,
                                 @Value("${APP_GOOGLE_CLIENT_ID}") String clientId) {
        this.jdbc = jdbc;
        this.decoder = googleJwtDecoder;
        this.sessions = sessions;
        this.flow = flow;
        this.audit = audit;
        this.clientId = clientId;
    }

    @Transactional
    public String issueNonce() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM google_sign_in_nonces WHERE expires_at < now() - interval '1 day'");
        jdbc.update("INSERT INTO google_sign_in_nonces (nonce_hash, expires_at) VALUES (?, ?)",
            digest(nonce), OffsetDateTime.ofInstant(Instant.now().plusSeconds(300), ZoneOffset.UTC));
        return nonce;
    }

    @Transactional
    public void link(UUID userId, String idToken) {
        String subject = verifiedSubject(idToken);
        linkSubject(userId, subject);
    }

    @Transactional
    public SessionService.Tokens enroll(String challengeToken, String idToken) {
        String subject = verifiedSubject(idToken);
        UUID userId = flow.consumeAlternativeSetup(challengeToken)
            .orElseThrow(() -> new BadCredentialsException("Invalid setup challenge"));
        linkSubject(userId, subject);
        return sessions.create(userId);
    }

    @Transactional
    public SessionService.Tokens login(String idToken) {
        String subject = verifiedSubject(idToken);
        List<UUID> users = jdbc.query("""
            SELECT g.user_id FROM google_identity_links g
            JOIN users u ON u.id = g.user_id
            WHERE g.google_subject = ? AND u.status = 'ACTIVE'
            """, (row, ignored) -> row.getObject("user_id", UUID.class), subject);
        if (users.isEmpty()) throw new BadCredentialsException("Google account is not linked");
        UUID userId = users.getFirst();
        audit.record(userId, AuditService.Action.GOOGLE_LOGIN, "USER", userId, null);
        return sessions.create(userId);
    }

    private void linkSubject(UUID userId, String subject) {
        List<String> existing = jdbc.query("SELECT google_subject FROM google_identity_links WHERE user_id = ?",
            (row, ignored) -> row.getString("google_subject"), userId);
        if (!existing.isEmpty() && !existing.getFirst().equals(subject)) {
            throw new IllegalStateException("A different Google account is already linked");
        }
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO google_identity_links (user_id, google_subject) VALUES (?, ?)",
                userId, subject);
            audit.record(userId, AuditService.Action.GOOGLE_LINKED, "USER", userId, null);
        }
        jdbc.update("""
            UPDATE users SET preferred_sign_in_method = 'GOOGLE'
            WHERE id = ? AND preferred_sign_in_method IS NULL
            """, userId);
    }

    private String verifiedSubject(String idToken) {
        if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
            throw new BadCredentialsException("Invalid Google credential");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException exception) {
            throw new BadCredentialsException("Invalid Google credential", exception);
        }
        String nonce = jwt.getClaimAsString("nonce");
        String subject = jwt.getSubject();
        if (!jwt.getAudience().contains(clientId) || subject == null || subject.isBlank()
            || subject.length() > 255 || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
            || nonce == null || nonce.isBlank()) {
            throw new BadCredentialsException("Invalid Google credential");
        }
        List<Integer> consumed = jdbc.query("""
            UPDATE google_sign_in_nonces SET consumed_at = now()
            WHERE nonce_hash = ? AND consumed_at IS NULL AND expires_at > now()
            RETURNING 1
            """, (row, ignored) -> row.getInt(1), digest(nonce));
        if (consumed.isEmpty()) throw new BadCredentialsException("Google credential expired");
        return subject;
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
