package org.harbourcu.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.approval.ApprovalService.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class ContributionApprovalIntegrationTest {
    @Autowired
    ApprovalService approvals;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    @Transactional
    void approvedContributionPostsOnceAndUpdatesDerivedBalance() {
        UUID member = member("ACTIVE");
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        UUID request = approvals.submit("LEDGER_POST", payload(member, "SEED", "125.50"), maker, 1);
        assertThat(balance(member)).isZero();
        approvals.decide(request, checker, Decision.APPROVE, null);
        assertThat(balance(member)).isEqualByComparingTo("125.5000");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ledger_entries
            WHERE reference = ? AND created_by = ? AND approved_by = ?
              AND component = 'PRINCIPAL' AND type = 'SEED'
            """, Integer.class, "APR-" + request, maker, checker)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log
            WHERE action = 'LEDGER_POSTED' AND details ->> 'approvalRequestId' = ?
            """, Integer.class, request.toString())).isEqualTo(1);
        assertThatThrownBy(() -> approvals.decide(request, checker, Decision.APPROVE, null))
            .hasMessageContaining("no longer pending");
        assertThat(balance(member)).isEqualByComparingTo("125.5000");
    }

    @Test
    @Transactional
    void invalidMoneyAndInactiveMemberCannotBeSubmitted() {
        UUID member = member("ACTIVE");
        UUID maker = user("TREASURER");
        assertThatThrownBy(() -> approvals.submit("LEDGER_POST", payload(member, "SEED", "1.001"), maker, 1))
            .hasMessageContaining("string amount");
        assertThatThrownBy(() -> approvals.submit("LEDGER_POST", payload(member, "SEED", "0.00"), maker, 1))
            .hasMessageContaining("positive GHS");
        assertThatThrownBy(() -> approvals.submit("LEDGER_POST", payload(member, "INCOME_ALLOCATION", "1.00"), maker, 1))
            .hasMessageContaining("Only seed and addition");
        UUID inactive = member("INACTIVE");
        assertThatThrownBy(() -> approvals.submit("LEDGER_POST", payload(inactive, "ADDITION", "1.00"), maker, 1))
            .hasMessageContaining("Member is not active");
    }

    @Test
    @Transactional
    void unpermittedMakerAndClosedYearCannotSubmit() {
        UUID member = member("ACTIVE");
        UUID ordinaryMember = user("MEMBER");
        assertThatThrownBy(() -> approvals.submit("LEDGER_POST", payload(member, "ADDITION", "10.00"), ordinaryMember, 1))
            .hasMessageContaining("Maker cannot submit");
        UUID treasurer = user("TREASURER");
        jdbc.update("""
            UPDATE fiscal_years SET status = 'CLOSED', closed_at = now(), closed_by = ?
            WHERE year = ?
            """, treasurer, LocalDate.now(ZoneId.of("Africa/Accra")).getYear());
        assertThatThrownBy(() -> approvals.submit("LEDGER_POST", payload(member, "ADDITION", "10.00"), treasurer, 1))
            .hasMessageContaining("Fiscal year is not open");
    }

    @Test
    @Transactional
    void approvedReversalExactlyOffsetsTodaysManualContribution() {
        UUID member = member("ACTIVE");
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        UUID post = approvals.submit("LEDGER_POST", payload(member, "ADDITION", "25.75"), maker, 1);
        approvals.decide(post, checker, Decision.APPROVE, null);
        UUID original = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE reference = ?",
            UUID.class, "APR-" + post);
        assertThat(balance(member)).isEqualByComparingTo("25.75");

        var reversalPayload = json.createObjectNode()
            .put("entryId", original.toString()).put("reason", "Fixture duplicate correction");
        UUID reversal = approvals.submit("LEDGER_REVERSE", reversalPayload, maker, 1);
        assertThat(balance(member)).isEqualByComparingTo("25.75");
        approvals.decide(reversal, checker, Decision.APPROVE, null);
        assertThat(balance(member)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
            SELECT amount FROM ledger_entries WHERE reversal_of = ?
            """, BigDecimal.class, original)).isEqualByComparingTo("-25.75");
        assertThatThrownBy(() -> approvals.submit("LEDGER_REVERSE", reversalPayload, maker, 1))
            .hasMessageContaining("already been reversed");
    }

    @Test
    @Transactional
    void reversalRequiresReasonAndPermission() {
        UUID member = member("ACTIVE");
        UUID maker = user("TREASURER");
        UUID checker = user("TREASURER");
        UUID ordinaryMember = user("MEMBER");
        UUID post = approvals.submit("LEDGER_POST", payload(member, "SEED", "10.00"), maker, 1);
        approvals.decide(post, checker, Decision.APPROVE, null);
        UUID original = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE reference = ?",
            UUID.class, "APR-" + post);
        var missingReason = json.createObjectNode()
            .put("entryId", original.toString()).put("reason", " ");
        assertThatThrownBy(() -> approvals.submit("LEDGER_REVERSE", missingReason, maker, 1))
            .hasMessageContaining("reason are required");
        var valid = json.createObjectNode()
            .put("entryId", original.toString()).put("reason", "Fixture correction");
        assertThatThrownBy(() -> approvals.submit("LEDGER_REVERSE", valid, ordinaryMember, 1))
            .hasMessageContaining("Maker cannot reverse");
    }

    private com.fasterxml.jackson.databind.JsonNode payload(UUID member, String type, String amount) {
        return json.createObjectNode().put("memberId", member.toString())
            .put("type", type).put("amount", amount);
    }

    private UUID member(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO members (id, member_number, full_name, join_date, status)
            VALUES (?, ?, 'Fixture Member', ?, ?)
            """, id, "T-" + id, LocalDate.now(ZoneId.of("Africa/Accra")), status);
        return id;
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "test-" + id + "@invalid.example");
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            VALUES (?, ?, ?)
            """, id, roleId, LocalDate.now(ZoneId.of("Africa/Accra")));
        return id;
    }

    private BigDecimal balance(UUID member) {
        return jdbc.queryForObject("""
            SELECT coalesce((SELECT equity FROM member_balances WHERE member_id = ?), 0)
            """, BigDecimal.class, member);
    }
}
