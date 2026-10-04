package org.harbourcu.payment;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.DateTimeException;
import java.util.function.Function;
import org.harbourcu.common.*;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/payments") @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class PaymentController {
 public record Method(UUID id,String name,String instructions,boolean active){} public record Reason(String action,String reason){} public record Decision(String decision,String comment){}
 private final ReceiptRevisionService receiptRevisions;
 private final org.springframework.jdbc.core.JdbcTemplate jdbc;private final org.harbourcu.report.ReportRenderer renderer;private final FinancialPolicyService policy;private final OnlinePaymentService online;private final PaymentService payments;private final ProofStorage proofs;private final SessionService sessions;private final String origin;
 public PaymentController(ReceiptRevisionService receiptRevisions,PaymentService payments,org.springframework.jdbc.core.JdbcTemplate jdbc,org.harbourcu.report.ReportRenderer renderer,FinancialPolicyService policy,OnlinePaymentService online,ProofStorage proofs,SessionService sessions,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin){this.receiptRevisions=receiptRevisions;this.jdbc=jdbc;this.renderer=renderer;this.policy=policy;this.online=online;this.payments=payments;this.proofs=proofs;this.sessions=sessions;this.origin=origin;}
 @GetMapping("/{id}/receipt-revisions") public ResponseEntity<?> receiptHistory(@PathVariable UUID id,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(receiptRevisions.history(a,id)));}
 @PostMapping("/{id}/receipt") public ResponseEntity<?> receiptAmendment(@PathVariable UUID id,@RequestBody ReceiptRevisionService.Amendment b,HttpServletRequest r){return call(r,true,a->{receiptRevisions.amend(a,id,b);return ResponseEntity.noContent().build();});}
 @GetMapping public ResponseEntity<?> list(@RequestParam(defaultValue="0") int page,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(payments.list(a,page)));}
 @GetMapping("/methods") public ResponseEntity<?> methods(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(payments.methods(a)));}
 @GetMapping("/own-member") public ResponseEntity<?> member(HttpServletRequest r){return call(r,false,a->{UUID id=payments.ownMember(a);return ResponseEntity.ok(Collections.singletonMap("memberId",id));});}
 @PostMapping("/methods") public ResponseEntity<?> method(@RequestBody Method b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",payments.method(a,b.id(),b.name(),b.instructions(),b.active()))));}
 @PostMapping public ResponseEntity<?> submit(@RequestBody PaymentService.Submit b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",payments.submit(a,UUID.fromString(Optional.ofNullable(r.getHeader("Idempotency-Key")).orElse("")),b))));}
 @PostMapping("/proofs") public ResponseEntity<?> upload(@RequestParam UUID memberId,@RequestParam MultipartFile file,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",proofs.upload(a,memberId,file))));}
 @GetMapping("/proofs/{id}") public ResponseEntity<?> proof(@PathVariable UUID id,HttpServletRequest r){return call(r,false,a->{var p=proofs.download(a,id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.contentType())).header("Content-Disposition","attachment; filename=receipt-"+id+(p.contentType().equals("application/pdf")?".pdf":p.contentType().equals("image/png")?".png":".jpg")).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").body(p.bytes());});}
 @PostMapping("/{id}/verify") public ResponseEntity<?> verify(@PathVariable UUID id,@RequestBody PaymentService.Verify b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("approvalRequestId",payments.verify(a,id,b))));}
 @PostMapping("/{id}/review") public ResponseEntity<?> review(@PathVariable UUID id,@RequestBody Reason b,HttpServletRequest r){return call(r,true,a->{payments.review(a,id,b.action(),b.reason());return ResponseEntity.noContent().build();});}
 @PostMapping("/{id}/decide") public ResponseEntity<?> decide(@PathVariable UUID id,@RequestBody Decision b,HttpServletRequest r){return call(r,true,a->{payments.decide(a,id,b.decision(),b.comment());return ResponseEntity.noContent().build();});}
 @PostMapping("/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{payments.cancel(a,id);return ResponseEntity.noContent().build();});}
 @GetMapping("/online/config") public ResponseEntity<?> onlineConfig(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(online.config()));}
 @PostMapping("/online") public ResponseEntity<?> start(@RequestBody OnlinePaymentService.Start b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(online.start(a,UUID.fromString(Optional.ofNullable(r.getHeader("Idempotency-Key")).orElse("")),b)));}
 @PostMapping("/{id}/simulate") public ResponseEntity<?> simulate(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{online.simulate(a,id);return ResponseEntity.noContent().build();});}
 @PostMapping("/{id}/check") public ResponseEntity<?> check(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{online.check(a,id);return ResponseEntity.noContent().build();});}
 @PostMapping("/webhook") public ResponseEntity<?> webhook(@RequestBody byte[] bytes,@RequestHeader(name="x-paystack-signature",required=false)String signature,HttpServletRequest r){try{online.webhook(bytes,signature);return ResponseEntity.ok().build();}catch(SecurityException e){return error(r,403,"INVALID_SIGNATURE","Webhook signature is invalid.");}catch(IllegalArgumentException e){return error(r,400,"INVALID_WEBHOOK","Webhook body is invalid.");}}
 @GetMapping("/policy") public ResponseEntity<?> policy(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(policy.get(a)));}
 @PostMapping("/policy") public ResponseEntity<?> policy(@RequestBody FinancialPolicyService.Policy b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(policy.update(a,b)));}
 @PostMapping("/manual-reference") public ResponseEntity<?> prepare(@RequestBody PaymentService.Submit b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(payments.prepare(a,UUID.fromString(Optional.ofNullable(r.getHeader("Idempotency-Key")).orElse("")),b)));}
 @GetMapping("/{id}/confirmation.pdf") public ResponseEntity<?> confirmation(@PathVariable UUID id,HttpServletRequest r){return call(r,false,a->{
  var p=jdbc.queryForMap("SELECT p.*,m.member_number,m.full_name,l.amount AS credited FROM payments p JOIN members m ON m.id=p.member_id LEFT JOIN ledger_entries l ON l.id=p.ledger_entry_id WHERE p.id=?",id);proofs.ownOrStaff(a,(UUID)p.get("member_id"),false);
  if(!List.of("POSTED","CONFIRMED").contains(p.get("status")))throw new IllegalStateException("A confirmation is available after the payment is posted.");
  var rows=new java.util.ArrayList<List<Object>>();for(String key:List.of("member_number","full_name","internal_reference","status","method","purpose","amount","fee_amount","credited","effective_date"))rows.add(List.of(key.replace('_',' '),Objects.toString(p.get(key),"—")));
  String title="FAKE".equals(p.get("provider"))?"DEVELOPMENT PAYMENT SIMULATION — no real payment":"Payment confirmation";
  var report=new org.harbourcu.report.ReportRenderer.Report(title,"GHS · Generated "+java.time.LocalDate.now(java.time.ZoneId.of("Africa/Accra")),List.of(new org.harbourcu.report.ReportRenderer.Column("Detail",25,false),new org.harbourcu.report.ReportRenderer.Column("Value",65,false)),List.of(),rows,false);
  byte[] bytes=renderer.render(report,"pdf");jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'PAYMENT_CONFIRMATION_DOWNLOADED','PAYMENT',?)",a,id);
  return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Content-Disposition","attachment; filename=payment-"+id+".pdf").header("X-Content-Type-Options","nosniff").body(bytes);
 });}
 private ResponseEntity<?> call(HttpServletRequest r,boolean mutation,Function<UUID,ResponseEntity<?>> action){
  String access=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))access=c.getValue();var identity=sessions.resolveAccess(access).orElse(null);
  if(identity==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");
  if(mutation&&(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");
  try {var result=action.apply(identity.userId());return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).header("Cache-Control","no-store").body(result.getBody());}
  catch(SecurityException e){return error(r,403,"ACCESS_DENIED","You do not have permission for this payment.");}
  catch(IllegalArgumentException|DateTimeException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}
  catch(IllegalStateException e){return error(r,409,"PAYMENT_CONFLICT",e.getMessage());}
 }
 private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
