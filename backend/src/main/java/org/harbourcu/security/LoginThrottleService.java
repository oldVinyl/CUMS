package org.harbourcu.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed account lockout and IP throttling without storing raw identifiers. */
public class LoginThrottleService {
    private final JdbcTemplate jdbc;
    private final SecretKeySpec key;

    public LoginThrottleService(JdbcTemplate jdbc, String base64Key) {
        this.jdbc = jdbc;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AUTH_THROTTLE_KEY must be base64 encoded", exception);
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("AUTH_THROTTLE_KEY must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public boolean blocked(String email, String ip, UUID userId) {
        byte[] accountDigest = digest("account", normalize(email));
        byte[] ipDigest = digest("ip", ip);
        Integer ipFailures = jdbc.queryForObject("""
            SELECT count(*) FROM login_attempts
            WHERE ip_digest = ? AND success = false
              AND attempted_at > now() - interval '15 minutes'
            """, Integer.class, ipDigest);
        if (ipFailures != null && ipFailures >= 30) {
            return true;
        }
        if (userId == null) {
            Integer accountFailures = jdbc.queryForObject("""
                SELECT count(*) FROM login_attempts
                WHERE account_digest = ? AND success = false
                  AND attempted_at > now() - interval '15 minutes'
                """, Integer.class, accountDigest);
            return accountFailures != null && accountFailures >= 5;
        }
        Boolean locked = jdbc.queryForObject("""
            SELECT locked_until IS NOT NULL AND locked_until > now()
            FROM users WHERE id = ?
            """, Boolean.class, userId);
        return Boolean.TRUE.equals(locked);
    }

    @Transactional
    public void failed(String email, String ip, UUID userId) {
        if (userId != null) {
            jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (row, ignored) -> row.getObject("id", UUID.class), userId);
        }
        byte[] accountDigest = digest("account", normalize(email));
        byte[] ipDigest = digest("ip", ip);
        jdbc.update("""
            INSERT INTO login_attempts (user_id, account_digest, ip_digest, success)
            VALUES (?, ?, ?, false)
            """, userId, accountDigest, ipDigest);
        jdbc.update("INSERT INTO audit_log(action,entity_type,entity_id) VALUES('LOGIN_FAILED','USER',?)",userId);
        if (userId != null) {
            Integer failures = jdbc.queryForObject("""
                SELECT count(*) FROM login_attempts
                WHERE account_digest = ? AND success = false
                  AND attempted_at > greatest(now() - interval '15 minutes',
                      coalesce((SELECT max(attempted_at) FROM login_attempts
                                WHERE account_digest = ? AND success = true),
                               now() - interval '15 minutes'))
                """, Integer.class, accountDigest, accountDigest);
            if (failures != null && failures >= 5) {
                long seconds = Math.min(3600, 30L << Math.min(failures - 5, 7));
                jdbc.update("UPDATE users SET locked_until = ? WHERE id = ?",
                    OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(seconds), userId);
            }
        }
    }

    @Transactional
    public void succeeded(String email, String ip, UUID userId) {
        jdbc.update("""
            INSERT INTO login_attempts (user_id, account_digest, ip_digest, success)
            VALUES (?, ?, ?, true)
            """, userId, digest("account", normalize(email)), digest("ip", ip));
        jdbc.update("UPDATE users SET locked_until = NULL WHERE id = ?", userId);
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private byte[] digest(String purpose, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IP address is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal((purpose + "\0" + value).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Login digest failed", exception);
        }
    }
}
