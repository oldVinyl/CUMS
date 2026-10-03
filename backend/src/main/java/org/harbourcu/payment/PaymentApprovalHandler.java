package org.harbourcu.payment;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.ledger.FiscalYearLock;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component public class PaymentApprovalHandler implements ApprovalActionHandler {
 private final JdbcTemplate jdbc; private final PermissionService permissions; private final FiscalYearLock years;
 public PaymentApprovalHandler(JdbcTemplate jdbc,PermissionService permissions,FiscalYearLock years){this.jdbc=jdbc;this.permissions=permissions;this.years=years;}
 public String actionType(){return "PAYMENT_POST";}
 public void validateSubmission(UUID actor,JsonNode payload){
  if(!permissions.hasPermission(actor,"PAYMENT_VERIFY")) throw new SecurityException("Payment verification permission required.");
  UUID id=UUID.fromString(payload.path("paymentId").asText());
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments p JOIN members m ON m.id=p.member_id WHERE p.id=? AND p.status='VERIFIED_PENDING_APPROVAL' AND p.verified_by=? AND m.status='ACTIVE')",Boolean.class,id,actor))) throw new IllegalStateException("Payment is not ready for approval.");
 }
 public void execute(UUID request,UUID checker,JsonNode payload){
  UUID id=UUID.fromString(payload.path("paymentId").asText());
  var rows=jdbc.queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE",id); if(rows.isEmpty())throw new IllegalArgumentException("Payment not found.");
  var p=rows.getFirst(); UUID maker=(UUID)p.get("verified_by"); validateSubmission(maker,payload);
  var date=((java.sql.Date)p.get("effective_date")).toLocalDate();
  if(!date.equals(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Accra")))) throw new IllegalStateException("Verification date has passed. Cancel and verify again for today.");
  years.requireOpen(date.getYear()); UUID entry=UUID.randomUUID();
  jdbc.update("INSERT INTO ledger_entries(id,member_id,type,component,amount,effective_date,fiscal_year,payment_id,reference,narration,created_by,approved_by) VALUES(?,?,?,'PRINCIPAL',?,?,?,?,?,?,?,?)",entry,p.get("member_id"),p.get("purpose"),p.get("verified_amount"),date,date.getYear(),id,"PAY-"+id,"Verified manual payment "+p.get("internal_reference"),maker,checker);
  jdbc.update("UPDATE payments SET status='POSTED',ledger_entry_id=?,confirmed_at=now() WHERE id=?",entry,id);
  jdbc.update("INSERT INTO payment_events(payment_id,actor_id,action,details) VALUES(?,?,'POSTED',jsonb_build_object('approvalRequestId',?::text))",id,checker,request.toString());
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'PAYMENT_POSTED','PAYMENT',?,jsonb_build_object('approvalRequestId',?::text))",checker,id,request.toString());
 }
}
