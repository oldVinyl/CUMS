package org.harbourcu.payment;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.harbourcu.ledger.FiscalYearLock;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service public class OnlinePaymentService {
 public record Start(UUID memberId,String amount,String purpose,String method){}
 public record Checkout(UUID id,String provider,String status,String checkoutUrl){}
 private final JdbcTemplate jdbc;private final PaymentProvider provider;private final ProofStorage ownership;private final PermissionService permissions;private final FiscalYearLock years;private final TransactionTemplate tx;
 public OnlinePaymentService(JdbcTemplate jdbc,PaymentProvider provider,ProofStorage ownership,PermissionService permissions,FiscalYearLock years,PlatformTransactionManager manager){this.jdbc=jdbc;this.provider=provider;this.ownership=ownership;this.permissions=permissions;this.years=years;this.tx=new TransactionTemplate(manager);}
 public Map<String,Object> config(){return Map.of("provider",provider.name(),"enabled",provider.enabled(),"development",provider instanceof FakeProvider);}
 public Checkout start(UUID actor,UUID key,Start b){
  if(!provider.enabled())throw new IllegalStateException("Online payments are not configured.");if(b.memberId()==null||key==null)throw new IllegalArgumentException("Member and Idempotency-Key are required.");ownership.ownOrStaff(actor,b.memberId(),true);
  if(b.amount()==null||!b.amount().matches("[0-9]{1,8}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Enter an amount from GHS 1.00 to 1,000,000.00.");BigDecimal amount=new BigDecimal(b.amount());if(amount.compareTo(jdbc.queryForObject("SELECT payment_min FROM organisation_settings",BigDecimal.class))<0||amount.compareTo(jdbc.queryForObject("SELECT payment_max FROM organisation_settings",BigDecimal.class))>0)throw new IllegalArgumentException("Amount is outside the union’s configured payment limits.");
  if(!List.of("SEED","ADDITION").contains(b.purpose()==null?"":b.purpose())||!List.of("CARD","MOBILE_MONEY").contains(b.method()==null?"":b.method()))throw new IllegalArgumentException("Choose a purpose and card or mobile money.");
  // Persist the reference before any provider request. A timeout cannot create an untracked charge.
  Map<String,Object> payment=tx.execute(s->{
   jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor+":"+key);
   var old=jdbc.queryForList("SELECT * FROM payments WHERE submitted_by=? AND idempotency_key=?",actor,key);
   if(!old.isEmpty()){var p=old.getFirst();if(!b.memberId().equals(p.get("member_id"))||amount.compareTo((BigDecimal)p.get("amount"))!=0||!b.purpose().equals(p.get("purpose"))||!b.method().equals(p.get("method"))||!provider.name().equals(p.get("provider")))throw new IllegalStateException("This key was used for a different payment.");return p;}
   if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE id=? AND status='ACTIVE')",Boolean.class,b.memberId())))throw new IllegalArgumentException("Member is not active.");
   UUID id=UUID.randomUUID();String ref="CU-ONLINE-"+id;String internal="CU-"+LocalDate.now().getYear()+"-"+String.format("%06d",jdbc.queryForObject("SELECT nextval('payment_reference_seq')",Long.class));
   jdbc.update("INSERT INTO payments(id,member_id,amount,method,provider,provider_reference,internal_reference,status,purpose,submitted_by,idempotency_key,fee_bearer) VALUES(?,?,?,?,?,?,?,'INITIATED',?,?,?,(SELECT fee_bearer FROM organisation_settings))",id,b.memberId(),amount,b.method(),provider.name(),ref,internal,b.purpose(),actor,key);
   return jdbc.queryForMap("SELECT * FROM payments WHERE id=?",id);
  });
  UUID id=(UUID)payment.get("id");String ref=(String)payment.get("provider_reference");String status=(String)payment.get("status");
  if(!status.equals("INITIATED"))return new Checkout(id,provider.name(),status,(String)payment.get("checkout_url"));
  String email=jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,actor);
  var checkout=provider.initiate(new PaymentProvider.Initiation(ref,b.memberId(),amount,email,b.method()));if(!checkout.reference().equals(ref))throw new IllegalStateException("Provider reference does not match.");
  jdbc.update("UPDATE payments SET status='PENDING',checkout_url=? WHERE id=? AND status='INITIATED'",checkout.url(),id);return new Checkout(id,provider.name(),"PENDING",checkout.url());
 }
 public void webhook(byte[] bytes,String signature){String ref=provider.parseWebhook(bytes,signature);if(ref!=null)jdbc.update("INSERT INTO payment_webhook_jobs(provider_reference) SELECT provider_reference FROM payments WHERE provider_reference=? AND provider=? ON CONFLICT DO NOTHING",ref,provider.name());}
 public void simulate(UUID actor,UUID id){if(!(provider instanceof FakeProvider fake))throw new SecurityException("Simulation is unavailable.");var p=jdbc.queryForMap("SELECT * FROM payments WHERE id=?",id);ownership.ownOrStaff(actor,(UUID)p.get("member_id"),true);fake.complete((String)p.get("provider_reference"));confirm((String)p.get("provider_reference"),true);}
 public void check(UUID actor,UUID id){var p=jdbc.queryForMap("SELECT * FROM payments WHERE id=?",id);ownership.ownOrStaff(actor,(UUID)p.get("member_id"),false);confirm((String)p.get("provider_reference"),false);}
 public void confirm(String reference,boolean webhookConfirmed){
  var verified=provider.verify(reference);
  tx.executeWithoutResult(s->{jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var list=jdbc.queryForList("SELECT * FROM payments WHERE provider_reference=? AND provider=? FOR UPDATE",reference,provider.name());if(list.isEmpty())throw new IllegalArgumentException("Payment not found.");var p=list.getFirst();
   if(List.of("CONFIRMED","FAILED","EXPIRED","ABANDONED").contains(p.get("status")))return;
   if(!reference.equals(verified.reference())||!p.get("member_id").equals(verified.memberId())||((BigDecimal)p.get("amount")).compareTo(verified.amount())!=0||!"GHS".equals(verified.currency()))throw new IllegalStateException("Provider confirmation does not match the payment. No money was posted.");
   if(!verified.status().equals("CONFIRMED")){if(List.of("FAILED","ABANDONED").contains(verified.status()))jdbc.update("UPDATE payments SET status=? WHERE id=?",verified.status(),p.get("id"));return;}
   // Paystack success requires a validated persisted webhook as well as server verification.
   if(!(provider instanceof FakeProvider)&&!webhookConfirmed&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_webhook_jobs WHERE provider_reference=?)",Boolean.class,reference)))return;
   if(verified.confirmedAt()==null||verified.confirmedAt().isAfter(Instant.now().plusSeconds(60))||verified.confirmedAt().isBefore(((java.sql.Timestamp)p.get("created_at")).toInstant().minusSeconds(60)))throw new IllegalStateException("Provider confirmation timestamp is invalid.");
   BigDecimal fee=verified.fee();if(fee==null||fee.signum()<0||fee.compareTo(verified.amount())>=0)throw new IllegalStateException("Provider fee is invalid.");
   LocalDate date=verified.confirmedAt().atZone(ZoneId.of("Africa/Accra")).toLocalDate();years.requireOpen(date.getYear());
   BigDecimal credit=p.get("fee_bearer").equals("MEMBER")?verified.amount().subtract(fee):verified.amount();UUID entry=UUID.randomUUID();
   jdbc.update("INSERT INTO ledger_entries(id,member_id,type,component,amount,effective_date,fiscal_year,payment_id,reference,narration) VALUES(?,?,?,'PRINCIPAL',?,?,?,?,?,?)",entry,p.get("member_id"),p.get("purpose"),credit,date,date.getYear(),p.get("id"),"PAY-"+p.get("id"),provider instanceof FakeProvider?"DEVELOPMENT SIMULATION — not a real payment":"Gateway-confirmed payment");
   jdbc.update("UPDATE payments SET status='CONFIRMED',fee_amount=?,confirmed_at=?,effective_date=?,ledger_entry_id=? WHERE id=?",fee,java.sql.Timestamp.from(verified.confirmedAt()),date,entry,p.get("id"));
   jdbc.update("INSERT INTO audit_log(action,entity_type,entity_id,details) VALUES('ONLINE_PAYMENT_CONFIRMED','PAYMENT',?,jsonb_build_object('provider',?::text,'ledgerEntryId',?::text))",p.get("id"),provider.name(),entry.toString());
  });
 }
 public void processJobs(){var refs=jdbc.queryForList("SELECT provider_reference FROM payment_webhook_jobs WHERE processed_at IS NULL AND next_attempt_at<=now() ORDER BY received_at LIMIT 10",String.class);
  for(String ref:refs)try{confirm(ref,true);String state=jdbc.queryForObject("SELECT status FROM payments WHERE provider_reference=?",String.class,ref);if(List.of("CONFIRMED","FAILED","EXPIRED","ABANDONED").contains(state))jdbc.update("UPDATE payment_webhook_jobs SET processed_at=now(),attempts=attempts+1 WHERE provider_reference=?",ref);else jdbc.update("UPDATE payment_webhook_jobs SET attempts=attempts+1,next_attempt_at=now()+interval '1 minute' WHERE provider_reference=?",ref);}catch(RuntimeException e){jdbc.update("UPDATE payment_webhook_jobs SET attempts=attempts+1,next_attempt_at=now()+interval '1 minute' WHERE provider_reference=?",ref);}
 }
}
