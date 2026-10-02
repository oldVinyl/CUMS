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
class FiscalYearCloseIntegrationTest {
    @Autowired
    ApprovalService approvals;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    @Transactional
    void independentApprovalCreatesBalanceForwardAndClosesCompletedYear() {
        int year = 2024;
        jdbc.update("""
            INSERT INTO fiscal_years (year, start_date, end_date)
            VALUES (2024, DATE '2024-01-01', DATE '2024-12-31')
            ON CONFLICT (year) DO NOTHING
            """);
        UUID member = member(LocalDate.of(2024, 1, 1));
        UUID laterMember = member(LocalDate.of(2025, 1, 1));
        UUID maker = user("TREASURER");
        UUID checker = user("PRESIDENT");
        entry(member, "SEED", "PRINCIPAL", "100.00", LocalDate.of(2024, 2, 29));
        entry(member, "INCOME_ALLOCATION", "INCOME", "12.50", LocalDate.of(2024, 12, 31));
        var payload = json.createObjectNode().put("year", year);
        UUID request = approvals.submit("FISCAL_YEAR_CLOSE", payload, maker, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fiscal_year_snapshots WHERE fiscal_year = ?",
            Integer.class, year)).isZero();
        approvals.decide(request, checker, Decision.APPROVE, "Year-end review complete");
        assertThat(jdbc.queryForObject("""
            SELECT principal FROM fiscal_year_snapshots WHERE fiscal_year = ? AND member_id = ?
            """, BigDecimal.class, year, member)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("""
            SELECT income FROM fiscal_year_snapshots WHERE fiscal_year = ? AND member_id = ?
            """, BigDecimal.class, year, member)).isEqualByComparingTo("12.50");
        assertThat(jdbc.queryForObject("""
            SELECT equity FROM fiscal_year_snapshots WHERE fiscal_year = ? AND member_id = ?
            """, BigDecimal.class, year, member)).isEqualByComparingTo("112.50");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM fiscal_year_snapshots WHERE fiscal_year = ? AND member_id = ?
            """, Integer.class, year, laterMember)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fiscal_years WHERE year = ?",
            String.class, year)).isEqualTo("CLOSED");
        assertThatThrownBy(() -> approvals.submit("FISCAL_YEAR_CLOSE", payload, maker, 1))
            .hasMessageContaining("not ready to close");
    }

    @Test
    @Transactional
    void currentYearCannotCloseEarlyAndUnpermittedMakerCannotSubmit() {
        UUID treasurer = user("TREASURER");
        UUID member = user("MEMBER");
        var currentYear = json.createObjectNode()
            .put("year", LocalDate.now(ZoneId.of("Africa/Accra")).getYear());
        assertThatThrownBy(() -> approvals.submit("FISCAL_YEAR_CLOSE", currentYear, treasurer, 1))
            .hasMessageContaining("not ready to close");
        assertThatThrownBy(() -> approvals.submit("FISCAL_YEAR_CLOSE", currentYear, member, 1))
            .hasMessageContaining("Maker cannot close");
    }

    private UUID member(LocalDate joinDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, 'Fixture Member', ?)",
            id, "T-" + id, joinDate);
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

    private void entry(UUID member, String type, String component, String amount, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ledger_entries
                (id, member_id, type, component, amount, effective_date, fiscal_year, reference)
            VALUES (?, ?, ?, ?, ?::numeric, ?, ?, ?)
            """, id, member, type, component, amount, date, date.getYear(), "TEST-" + id);
    }
}
