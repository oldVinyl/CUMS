package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class PasswordLoginIntegrationTest {
    private static final String PASSWORD = "Unique passphrase 2026 boat compass";
    @Autowired
    PasswordLoginService login;
    @Autowired
    PasswordHasher passwords;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void successfulPasswordCheckReturnsNextStepWithoutCreatingSession() {
        UUID userId = user("ACTIVE", true);
        PasswordLoginService.PasswordResult result = login.verifyPassword(
            "TEST-" + userId + "@INVALID.EXAMPLE", PASSWORD, "192.0.2.10");
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.passwordChangeRequired()).isTrue();
        assertThat(result.totpEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ?",
            Integer.class, userId)).isZero();
    }

    @Test
    @Transactional
    void unknownDisabledAndWrongPasswordShareGenericFailure() {
        UUID active = user("ACTIVE", false);
        UUID disabled = user("DISABLED", false);
        for (Runnable attempt : new Runnable[] {
            () -> login.verifyPassword("unknown@invalid.example", PASSWORD, "192.0.2.11"),
            () -> login.verifyPassword("test-" + active + "@invalid.example", "incorrect", "192.0.2.11"),
            () -> login.verifyPassword("test-" + disabled + "@invalid.example", PASSWORD, "192.0.2.11")
        }) {
            assertThatThrownBy(attempt::run).isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempts WHERE success = false",
            Integer.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Transactional
    void fifthWrongPasswordLocksAccount() {
        UUID userId = user("ACTIVE", false);
        String email = "test-" + userId + "@invalid.example";
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> login.verifyPassword(email, "incorrect", "192.0.2.12"))
                .isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> login.verifyPassword(email, PASSWORD, "192.0.2.12"))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid email or password");
    }

    private UUID user(String status, boolean forcePasswordChange) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (id, email, password_hash, status, force_password_change)
            VALUES (?, ?, ?, ?, ?)
            """, id, "test-" + id + "@invalid.example", passwords.hashNewPassword(PASSWORD),
            status, forcePasswordChange);
        return id;
    }
}
