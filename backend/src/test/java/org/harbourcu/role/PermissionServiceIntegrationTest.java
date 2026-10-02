package org.harbourcu.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
}
