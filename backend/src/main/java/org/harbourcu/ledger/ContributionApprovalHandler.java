package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Posts an approved current-day seed or addition as one immutable principal entry. */
@Component
public class ContributionApprovalHandler implements ApprovalActionHandler {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    private record Contribution(UUID memberId, String type, BigDecimal amount) {}

    public ContributionApprovalHandler(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Override
    public String actionType() {
        return "LEDGER_POST";
    }

    @Override
    public void validateSubmission(UUID makerId, JsonNode payload) {
        if (!permissions.hasPermission(makerId, "LEDGER_POST")) {
            throw new IllegalArgumentException("Maker cannot submit a ledger entry");
        }
        Contribution contribution = parse(payload);
        ensureMemberAndPeriod(contribution.memberId(), LocalDate.now(BUSINESS_ZONE));
    }

    @Override
    public void execute(UUID requestId, UUID approverId, JsonNode payload) {
        UUID makerId = jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id = ?", UUID.class, requestId);
        validateSubmission(makerId, payload);
        Contribution contribution = parse(payload);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        UUID entryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ledger_entries
                (id, member_id, type, component, amount, effective_date, fiscal_year,
                 reference, created_by, approved_by)
            VALUES (?, ?, ?, 'PRINCIPAL', ?, ?, ?, ?, ?, ?)
            """, entryId, contribution.memberId(), contribution.type(), contribution.amount(),
            today, today.getYear(), "APR-" + requestId, makerId, approverId);
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'LEDGER_POSTED', 'LEDGER_ENTRY', ?, jsonb_build_object('approvalRequestId', ?::text))
            """, approverId, entryId, requestId.toString());
    }

    private Contribution parse(JsonNode payload) {
        if (payload == null || !payload.isObject()
            || payload.size() != 3
            || !payload.path("memberId").isTextual()
            || !payload.path("type").isTextual()
            || !payload.path("amount").isTextual()
            || !payload.get("amount").textValue().matches("[0-9]+(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Member, type, and string amount are required");
        }
        UUID memberId;
        BigDecimal amount;
        try {
            memberId = UUID.fromString(payload.get("memberId").textValue());
            amount = new BigDecimal(payload.get("amount").textValue());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid member or amount", exception);
        }
        String type = payload.get("type").textValue();
        if (!"SEED".equals(type) && !"ADDITION".equals(type)) {
            throw new IllegalArgumentException("Only seed and addition contributions are supported");
        }
        if (amount.signum() <= 0 || amount.scale() > 2
            || amount.precision() - amount.scale() > 15) {
            throw new IllegalArgumentException("Amount must be positive GHS with at most two decimals");
        }
        return new Contribution(memberId, type, amount);
    }

    private void ensureMemberAndPeriod(UUID memberId, LocalDate effectiveDate) {
        Boolean memberActive = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM members WHERE id = ? AND status = 'ACTIVE')
            """, Boolean.class, memberId);
        if (!Boolean.TRUE.equals(memberActive)) {
            throw new IllegalArgumentException("Member is not active");
        }
        Boolean yearOpen = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM fiscal_years WHERE year = ? AND status = 'OPEN')
            """, Boolean.class, effectiveDate.getYear());
        if (!Boolean.TRUE.equals(yearOpen)) {
            throw new IllegalStateException("Fiscal year is not open");
        }
    }
}
