package org.harbourcu.ledger;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.flyway.default-schema=historical_year_tests",
      "spring.datasource.hikari.schema=historical_year_tests",
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@AutoConfigureMockMvc
class HistoricalYearIntegrationTest {
  @Autowired LedgerWorkflowService ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired MockMvc mvc;

  @Test
  void missingPeriodRequiresIndependentApprovalAndNeverPostsBalances() {
    UUID maker = user("PRESIDENT"), checker = user("TREASURER");
    UUID id = ledger.createHistoricalYear(maker, 2001, "Synthetic baseline source");
    assertThat(exists(2001)).isFalse();
    assertThatThrownBy(() -> ledger.createHistoricalYear(maker, 2001, "Duplicate"))
        .hasMessageContaining("already awaits");
    assertThat(ledger.pending(checker)).extracting(LedgerWorkflowService.Pending::id).contains(id);
    assertThatThrownBy(() -> ledger.decide(maker, id, "APPROVE", null))
        .hasMessageContaining("own request");
    ledger.decide(checker, id, "APPROVE", null);
    assertThat(exists(2001)).isTrue();
    assertThat(jdbc.queryForObject("SELECT status FROM fiscal_years WHERE year=2001", String.class))
        .isEqualTo("OPEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE fiscal_year=2001", Integer.class))
        .isZero();
    assertThatThrownBy(() -> ledger.createHistoricalYear(maker, 2001, "Already exists"))
        .hasMessageContaining("already exists");
    assertThatThrownBy(() -> ledger.decide(checker, id, "APPROVE", null))
        .hasMessageContaining("no longer pending");
  }

  @Test
  void revokedMakerRollsBackDecisionAndMissingPeriodRemainsMissing() {
    UUID maker = user("PRESIDENT"), checker = user("TREASURER");
    UUID id = ledger.createHistoricalYear(maker, 2002, "Synthetic verified source");
    jdbc.update(
        "UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Test revocation' WHERE user_id=?",
        checker,
        maker);
    assertThatThrownBy(() -> ledger.decide(checker, id, "APPROVE", null))
        .isInstanceOf(SecurityException.class);
    assertThat(exists(2002)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM approval_decisions WHERE request_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void datesReasonsPermissionsAndCancelledRequestsCannotCreatePeriods() {
    UUID maker = user("PRESIDENT"), checker = user("TREASURER");
    assertThatThrownBy(() -> ledger.createHistoricalYear(checker, 2003, "Source"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> ledger.createHistoricalYear(maker, 1999, "Source"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ledger.createHistoricalYear(
                    maker, LocalDate.now(ZoneId.of("Africa/Accra")).getYear(), "Source"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger.createHistoricalYear(maker, 2003, " "))
        .isInstanceOf(IllegalArgumentException.class);
    UUID id = ledger.createHistoricalYear(maker, 2003, "Source");
    ledger.cancel(maker, id);
    assertThatThrownBy(() -> ledger.decide(checker, id, "APPROVE", null))
        .hasMessageContaining("no longer pending");
    assertThat(exists(2003)).isFalse();
  }

  @Test
  void httpSetupRequiresCurrentSessionOriginAndCsrf() throws Exception {
    var session = sessions.create(user("PRESIDENT"));
    String endpoint = "/api/v1/ledger/fiscal-years/2004/create",
        body = "{\"reason\":\"Synthetic HTTP source\"}";
    mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(endpoint)
                .cookie(new Cookie("cu_access", session.access()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(endpoint)
                .cookie(new Cookie("cu_access", session.access()))
                .header("X-CSRF-Token", session.csrf())
                .header("Origin", "https://foreign.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(endpoint)
                .cookie(new Cookie("cu_access", session.access()))
                .header("X-CSRF-Token", session.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalRequestId").exists());
    assertThat(exists(2004)).isFalse();
  }

  private boolean exists(int year) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM fiscal_years WHERE year=?)", Boolean.class, year));
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",
        id,
        id + "@invalid.example");
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code=?",
        id,
        role);
    return id;
  }
}
