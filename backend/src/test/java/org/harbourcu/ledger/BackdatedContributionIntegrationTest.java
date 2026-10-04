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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.flyway.default-schema=backdated_contribution_tests",
      "spring.datasource.hikari.schema=backdated_contribution_tests",
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@Transactional
class BackdatedContributionIntegrationTest {
  @Autowired BackdatedContributionService service;
  @Autowired ApprovalService approvals;
  @Autowired JdbcTemplate jdbc;
  @Autowired LedgerWorkflowService workflow;

  record Fixture(
      UUID maker,
      UUID checker,
      UUID a,
      UUID b,
      UUID cycle,
      LocalDate original,
      LocalDate purchase,
      LocalDate maturity) {}

  @Test
  void previewWritesNothingAndIndependentRepostPreservesOriginalsAndExactIncome() {
    var f = fixture();
    long before = count("ledger_entries");
    var preview = service.preview(f.maker(), input(f, "REPOST", null, null));
    assertThat(count("ledger_entries")).isEqualTo(before);
    assertThat(count("approval_requests")).isZero();
    assertThat(preview.repostAllowed()).isTrue();
    assertThat(preview.affectedCycles()).hasSize(1);
    var a =
        preview.affectedCycles().getFirst().shares().stream()
            .filter(s -> s.memberId().equals(f.a()))
            .findFirst()
            .orElseThrow();
    assertThat(new BigDecimal(a.actualIncome())).isEqualByComparingTo("13.34");
    UUID request =
        service.submit(f.maker(), input(f, "REPOST", null, preview.sourceFingerprint()), approvals);
    assertThat(workflow.pending(f.checker()))
        .extracting(LedgerWorkflowService.Pending::actionType)
        .contains("LEDGER_BACKDATED_POST");
    assertThat(service.review(f.checker(), request).affectedCycles()).hasSize(1);
    assertThatThrownBy(
            () -> approvals.decide(request, f.maker(), ApprovalService.Decision.APPROVE, null))
        .hasMessageContaining("own request");
    approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null);
    assertThat(balance(f.a())).isEqualByComparingTo("213.34");
    assertThat(balance(f.b())).isEqualByComparingTo("106.66");
    assertThat(count("investment_revision_history")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(actual_income_share) FROM investment_maturity_allocations WHERE investment_id=? AND revision=2",
                BigDecimal.class,
                f.cycle()))
        .isEqualByComparingTo("20");
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(amount) FROM ledger_entries WHERE investment_id=?",
                BigDecimal.class,
                f.cycle()))
        .isEqualByComparingTo("20");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE type='REVERSAL'", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT revision FROM investments WHERE id=?", Integer.class, f.cycle()))
        .isEqualTo(2);
    assertThatThrownBy(
            () -> approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null))
        .hasMessageContaining("no longer pending");
  }

  @Test
  void futureOnlyKeepsAllHistoricalAllocationsAndRecordsOriginalDate() {
    var f = fixture();
    var p = service.preview(f.maker(), input(f, "FUTURE_ONLY", null, null));
    UUID request =
        service.submit(
            f.maker(), input(f, "FUTURE_ONLY", LocalDate.now(), p.sourceFingerprint()), approvals);
    approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null);
    assertThat(
            jdbc.queryForObject(
                "SELECT revision FROM investments WHERE id=?", Integer.class, f.cycle()))
        .isEqualTo(1);
    assertThat(count("investment_revision_history")).isZero();
    assertThat(balance(f.a())).isEqualByComparingTo("210");
    assertThat(
            jdbc.queryForObject(
                "SELECT original_effective_date FROM ledger_entries WHERE reference=?",
                LocalDate.class,
                "APR-" + request))
        .isEqualTo(f.original());
    assertThat(
            jdbc.queryForObject(
                "SELECT effective_date FROM ledger_entries WHERE reference=?",
                LocalDate.class,
                "APR-" + request))
        .isEqualTo(LocalDate.now());
  }

  @Test
  void chronologicalCorrectionPropagatesIncomeToALaterPostedCycle() {
    var f = fixture();
    UUID later =
        cycle(
            f.a(), f.b(), f.maturity().plusDays(1), LocalDate.now().plusDays(30), "110", "POSTED");
    var p = service.preview(f.maker(), input(f, "REPOST", null, null));
    assertThat(p.affectedCycles()).hasSize(2);
    UUID request =
        service.submit(f.maker(), input(f, "REPOST", null, p.sourceFingerprint()), approvals);
    approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null);
    assertThat(
            jdbc.queryForObject(
                "SELECT participation FROM investment_allocations WHERE investment_id=? AND member_id=?",
                BigDecimal.class,
                later,
                f.a()))
        .isEqualByComparingTo("213.34");
    assertThat(
            jdbc.queryForObject(
                "SELECT participation FROM investment_allocations WHERE investment_id=? AND member_id=?",
                BigDecimal.class,
                later,
                f.b()))
        .isEqualByComparingTo("106.66");
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(cost_share) FROM investment_allocations WHERE investment_id=?",
                BigDecimal.class,
                later))
        .isEqualByComparingTo("220");
  }

  @Test
  void permissionsDatesClosedYearsAndPendingDependenciesRemainProtected() {
    var f = fixture();
    UUID member = user("MEMBER");
    assertThatThrownBy(() -> service.preview(member, input(f, "REPOST", null, null)))
        .isInstanceOf(SecurityException.class);
    jdbc.update(
        "INSERT INTO investments(tenor,custom_days,purchase_date,maturity_date,cost,maturity_value,rollover_source_id) VALUES('CUSTOM',30,current_date,current_date+30,220,242,?)",
        f.cycle());
    var p = service.preview(f.maker(), input(f, "FUTURE_ONLY", null, null));
    assertThat(p.repostAllowed()).isFalse();
    assertThat(p.restriction()).contains("pending/draft");
    assertThatThrownBy(
            () ->
                service.submit(
                    f.maker(), input(f, "REPOST", null, p.sourceFingerprint()), approvals))
        .hasMessageContaining("pending/draft");
    assertThatThrownBy(
            () ->
                service.submit(
                    f.maker(),
                    input(f, "FUTURE_ONLY", f.purchase(), p.sourceFingerprint()),
                    approvals))
        .hasMessageContaining("next cycle");
    jdbc.update(
        "UPDATE fiscal_years SET status='CLOSED',closed_by=?,closed_at=now() WHERE year=?",
        f.maker(),
        LocalDate.now().getYear());
    assertThatThrownBy(() -> service.preview(f.maker(), input(f, "REPOST", null, null)))
        .hasMessageContaining("not open");
  }

  @Test
  void explicitParticipationRemainsFixedWhileItsIncomeIsReconciled() {
    var f = fixture();
    jdbc.update("UPDATE investments SET status='DRAFT',actual_proceeds=NULL WHERE id=?", f.cycle());
    jdbc.update(
        "UPDATE investment_allocations SET edit_reason='Fixture fixed participation' WHERE investment_id=? AND member_id=?",
        f.cycle(),
        f.a());
    jdbc.update(
        "UPDATE investments SET status='MATURED',actual_proceeds=220 WHERE id=?", f.cycle());
    var p = service.preview(f.maker(), input(f, "REPOST", null, null));
    assertThat(p.repostAllowed()).isTrue();
    UUID request =
        service.submit(f.maker(), input(f, "REPOST", null, p.sourceFingerprint()), approvals);
    approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null);
    assertThat(
            jdbc.queryForObject(
                "SELECT participation FROM investment_allocations WHERE investment_id=? AND member_id=?",
                BigDecimal.class,
                f.cycle(),
                f.a()))
        .isEqualByComparingTo("100");
    assertThat(
            jdbc.queryForObject(
                "SELECT edit_reason FROM investment_allocations WHERE investment_id=? AND member_id=?",
                String.class,
                f.cycle(),
                f.a()))
        .isEqualTo("Fixture fixed participation");
    assertThat(balance(f.a())).isEqualByComparingTo("210");
  }

  @Test
  void principalOnlyRolloverUsesCorrectedPrincipalWithoutRedistributedIncome() {
    var f = fixture();
    UUID later =
        cycle(
            f.a(), f.b(), f.maturity().plusDays(1), LocalDate.now().plusDays(30), "100", "POSTED");
    jdbc.update(
        "UPDATE investments SET rollover_source_id=?,rollover_policy='ROLL_PRINCIPAL_ONLY' WHERE id=?",
        f.cycle(),
        later);
    jdbc.update("UPDATE investments SET status='ROLLED_OVER' WHERE id=?", f.cycle());
    var p = service.preview(f.maker(), input(f, "REPOST", null, null));
    UUID request =
        service.submit(f.maker(), input(f, "REPOST", null, p.sourceFingerprint()), approvals);
    approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null);
    assertThat(
            jdbc.queryForObject(
                "SELECT participation FROM investment_allocations WHERE investment_id=? AND member_id=?",
                BigDecimal.class,
                later,
                f.a()))
        .isEqualByComparingTo("200");
    assertThat(
            jdbc.queryForObject(
                "SELECT participation FROM investment_allocations WHERE investment_id=? AND member_id=?",
                BigDecimal.class,
                later,
                f.b()))
        .isEqualByComparingTo("100");
  }

  @Test
  void revokedMakerPermissionPreventsPostingAndASecondCorrectionCanUseTheCurrentRevision() {
    var f = fixture();
    var p = service.preview(f.maker(), input(f, "REPOST", null, null));
    UUID first =
        service.submit(f.maker(), input(f, "REPOST", null, p.sourceFingerprint()), approvals);
    approvals.decide(first, f.checker(), ApprovalService.Decision.APPROVE, null);
    var second = service.preview(f.maker(), input(f, "REPOST", null, null));
    assertThat(second.repostAllowed()).isTrue();
    UUID request =
        service.submit(f.maker(), input(f, "REPOST", null, second.sourceFingerprint()), approvals);
    jdbc.update(
        "UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Fixture revoked' WHERE user_id=?",
        f.checker(),
        f.maker());
    assertThatThrownBy(
            () -> approvals.decide(request, f.checker(), ApprovalService.Decision.APPROVE, null))
        .isInstanceOf(SecurityException.class);
    assertThat(balance(f.a())).isEqualByComparingTo("213.34");
  }

  BackdatedContributionService.Input input(
      Fixture f, String mode, LocalDate next, String fingerprint) {
    return new BackdatedContributionService.Input(
        f.a(),
        "ADDITION",
        "100.00",
        f.original(),
        mode,
        next,
        "Synthetic historical contribution evidence",
        fingerprint);
  }

  Fixture fixture() {
    int year = LocalDate.now().getYear();
    LocalDate original = LocalDate.of(year, 1, 1),
        purchase = original.plusDays(2),
        maturity = purchase.plusDays(30);
    UUID a = member("A", original),
        b = member("B", original),
        maker = user("TREASURER"),
        checker = user("TREASURER");
    entry(a, "OPENING_BALANCE", "PRINCIPAL", "100", original, null, "OA-" + a);
    entry(b, "OPENING_BALANCE", "PRINCIPAL", "100", original, null, "OB-" + b);
    UUID cycle = cycle(a, b, purchase, maturity, "100", "MATURED");
    entry(a, "INCOME_ALLOCATION", "INCOME", "10", maturity, cycle, "IA-" + cycle);
    entry(b, "INCOME_ALLOCATION", "INCOME", "10", maturity, cycle, "IB-" + cycle);
    return new Fixture(maker, checker, a, b, cycle, original, purchase, maturity);
  }

  private UUID cycle(
      UUID a, UUID b, LocalDate purchase, LocalDate maturity, String participation, String status) {
    UUID id = UUID.randomUUID();
    BigDecimal part = new BigDecimal(participation),
        cost = part.multiply(BigDecimal.TWO),
        fv = cost.multiply(new BigDecimal("1.1"));
    jdbc.update(
        "INSERT INTO investments(id,tenor,custom_days,purchase_date,maturity_date,cost,maturity_value,status,actual_proceeds) VALUES(?,'CUSTOM',?,?,?,?,?,?,?)",
        id,
        (int) java.time.temporal.ChronoUnit.DAYS.between(purchase, maturity),
        purchase,
        maturity,
        cost,
        fv,
        status,
        "MATURED".equals(status) ? fv : null);
    // Construct the allowed draft allocation then restore its posted status, just as the workflow
    // does.
    jdbc.update("UPDATE investments SET status='DRAFT',actual_proceeds=NULL WHERE id=?", id);
    for (UUID member : new UUID[] {a, b})
      jdbc.update(
          "INSERT INTO investment_allocations(investment_id,member_id,participation,ratio_basis_points,cost_share,maturity_value_share,income_share,closing_balance) VALUES(?,?,?,5000,?,?,?,?)",
          id,
          member,
          part,
          part,
          part.multiply(new BigDecimal("1.1")),
          part.multiply(new BigDecimal("0.1")),
          part.multiply(new BigDecimal("1.1")));
    if ("MATURED".equals(status))
      for (UUID member : new UUID[] {a, b})
        jdbc.update(
            "INSERT INTO investment_maturity_allocations(investment_id,member_id,actual_maturity_share,actual_income_share) VALUES(?,?,?,?)",
            id,
            member,
            part.multiply(new BigDecimal("1.1")),
            part.multiply(new BigDecimal("0.1")));
    jdbc.update(
        "UPDATE investments SET status=?,actual_proceeds=? WHERE id=?",
        status,
        "MATURED".equals(status) ? fv : null,
        id);
    return id;
  }

  private UUID member(String prefix, LocalDate joined) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Anonymous fixture',?)",
        id,
        prefix + id,
        joined);
    return id;
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture-hash')",
        id,
        id + "@example.invalid");
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code=?",
        id,
        role);
    return id;
  }

  private void entry(
      UUID member,
      String type,
      String component,
      String amount,
      LocalDate date,
      UUID cycle,
      String reference) {
    jdbc.update(
        "INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,investment_id,reference) VALUES(?,?,?,?,?,?,?,?)",
        member,
        type,
        component,
        new BigDecimal(amount),
        date,
        date.getYear(),
        cycle,
        reference);
  }

  private BigDecimal balance(UUID member) {
    return jdbc.queryForObject(
        "SELECT sum(amount) FROM ledger_entries WHERE member_id=?", BigDecimal.class, member);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }
}
