package org.harbourcu.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(
    properties = {
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "spring.flyway.default-schema=financial_change_audit_tests",
      "spring.datasource.hikari.schema=financial_change_audit_tests"
    })
@AutoConfigureMockMvc
class FinancialChangeAuditIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  final LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));

  @Test
  void exactPaymentChangesExcludeAllFreeTextAndPreserveBeforeAfter() throws Exception {
    UUID member = member(), payment = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payments(id,member_id,amount,method,internal_reference,status,purpose,rejection_reason) VALUES(?,?,123.4567,'PRIVATE_METHOD','PRIVATE_BANK_REFERENCE','SUBMITTED','SEED','PRIVATE_CONTACT_NOTE')",
        payment,
        member);
    jdbc.update(
        "UPDATE payments SET status='UNDER_REVIEW',verified_amount=120.12 WHERE id=?", payment);
    var change = json.readTree(change(payment, "UPDATE"));
    assertThat(change.path("before").path("amount").asText()).isEqualTo("123.4567");
    assertThat(change.path("before").path("status").asText()).isEqualTo("SUBMITTED");
    assertThat(change.path("after").path("status").asText()).isEqualTo("UNDER_REVIEW");
    assertThat(change.path("after").path("verified_amount").asText()).isEqualTo("120.12");
    assertThat(change.toString())
        .doesNotContain("PRIVATE_", "method", "internal_reference", "rejection_reason");
  }

  @Test
  void allocationReplacementRecordsExactDeleteInsertAndNoTimestampNoise() throws Exception {
    UUID member = member(), investment = investment();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "INSERT INTO investment_allocations(investment_id,member_id,participation,ratio_basis_points,cost_share,maturity_value_share,income_share,closing_balance,edit_reason) VALUES(?,?,100,10000,100,110,10,110,'PRIVATE_EDIT_NOTE')",
                  investment,
                  member);
              jdbc.update("DELETE FROM investment_allocations WHERE investment_id=?", investment);
              jdbc.update(
                  "INSERT INTO investment_allocations(investment_id,member_id,participation,ratio_basis_points,cost_share,maturity_value_share,income_share,closing_balance) VALUES(?,?,100,10000,100,120,20,120)",
                  investment,
                  member);
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT f.transaction_id) FROM financial_audit_changes f JOIN audit_log a ON a.id=f.audit_id WHERE a.entity_id=? AND a.entity_type='ALLOCATION'",
                Integer.class,
                investment))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id=? AND entity_type='ALLOCATION'",
                Integer.class,
                investment))
        .isEqualTo(3);
    assertThat(change(investment, "DELETE"))
        .contains("cost_share", "100.00")
        .doesNotContain("PRIVATE_EDIT_NOTE");
    jdbc.update("UPDATE investments SET updated_at=now() WHERE id=?", investment);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id=? AND action='FINANCIAL_INVESTMENT_UPDATE'",
                Integer.class,
                investment))
        .isZero();
  }

  @Test
  void financialWritesAndAuditRollBackTogether() {
    UUID member = member(), entry = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(manager)
                    .executeWithoutResult(
                        status -> {
                          jdbc.update(
                              "INSERT INTO ledger_entries(id,member_id,type,component,amount,effective_date,fiscal_year,reference,narration) VALUES(?,?,'SEED','PRINCIPAL',42.1234,?,?,?,'PRIVATE_NARRATION')",
                              entry,
                              member,
                              today,
                              today.getYear(),
                              entry.toString());
                          throw new IllegalStateException("Synthetic transaction rejection");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE id=?", Integer.class, entry))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id=?", Integer.class, entry))
        .isZero();
  }

  @Test
  void authenticatedActorAndRequestContextAreClearedForReusedConnections() {
    UUID actor = user("AUDITOR"), investment = investment();
    var token = sessions.create(actor);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.8");
    request.setAttribute(TraceIdFilter.ATTRIBUTE, UUID.randomUUID().toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      assertThat(sessions.resolveAccess(token.access())).isPresent();
      jdbc.update("UPDATE investments SET maturity_value=120 WHERE id=?", investment);
      assertThat(
              jdbc.queryForObject(
                  "SELECT actor_id FROM audit_log WHERE entity_id=? AND action='FINANCIAL_INVESTMENT_UPDATE'",
                  UUID.class,
                  investment))
          .isEqualTo(actor);
      assertThat(sessions.resolveAccess("invalid-token")).isEmpty();
      jdbc.update("UPDATE investments SET maturity_value=130 WHERE id=?", investment);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    jdbc.update("UPDATE investments SET maturity_value=140 WHERE id=?", investment);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id=? AND action='FINANCIAL_INVESTMENT_UPDATE' AND actor_id IS NULL",
                Integer.class,
                investment))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id=? AND action='FINANCIAL_INVESTMENT_UPDATE' AND request_ip IS NULL AND request_id IS NULL",
                Integer.class,
                investment))
        .isEqualTo(1);
  }

  @Test
  void authorizedViewAndCsvExposeOnlySafeChangesAndSnapshotsAreImmutable() throws Exception {
    UUID actor = user("AUDITOR"), investment = investment();
    var token = sessions.create(actor);
    String body =
        mvc.perform(
                get("/api/v1/audit")
                    .param("from", today.toString())
                    .param("to", today.toString())
                    .param("entityId", investment.toString())
                    .cookie(new Cookie("cu_access", token.access())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains("financialChange", "before", "after", "100.0000")
        .doesNotContain("PRIVATE_INSTITUTION", "PRIVATE_REFERENCE");
    String csv =
        mvc.perform(
                get("/api/v1/audit/export.csv")
                    .param("from", today.toString())
                    .param("to", today.toString())
                    .param("entityId", investment.toString())
                    .cookie(new Cookie("cu_access", token.access())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(csv).contains("Financial change", "100.0000").doesNotContain("PRIVATE_INSTITUTION");
    UUID event =
        jdbc.queryForObject(
            "SELECT id FROM audit_log WHERE entity_id=? AND action='FINANCIAL_INVESTMENT_INSERT'",
            UUID.class,
            investment);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM financial_audit_changes WHERE audit_id=?", event))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE financial_audit_changes SET before_values='{}' WHERE audit_id=?",
                    event))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  private String change(UUID id, String operation) {
    return jdbc.queryForObject(
        "SELECT jsonb_build_object('before',f.before_values,'after',f.after_values)::text FROM financial_audit_changes f JOIN audit_log a ON a.id=f.audit_id WHERE a.entity_id=? AND f.operation=? ORDER BY a.occurred_at DESC LIMIT 1",
        String.class,
        id,
        operation);
  }

  private UUID investment() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO investments(id,tenor,custom_days,purchase_date,maturity_date,cost,maturity_value,institution,reference_number) VALUES(?,'CUSTOM',1,?,?,100,110,'PRIVATE_INSTITUTION',?)",
        id,
        today,
        today.plusDays(1),
        "PRIVATE_REFERENCE_" + id);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Anonymous synthetic audit fixture',?)",
        id,
        "AUD-" + id.toString().substring(0, 8),
        today);
    return id;
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",
        id,
        id + "@example.invalid");
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",
        id,
        today,
        role);
    return id;
  }
}
