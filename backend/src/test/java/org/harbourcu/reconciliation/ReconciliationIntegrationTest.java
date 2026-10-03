package org.harbourcu.reconciliation;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.UUID;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.flyway.default-schema=reconciliation_tests","spring.datasource.hikari.schema=reconciliation_tests","APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}) @AutoConfigureMockMvc class ReconciliationIntegrationTest {
 @Autowired ReconciliationService service;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;@Autowired MockMvc mvc;
 final LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
 @Test @Transactional void exactDifferencesAreRetainedAndMakerCannotFinalizeOwnRecord(){UUID maker=user("TREASURER"),checker=user("PRESIDENT");entry("100.25");var f=service.preview(maker);
  UUID id=service.submit(maker,new ReconciliationService.Input("0.00","95.00","Fixture bank charges and timing",f.sourceFingerprint()));
  assertThatThrownBy(()->service.decide(maker,id,"APPROVE",null)).hasMessageContaining("own request");service.decide(checker,id,"APPROVE",null);
  var r=service.list(checker,0).records().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();assertThat(r.status()).isEqualTo("APPROVED");assertThat(r.snapshot().path("cashDifference").asText()).isEqualTo(new java.math.BigDecimal("95.00").subtract(new java.math.BigDecimal(f.expectedCash())).toPlainString());assertThat(r.snapshot().path("integrityDifference").asText()).isEqualTo("0.0000");
  assertThatThrownBy(()->jdbc.update("UPDATE reconciliations SET notes='tampered' WHERE id=?",id)).hasMessageContaining("append-only");
 }
 @Test void changedSourcesBlockFinalizationWithoutRecordingADecision(){UUID maker=user("TREASURER"),checker=user("PRESIDENT");var f=service.preview(maker);UUID id=service.submit(maker,new ReconciliationService.Input("0.00","0.00","Stale source fixture",f.sourceFingerprint()));entry("1.00");
  assertThatThrownBy(()->service.decide(checker,id,"APPROVE",null)).hasMessageContaining("sources changed");
  UUID req=jdbc.queryForObject("SELECT approval_request_id FROM reconciliations WHERE id=?",UUID.class,id);assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions WHERE request_id=?",Integer.class,req)).isZero();
  service.cancel(maker,id);assertThat(jdbc.queryForObject("SELECT status FROM approval_requests WHERE id=?",String.class,req)).isEqualTo("CANCELLED");
 }
 @Test @Transactional void membersCannotReadUnionReconciliationAndStalePreviewsCannotSubmit() throws Exception {UUID member=user("MEMBER"),maker=user("TREASURER");var token=sessions.create(member);mvc.perform(get("/api/v1/reconciliations/preview").cookie(new Cookie("cu_access",token.access()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/reconciliations")).andExpect(status().isUnauthorized());
  assertThatThrownBy(()->service.submit(maker,new ReconciliationService.Input("0","0","Fixture","stale"))).hasMessageContaining("changed");
  var tokens=sessions.create(maker);mvc.perform(post("/api/v1/reconciliations").cookie(new Cookie("cu_access",tokens.access())).contentType("application/json").content("{}")).andExpect(status().isForbidden());
 }
 private void entry(String value){UUID member=UUID.randomUUID();jdbc.update("INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Reconcile fixture',?)",member,"RC-"+member,today);jdbc.update("INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference) VALUES(?,'SEED','PRINCIPAL',?,?,?,?)",member,new java.math.BigDecimal(value),today,today.getYear(),UUID.randomUUID().toString());}
 private UUID user(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture')",id,id+"@invalid.example");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",id,today,role);return id;}
}
