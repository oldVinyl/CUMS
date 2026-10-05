package org.harbourcu.reconciliation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component public class ReconciliationHandler implements ApprovalActionHandler {
 private final JdbcTemplate jdbc;private final PermissionService permissions;
 public ReconciliationHandler(JdbcTemplate jdbc,PermissionService permissions){this.jdbc=jdbc;this.permissions=permissions;}
 public String actionType(){return "RECONCILIATION_FINALIZE";}
 public void validateSubmission(UUID actor,JsonNode payload){if(!permissions.hasPermission(actor,"RECONCILE"))throw new SecurityException("Reconciliation permission required.");UUID.fromString(payload.path("reconciliationId").asText());if(!ReconciliationService.today().toString().equals(payload.path("asAt").asText()))throw new IllegalArgumentException("Reconciliation date must be today.");}
 public void execute(UUID req,UUID checker,JsonNode payload){
  UUID maker=jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id=?",UUID.class,req);validateSubmission(maker,payload);
  // Serialize with financial posting before comparing the reviewed source set.
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(78124510)",Object.class);
  // Fiscal-year lock is also used by contribution/payment posting and year-end close.
  jdbc.queryForList("SELECT year FROM fiscal_years WHERE year=? FOR UPDATE",ReconciliationService.today().getYear());
  String current=jdbc.queryForObject("""
   SELECT md5(coalesce((SELECT string_agg(id::text,',' ORDER BY id) FROM ledger_entries WHERE effective_date<=?), '') || '|' ||
    coalesce((SELECT string_agg(id::text || ':' || revision::text || ':' || status || ':' || cost::text,',' ORDER BY id) FROM investments WHERE status<>'DRAFT'),'') || '|' ||
    coalesce((SELECT string_agg(id::text || ':' || status || ':' || amount::text || ':' || fee_amount::text || ':' || coalesce(settled_amount::text,'') || ':' || coalesce(settled_date::text,''),',' ORDER BY id) FROM payments WHERE provider IS NOT NULL),''))
   """,String.class,ReconciliationService.today());
  if(!current.equals(payload.path("sourceFingerprint").asText()))throw new IllegalStateException("Reconciliation sources changed after submission. Cancel and review a new reconciliation.");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reconciliations WHERE id=? AND approval_request_id=?)",Boolean.class,UUID.fromString(payload.path("reconciliationId").asText()),req)))throw new IllegalStateException("Reconciliation not found.");
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'RECONCILIATION_FINALIZED','RECONCILIATION',?)",checker,UUID.fromString(payload.path("reconciliationId").asText()));
 }
}
