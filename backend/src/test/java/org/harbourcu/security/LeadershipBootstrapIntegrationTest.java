package org.harbourcu.security;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties={"spring.flyway.default-schema=leadership_bootstrap_tests","spring.datasource.hikari.schema=leadership_bootstrap_tests"})
class LeadershipBootstrapIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher passwords;
    @Autowired PlatformTransactionManager manager;

    @Test void provisionsDistinctProtectedLeadershipOnceAndRejectsReexecution() {
        var bootstrap = new LeadershipBootstrap(jdbc, passwords, new MockEnvironment());
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> {
            bootstrap.provision("same@example.invalid", "Fixture administrator compass 2026!",
                "SAME@example.invalid", "Fixture president compass 2026!"); return null;
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        tx.execute(status -> {
            bootstrap.provision("admin@example.invalid", "Fixture administrator compass 2026!",
                "president@example.invalid", "Fixture president compass 2026!"); return null;
        });
        assertThat(jdbc.queryForList("SELECT r.code FROM roles r JOIN user_role_assignments a ON a.role_id=r.id", String.class))
            .containsExactlyInAnyOrder("ADMIN", "PRESIDENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE force_password_change", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='LEADERSHIP_BOOTSTRAPPED'", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> tx.execute(status -> {
            bootstrap.provision("other-admin@example.invalid", "Another administrator compass 2026!",
                "other-president@example.invalid", "Another president compass 2026!"); return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
    }
}
