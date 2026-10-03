package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reverses a current-day manual contribution after independent approval. */
@Component
public class ContributionReversalHandler implements ApprovalActionHandler {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private record Reversal(UUID entryId, String reason) {}
    private record Original(UUID memberId, BigDecimal amount, String component,
                            String type, LocalDate effectiveDate, UUID paymentId) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final FiscalYearLock fiscalYearLock;

    public ContributionReversalHandler(JdbcTemplate jdbc, PermissionService permissions,
                                       FiscalYearLock fiscalYearLock) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.fiscalYearLock = fiscalYearLock;
    }

    @Override
    public String actionType() {
        return "LEDGER_REVERSE";
    }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        if (!permissions.hasPermission(makerId, "LEDGER_REVERSE")) {
            throw new IllegalArgumentException("Maker cannot reverse a ledger entry");
        }
        Reversal reversal = parse(payload);
        Original original = original(reversal.entryId());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (!today.equals(original.effectiveDate())
            || !("SEED".equals(original.type()) || "ADDITION".equals(original.type()))
            || !"PRINCIPAL".equals(original.component())
            || original.amount().signum() <= 0 || original.paymentId() != null) {
            throw new IllegalArgumentException("Only current-day manual contributions can be reversed here");
        }
        Boolean yearOpen = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM fiscal_years WHERE year = ? AND status = 'OPEN')
            """, Boolean.class, today.getYear());
        if (!Boolean.TRUE.equals(yearOpen)) {
            throw new IllegalStateException("Fiscal year is not open");
        }
        Boolean alreadyReversed = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM ledger_entries WHERE reversal_of = ?)
            """, Boolean.class, reversal.entryId());
        if (Boolean.TRUE.equals(alreadyReversed)) {
            throw new IllegalArgumentException("Entry has already been reversed");
        }
        Boolean safe = jdbc.queryForObject("""
            SELECT coalesce((SELECT sum(amount) FROM ledger_entries WHERE member_id = ? AND effective_date <= ?), 0) - ?
                >= coalesce((SELECT sum(a.participation) FROM investment_allocations a JOIN investments i ON i.id = a.investment_id
                    WHERE a.member_id = ? AND i.status = 'POSTED'), 0)
            """, Boolean.class, original.memberId(), today, original.amount(), original.memberId());
        if (!Boolean.TRUE.equals(safe)) throw new IllegalStateException("Contribution funds are committed to a posted investment");
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        fiscalYearLock.requireOpen(LocalDate.now(BUSINESS_ZONE).getYear());
        UUID makerId = jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id = ?", UUID.class, requestId);
        validateSubmission(makerId, payload);
        Reversal reversal = parse(payload);
        Original original = original(reversal.entryId());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        UUID entryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ledger_entries
                (id, member_id, type, component, amount, effective_date, fiscal_year,
                 reference, narration, reversal_of, created_by, approved_by)
            VALUES (?, ?, 'REVERSAL', 'PRINCIPAL', ?, ?, ?, ?, ?, ?, ?, ?)
            """, entryId, original.memberId(), original.amount().negate(), today, today.getYear(),
            "APR-" + requestId, reversal.reason(), reversal.entryId(), makerId, approverId);
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'LEDGER_REVERSED', 'LEDGER_ENTRY', ?,
                    jsonb_build_object('originalEntryId', ?::text, 'approvalRequestId', ?::text))
            """, approverId, entryId, reversal.entryId().toString(), requestId.toString());
    }

    private Reversal parse(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 2
            || !payload.path("entryId").isTextual() || !payload.path("reason").isTextual()
            || payload.get("reason").textValue().isBlank() || payload.get("reason").textValue().length() > 1000) {
            throw new IllegalArgumentException("Entry and reversal reason are required");
        }
        try {
            return new Reversal(UUID.fromString(payload.get("entryId").textValue()),
                payload.get("reason").textValue().trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid ledger entry ID", exception);
        }
    }

    private Original original(UUID entryId) {
        List<Original> rows = jdbc.query("""
            SELECT member_id, amount, component, type, effective_date, payment_id
            FROM ledger_entries WHERE id = ? AND investment_id IS NULL FOR UPDATE
            """, this::mapOriginal, entryId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Ledger entry does not exist");
        }
        return rows.getFirst();
    }

    private Original mapOriginal(ResultSet row, int ignored) throws SQLException {
        return new Original(row.getObject("member_id", UUID.class), row.getBigDecimal("amount"),
            row.getString("component"), row.getString("type"),
            row.getDate("effective_date").toLocalDate(), row.getObject("payment_id", UUID.class));
    }
}
