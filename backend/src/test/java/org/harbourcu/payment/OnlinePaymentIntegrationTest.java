package org.harbourcu.payment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.harbourcu.ledger.FiscalYearLock;
import org.harbourcu.role.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties={"spring.flyway.default-schema=online_payment_tests","spring.datasource.hikari.schema=online_payment_tests","APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}) class OnlinePaymentIntegrationTest {
 @Autowired JdbcTemplate jdbc;@Autowired ProofStorage ownership;@Autowired PermissionService permissions;@Autowired FiscalYearLock years;@Autowired PlatformTransactionManager transactions;
 final LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
 @Test void simulatorCreditsExactlyOnceAndPersistsGrossAndSeparateFee(){var provider=new FakeProvider();var service=service(provider);UUID member=member(),actor=user(member),key=UUID.randomUUID();var input=new OnlinePaymentService.Start(member,"20.75","ADDITION","CARD");
  var checkout=service.start(actor,key,input);assertThat(service.start(actor,key,input).id()).isEqualTo(checkout.id());assertThat(balance(member)).isZero();service.simulate(actor,checkout.id());service.simulate(actor,checkout.id());
  assertThat(balance(member)).isEqualByComparingTo("20.75");assertThat(jdbc.queryForObject("SELECT fee_amount FROM payments WHERE id=?",BigDecimal.class,checkout.id())).isEqualByComparingTo("0.25");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE payment_id=?",Integer.class,checkout.id())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT narration FROM ledger_entries WHERE payment_id=?",String.class,checkout.id())).contains("DEVELOPMENT SIMULATION");
 }
 @Test void wrongProviderAmountCannotCreditAndOtherMembersCannotSimulate(){var fake=new FakeProvider();var service=service(fake);UUID member=member(),actor=user(member),other=user(member());var p=service.start(actor,UUID.randomUUID(),new OnlinePaymentService.Start(member,"10.00","SEED","MOBILE_MONEY"));
  assertThatThrownBy(()->service.simulate(other,p.id())).isInstanceOf(SecurityException.class);
  String ref=jdbc.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,p.id());PaymentProvider bad=mock(PaymentProvider.class);when(bad.name()).thenReturn("FAKE");when(bad.verify(ref)).thenReturn(new PaymentProvider.Verification(ref,member,new BigDecimal("11.00"),"GHS","CONFIRMED",BigDecimal.ZERO,Instant.now()));
  assertThatThrownBy(()->service(bad).confirm(ref,true)).hasMessageContaining("does not match");assertThat(balance(member)).isZero();assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p.id())).isEqualTo("PENDING");
 }
 @Test void memberFeePolicyCreditsNetAndLimitsAreEnforced(){
  jdbc.update("UPDATE organisation_settings SET fee_bearer='MEMBER',payment_min=5,payment_max=100 WHERE singleton=true");
  try {var fake=new FakeProvider();var service=service(fake);UUID m=member(),actor=user(m);assertThatThrownBy(()->service.start(actor,UUID.randomUUID(),new OnlinePaymentService.Start(m,"1.00","SEED","CARD"))).hasMessageContaining("limits");
   var p=service.start(actor,UUID.randomUUID(),new OnlinePaymentService.Start(m,"10.00","SEED","CARD"));service.simulate(actor,p.id());assertThat(balance(m)).isEqualByComparingTo("9.75");
  } finally {jdbc.update("UPDATE organisation_settings SET fee_bearer='UNION',payment_min=1,payment_max=1000000 WHERE singleton=true");}
 }
 private OnlinePaymentService service(PaymentProvider p){return new OnlinePaymentService(jdbc,p,ownership,permissions,years,transactions);}
 private BigDecimal balance(UUID m){return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM ledger_entries WHERE member_id=?",BigDecimal.class,m);}
 private UUID member(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Online fixture',?)",id,"ON-"+id,today);return id;}
 private UUID user(UUID member){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash,member_id) VALUES(?,?,'fixture',?)",id,id+"@invalid.example",member);jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code='MEMBER'",id,today);return id;}
}
