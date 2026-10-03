package org.harbourcu.investment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.ledger.FiscalYearLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Approved, future-only compensation. Original dates and allocations remain in history. */
@Component
public class InvestmentReverseHandler implements ApprovalActionHandler {
    private record Input(UUID id, int revision, String reason, LocalDate date, LocalDate maturity) {}
    private record Cycle(String status, int revision, UUID source) {}
    private final JdbcTemplate jdbc;
    private final InvestmentService investments;
    private final FiscalYearLock fiscalYears;

    public InvestmentReverseHandler(JdbcTemplate jdbc, InvestmentService investments, FiscalYearLock fiscalYears) {
        this.jdbc = jdbc;
        this.investments = investments;
        this.fiscalYears = fiscalYears;
    }

    @Override public String actionType() { return "INVESTMENT_REVERSE"; }

    @Override public void validateSubmission(UUID maker, JsonNode payload) {
        investments.require(maker, "CYCLE_REPOST");
        investments.require(maker, "INVESTMENT_WRITE");
        Input input = parse(payload);
        validate(input);
        investments.requireNoPending(input.id());
    }

    @Override public void execute(UUID requestId, UUID approver, JsonNode payload) {
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        UUID maker = jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id = ?", UUID.class, requestId);
        investments.require(maker, "CYCLE_REPOST");
        investments.require(maker, "INVESTMENT_WRITE");
        Input input = parse(payload);
        Cycle cycle = validate(input);
        fiscalYears.requireOpen(input.date().getYear());
        // Archive the entire approved revision before making its working copy editable.
        jdbc.update("""
            INSERT INTO investment_revision_history
                (investment_id, revision, snapshot, reason, approval_request_id)
            SELECT i.id, i.revision, jsonb_build_object('investment', to_jsonb(i),
                'allocations', (SELECT jsonb_agg(to_jsonb(a)) FROM investment_allocations a WHERE a.investment_id = i.id),
                'maturityAllocations', (SELECT jsonb_agg(to_jsonb(m)) FROM investment_maturity_allocations m
                    WHERE m.investment_id = i.id AND m.revision = i.revision)), ?, ?
            FROM investments i WHERE i.id = ?
            """, input.reason(), requestId, input.id());
        jdbc.update("""
            INSERT INTO ledger_entries
                (member_id, type, component, amount, effective_date, fiscal_year, investment_id,
                 reference, narration, original_effective_date, reversal_of, created_by, approved_by)
            SELECT l.member_id, 'REVERSAL', l.component, -l.amount, ?, ?, l.investment_id,
                'INV-REV-' || ?::text || '-' || l.id::text, ?, l.effective_date, l.id, ?, ?
            FROM ledger_entries l WHERE l.investment_id = ? AND l.type = 'INCOME_ALLOCATION'
              AND NOT EXISTS (SELECT 1 FROM ledger_entries r WHERE r.reversal_of = l.id)
            """, input.date(), input.date().getYear(), requestId.toString(), input.reason(), maker, approver, input.id());
        jdbc.update("""
            UPDATE investments SET status = 'DRAFT', actual_proceeds = NULL, revision = revision + 1,
                purchase_date = ?, maturity_date = ?, created_by = ?, updated_at = now() WHERE id = ?
            """, input.date(), input.maturity(), maker, input.id());
        if (cycle.source() != null) {
            jdbc.update("UPDATE investments SET status = 'MATURED', updated_at = now() WHERE id = ?", cycle.source());
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'INVESTMENT_REVERSED', 'INVESTMENT', ?, jsonb_build_object(
                'revision', ?, 'reason', ?, 'approvalRequestId', ?::text, 'effectiveDate', ?::text))
            """, approver, input.id(), input.revision(), input.reason(), requestId.toString(), input.date().toString());
    }

    private Cycle validate(Input input) {
        if (!input.date().equals(LocalDate.now(ZoneId.of("Africa/Accra"))) || !input.maturity().isAfter(input.date())) {
            throw new IllegalStateException("Reversal must be approved today with a future replacement maturity date");
        }
        var cycles = jdbc.query("SELECT status, revision, rollover_source_id FROM investments WHERE id = ? FOR UPDATE",
            (row, ignored) -> new Cycle(row.getString("status"), row.getInt("revision"),
                row.getObject("rollover_source_id", UUID.class)), input.id());
        if (cycles.isEmpty() || !java.util.List.of("POSTED", "MATURED").contains(cycles.getFirst().status())
            || cycles.getFirst().revision() != input.revision()) {
            throw new IllegalStateException("Only the current posted or matured revision can be reversed");
        }
        Boolean descendant = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM investments WHERE rollover_source_id = ?
                AND status NOT IN ('DISCARDED', 'REVERSED'))
            """, Boolean.class, input.id());
        if (Boolean.TRUE.equals(descendant)) throw new IllegalStateException("Discard or reverse the dependent rollover first");
        // Removing a gain must not spend another posted cycle's committed funds.
        Boolean insufficient = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM members m WHERE
                    coalesce((SELECT sum(amount) FROM ledger_entries WHERE member_id = m.id AND effective_date <= ?), 0)
                    - coalesce((SELECT sum(l.amount) FROM ledger_entries l WHERE l.member_id = m.id
                        AND l.investment_id = ? AND l.type = 'INCOME_ALLOCATION'
                        AND NOT EXISTS (SELECT 1 FROM ledger_entries r WHERE r.reversal_of = l.id)), 0)
                    < coalesce((SELECT sum(a.participation) FROM investment_allocations a
                        JOIN investments i ON i.id = a.investment_id WHERE a.member_id = m.id
                          AND i.status = 'POSTED' AND i.id <> ?), 0)
                    AND EXISTS (SELECT 1 FROM investment_allocations a WHERE a.investment_id = ? AND a.member_id = m.id)
            )
            """, Boolean.class, input.date(), input.id(), input.id(), input.id());
        if (Boolean.TRUE.equals(insufficient)) throw new IllegalStateException("Income is committed to another posted investment; reverse that cycle first");
        return cycles.getFirst();
    }

    private static Input parse(JsonNode payload) {
        try {
            if (payload == null || payload.size() != 5 || !payload.path("investmentId").isTextual()
                || !payload.path("revision").isIntegralNumber() || !payload.path("reason").isTextual()
                || !payload.path("effectiveDate").isTextual() || !payload.path("maturityDate").isTextual()) {
                throw new IllegalArgumentException();
            }
            String reason = payload.get("reason").textValue().trim();
            if (reason.isEmpty() || reason.length() > 1000) throw new IllegalArgumentException();
            return new Input(UUID.fromString(payload.get("investmentId").textValue()), payload.get("revision").intValue(),
                reason, LocalDate.parse(payload.get("effectiveDate").textValue()), LocalDate.parse(payload.get("maturityDate").textValue()));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Investment revision, reason and valid replacement dates are required", exception);
        }
    }
}
