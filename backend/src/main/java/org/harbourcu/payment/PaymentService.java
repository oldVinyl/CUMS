package org.harbourcu.payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class PaymentService {
 public record Method(UUID id,String name,String instructions,boolean active){}
 public record Submit(UUID memberId,UUID methodId,String amount,String purpose,LocalDate paidDate,UUID proofId){}
 public record Verify(String amount,LocalDate effectiveDate){}
 public record Payment(UUID id,UUID memberId,String memberNumber,String fullName,String amount,String verifiedAmount,String purpose,String method,String reference,String status,LocalDate paidDate,LocalDate effectiveDate,UUID proofId,String reason,UUID approvalRequestId,UUID makerId,int approvals,int requiredApprovals,boolean duplicate,String provider,String checkoutUrl,String feeAmount,boolean compensated){}
 public record Page(List<Payment> payments,int page,boolean hasNext){}
 private final HistoricalPaymentService historical; private final JdbcTemplate jdbc; private final PermissionService permissions; private final ProofStorage proofs; private final ApprovalService approvals; private final ObjectMapper json;
 public PaymentService(JdbcTemplate jdbc,PermissionService permissions,ProofStorage proofs,ApprovalService approvals,ObjectMapper json,HistoricalPaymentService historical){this.historical=historical;this.jdbc=jdbc;this.permissions=permissions;this.proofs=proofs;this.approvals=approvals;this.json=json;}
 public List<Method> methods(UUID actor){requireReader(actor);return jdbc.query("SELECT * FROM payment_methods WHERE active OR ? ORDER BY name",(r,n)->new Method(r.getObject("id",UUID.class),r.getString("name"),r.getString("instructions"),r.getBoolean("active")),permissions.hasPermission(actor,"SETTINGS_WRITE"));}
 @Transactional public UUID method(UUID actor,UUID id,String name,String instructions,boolean active){
  require(actor,"SETTINGS_WRITE"); if(name==null||name.isBlank()||name.length()>120||instructions==null||instructions.isBlank()||instructions.length()>3000)throw new IllegalArgumentException("Name and payment instructions are required.");
  UUID key=id==null?UUID.randomUUID():id;
  jdbc.update("INSERT INTO payment_methods(id,name,instructions,active) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,instructions=excluded.instructions,active=excluded.active,updated_at=now()",key,name.trim(),instructions.trim(),active);
  audit(actor,key,"PAYMENT_METHOD_UPDATED");return key;
 }
 public UUID ownMember(UUID actor){require(actor,"OWN_DATA_READ");var rows=jdbc.queryForList("SELECT member_id FROM users WHERE id=? AND status='ACTIVE' AND member_id IS NOT NULL",UUID.class,actor);return rows.isEmpty()?null:rows.getFirst();}
 @Transactional public UUID submit(UUID actor,UUID key,Submit b){
  if(key==null||b==null||b.memberId()==null)throw new IllegalArgumentException("Member and Idempotency-Key are required."); proofs.ownOrStaff(actor,b.memberId(),true);
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor+":"+key);
  var existing=jdbc.queryForList("SELECT id,member_id,payment_method_id,amount,purpose,paid_date,proof_id,status,provider,internal_reference FROM payments WHERE submitted_by=? AND idempotency_key=?",actor,key);
  BigDecimal value=money(b.amount());
  UUID reserved=null;
  if(!existing.isEmpty()){
   var p=existing.getFirst();if(!b.memberId().equals(p.get("member_id"))||!Objects.equals(b.methodId(),p.get("payment_method_id"))||value.compareTo((BigDecimal)p.get("amount"))!=0||!Objects.equals(b.purpose(),p.get("purpose"))||p.get("provider")!=null)throw new IllegalStateException("This idempotency key was used for a different payment.");
   if(p.get("status").equals("INITIATED")) reserved=(UUID)p.get("id");
   else {if(!Objects.equals(b.paidDate(),p.get("paid_date")==null?null:((java.sql.Date)p.get("paid_date")).toLocalDate())||!Objects.equals(b.proofId(),p.get("proof_id")))throw new IllegalStateException("This idempotency key was used for a different payment.");return (UUID)p.get("id");}
  }
  if(!List.of("SEED","ADDITION").contains(b.purpose()==null?"":b.purpose()))throw new IllegalArgumentException("Choose seed or addition.");
  if(b.paidDate()==null||b.paidDate().isAfter(today())||b.paidDate().isBefore(LocalDate.of(2000,1,1)))throw new IllegalArgumentException("Choose the actual payment date, no later than today.");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE id=? AND status='ACTIVE' AND join_date<=?)",Boolean.class,b.memberId(),b.paidDate())))throw new IllegalArgumentException("Member must be active and payment cannot predate joining.");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM attachments WHERE id=? AND member_id=?)",Boolean.class,b.proofId(),b.memberId())))throw new IllegalArgumentException("Upload a receipt belonging to this member first.");
  var names=jdbc.queryForList("SELECT name FROM payment_methods WHERE id=? AND active",String.class,b.methodId());if(names.isEmpty())throw new IllegalArgumentException("Choose an active payment method.");
  if(reserved!=null){jdbc.update("UPDATE payments SET status='SUBMITTED',proof_id=?,paid_date=?,submitted_at=now() WHERE id=?",b.proofId(),b.paidDate(),reserved);event(actor,reserved,"SUBMITTED",json.createObjectNode());audit(actor,reserved,"PAYMENT_SUBMITTED");return reserved;}
  UUID id=UUID.randomUUID(); long seq=jdbc.queryForObject("SELECT nextval('payment_reference_seq')",Long.class);String ref="CU-"+today().getYear()+"-"+String.format("%06d",seq);
  jdbc.update("INSERT INTO payments(id,member_id,amount,method,internal_reference,status,purpose,submitted_at,payment_method_id,proof_id,paid_date,submitted_by,idempotency_key) VALUES(?,?,?,?,?,'SUBMITTED',?,now(),?,?,?,?,?)",id,b.memberId(),value,names.getFirst(),ref,b.purpose(),b.methodId(),b.proofId(),b.paidDate(),actor,key);
  event(actor,id,"SUBMITTED",json.createObjectNode());audit(actor,id,"PAYMENT_SUBMITTED"); return id;
 }
 @Transactional public Map<String,Object> prepare(UUID actor,UUID key,Submit b){
  if(key==null||b.memberId()==null)throw new IllegalArgumentException("Choose a member and payment details.");proofs.ownOrStaff(actor,b.memberId(),true);BigDecimal amount=money(b.amount());
  if(!List.of("SEED","ADDITION").contains(b.purpose()==null?"":b.purpose()))throw new IllegalArgumentException("Choose seed or addition.");
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor+":"+key);
  var old=jdbc.queryForList("SELECT * FROM payments WHERE submitted_by=? AND idempotency_key=?",actor,key);
  if(!old.isEmpty()){var p=old.getFirst();if(!b.memberId().equals(p.get("member_id"))||!Objects.equals(b.methodId(),p.get("payment_method_id"))||amount.compareTo((BigDecimal)p.get("amount"))!=0||!Objects.equals(b.purpose(),p.get("purpose"))||p.get("provider")!=null)throw new IllegalStateException("Key belongs to a different payment.");return Map.of("id",p.get("id"),"reference",p.get("internal_reference"));}
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE id=? AND status='ACTIVE')",Boolean.class,b.memberId())))throw new IllegalArgumentException("Member is not active.");
  var names=jdbc.queryForList("SELECT name FROM payment_methods WHERE id=? AND active",String.class,b.methodId());if(names.isEmpty())throw new IllegalArgumentException("Choose an active payment method.");
  UUID id=UUID.randomUUID();String ref="CU-"+today().getYear()+"-"+String.format("%06d",jdbc.queryForObject("SELECT nextval('payment_reference_seq')",Long.class));
  jdbc.update("INSERT INTO payments(id,member_id,amount,method,internal_reference,status,purpose,payment_method_id,submitted_by,idempotency_key) VALUES(?,?,?,?,?,'INITIATED',?,?,?,?)",id,b.memberId(),amount,names.getFirst(),ref,b.purpose(),b.methodId(),actor,key);audit(actor,id,"PAYMENT_REFERENCE_CREATED");return Map.of("id",id,"reference",ref);
 }
 public Page list(UUID actor,int page){
  requireReader(actor);if(page<0||page>10000)throw new IllegalArgumentException("Invalid page.");boolean staff=permissions.hasPermission(actor,"PAYMENT_VERIFY")||permissions.hasPermission(actor,"APPROVE");UUID own=staff?null:ownMember(actor);
  if(!staff&&own==null)return new Page(List.of(),page,false);
  var rows=jdbc.query("""
   SELECT p.*,m.member_number,m.full_name,r.made_by,r.required_approvals,
    (SELECT count(*) FROM approval_decisions d WHERE d.request_id=r.id AND d.decision='APPROVE') AS approvals,
    EXISTS(SELECT 1 FROM payments other JOIN attachments oa ON oa.id=other.proof_id JOIN attachments pa ON pa.id=p.proof_id
      WHERE other.id<>p.id AND (oa.sha256=pa.sha256 OR (other.member_id=p.member_id AND other.amount=p.amount AND other.paid_date=p.paid_date))) AS duplicate,
    EXISTS(SELECT 1 FROM ledger_entries correction WHERE correction.reversal_of=p.ledger_entry_id) AS compensated
   FROM payments p JOIN members m ON m.id=p.member_id LEFT JOIN approval_requests r ON r.id=p.approval_request_id
   WHERE (? OR p.member_id=?) ORDER BY p.created_at DESC,p.id DESC LIMIT 51 OFFSET ?
   """,(r,n)->new Payment(r.getObject("id",UUID.class),r.getObject("member_id",UUID.class),r.getString("member_number"),r.getString("full_name"),r.getBigDecimal("amount").toPlainString(),r.getString("verified_amount"),r.getString("purpose"),r.getString("method"),r.getString("internal_reference"),r.getString("status"),r.getDate("paid_date")==null?null:r.getDate("paid_date").toLocalDate(),r.getDate("effective_date")==null?null:r.getDate("effective_date").toLocalDate(),r.getObject("proof_id",UUID.class),r.getString("rejection_reason"),r.getObject("approval_request_id",UUID.class),r.getObject("made_by",UUID.class),r.getInt("approvals"),r.getInt("required_approvals"),r.getBoolean("duplicate"),r.getString("provider"),r.getString("checkout_url"),r.getBigDecimal("fee_amount").toPlainString(),r.getBoolean("compensated")),staff,own,page*50);
  return new Page(rows.size()>50?rows.subList(0,50):rows,page,rows.size()>50);
 }
 @Transactional public UUID verifyHistorical(UUID actor,UUID id,HistoricalPaymentService.Verify body){return historical.verify(actor,id,body,approvals);}
 @Transactional public UUID verify(UUID actor,UUID id,Verify b){
  require(actor,"PAYMENT_VERIFY");jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var p=locked(id);if(!List.of("SUBMITTED","UNDER_REVIEW").contains(p.get("status")))throw new IllegalStateException("Payment is no longer awaiting verification.");
  BigDecimal amount=money(b.amount());if(!today().equals(b.effectiveDate()))throw new IllegalArgumentException("Use today's posting date. Historical corrections require the period-impact workflow.");
  jdbc.update("UPDATE payments SET verified_amount=?,effective_date=?,verified_by=?,status='VERIFIED_PENDING_APPROVAL',rejection_reason=NULL WHERE id=?",amount,b.effectiveDate(),actor,id);
  UUID request=approvals.submit("PAYMENT_POST",json.createObjectNode().put("paymentId",id.toString()),actor,1);
  jdbc.update("UPDATE payments SET approval_request_id=? WHERE id=?",request,id);
  event(actor,id,"VERIFIED",json.createObjectNode().put("submittedAmount",p.get("amount").toString()).put("verifiedAmount",amount.toPlainString()).put("paidDate",p.get("paid_date").toString()).put("effectiveDate",b.effectiveDate().toString()));audit(actor,id,"PAYMENT_VERIFIED"); return request;
 }
 @Transactional public void review(UUID actor,UUID id,String action,String reason){
  require(actor,"PAYMENT_VERIFY");jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var p=locked(id);if(!List.of("SUBMITTED","UNDER_REVIEW").contains(p.get("status")))throw new IllegalStateException("Payment is no longer awaiting review.");
  if(reason==null||reason.isBlank()||reason.length()>1000)throw new IllegalArgumentException("Explain what needs correcting (up to 1,000 characters).");
  if(!List.of("REJECT","MORE_INFO").contains(action))throw new IllegalArgumentException("Choose reject or request more information.");
  jdbc.update("UPDATE payments SET status=?,rejection_reason=? WHERE id=?",action.equals("REJECT")?"REJECTED":"UNDER_REVIEW",reason.trim(),id);event(actor,id,action,json.createObjectNode().put("reason",reason.trim()));audit(actor,id,"PAYMENT_REVIEWED");
 }
 @Transactional public void decide(UUID actor,UUID id,String decision,String comment){
  require(actor,"APPROVE");jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var p=locked(id);UUID req=(UUID)p.get("approval_request_id");if(req==null||!p.get("status").equals("VERIFIED_PENDING_APPROVAL"))throw new IllegalStateException("Payment is not awaiting approval.");
  approvals.decide(req,actor,ApprovalService.Decision.valueOf(decision),comment);
  if(decision.equals("REJECT")){jdbc.update("UPDATE payments SET status='REJECTED',rejection_reason=? WHERE id=?",comment,id);event(actor,id,"REJECTED",json.createObjectNode().put("reason",comment));}
 }
 @Transactional public void cancel(UUID actor,UUID id){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var p=locked(id);UUID req=(UUID)p.get("approval_request_id");if(req==null||!p.get("status").equals("VERIFIED_PENDING_APPROVAL"))throw new IllegalStateException("Payment is not awaiting approval.");approvals.cancel(req,actor);jdbc.update("UPDATE payments SET status='SUBMITTED',approval_request_id=NULL,verified_by=NULL,verified_amount=NULL,effective_date=NULL WHERE id=?",id);event(actor,id,"VERIFICATION_CANCELLED",json.createObjectNode());}
 private Map<String,Object> locked(UUID id){var rows=jdbc.queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new IllegalArgumentException("Payment not found.");return rows.getFirst();}
 private void event(UUID actor,UUID id,String action,com.fasterxml.jackson.databind.JsonNode details){jdbc.update("INSERT INTO payment_events(payment_id,actor_id,action,details) VALUES(?,?,?,?::jsonb)",id,actor,action,details.toString());}
 private void audit(UUID actor,UUID id,String action){jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,?,'PAYMENT',?)",actor,action,id);}
 private void requireReader(UUID actor){if(!permissions.hasPermission(actor,"PAYMENT_VERIFY")&&!permissions.hasPermission(actor,"APPROVE")&&!permissions.hasPermission(actor,"OWN_DATA_READ"))throw new SecurityException("Access denied");}
 private void require(UUID actor,String code){if(!permissions.hasPermission(actor,code))throw new SecurityException("Access denied");}
 private static BigDecimal money(String text){if(text==null||!text.matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Enter a positive GHS amount with up to two decimals.");BigDecimal value=new BigDecimal(text);if(value.signum()<=0)throw new IllegalArgumentException("Amount must be positive.");return value;}
 private static LocalDate today(){return LocalDate.now(ZoneId.of("Africa/Accra"));}
}
