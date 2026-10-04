package org.harbourcu.ledger;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.flyway.default-schema=backdated_atomic_tests",
      "spring.datasource.hikari.schema=backdated_atomic_tests",
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
class BackdatedContributionAtomicIntegrationTest {
  @Autowired PlatformTransactionManager transactions;
  @Autowired BackdatedContributionService service;
  @Autowired ApprovalService approvals;
  @Autowired JdbcTemplate jdbc;

  @Test
  void changedFinancialSourceRejectsApprovalAtomicallyAcrossRealTransactionBoundaries() {
    var fixtures = new BackdatedContributionIntegrationTest();
    fixtures.jdbc = jdbc;
    var f = new TransactionTemplate(transactions).execute(status -> fixtures.fixture());
    var p = service.preview(f.maker(), fixtures.input(f, "REPOST", null, null));
    UUID request =
        service.submit(
            f.maker(), fixtures.input(f, "REPOST", null, p.sourceFingerprint()), approvals);
    jdbc.update(
        "INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference) VALUES(?,'ADDITION','PRINCIPAL',1,?,?,?)",
        f.b(),
        LocalDate.now(),
        LocalDate.now().getYear(),
        "CHANGED-" + UUID.randomUUID());
    assertThatThrownBy(
            () -> approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null))
        .hasMessageContaining("source changed");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM approval_decisions WHERE request_id=?",
                Integer.class,
                request))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM approval_requests WHERE id=?", String.class, request))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM investment_revision_history", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(amount) FROM ledger_entries WHERE member_id=?",
                BigDecimal.class,
                f.a()))
        .isEqualByComparingTo("110");
  }
}
