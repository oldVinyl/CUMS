package org.harbourcu.investment;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.harbourcu.allocation.AllocationEngine;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.ledger.FiscalYearLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InvestmentMatureHandler implements ApprovalActionHandler {
    private record Maturity(UUID id, BigDecimal actualProceeds) {}
    private record Cycle(String status, LocalDate maturityDate, BigDecimal cost, int revision) {}
    private record Row(UUID memberId, BigDecimal participation, BigDecimal costShare) {}

    private final JdbcTemplate jdbc;
    private final InvestmentService investments;
    private final FiscalYearLock fiscalYears;

    public InvestmentMatureHandler(JdbcTemplate jdbc, InvestmentService investments,
                                   FiscalYearLock fiscalYears) {
        this.jdbc = jdbc;
        this.investments = investments;
        this.fiscalYears = fiscalYears;
    }

    @Override public String actionType() { return "INVESTMENT_MATURE"; }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        investments.require(makerId, "INVESTMENT_WRITE");
        Maturity maturity = parse(payload);
        Cycle cycle = load(maturity.id(), true);
        requireMaturable(cycle);
        investments.requireNoPending(maturity.id());
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        Maturity maturity = parse(payload);
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        UUID maker = jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id = ?",
            UUID.class, requestId);
        investments.require(maker, "INVESTMENT_WRITE");
        Cycle cycle = load(maturity.id(), true);
        requireMaturable(cycle);
        fiscalYears.requireOpen(cycle.maturityDate().getYear());
        List<Row> rows = jdbc.query("""
            SELECT a.member_id, a.participation, a.cost_share
            FROM investment_allocations a JOIN members m ON m.id = a.member_id
            WHERE a.investment_id = ? ORDER BY m.member_number, m.id
            """, (row, ignored) -> new Row(row.getObject("member_id", UUID.class),
                row.getBigDecimal("participation"), row.getBigDecimal("cost_share")), maturity.id());
        if (rows.isEmpty()) throw new IllegalStateException("Investment has no allocations");
        List<AllocationEngine.Participant> participants = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            participants.add(new AllocationEngine.Participant(i + 1,
                AllocationEngine.toPesewas(rows.get(i).participation())));
        }
        List<AllocationEngine.Share> shares = AllocationEngine.allocate(participants,
            AllocationEngine.toPesewas(cycle.cost()),
            AllocationEngine.toPesewas(maturity.actualProceeds()));
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            BigDecimal actualShare = AllocationEngine.toCedis(shares.get(i).maturityValuePesewas());
            BigDecimal income = actualShare.subtract(row.costShare());
            jdbc.update("""
                INSERT INTO investment_maturity_allocations
                    (investment_id, member_id, actual_maturity_share, actual_income_share, revision)
                VALUES (?, ?, ?, ?, ?)
                """, maturity.id(), row.memberId(), actualShare, income, cycle.revision());
            if (income.signum() != 0) {
                jdbc.update("""
                    INSERT INTO ledger_entries
                        (member_id, type, component, amount, effective_date, fiscal_year,
                         investment_id, reference, narration, created_by, approved_by)
                    VALUES (?, 'INCOME_ALLOCATION', 'INCOME', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, row.memberId(), income, cycle.maturityDate(), cycle.maturityDate().getYear(),
                    maturity.id(), "INV-MAT-" + requestId + "-" + row.memberId(),
                    "Approved treasury bill maturity allocation", maker, approverId);
            }
        }
        jdbc.update("""
            UPDATE investments SET status = 'MATURED', actual_proceeds = ?, updated_at = now()
            WHERE id = ?
            """, maturity.actualProceeds(), maturity.id());
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'INVESTMENT_MATURED', 'INVESTMENT', ?,
                    jsonb_build_object('approvalRequestId', ?::text))
            """, approverId, maturity.id(), requestId.toString());
    }

    private Cycle load(UUID id, boolean lock) {
        List<Cycle> rows = jdbc.query("""
            SELECT status, maturity_date, cost, revision FROM investments WHERE id = ?
            """ + (lock ? " FOR UPDATE" : ""), (row, ignored) ->
            new Cycle(row.getString("status"), row.getDate("maturity_date").toLocalDate(),
                row.getBigDecimal("cost"), row.getInt("revision")), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Investment not found");
        return rows.getFirst();
    }

    private static void requireMaturable(Cycle cycle) {
        if (!"POSTED".equals(cycle.status())
            || cycle.maturityDate().isAfter(LocalDate.now(ZoneId.of("Africa/Accra")))) {
            throw new IllegalStateException("Investment is not posted or has not reached maturity");
        }
    }

    private static Maturity parse(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 2
            || !payload.path("investmentId").isTextual()
            || !payload.path("actualProceeds").isTextual()) {
            throw new IllegalArgumentException("Investment and actual proceeds are required");
        }
        try {
            return new Maturity(UUID.fromString(payload.get("investmentId").textValue()),
                InvestmentService.money(payload.get("actualProceeds").textValue(), false));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid investment or actual proceeds", exception);
        }
    }
}
