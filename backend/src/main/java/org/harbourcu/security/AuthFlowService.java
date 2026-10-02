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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.common.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Password, mandatory password change, and TOTP stages before issuing a session. */
public class AuthFlowService {
    public enum Stage { AUTHENTICATED, PASSWORD_CHANGE, TOTP_SETUP, TOTP_VERIFY }
    public record Step(Stage stage, String challenge, SessionService.Tokens tokens) {}
    public record Enrollment(SessionService.Tokens tokens, List<String> recoveryCodes) {}

    private record Challenge(UUID id, UUID userId, String purpose, Instant expiresAt,
                             Instant consumedAt, int attempts, String userStatus) {}

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final JdbcTemplate jdbc;
    private final PasswordLoginService login;
    private final PasswordHasher passwords;
    private final SessionService sessions;
    private final TotpCodes totp;
    private final FieldEncryption fields;
    private final RecoveryCodeService recoveryCodes;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AuthFlowService(JdbcTemplate jdbc, PasswordLoginService login, PasswordHasher passwords,
                           SessionService sessions, TotpCodes totp, FieldEncryption fields,
                           RecoveryCodeService recoveryCodes, AuditService audit) {
        this.jdbc = jdbc;
        this.login = login;
        this.passwords = passwords;
        this.sessions = sessions;
        this.totp = totp;
        this.fields = fields;
        this.recoveryCodes = recoveryCodes;
        this.audit = audit;
    }

    public Step begin(String email, String password, String remoteIp) {
        PasswordLoginService.PasswordResult result = login.verifyPassword(email, password, remoteIp);
        return nextStep(result.userId(), result.passwordChangeRequired(), result.totpEnabled());
    }

