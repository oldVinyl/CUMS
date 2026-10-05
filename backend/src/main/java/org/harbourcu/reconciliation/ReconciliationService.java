package org.harbourcu.reconciliation;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service public class ReconciliationService {
 public record Figures(String equity,String principal,String income,String integrityDifference,String invested,String expectedCash,String sourceFingerprint){}
 public record Input(String actualHoldings,String actualCash,String notes,String sourceFingerprint){}
 public record Record(UUID id,LocalDate asAt,String actualHoldings,String actualCash,JsonNode snapshot,String notes,UUID makerId,UUID requestId,String status,int approvals,int requiredApprovals){}
 public record Page(List<Record> records,int page,boolean hasNext){}
 private final JdbcTemplate jdbc;private final PermissionService permissions;private final ApprovalService approvals;private final ObjectMapper json;
 public ReconciliationService(JdbcTemplate jdbc,PermissionService permissions,ApprovalService approvals,ObjectMapper json){this.jdbc=jdbc;this.permissions=permissions;this.approvals=approvals;this.json=json;}
 public Figures preview(UUID actor){reader(actor);return figures();}
 /** Fingerprint includes every source row/version: changes after review require a new reconciliation. */
 public Figures figures(){
  var balances=jdbc.queryForMap("SELECT coalesce(sum(amount),0) AS equity,coalesce(sum(amount) FILTER(WHERE component='PRINCIPAL'),0) AS principal,coalesce(sum(amount) FILTER(WHERE component='INCOME'),0) AS income FROM ledger_entries WHERE effective_date<=?",today());
  BigDecimal equity=(BigDecimal)balances.get("equity"),p=(BigDecimal)balances.get("principal"),i=(BigDecimal)balances.get("income");
  BigDecimal invested=jdbc.queryForObject("SELECT coalesce(sum(cost),0) FROM investments WHERE status='POSTED'",BigDecimal.class);
  String hash=jdbc.queryForObject("""
   SELECT md5(coalesce((SELECT string_agg(id::text,',' ORDER BY id) FROM ledger_entries WHERE effective_date<=?), '') || '|' ||
    coalesce((SELECT string_agg(id::text || ':' || revision::text || ':' || status || ':' || cost::text,',' ORDER BY id) FROM investments WHERE status<>'DRAFT'),'') || '|' ||
    coalesce((SELECT string_agg(id::text || ':' || status || ':' || amount::text || ':' || fee_amount::text || ':' || coalesce(settled_amount::text,'') || ':' || coalesce(settled_date::text,''),',' ORDER BY id) FROM payments WHERE provider IS NOT NULL),''))
   """,String.class,today());
  return new Figures(equity.toPlainString(),p.toPlainString(),i.toPlainString(),equity.subtract(p).subtract(i).toPlainString(),invested.toPlainString(),equity.subtract(invested).toPlainString(),hash);
 }
 @Transactional(isolation=Isolation.REPEATABLE_READ) public UUID submit(UUID actor,Input b){
  require(actor,"RECONCILE");BigDecimal holdings=money(b.actualHoldings()),cash=money(b.actualCash());
  if(b.notes()==null||b.notes().isBlank()||b.notes().length()>3000)throw new IllegalArgumentException("Add reconciliation notes (up to 3,000 characters).");
  var f=figures();if(!f.sourceFingerprint().equals(b.sourceFingerprint()))throw new IllegalStateException("The ledger or investment sources changed. Refresh the figures and review again.");
  UUID id=UUID.randomUUID();var snapshot=json.valueToTree(f);((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).put("holdingsDifference",holdings.subtract(new BigDecimal(f.invested())).toPlainString()).put("cashDifference",cash.subtract(new BigDecimal(f.expectedCash())).toPlainString());
  var payload=json.createObjectNode().put("reconciliationId",id.toString()).put("sourceFingerprint",f.sourceFingerprint()).put("asAt",today().toString());
  UUID req=approvals.submit("RECONCILIATION_FINALIZE",payload,actor,1);
  jdbc.update("INSERT INTO reconciliations(id,as_at,actual_holdings,actual_cash,snapshot,notes,made_by,approval_request_id) VALUES(?,?,?,?,?::jsonb,?,?,?)",id,today(),holdings,cash,snapshot.toString(),b.notes().trim(),actor,req);audit(actor,id,"RECONCILIATION_SUBMITTED");return id;
 }
 public Page list(UUID actor,int page){reader(actor);if(page<0||page>10000)throw new IllegalArgumentException("Invalid page.");
  var rows=jdbc.query("SELECT c.*,r.status,r.required_approvals,(SELECT count(*) FROM approval_decisions d WHERE d.request_id=r.id AND d.decision='APPROVE') AS approvals FROM reconciliations c JOIN approval_requests r ON r.id=c.approval_request_id ORDER BY c.as_at DESC,c.created_at DESC,c.id DESC LIMIT 51 OFFSET ?",(r,n)->new Record(r.getObject("id",UUID.class),r.getDate("as_at").toLocalDate(),r.getBigDecimal("actual_holdings").toPlainString(),r.getBigDecimal("actual_cash").toPlainString(),tree(r.getString("snapshot")),r.getString("notes"),r.getObject("made_by",UUID.class),r.getObject("approval_request_id",UUID.class),r.getString("status"),r.getInt("approvals"),r.getInt("required_approvals")),page*50);
  return new Page(rows.size()>50?rows.subList(0,50):rows,page,rows.size()>50);
 }
 @Transactional public void decide(UUID actor,UUID id,String decision,String comment){reader(actor);require(actor,"APPROVE");approvals.decide(request(id),actor,ApprovalService.Decision.valueOf(decision),comment);audit(actor,id,"RECONCILIATION_DECISION");}
 @Transactional public void cancel(UUID actor,UUID id){reader(actor);approvals.cancel(request(id),actor);audit(actor,id,"RECONCILIATION_CANCELLED");}
 private UUID request(UUID id){var req=jdbc.queryForList("SELECT approval_request_id FROM reconciliations WHERE id=?",UUID.class,id);if(req.isEmpty())throw new IllegalArgumentException("Reconciliation not found.");return req.getFirst();}
 private JsonNode tree(String text){try{return json.readTree(text);}catch(java.io.IOException e){throw new IllegalStateException("Stored reconciliation is invalid.");}}
 private void audit(UUID actor,UUID id,String action){jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,?,'RECONCILIATION',?)",actor,action,id);}
 private void reader(UUID actor){if(!permissions.hasPermission(actor,"RECONCILE")&&!permissions.hasPermission(actor,"APPROVE")&&!permissions.hasPermission(actor,"AUDIT_READ"))throw new SecurityException("Access denied");}
 private void require(UUID actor,String code){if(!permissions.hasPermission(actor,code))throw new SecurityException("Access denied");}
 private static BigDecimal money(String text){if(text==null||!text.matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Enter a nonnegative GHS amount with at most two decimals.");return new BigDecimal(text);}
 public static LocalDate today(){return LocalDate.now(ZoneId.of("Africa/Accra"));}
}
