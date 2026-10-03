package org.harbourcu.payment;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.flyway.default-schema=manual_payment_tests","spring.datasource.hikari.schema=manual_payment_tests","APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","APP_UPLOAD_DIR=${java.io.tmpdir}/cums-test-receipts"})
@AutoConfigureMockMvc class PaymentWorkflowIntegrationTest {
 @Autowired FinancialPolicyService policy;@Autowired PaymentService payments;@Autowired ProofStorage storage;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;@Autowired MockMvc mvc;
 final LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
 @Test @Transactional void manualPaymentPostsExactlyOnceAfterIndependentApprovalAndPreservesOriginalValues() throws Exception {
  UUID member=member(),owner=user("MEMBER",member),maker=user("TREASURER",null),checker=user("PRESIDENT",null);
  UUID method=payments.method(user("ADMIN",null),null,"Bank transfer","Fixture account only",true);
  UUID proof=storage.upload(owner,member,image());UUID key=UUID.randomUUID();var submit=new PaymentService.Submit(member,method,"25.75","ADDITION",today,proof);
  UUID id=payments.submit(owner,key,submit);assertThat(payments.submit(owner,key,submit)).isEqualTo(id);
  assertThatThrownBy(()->payments.submit(owner,key,new PaymentService.Submit(member,method,"30.00","ADDITION",today,proof))).hasMessageContaining("different payment");
  payments.verify(maker,id,new PaymentService.Verify("25.50",today));assertThat(balance(member)).isZero();
  assertThatThrownBy(()->payments.decide(maker,id,"APPROVE",null)).hasMessageContaining("own request");
  payments.decide(checker,id,"APPROVE",null);assertThat(balance(member)).isEqualByComparingTo("25.50");
  assertThatThrownBy(()->payments.decide(checker,id,"APPROVE",null)).hasMessageContaining("not awaiting approval");
  var payment=payments.list(owner,0).payments().getFirst();assertThat(payment.status()).isEqualTo("POSTED");assertThat(payment.amount()).isEqualTo("25.7500");assertThat(payment.verifiedAmount()).isEqualTo("25.50");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE payment_id=?",Integer.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT details->>'submittedAmount' FROM payment_events WHERE payment_id=? AND action='VERIFIED'",String.class,id)).isEqualTo("25.7500");
  assertThat(storage.download(checker,proof).bytes()).isNotEmpty();
 }
 @Test @Transactional void ownershipIsEnforcedOnSubmissionProofsAndLists() throws Exception {
  UUID member=member(),other=member(),owner=user("MEMBER",member),intruder=user("MEMBER",other);UUID method=payments.method(user("ADMIN",null),null,"Bank","Fixture",true);
  UUID proof=storage.upload(owner,member,image());UUID id=payments.submit(owner,UUID.randomUUID(),new PaymentService.Submit(member,method,"10.00","SEED",today,proof));
  assertThat(payments.list(intruder,0).payments()).isEmpty();assertThat(payments.list(owner,0).payments()).extracting(PaymentService.Payment::id).containsExactly(id);
  assertThatThrownBy(()->storage.download(intruder,proof)).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->payments.submit(intruder,UUID.randomUUID(),new PaymentService.Submit(member,method,"10.00","SEED",today,proof))).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->storage.upload(intruder,member,image())).isInstanceOf(SecurityException.class);
  var tokens=sessions.create(intruder);mvc.perform(get("/api/v1/payments/proofs/"+proof).cookie(new Cookie("cu_access",tokens.access()))).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
 }
 @Test @Transactional void receiptMagicSizeAndDuplicateWarningsAreChecked() throws Exception {
  UUID member=member(),owner=user("MEMBER",member),admin=user("ADMIN",null);UUID method=payments.method(admin,null,"Bank","Fixture",true);
  assertThatThrownBy(()->storage.upload(owner,member,new MockMultipartFile("file","receipt.png","image/png","<script>evil</script>".getBytes()))).hasMessageContaining("Only JPEG");
  assertThatThrownBy(()->storage.upload(owner,member,new MockMultipartFile("file","large.pdf","application/pdf",new byte[10485761]))).hasMessageContaining("10 MB");
  for(int i=0;i<2;i++){UUID proof=storage.upload(owner,member,image());payments.submit(owner,UUID.randomUUID(),new PaymentService.Submit(member,method,"10.00","ADDITION",today,proof));}
  assertThat(payments.list(owner,0).payments()).allMatch(PaymentService.Payment::duplicate);
  var tokens=sessions.create(owner);mvc.perform(post("/api/v1/payments").cookie(new Cookie("cu_access",tokens.access())).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/payments").cookie(new Cookie("cu_access",tokens.access())).header("X-CSRF-Token",tokens.csrf()).header("Origin","https://foreign.invalid").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
 }
 @Test void rejectionAndCancellationDoNotPostMoneyAndRevokedVerifierCannotPost() throws Exception {
  UUID member=member(),owner=user("MEMBER",member),maker=user("TREASURER",null),checker=user("TREASURER",null);UUID method=payments.method(user("ADMIN",null),null,"Bank","Fixture",true);
  UUID id=payments.submit(owner,UUID.randomUUID(),new PaymentService.Submit(member,method,"10.00","SEED",today,storage.upload(owner,member,image())));
  payments.review(maker,id,"MORE_INFO","Show the bank reference");assertThat(payments.list(owner,0).payments().getFirst().reason()).contains("bank reference");
  payments.verify(maker,id,new PaymentService.Verify("10.00",today));payments.cancel(maker,id);assertThat(payments.list(owner,0).payments().getFirst().status()).isEqualTo("SUBMITTED");
  payments.verify(maker,id,new PaymentService.Verify("10.00",today));jdbc.update("UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Fixture' WHERE user_id=?",checker,maker);
  assertThatThrownBy(()->payments.decide(checker,id,"APPROVE",null)).isInstanceOf(SecurityException.class);assertThat(balance(member)).isZero();
  payments.decide(checker,id,"REJECT","Unable to verify bank evidence");assertThat(payments.list(owner,0).payments().getFirst().status()).isEqualTo("REJECTED");assertThat(balance(member)).isZero();
 }
 @Test @Transactional void configuredDistinctApprovalsAreRequiredAndTerminalMailIsQueued() throws Exception {
  UUID member=member(),owner=user("MEMBER",member),maker=user("TREASURER",null),checker=user("PRESIDENT",null),second=user("VICE_PRESIDENT",null),admin=user("ADMIN",null);
  var old=policy.current();policy.update(admin,new FinancialPolicyService.Policy(old.organisationName(),old.contactEmail(),old.contactPhone(),2,"UNION","1.00","1000.00"));
  UUID method=payments.method(admin,null,"Fixture bank","Fixture instructions",true),key=UUID.randomUUID();var input=new PaymentService.Submit(member,method,"10.00","SEED",today,storage.upload(owner,member,image()));
  var ref=payments.prepare(owner,key,input);assertThat(payments.prepare(owner,key,input)).isEqualTo(ref);UUID id=payments.submit(owner,key,input);assertThat(id).isEqualTo(ref.get("id"));
  payments.verify(maker,id,new PaymentService.Verify("10.00",today));payments.decide(checker,id,"APPROVE",null);assertThat(balance(member)).isZero();payments.decide(second,id,"APPROVE",null);assertThat(balance(member)).isEqualByComparingTo("10.00");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND entity_id=? AND kind='PAYMENT' AND outcome='POSTED' AND status='PENDING'",Integer.class,owner,id)).isEqualTo(1);
  var tokens=sessions.create(owner);mvc.perform(get("/api/v1/payments/"+id+"/confirmation.pdf").cookie(new Cookie("cu_access",tokens.access()))).andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andExpect(header().string("Cache-Control","no-store"));
  var stranger=sessions.create(user("MEMBER",member()));mvc.perform(get("/api/v1/payments/"+id+"/confirmation.pdf").cookie(new Cookie("cu_access",stranger.access()))).andExpect(status().isForbidden());
  assertThatThrownBy(()->policy.update(owner,old)).isInstanceOf(SecurityException.class);
 }
 private BigDecimal balance(UUID id){return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM ledger_entries WHERE member_id=?",BigDecimal.class,id);}
 private MockMultipartFile image() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);return new MockMultipartFile("file","proof.png","image/png",out.toByteArray());}
 private UUID member(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Payment fixture',?)",id,"P-"+id,today);return id;}
 private UUID user(String role,UUID member){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash,member_id) VALUES(?,?,'fixture',?)",id,id+"@invalid.example",member);jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",id,today,role);return id;}
}
