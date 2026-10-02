package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class LoginThrottleIntegrationTest {
    @Autowired
    LoginThrottleService throttle;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void knownAccountLocksAfterFiveFailuresAndSuccessResetsIt() {
        UUID userId = user();
        String email = "test-" + userId + "@invalid.example";
        String ip = "192.0.2.1";
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(throttle.blocked(email, ip, userId)).isFalse();
            throttle.failed(email, ip, userId);
        }
        throttle.failed(email, ip, userId);
        assertThat(throttle.blocked(email, ip, userId)).isTrue();
        OffsetDateTime firstLock = jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?",
            OffsetDateTime.class, userId);
        throttle.failed(email, ip, userId);
        OffsetDateTime secondLock = jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?",
            OffsetDateTime.class, userId);
        assertThat(secondLock).isAfter(firstLock);
        throttle.succeeded(email, ip, userId);
        assertThat(throttle.blocked(email, ip, userId)).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM login_attempts WHERE user_id = ?
              AND octet_length(account_digest) = 32 AND octet_length(ip_digest) = 32
            """, Integer.class, userId)).isEqualTo(7);
    }

    @Test
    @Transactional
    void unknownAccountAndIpAreRateLimitedWithoutPlaintextStorage() {
        String email = "unknown@invalid.example";
        String ip = "192.0.2.2";
        for (int attempt = 0; attempt < 5; attempt++) {
            throttle.failed(email, ip, null);
        }
        assertThat(throttle.blocked(email, ip, null)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM login_attempts WHERE user_id IS NULL
              AND encode(account_digest, 'hex') LIKE '%unknown%'
            """, Integer.class)).isZero();
    }

    @Test
    void rejectsShortDigestKey() {
        assertThatThrownBy(() -> new LoginThrottleService(jdbc,
            Base64.getEncoder().encodeToString(new byte[16])))
            .hasMessageContaining("32 bytes");
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "test-" + id + "@invalid.example");
        return id;
    }
}
