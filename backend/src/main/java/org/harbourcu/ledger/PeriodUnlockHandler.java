package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PeriodUnlockHandler implements ApprovalActionHandler {
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final FiscalSnapshotService snapshots;
    public PeriodUnlockHandler(JdbcTemplate jdbc, PermissionService permissions, FiscalSnapshotService snapshots) {
        this.jdbc=jdbc; this.permissions=permissions; this.snapshots=snapshots;
    }
    public String actionType() { return "PERIOD_UNLOCK"; }
    public void validateSubmission(UUID maker, JsonNode payload) {
        if (!permissions.hasPermission(maker,"PERIOD_UNLOCK")) throw new IllegalArgumentException("Maker cannot unlock a fiscal year");
        if(payload==null || !payload.isObject() || payload.size()!=2 || !payload.path("year").isIntegralNumber()
            || !payload.path("year").canConvertToInt() || !payload.path("reason").isTextual()
            || payload.path("reason").asText().isBlank() || payload.path("reason").asText().length()>1000) {
            throw new IllegalArgumentException("A fiscal year and reason of up to 1,000 characters are required");
        }
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fiscal_years WHERE year=? AND status='CLOSED')",
            Boolean.class,payload.path("year").intValue()))) throw new IllegalStateException("Only a closed fiscal year can be unlocked");
    }
    public void execute(UUID request, UUID checker, JsonNode payload) {
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        int year=payload.path("year").intValue();
        jdbc.queryForList("SELECT year FROM fiscal_years WHERE year>=? ORDER BY year FOR UPDATE",year);
        UUID maker=jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id=?",UUID.class,request);
        validateSubmission(maker,payload);
        snapshots.recomputeFrom(year,checker,request,true);
        jdbc.update("UPDATE fiscal_years SET status='OPEN',closed_at=NULL,closed_by=NULL WHERE year=?",year);
        jdbc.update("""
            INSERT INTO audit_log(actor_id,action,entity_type,details)
            VALUES(?,'FISCAL_YEAR_UNLOCKED','FISCAL_YEAR',
              jsonb_build_object('year',?,'reason',?::text,'approvalRequestId',?::text,'makerId',?::text))
            """,checker,year,payload.path("reason").asText(),request.toString(),maker.toString());
    }
}
