package org.harbourcu.reconciliation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.UUID;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "spring.flyway.default-schema=settlement_tests",
      "spring.datasource.hikari.schema=settlement_tests"
    })
@AutoConfigureMockMvc
class SettlementIntegrationTest {
  @Autowired SettlementService service;
  @Autowired ReconciliationService reconciliation;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired MockMvc mvc;
  final LocalDate today = ReconciliationService.today();

  @Test
  void independentMatchAndCorrectionPreserveHistoryWithoutPostingAnotherContribution() {
    UUID maker = user("TREASURER"), checker = user("PRESIDENT"), payment = payment();
    var p = find(maker, payment);
    String total = total();
    assertThat(p.expectedNet()).isEqualTo("97.5000");
    var figures = reconciliation.preview(maker);
    UUID match =
        service.submit(
            maker,
            payment,
            new SettlementService.Input(
                "97.50", today, "Synthetic bank evidence", p.sourceFingerprint()));
    assertThat(
            jdbc.queryForObject(
                "SELECT encrypted_notes FROM payment_settlement_matches WHERE id=?",
                String.class,
                match))
        .doesNotContain("Synthetic bank evidence");
    assertThatThrownBy(() -> service.decide(maker, match, "APPROVE", null))
        .hasMessageContaining("own request");
    assertThatThrownBy(
            () ->
                service.submit(
                    maker,
                    payment,
                    new SettlementService.Input(
                        "97.50", today, "Second attempt", p.sourceFingerprint())))
        .hasMessageContaining("awaiting review");
    service.decide(checker, match, "APPROVE", null);
    assertThat(find(maker, payment).settledAmount()).isEqualTo("97.50");
    assertThat(total()).isEqualTo(total);
    assertThat(reconciliation.preview(maker).sourceFingerprint())
        .isNotEqualTo(figures.sourceFingerprint());
    assertThatThrownBy(() -> service.decide(checker, match, "APPROVE", null))
        .hasMessageContaining("no longer pending");
    assertThatThrownBy(
            () ->
                service.submit(
                    maker,
                    payment,
                    new SettlementService.Input(
                        "97.50", today, "No change", find(maker, payment).sourceFingerprint())))
        .hasMessageContaining("already recorded");
    UUID correction =
        service.submit(
            maker,
            payment,
            new SettlementService.Input(
                "96.50",
                today,
                "Synthetic corrected bank evidence; one GHS discrepancy",
                find(maker, payment).sourceFingerprint()));
    service.decide(checker, correction, "APPROVE", null);
    assertThat(find(checker, payment).settledAmount()).isEqualTo("96.50");
    assertThat(total()).isEqualTo(total);
    assertThat(
            service.list(checker, 0).matches().stream().filter(m -> m.paymentId().equals(payment)))
        .hasSize(2);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM payment_settlement_matches WHERE id=?", match))
        .hasMessageContaining("append-only");
  }

  @Test
  void changedPaymentAndRevokedMakerRollbackApprovalDecisions() {
    UUID maker = user("TREASURER"), checker = user("PRESIDENT"), payment = payment();
    var p = find(maker, payment);
    UUID match =
        service.submit(
            maker,
            payment,
            new SettlementService.Input(
                "97.50", today, "Synthetic evidence", p.sourceFingerprint()));
    jdbc.update("UPDATE payments SET fee_amount=3 WHERE id=?", payment);
    assertThatThrownBy(() -> service.decide(checker, match, "APPROVE", null))
        .hasMessageContaining("changed");
    assertNoDecision(match);
    service.cancel(maker, match);
    UUID next =
        service.submit(
            maker,
            payment,
            new SettlementService.Input(
                "97.00", today, "Reviewed changed fee", find(maker, payment).sourceFingerprint()));
    jdbc.update("DELETE FROM user_role_assignments WHERE user_id=?", maker);
    assertThatThrownBy(() -> service.decide(checker, next, "APPROVE", null))
        .isInstanceOf(SecurityException.class);
    assertNoDecision(next);
    assertThat(find(checker, payment).settledAmount()).isNull();
  }

