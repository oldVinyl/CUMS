package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Closes a completed fiscal year and records each member's cumulative balance forward. */
@Component
public class FiscalYearCloseHandler implements ApprovalActionHandler {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final FiscalYearLock fiscalYearLock;

    public FiscalYearCloseHandler(JdbcTemplate jdbc, PermissionService permissions,
                                  FiscalYearLock fiscalYearLock) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.fiscalYearLock = fiscalYearLock;
    }

    @Override
    public String actionType() {
        return "FISCAL_YEAR_CLOSE";
    }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        if (!permissions.hasPermission(makerId, "FISCAL_YEAR_CLOSE")) {
            throw new IllegalArgumentException("Maker cannot close a fiscal year");
        }
        int year = year(payload);
        Boolean eligible = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM fiscal_years
                WHERE year = ? AND status = 'OPEN' AND end_date < ?
            )
            """, Boolean.class, year, LocalDate.now(BUSINESS_ZONE));
        if (!Boolean.TRUE.equals(eligible)) {
            throw new IllegalStateException("Fiscal year is not ready to close");
        }
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        fiscalYearLock.requireOpen(year(payload));
        UUID makerId = jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id = ?", UUID.class, requestId);
        validateSubmission(makerId, payload);
        int year = year(payload);
        jdbc.update("""
            INSERT INTO fiscal_year_snapshots
                (fiscal_year, member_id, principal, income, equity, created_by)
            SELECT ?, m.id,
                   coalesce(sum(l.amount) FILTER (WHERE l.component = 'PRINCIPAL'), 0),
                   coalesce(sum(l.amount) FILTER (WHERE l.component = 'INCOME'), 0),
                   coalesce(sum(l.amount), 0), ?
            FROM members m
            LEFT JOIN ledger_entries l ON l.member_id = m.id
              AND l.effective_date <= make_date(?, 12, 31)
            WHERE m.join_date <= make_date(?, 12, 31)
            GROUP BY m.id
            """, year, approverId, year, year);
        int changed = jdbc.update("""
            UPDATE fiscal_years
            SET status = 'CLOSED', closed_at = now(), closed_by = ?
            WHERE year = ? AND status = 'OPEN'
            """, approverId, year);
        if (changed != 1) {
            throw new IllegalStateException("Fiscal year changed while closing");
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, details)
            VALUES (?, 'FISCAL_YEAR_CLOSED', 'FISCAL_YEAR',
                    jsonb_build_object('year', ?, 'approvalRequestId', ?::text))
            """, approverId, year, requestId.toString());
    }

    private int year(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 1
            || !payload.path("year").canConvertToInt()) {
            throw new IllegalArgumentException("Fiscal year is required");
        }
        int year = payload.get("year").intValue();
        if (year < 2000 || year > 9999) {
            throw new IllegalArgumentException("Fiscal year is out of range");
        }
        return year;
    }
}
