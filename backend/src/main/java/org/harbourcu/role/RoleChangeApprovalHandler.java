package org.harbourcu.role;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies role grants and revocations only after independent approval. */
@Component
public class RoleChangeApprovalHandler implements ApprovalActionHandler {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    private record Assignment(UUID id, UUID userId, UUID roleId, String roleCode,
                              LocalDate startDate, LocalDate endDate, boolean revoked) {}

    public RoleChangeApprovalHandler(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Override
    public String actionType() {
        return "ROLE_CHANGE";
    }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        if (!permissions.hasPermission(makerId, "ROLE_ASSIGN")) {
            throw new IllegalArgumentException("Maker cannot change roles");
        }
        String operation = operation(payload);
        if ("ASSIGN".equals(operation)) {
            validateGrant(payload);
        } else {
            validateRevoke(payload);
        }
    }

    @Override
    public void validateApprover(UUID approverId, JsonNode payload) {
        UUID affectedUser = "ASSIGN".equals(operation(payload))
            ? uuid(payload, "userId") : assignment(uuid(payload, "assignmentId")).userId();
        if (affectedUser.equals(approverId)) {
            throw new IllegalArgumentException("User cannot approve own role change");
        }
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        UUID makerId = jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id = ?", UUID.class, requestId);
        String operation = operation(payload);
        UUID roleId;
        if ("ASSIGN".equals(operation)) {
            roleId = roleId(payload.get("roleCode").asText());
        } else {
            roleId = assignment(uuid(payload, "assignmentId")).roleId();
        }
        lockRole(roleId);
        validateSubmission(makerId, payload);
        UUID affectedUser;
        String roleCode;
        if ("ASSIGN".equals(operation)) {
            affectedUser = uuid(payload, "userId");
            roleCode = payload.get("roleCode").asText();
            LocalDate endDate = payload.has("endDate")
                ? LocalDate.parse(payload.get("endDate").asText()) : null;
            jdbc.update("""
                INSERT INTO user_role_assignments
                    (user_id, role_id, assigned_by, start_date, end_date, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, affectedUser, roleId, makerId, today(), endDate,
                payload.get("reason").asText().trim());
        } else {
            Assignment target = assignment(uuid(payload, "assignmentId"));
            affectedUser = target.userId();
            roleCode = target.roleCode();
            jdbc.update("""
                UPDATE user_role_assignments
                SET revoked_at = now(), revoked_by = ?, reason = ?
                WHERE id = ? AND revoked_at IS NULL
                """, makerId, payload.get("reason").asText().trim(), target.id());
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'ROLE_CHANGED', 'USER', ?,
                    jsonb_build_object('operation', ?, 'role', ?, 'approvalRequestId', ?::text))
            """, approverId, affectedUser, operation, roleCode, requestId.toString());
    }

    private void validateGrant(JsonNode payload) {
        if ((payload.size() != 4 && payload.size() != 5)
            || (payload.size() == 5 && !payload.has("endDate"))
            || !payload.path("userId").isTextual()
            || !payload.path("roleCode").isTextual()
            || !validReason(payload)
            || (payload.has("endDate") && !payload.path("endDate").isTextual())) {
            throw new IllegalArgumentException("User, role, and reason are required");
        }
        UUID userId = uuid(payload, "userId");
        UUID roleId = roleId(payload.get("roleCode").asText());
        if (payload.has("endDate")) {
            LocalDate endDate;
            try {
                endDate = LocalDate.parse(payload.get("endDate").asText());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid role end date", exception);
            }
            if (endDate.isBefore(today())) {
                throw new IllegalArgumentException("Role end date cannot be in the past");
            }
        }
        Boolean activeUser = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')
            """, Boolean.class, userId);
        if (!Boolean.TRUE.equals(activeUser)) {
            throw new IllegalArgumentException("User is not active");
        }
        Boolean currentAssignment = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM user_role_assignments
                WHERE user_id = ? AND role_id = ? AND revoked_at IS NULL
                  AND (end_date IS NULL OR end_date >= ?)
            )
            """, Boolean.class, userId, roleId, today());
        if (Boolean.TRUE.equals(currentAssignment)) {
            throw new IllegalArgumentException("User already has this role");
        }
    }

    private void validateRevoke(JsonNode payload) {
        if (payload.size() != 3 || !payload.path("assignmentId").isTextual()
            || !validReason(payload)) {
            throw new IllegalArgumentException("Assignment and revocation reason are required");
        }
        Assignment target = assignment(uuid(payload, "assignmentId"));
        if (target.revoked()) {
            throw new IllegalArgumentException("Role assignment is already revoked");
        }
        if (("ADMIN".equals(target.roleCode()) || "PRESIDENT".equals(target.roleCode()))
            && !target.startDate().isAfter(today())
            && (target.endDate() == null || !target.endDate().isBefore(today()))) {
            Integer activeHolders = jdbc.queryForObject("""
                SELECT count(*) FROM user_role_assignments a
                JOIN users u ON u.id = a.user_id
                WHERE a.role_id = ? AND a.revoked_at IS NULL AND u.status = 'ACTIVE'
                  AND a.start_date <= ? AND (a.end_date IS NULL OR a.end_date >= ?)
                """, Integer.class, target.roleId(), today(), today());
            if (activeHolders == null || activeHolders <= 1) {
                throw new IllegalStateException("Cannot revoke the last active " + target.roleCode());
            }
        }
    }

    private Assignment assignment(UUID id) {
        List<Assignment> rows = jdbc.query("""
            SELECT a.id, a.user_id, a.role_id, r.code, a.start_date, a.end_date, a.revoked_at
            FROM user_role_assignments a JOIN roles r ON r.id = a.role_id
            WHERE a.id = ?
            """, (row, ignored) -> new Assignment(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("role_id", UUID.class),
                row.getString("code"), row.getDate("start_date").toLocalDate(),
                row.getDate("end_date") == null ? null : row.getDate("end_date").toLocalDate(),
                row.getTimestamp("revoked_at") != null), id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Role assignment does not exist");
        }
        return rows.getFirst();
    }

    private UUID roleId(String code) {
        List<UUID> rows = jdbc.query("SELECT id FROM roles WHERE code = ?",
            (row, ignored) -> row.getObject("id", UUID.class), code);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Role does not exist");
        }
        return rows.getFirst();
    }

    private void lockRole(UUID id) {
        jdbc.query("SELECT id FROM roles WHERE id = ? FOR UPDATE",
            (row, ignored) -> row.getObject("id", UUID.class), id);
    }

    private UUID uuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(payload.get(field).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid " + field, exception);
        }
    }

    private boolean validReason(JsonNode payload) {
        return payload.path("reason").isTextual()
            && !payload.get("reason").asText().isBlank();
    }

    private String operation(JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.path("operation").isTextual()) {
            throw new IllegalArgumentException("Role operation is required");
        }
        String value = payload.get("operation").asText();
        if (!"ASSIGN".equals(value) && !"REVOKE".equals(value)) {
            throw new IllegalArgumentException("Unsupported role operation");
        }
        return value;
    }

    private LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }
}
