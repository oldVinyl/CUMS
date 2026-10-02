package org.harbourcu.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.approval.ApprovalService.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class RoleChangeApprovalIntegrationTest {
    @Autowired
    ApprovalService approvals;
    @Autowired
    PermissionService permissions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    @Transactional
    void approvedGrantAndRevocationChangePermissionsImmediately() {
        UUID maker = user("ADMIN");
        UUID checker = user("PRESIDENT");
        UUID target = user("MEMBER");
        UUID grant = approvals.submit("ROLE_CHANGE", json.createObjectNode()
            .put("operation", "ASSIGN").put("userId", target.toString())
            .put("roleCode", "TREASURER").put("reason", "Fixture election"), maker, 1);
        assertThat(permissions.hasPermission(target, "LEDGER_POST")).isFalse();
        approvals.decide(grant, checker, Decision.APPROVE, null);
        assertThat(permissions.hasPermission(target, "LEDGER_POST")).isTrue();
        UUID assignment = jdbc.queryForObject("""
            SELECT a.id FROM user_role_assignments a JOIN roles r ON r.id = a.role_id
            WHERE a.user_id = ? AND r.code = 'TREASURER' AND a.revoked_at IS NULL
            """, UUID.class, target);
        UUID revoke = approvals.submit("ROLE_CHANGE", json.createObjectNode()
            .put("operation", "REVOKE").put("assignmentId", assignment.toString())
            .put("reason", "Fixture term ended"), maker, 1);
        approvals.decide(revoke, checker, Decision.APPROVE, null);
        assertThat(permissions.hasPermission(target, "LEDGER_POST")).isFalse();
    }

    @Test
    @Transactional
    void lastAdministratorAndPresidentCannotBeRevoked() {
        UUID admin = user("ADMIN");
        UUID president = user("PRESIDENT");
        for (UUID target : new UUID[] {admin, president}) {
            UUID assignment = jdbc.queryForObject("""
                SELECT id FROM user_role_assignments WHERE user_id = ?
                """, UUID.class, target);
            var payload = json.createObjectNode().put("operation", "REVOKE")
                .put("assignmentId", assignment.toString()).put("reason", "Fixture rotation");
            assertThatThrownBy(() -> approvals.submit("ROLE_CHANGE", payload, admin, 1))
                .hasMessageContaining("Cannot revoke the last active");
        }
    }

    @Test
    @Transactional
    void recipientCannotApproveOwnRoleGrant() {
        UUID maker = user("ADMIN");
        UUID target = user("TREASURER");
        UUID checker = user("PRESIDENT");
        UUID request = approvals.submit("ROLE_CHANGE", json.createObjectNode()
            .put("operation", "ASSIGN").put("userId", target.toString())
            .put("roleCode", "VICE_PRESIDENT").put("reason", "Fixture election"), maker, 1);
        assertThatThrownBy(() -> approvals.decide(request, target, Decision.APPROVE, null))
            .hasMessageContaining("own role change");
        approvals.decide(request, checker, Decision.APPROVE, null);
        assertThat(permissions.hasPermission(target, "APPROVE")).isTrue();
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "test-" + id + "@invalid.example");
        jdbc.update("INSERT INTO user_role_assignments (user_id, role_id, start_date) VALUES (?, ?, ?)",
            id, roleId, LocalDate.now(ZoneId.of("Africa/Accra")));
        return id;
    }
}
