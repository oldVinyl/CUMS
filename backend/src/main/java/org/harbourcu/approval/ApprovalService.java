package org.harbourcu.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {
    public enum Decision { APPROVE, REJECT }

    private record Request(UUID id, UUID makerId, String actionType, JsonNode payload,
                           String status, int requiredApprovals) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final Map<String, ApprovalActionHandler> handlers;

    public ApprovalService(JdbcTemplate jdbc, PermissionService permissions, ObjectMapper json,
                           List<ApprovalActionHandler> actionHandlers) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.json = json;
        Map<String, ApprovalActionHandler> mapped = new HashMap<>();
        for (ApprovalActionHandler handler : actionHandlers) {
            if (mapped.putIfAbsent(handler.actionType(), handler) != null) {
                throw new IllegalStateException("Duplicate approval handler: " + handler.actionType());
            }
        }
        this.handlers = Map.copyOf(mapped);
    }

    @Transactional
    public UUID submit(String actionType, JsonNode payload, UUID makerId, int requiredApprovals) {
        if (makerId == null || payload == null || requiredApprovals < 1 || requiredApprovals > 3) {
            throw new IllegalArgumentException("Maker, payload, and 1–3 approvals are required");
        }
        requiredApprovals = Math.max(requiredApprovals, jdbc.queryForObject("SELECT required_approvals FROM organisation_settings WHERE singleton=true", Integer.class));
        ApprovalActionHandler handler = handlerFor(actionType);
        handler.validateSubmission(makerId, payload);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO approval_requests (id, action_type, payload, made_by, required_approvals)
            VALUES (?, ?, ?::jsonb, ?, ?)
            """, id, actionType, payload.toString(), makerId, requiredApprovals);
        return id;
    }

    @Transactional
    public void decide(UUID requestId, UUID approverId, Decision decision, String comment) {
        if (requestId == null || approverId == null || decision == null) {
            throw new IllegalArgumentException("Request, approver, and decision are required");
        }
        Request request = lockedRequest(requestId);
        if (!"PENDING".equals(request.status())) {
            throw new IllegalStateException("Approval request is no longer pending");
        }
        if (request.makerId().equals(approverId)) {
            throw new IllegalArgumentException("Maker cannot approve own request");
        }
        if (!permissions.hasPermission(approverId, "APPROVE")) {
            throw new IllegalArgumentException("Approver is not currently eligible");
        }
        handlerFor(request.actionType()).validateApprover(approverId, request.payload());
        if (decision == Decision.REJECT && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException("Rejection requires a comment");
        }
        Integer prior = jdbc.queryForObject("""
            SELECT count(*) FROM approval_decisions WHERE request_id = ? AND approver_id = ?
            """, Integer.class, requestId, approverId);
        if (prior != null && prior > 0) {
            throw new IllegalArgumentException("Approver has already decided this request");
        }
        jdbc.update("""
            INSERT INTO approval_decisions (request_id, approver_id, decision, comment)
            VALUES (?, ?, ?, ?)
            """, requestId, approverId, decision.name(), comment);
        if (decision == Decision.REJECT) {
            resolve(requestId, "REJECTED");
            return;
        }
        Integer approvals = jdbc.queryForObject("""
            SELECT count(*) FROM approval_decisions WHERE request_id = ? AND decision = 'APPROVE'
            """, Integer.class, requestId);
        if (approvals != null && approvals >= request.requiredApprovals()) {
            handlerFor(request.actionType()).execute(requestId, approverId, request.payload());
            resolve(requestId, "APPROVED");
        }
    }

    @Transactional
    public void cancel(UUID requestId, UUID makerId) {
        Request request = lockedRequest(requestId);
        if (!request.makerId().equals(makerId) || !"PENDING".equals(request.status())) {
            throw new IllegalArgumentException("Only the maker can cancel a pending request");
        }
        resolve(requestId, "CANCELLED");
    }

    private ApprovalActionHandler handlerFor(String actionType) {
        ApprovalActionHandler handler = handlers.get(actionType);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported approval action");
        }
        return handler;
    }

    private Request lockedRequest(UUID requestId) {
        List<Request> rows = jdbc.query("""
            SELECT id, made_by, action_type, payload::text, status, required_approvals
            FROM approval_requests WHERE id = ? FOR UPDATE
            """, this::mapRequest, requestId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Approval request does not exist");
        }
        return rows.getFirst();
    }

    private Request mapRequest(ResultSet row, int ignored) throws SQLException {
        try {
            return new Request(row.getObject("id", UUID.class), row.getObject("made_by", UUID.class),
                row.getString("action_type"), json.readTree(row.getString("payload")),
                row.getString("status"), row.getInt("required_approvals"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored approval payload is invalid", exception);
        }
    }

    private void resolve(UUID requestId, String status) {
        jdbc.update("""
            UPDATE approval_requests
            SET status = ?, resolved_at = now(), version = version + 1
            WHERE id = ? AND status = 'PENDING'
            """, status, requestId);
    }
}
