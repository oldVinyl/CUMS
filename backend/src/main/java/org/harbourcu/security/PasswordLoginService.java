package org.harbourcu.security;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;

/** First login factor; returns only the next required step, never a session. */
public class PasswordLoginService {
    public record PasswordResult(UUID userId, boolean passwordChangeRequired,
                                 boolean totpEnabled) {}
    private record Account(UUID id, String passwordHash, String status,
                           boolean forcePasswordChange, boolean totpEnabled) {}

    private static final String FAILURE = "Invalid email or password";
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final LoginThrottleService throttle;
    private final String dummyHash;

    public PasswordLoginService(JdbcTemplate jdbc, PasswordHasher passwords,
                                LoginThrottleService throttle) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.throttle = throttle;
        this.dummyHash = passwords.hashNewPassword("dummy absent account credential 2026");
    }

    public PasswordResult verifyPassword(String email, String password, String ip) {
        String normalized = normalize(email);
        if (password == null || password.length() > 1024 || ip == null || ip.isBlank()) {
            throw new BadCredentialsException(FAILURE);
        }
        List<Account> accounts = jdbc.query("""
            SELECT id, password_hash, status, force_password_change, totp_enabled_at
            FROM users WHERE lower(email) = ?
            """, (row, ignored) -> new Account(row.getObject("id", UUID.class),
                row.getString("password_hash"), row.getString("status"),
                row.getBoolean("force_password_change"),
                row.getTimestamp("totp_enabled_at") != null), normalized);
        Account account = accounts.isEmpty() ? null : accounts.getFirst();
        UUID userId = account == null ? null : account.id();
        if (throttle.blocked(normalized, ip, userId)) {
            passwords.verify(password, dummyHash);
            throw new BadCredentialsException(FAILURE);
        }
        boolean matched = passwords.verify(password,
            account == null ? dummyHash : account.passwordHash());
        if (!matched || account == null || !"ACTIVE".equals(account.status())) {
            throttle.failed(normalized, ip, userId);
            throw new BadCredentialsException(FAILURE);
        }
        throttle.succeeded(normalized, ip, userId);
        return new PasswordResult(userId, account.forcePasswordChange(), account.totpEnabled());
    }

    private String normalize(String email) {
        if (email == null || email.isBlank() || email.length() > 320) {
            throw new BadCredentialsException(FAILURE);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
