package org.harbourcu.investment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.ledger.FiscalYearLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InvestmentPostHandler implements ApprovalActionHandler {
    private final JdbcTemplate jdbc;
    private final InvestmentService investments;
    private final FiscalYearLock fiscalYears;

    public InvestmentPostHandler(JdbcTemplate jdbc, InvestmentService investments,
                                 FiscalYearLock fiscalYears) {
        this.jdbc = jdbc;
        this.investments = investments;
        this.fiscalYears = fiscalYears;
    }

    @Override public String actionType() { return "INVESTMENT_POST"; }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        investments.require(makerId, "INVESTMENT_WRITE");
        UUID id = parse(payload);
        var rows = jdbc.query("""
            SELECT status, created_by, purchase_date, rollover_source_id, rollover_policy
            FROM investments WHERE id = ? FOR UPDATE
            """, (row, ignored) -> new Draft(row.getString("status"),
                row.getObject("created_by", UUID.class), row.getDate("purchase_date").toLocalDate(),
                row.getObject("rollover_source_id", UUID.class), row.getString("rollover_policy")), id);
        if (rows.isEmpty() || !"DRAFT".equals(rows.getFirst().status())
            || !makerId.equals(rows.getFirst().createdBy())) {
            throw new IllegalArgumentException("Only the draft creator can submit this investment");
        }
        if (!rows.getFirst().purchaseDate().equals(LocalDate.now(ZoneId.of("Africa/Accra")))) {
            throw new IllegalStateException("Draft purchase date has passed; create a new draft");
        }
        Boolean pending = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM approval_requests
                WHERE action_type = 'INVESTMENT_POST' AND status = 'PENDING'
                  AND payload ->> 'investmentId' = ?)
            """, Boolean.class, id.toString());
        if (Boolean.TRUE.equals(pending)) throw new IllegalStateException("Investment already awaits approval");
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        UUID id = parse(payload);
        // Serialize competing investment approvals before checking uninvested balances.
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        UUID maker = jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id = ?",
            UUID.class, requestId);
        investments.require(maker, "INVESTMENT_WRITE");
        var rows = jdbc.query("""
            SELECT status, created_by, purchase_date, rollover_source_id, rollover_policy
            FROM investments WHERE id = ? FOR UPDATE
            """, (row, ignored) -> new Draft(row.getString("status"),
                row.getObject("created_by", UUID.class), row.getDate("purchase_date").toLocalDate(),
                row.getObject("rollover_source_id", UUID.class), row.getString("rollover_policy")), id);
        if (rows.isEmpty() || !"DRAFT".equals(rows.getFirst().status())
            || !maker.equals(rows.getFirst().createdBy())) {
            throw new IllegalStateException("Investment draft changed or was already posted");
        }
        LocalDate date = rows.getFirst().purchaseDate();
        if (!date.equals(LocalDate.now(ZoneId.of("Africa/Accra")))) {
            throw new IllegalStateException("Draft purchase date has passed; create a new draft");
        }
        fiscalYears.requireOpen(date.getYear());
        UUID sourceId = rows.getFirst().sourceId();
        if (sourceId != null) {
            var sourceStatuses = jdbc.query("SELECT status FROM investments WHERE id = ? FOR UPDATE",
                (row, ignored) -> row.getString("status"), sourceId);
            if (sourceStatuses.isEmpty() || !"MATURED".equals(sourceStatuses.getFirst())) {
                throw new IllegalStateException("Rollover source is no longer matured");
            }
        }
        investments.assertDraftCanPost(id, date);
        jdbc.update("UPDATE investments SET status = 'POSTED', updated_at = now() WHERE id = ?", id);
        if (sourceId != null) {
            jdbc.update("""
                UPDATE investments SET status = 'ROLLED_OVER', rollover_policy = ?, updated_at = now()
                WHERE id = ?
                """, rows.getFirst().policy(), sourceId);
            jdbc.update("""
                INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'INVESTMENT_ROLLED_OVER', 'INVESTMENT', ?,
                        jsonb_build_object('targetInvestmentId', ?::text, 'policy', ?))
                """, approverId, sourceId, id.toString(), rows.getFirst().policy());
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'INVESTMENT_POSTED', 'INVESTMENT', ?,
                    jsonb_build_object('approvalRequestId', ?::text))
            """, approverId, id, requestId.toString());
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

    private record Draft(String status, UUID createdBy, LocalDate purchaseDate,
                         UUID sourceId, String policy) {}
}