    @Transactional
    public Optional<Step> changePassword(String challengeToken, String newPassword) {
        Challenge challenge = activeChallenge(challengeToken, "PASSWORD_CHANGE");
        if (challenge == null) {
            return Optional.empty();
        }
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
            String.class, challenge.userId());
        if (passwords.verify(newPassword, oldHash)) {
            throw new IllegalArgumentException("Choose a new password");
        }
        String newHash = passwords.hashNewPassword(newPassword);
        jdbc.update("""
            UPDATE users SET password_hash = ?, password_changed_at = now(),
                             force_password_change = false WHERE id = ?
            """, newHash, challenge.userId());
        sessions.revokeAll(challenge.userId());
        audit.record(challenge.userId(), AuditService.Action.PASSWORD_CHANGED,
            "USER", challenge.userId(), null);
        consumeAll(challenge.userId());
        Boolean enabled = jdbc.queryForObject("SELECT totp_enabled_at IS NOT NULL FROM users WHERE id = ?",
            Boolean.class, challenge.userId());
        return Optional.of(nextStep(challenge.userId(), false, Boolean.TRUE.equals(enabled)));
    }

    @Transactional
    public Optional<String> beginTotpSetup(String challengeToken) {
        Challenge challenge = activeChallenge(challengeToken, "TOTP_SETUP");
        if (challenge == null) {
            return Optional.empty();
        }
        List<byte[]> rows = jdbc.query("""
            SELECT totp_secret_ciphertext FROM users
            WHERE id = ? AND totp_enabled_at IS NULL FOR UPDATE
            """, (row, ignored) -> row.getBytes("totp_secret_ciphertext"), challenge.userId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        byte[] encrypted = rows.getFirst();
        byte[] secret;
        if (encrypted == null) {
            secret = totp.newSecret();
            String encoded = Base64.getEncoder().encodeToString(secret);
            jdbc.update("UPDATE users SET totp_secret_ciphertext = ? WHERE id = ?",
                fields.encrypt(encoded), challenge.userId());
        } else {
            secret = Base64.getDecoder().decode(fields.decrypt(encrypted));
        }
        return Optional.of(base32(secret));
    }

    @Transactional
    public Optional<Enrollment> confirmTotpSetup(String challengeToken, String code) {
        Challenge challenge = activeChallenge(challengeToken, "TOTP_SETUP");
        if (challenge == null) {
            return Optional.empty();
        }
        byte[] secret = secret(challenge.userId());
        if (secret == null) {
            failedCode(challenge.id());
            return Optional.empty();
        }
        OptionalLong step = totp.matchedStep(secret, Instant.now(), code);
        if (step.isEmpty()) {
            failedCode(challenge.id());
            return Optional.empty();
        }
        int changed = jdbc.update("""
            UPDATE users SET totp_enabled_at = now(), last_totp_step = ?,
                             preferred_sign_in_method = coalesce(preferred_sign_in_method, 'TOTP')
            WHERE id = ? AND totp_enabled_at IS NULL
            """, step.getAsLong(), challenge.userId());
        if (changed != 1) {
            return Optional.empty();
        }
        consumeAll(challenge.userId());
        audit.record(challenge.userId(), AuditService.Action.TOTP_ENABLED,
            "USER", challenge.userId(), null);
        return Optional.of(new Enrollment(sessions.create(challenge.userId()),
            recoveryCodes.issue(challenge.userId())));
    }

    @Transactional
    public Optional<SessionService.Tokens> verifyTotp(String challengeToken, String code) {
        Challenge challenge = activeChallenge(challengeToken, "TOTP_VERIFY");
        if (challenge == null) {
            return Optional.empty();
        }
        if (recoveryCodes.consume(challenge.userId(), code)) {
            consumeAll(challenge.userId());
            audit.record(challenge.userId(), AuditService.Action.RECOVERY_CODE_USED,
                "USER", challenge.userId(), null);
            return Optional.of(sessions.create(challenge.userId()));
        }
        byte[] secret = secret(challenge.userId());
        OptionalLong step = secret == null ? OptionalLong.empty()
            : totp.matchedStep(secret, Instant.now(), code);
        if (step.isEmpty()) {
            failedCode(challenge.id());
            return Optional.empty();
        }
        int changed = jdbc.update("""
            UPDATE users SET last_totp_step = ?
            WHERE id = ? AND totp_enabled_at IS NOT NULL
              AND (last_totp_step IS NULL OR last_totp_step < ?)
            """, step.getAsLong(), challenge.userId(), step.getAsLong());
        if (changed != 1) {
            failedCode(challenge.id());
            return Optional.empty();
        }
        consumeAll(challenge.userId());
        return Optional.of(sessions.create(challenge.userId()));
    }

    private Step nextStep(UUID userId, boolean passwordChange, boolean totpEnabled) {
        if (passwordChange) {
            return new Step(Stage.PASSWORD_CHANGE, issue(userId, "PASSWORD_CHANGE"), null);
        }
        if (totpEnabled) {
            return new Step(Stage.TOTP_VERIFY, issue(userId, "TOTP_VERIFY"), null);
        }
        return new Step(Stage.TOTP_SETUP, issue(userId, "TOTP_SETUP"), null);
    }

    @Transactional
    public Optional<UUID> consumeAlternativeSetup(String challengeToken) {
        Challenge challenge = activeChallenge(challengeToken, "TOTP_SETUP");
        if (challenge == null) return Optional.empty();
        consumeAll(challenge.userId());
        return Optional.of(challenge.userId());
    }

    private String issue(UUID userId, String purpose) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
            INSERT INTO auth_challenges (user_id, token_hash, purpose, expires_at)
            VALUES (?, ?, ?, ?)
            """, userId, digest(token), purpose,
            OffsetDateTime.ofInstant(Instant.now().plusSeconds(600), ZoneOffset.UTC));
        return token;
    }

    private Challenge activeChallenge(String token, String purpose) {
        byte[] hash = tokenHash(token);
        if (hash == null) {
            return null;
        }
        List<Challenge> rows = jdbc.query("""
            SELECT c.id, c.user_id, c.purpose, c.expires_at, c.consumed_at,
                   c.attempts, u.status AS user_status
            FROM auth_challenges c JOIN users u ON u.id = c.user_id
            WHERE c.token_hash = ? FOR UPDATE OF c
            """, (row, ignored) -> new Challenge(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class), row.getString("purpose"),
                row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("consumed_at") == null ? null : row.getTimestamp("consumed_at").toInstant(),
                row.getInt("attempts"), row.getString("user_status")), hash);
        if (rows.isEmpty()) {
            return null;
        }
        Challenge challenge = rows.getFirst();
        if (!purpose.equals(challenge.purpose()) || challenge.consumedAt() != null
            || !challenge.expiresAt().isAfter(Instant.now()) || challenge.attempts() >= 5
            || !"ACTIVE".equals(challenge.userStatus())) {
            return null;
        }
        return challenge;
    }

    private byte[] secret(UUID userId) {
        List<byte[]> rows = jdbc.query("SELECT totp_secret_ciphertext FROM users WHERE id = ?",
            (row, ignored) -> row.getBytes("totp_secret_ciphertext"), userId);
        return rows.isEmpty() || rows.getFirst() == null ? null
            : Base64.getDecoder().decode(fields.decrypt(rows.getFirst()));
    }

    private void failedCode(UUID challengeId) {
        jdbc.update("UPDATE auth_challenges SET attempts = attempts + 1 WHERE id = ? AND attempts < 5",
            challengeId);
    }

    private void consumeAll(UUID userId) {
        jdbc.update("UPDATE auth_challenges SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL",
            userId);
    }

    private byte[] tokenHash(String token) {
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
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String base32(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                output.append(ALPHABET.charAt((buffer >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            output.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return output.toString();
    }
}
