package org.harbourcu.investment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InvestmentSettleHandler implements ApprovalActionHandler {
    private final JdbcTemplate jdbc;
    private final InvestmentService investments;

    public InvestmentSettleHandler(JdbcTemplate jdbc, InvestmentService investments) {
        this.jdbc = jdbc;
        this.investments = investments;
    }

    @Override public String actionType() { return "INVESTMENT_SETTLE"; }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        investments.require(makerId, "INVESTMENT_WRITE");
        UUID id = parse(payload);
        requireMaturedWithoutRollover(id);
        investments.requireNoPending(id);
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        UUID id = parse(payload);
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        UUID maker = jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id = ?",
            UUID.class, requestId);
        investments.require(maker, "INVESTMENT_WRITE");
        var rows = jdbc.query("SELECT status FROM investments WHERE id = ? FOR UPDATE",
            (row, ignored) -> row.getString("status"), id);
        if (rows.isEmpty() || !"MATURED".equals(rows.getFirst())) {
            throw new IllegalStateException("Investment is no longer awaiting settlement");
        }
        requireNoRolloverDraft(id);
        jdbc.update("""
            UPDATE investments SET status = 'SETTLED', rollover_policy = 'NO_ROLLOVER',
                                   updated_at = now() WHERE id = ?
            """, id);
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'INVESTMENT_SETTLED', 'INVESTMENT', ?,
                    jsonb_build_object('approvalRequestId', ?::text))
            """, approverId, id, requestId.toString());
    }

    private void requireMaturedWithoutRollover(UUID id) {
        var rows = jdbc.query("SELECT status FROM investments WHERE id = ? FOR UPDATE",
            (row, ignored) -> row.getString("status"), id);
        if (rows.isEmpty() || !"MATURED".equals(rows.getFirst())) {
            throw new IllegalStateException("Investment must be matured before settlement");
        }
        requireNoRolloverDraft(id);
    }

    private void requireNoRolloverDraft(UUID id) {
        Boolean target = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM investments
                WHERE rollover_source_id = ? AND status NOT IN ('REVERSED', 'DISCARDED'))
            """, Boolean.class, id);
        if (Boolean.TRUE.equals(target)) {
            throw new IllegalStateException("A rollover draft already uses this investment");
        }
    }

    private static UUID parse(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 1
            || !payload.path("investmentId").isTextual()) {
            throw new IllegalArgumentException("Investment ID is required");
        }
        try { return UUID.fromString(payload.get("investmentId").textValue()); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Investment ID is invalid", exception);
        }
    }
}
