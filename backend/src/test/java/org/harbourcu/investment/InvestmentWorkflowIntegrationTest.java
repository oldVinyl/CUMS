package org.harbourcu.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class InvestmentWorkflowIntegrationTest {
    @Autowired InvestmentService investments;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    @Transactional
    void proposalPreviewAndIndependentApprovalReserveExactlyAvailableFunds() {
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        UUID first = member("A", "100.00");
        UUID second = member("B", "300.00");
        UUID id = investments.create(maker, draft("402.00", "442.00"));
        InvestmentService.Detail preview = investments.get(maker, id);
        assertThat(preview.pool()).isEqualByComparingTo("400.00");
        assertThat(preview.costDifference()).isEqualByComparingTo("2.00");
        assertThat(preview.expectedIncome()).isEqualByComparingTo("40.00");
        assertThat(preview.expectedClosing()).isEqualByComparingTo("440.00");
        assertThat(preview.allocations()).hasSize(2);
        assertThat(preview.allocations().stream().map(InvestmentService.Allocation::costShare)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("402.00");
        assertThat(preview.allocations().stream().map(InvestmentService.Allocation::incomeShare)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("40.00");
        assertThat(preview.allocations().stream().map(InvestmentService.Allocation::ratioPercent)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");

        UUID request = approvals.submit("INVESTMENT_POST", payload(id), maker, 1);
        assertThatThrownBy(() -> approvals.submit("INVESTMENT_POST", payload(id), maker, 1))
            .hasMessageContaining("awaits approval");
        assertThatThrownBy(() -> approvals.decide(request, maker, ApprovalService.Decision.APPROVE, null))
            .hasMessageContaining("Maker cannot approve");
        approvals.decide(request, checker, ApprovalService.Decision.APPROVE, null);
        assertThat(investments.get(maker, id).investment().status()).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'INVESTMENT_POSTED' AND entity_id = ?",
            Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT equity FROM member_balances WHERE member_id = ?",
            BigDecimal.class, first)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT equity FROM member_balances WHERE member_id = ?",
            BigDecimal.class, second)).isEqualByComparingTo("300.00");
        assertThatThrownBy(() -> investments.create(maker, draft("1.00", "2.00")))
            .hasMessageContaining("No members have uninvested funds");
    }

    @Test
    @Transactional
    void staleDraftCannotOversubscribeOnApproval() {
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        member("C", "50.00");
        UUID stale = investments.create(maker, draft("50.00", "55.00"));
        UUID posted = investments.create(maker, draft("50.00", "55.00"));
        UUID postRequest = approvals.submit("INVESTMENT_POST", payload(posted), maker, 1);
        approvals.decide(postRequest, checker, ApprovalService.Decision.APPROVE, null);
        UUID staleRequest = approvals.submit("INVESTMENT_POST", payload(stale), maker, 1);
        assertThatThrownBy(() -> approvals.decide(staleRequest, checker, ApprovalService.Decision.APPROVE, null))
            .hasMessageContaining("uninvested funds changed");
        assertThat(investments.get(maker, stale).investment().status()).isEqualTo("DRAFT");
    }

    @Test
    @Transactional
    void editingParticipationRecordsReasonAndRebalancesExactShares() {
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        UUID first = member("D", "100.00");
        member("E", "100.00");
        UUID id = investments.create(maker, draft("201.00", "221.00"));
        assertThatThrownBy(() -> investments.editParticipation(maker, id, first, "40.00", " "))
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> investments.editParticipation(maker, id, first, "101.00", "Correction"))
            .hasMessageContaining("uninvested funds");
        investments.editParticipation(maker, id, first, "40.00", "Partial participation requested");
        var preview = investments.get(maker, id);
        assertThat(preview.pool()).isEqualByComparingTo("140.00");
        assertThat(preview.costDifference()).isEqualByComparingTo("61.00");
        assertThat(preview.allocations().stream().map(InvestmentService.Allocation::costShare)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("201.00");
        assertThat(jdbc.queryForObject("""
            SELECT edit_reason FROM investment_allocations WHERE investment_id = ? AND member_id = ?
            """, String.class, id, first)).isEqualTo("Partial participation requested");
        UUID request = approvals.submit("INVESTMENT_POST", payload(id), maker, 1);
        assertThatThrownBy(() -> investments.editParticipation(maker, id, first, "30.00", "Late edit"))
            .hasMessageContaining("awaits approval");
        approvals.decide(request, checker, ApprovalService.Decision.APPROVE, null);
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE investment_allocations SET participation = 1
            WHERE investment_id = ? AND member_id = ?
            """, id, first)).hasMessageContaining("immutable");
    }

    @Test
    @Transactional
    void readAndWriteRequireInvestmentPermissions() {
        UUID member = user("MEMBER");
        assertThatThrownBy(() -> investments.list(member)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> investments.create(member, draft("1.00", "2.00")))
            .isInstanceOf(SecurityException.class);
        UUID admin = user("ADMIN");
        assertThat(investments.list(admin)).isNotNull();
        assertThatThrownBy(() -> investments.create(admin, draft("1.00", "2.00")))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @Transactional
    void approvedActualMaturityPostsExactIncomeIncludingLosses() {
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        UUID first = member("Maturity A", "100.00");
        UUID second = member("Maturity B", "100.00");
        UUID investment = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        jdbc.update("""
            INSERT INTO investments (id, tenor, purchase_date, maturity_date,
                                     cost, maturity_value, status, created_by)
            VALUES (?, '91_DAY', ?, ?, 200.00, 220.00, 'DRAFT', ?)
            """, investment, today.minusDays(1), today, maker);
        for (UUID member : new UUID[] { first, second }) {
            jdbc.update("""
                INSERT INTO investment_allocations
                    (investment_id, member_id, participation, ratio_basis_points, cost_share,
                     maturity_value_share, income_share, closing_balance)
                VALUES (?, ?, 100.00, 5000, 100.00, 110.00, 10.00, 110.00)
                """, investment, member);
        }
        jdbc.update("UPDATE investments SET status = 'POSTED' WHERE id = ?", investment);
        var payload = json.createObjectNode().put("investmentId", investment.toString())
            .put("actualProceeds", "190.00");
        UUID request = approvals.submit("INVESTMENT_MATURE", payload, maker, 1);
        assertThatThrownBy(() -> approvals.submit("INVESTMENT_MATURE", payload, maker, 1))
            .hasMessageContaining("awaits approval");
        approvals.decide(request, checker, ApprovalService.Decision.APPROVE, null);
        var detail = investments.get(maker, investment);
        assertThat(detail.investment().status()).isEqualTo("MATURED");
        assertThat(detail.investment().actualProceeds()).isEqualByComparingTo("190.00");
        assertThat(detail.maturityAllocations()).hasSize(2);
        assertThat(detail.maturityAllocations().stream()
            .map(InvestmentService.MaturityAllocation::actualIncomeShare)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-10.00");
        assertThat(jdbc.queryForObject("""
            SELECT sum(amount) FROM ledger_entries
            WHERE investment_id = ? AND type = 'INCOME_ALLOCATION'
            """, BigDecimal.class, investment)).isEqualByComparingTo("-10.00");
        assertThat(jdbc.queryForObject("SELECT equity FROM member_balances WHERE member_id = ?",
            BigDecimal.class, first)).isEqualByComparingTo("95.00");
        assertThat(jdbc.queryForObject("SELECT equity FROM member_balances WHERE member_id = ?",
            BigDecimal.class, second)).isEqualByComparingTo("95.00");
        assertThatThrownBy(() -> approvals.submit("INVESTMENT_MATURE", payload, maker, 1))
            .hasMessageContaining("not posted");
    }

    private InvestmentService.Create draft(String cost, String fv) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        return new InvestmentService.Create("91_DAY", null, "Fixture Bank", null,
            today, today.plusDays(91), cost, fv);
    }

    private com.fasterxml.jackson.databind.JsonNode payload(UUID id) {
        return json.createObjectNode().put("investmentId", id.toString());
    }

    private UUID member(String name, String amount) {
        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, ?, ?)",
            id, "INV-" + id, name, today);
        jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount, effective_date,
                                        fiscal_year, reference)
            VALUES (?, 'SEED', 'PRINCIPAL', ?, ?, ?, ?)
            """, id, new BigDecimal(amount), today, today.getYear(), "INV-SEED-" + id);
        return id;
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "test-" + id + "@invalid.example");
        jdbc.update("INSERT INTO user_role_assignments (user_id, role_id, start_date) VALUES (?, ?, ?)",
            id, roleId, LocalDate.now(ZoneId.of("Africa/Accra")));
        return id;
    }
}