  @Test
  void datesNotesStatusAndAccessAreChecked() throws Exception {
    UUID maker = user("TREASURER"), member = user("MEMBER"), payment = payment();
    var p = find(maker, payment);
    for (LocalDate date : java.util.List.of(today.minusDays(1), today.plusDays(1)))
      assertThatThrownBy(
              () ->
                  service.submit(
                      maker,
                      payment,
                      new SettlementService.Input("97.5", date, "Evidence", p.sourceFingerprint())))
          .hasMessageContaining("settlement date");
    assertThatThrownBy(
            () ->
                service.submit(
                    maker,
                    payment,
                    new SettlementService.Input("97.5", today, "", p.sourceFingerprint())))
        .hasMessageContaining("evidence");
    assertThatThrownBy(
            () ->
                service.submit(
                    maker,
                    payment,
                    new SettlementService.Input("-1", today, "Evidence", p.sourceFingerprint())))
        .hasMessageContaining("nonnegative");
    assertThatThrownBy(() -> service.list(member, 0)).isInstanceOf(SecurityException.class);
    mvc.perform(get("/api/v1/reconciliations/settlements")).andExpect(status().isUnauthorized());
    var token = sessions.create(member);
    mvc.perform(
            get("/api/v1/reconciliations/settlements")
                .cookie(new Cookie("cu_access", token.access())))
        .andExpect(status().isForbidden());
    var staff = sessions.create(maker);
    mvc.perform(
            post("/api/v1/reconciliations/settlements/payments/" + payment)
                .cookie(new Cookie("cu_access", staff.access()))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/reconciliations/settlements/payments/" + payment)
                .cookie(new Cookie("cu_access", staff.access()))
                .header("X-CSRF-Token", staff.csrf())
                .header("Origin", "https://foreign.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    jdbc.update("UPDATE payments SET status='PENDING' WHERE id=?", payment);
    assertThatThrownBy(
            () ->
                service.submit(
                    maker,
                    payment,
                    new SettlementService.Input("97.5", today, "Evidence", p.sourceFingerprint())))
        .hasMessageContaining("gateway-confirmed");
  }

  @Test
  void concurrentSubmissionsCreateOnlyOnePendingReview() throws Exception {
    UUID maker = user("TREASURER"), payment = payment();
    var input =
        new SettlementService.Input(
            "97.50",
            today,
            "Synthetic concurrent evidence",
            find(maker, payment).sourceFingerprint());
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Boolean> submit =
          () -> {
            gate.await();
            try {
              service.submit(maker, payment, input);
              return true;
            } catch (IllegalStateException conflict) {
              return false;
            }
          };
      var first = pool.submit(submit);
      var second = pool.submit(submit);
      gate.countDown();
      assertThat(
              java.util.List.of(
                  first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_settlement_matches WHERE payment_id=?",
                Integer.class,
                payment))
        .isEqualTo(1);
  }

  private void assertNoDecision(UUID match) {
    UUID request =
        jdbc.queryForObject(
            "SELECT approval_request_id FROM payment_settlement_matches WHERE id=?",
            UUID.class,
            match);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM approval_decisions WHERE request_id=?",
                Integer.class,
                request))
        .isZero();
  }

  private SettlementService.Payment find(UUID actor, UUID payment) {
    return service.list(actor, 0).payments().stream()
        .filter(p -> p.id().equals(payment))
        .findFirst()
        .orElseThrow();
  }

  private String total() {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(amount),0)::text FROM ledger_entries", String.class);
  }

  private UUID payment() {
    UUID member = UUID.randomUUID(), payment = UUID.randomUUID(), entry = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Anonymous settlement fixture',?)",
        member,
        "SM-" + member,
        today);
    jdbc.update(
        "INSERT INTO payments(id,member_id,amount,method,provider,internal_reference,status,purpose,fee_amount,confirmed_at,effective_date) VALUES(?,?,100,'Gateway','FakeProvider',?,'CONFIRMED','ADDITION',2.50,now(),?)",
        payment,
        member,
        "SYNTHETIC-" + payment,
        today);
    jdbc.update(
        "INSERT INTO ledger_entries(id,member_id,type,component,amount,effective_date,fiscal_year,payment_id,reference) VALUES(?,?,'ADDITION','PRINCIPAL',100,?,?,?,?)",
        entry,
        member,
        today,
        today.getYear(),
        payment,
        "SM-" + entry);
    jdbc.update("UPDATE payments SET ledger_entry_id=? WHERE id=?", entry, payment);
    return payment;
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",
        id,
        id + "@invalid.example");
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",
        id,
        today,
        role);
    return id;
  }
}
