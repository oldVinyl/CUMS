package org.harbourcu.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class PermissionServiceIntegrationTest {
    @Autowired
    PermissionService permissions;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void revocationAndExpiryTakeEffectOnNextCheck() {
        UUID userId = UUID.randomUUID();
        UUID revokerId = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'TREASURER'", UUID.class);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            userId, "test-" + userId + "@invalid.example");
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            revokerId, "test-" + revokerId + "@invalid.example");
        jdbc.update("""
            INSERT INTO user_role_assignments (id, user_id, role_id, start_date, end_date)
            VALUES (?, ?, ?, ?, ?)
            """, assignmentId, userId, roleId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(permissions.hasPermission(userId, "APPROVE", LocalDate.of(2026, 10, 2))).isTrue();
        assertThat(permissions.hasPermission(userId, "APPROVE", LocalDate.of(2027, 1, 1))).isFalse();
        assertThat(permissions.hasPermission(userId, "ROLE_ASSIGN", LocalDate.of(2026, 10, 2))).isFalse();
        jdbc.update("""
            UPDATE user_role_assignments
            SET revoked_at = now(), revoked_by = ?, reason = 'Fixture role change'
            WHERE id = ?
            """, revokerId, assignmentId);
        assertThat(permissions.hasPermission(userId, "APPROVE", LocalDate.of(2026, 10, 2))).isFalse();
    }

    @Test
    void unknownUserAndPermissionAreDenied() {
        assertThat(permissions.hasPermission(UUID.randomUUID(), "APPROVE")).isFalse();
        assertThat(permissions.hasPermission(null, "APPROVE")).isFalse();
    }

    @Test
    @Transactional
    void configuredDevAccountGetsAllKnownPermissionsOnlyInDevProfile() {
        UUID userId = UUID.randomUUID();
        String email = "dev-" + userId + "@invalid.example";
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            userId, email);
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        dev.setProperty("DEV_BOOTSTRAP_EMAIL", email.toUpperCase());
        PermissionService devPermissions = new PermissionService(jdbc, dev);
        assertThat(devPermissions.hasPermission(userId, "INVESTMENT_WRITE")).isTrue();
        assertThat(devPermissions.hasPermission(userId, "APPROVE")).isTrue();
        assertThat(devPermissions.currentPermissions(userId))
            .contains("MEMBER_READ", "LEDGER_POST", "CYCLE_REPOST", "APPROVE");
        assertThat(devPermissions.hasPermission(userId, "NOT_A_PERMISSION")).isFalse();

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        production.setProperty("DEV_BOOTSTRAP_EMAIL", email);
        PermissionService productionPermissions = new PermissionService(jdbc, production);
        assertThat(productionPermissions.hasPermission(userId, "INVESTMENT_WRITE")).isFalse();
        assertThat(productionPermissions.currentPermissions(userId)).doesNotContain("APPROVE");

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", userId);
        assertThat(devPermissions.hasPermission(userId, "APPROVE")).isFalse();
        assertThat(devPermissions.currentPermissions(userId)).isEmpty();
    }
}
