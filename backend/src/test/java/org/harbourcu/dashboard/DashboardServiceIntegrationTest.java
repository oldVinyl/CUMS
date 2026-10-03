package org.harbourcu.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class DashboardServiceIntegrationTest {
    @Autowired DashboardService dashboards;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void asAtSeparatesBalanceForwardAndYearToDateWithExactTotals() {
        for (int year : new int[] {2000, 2001}) {
            jdbc.update("""
                INSERT INTO fiscal_years (year, start_date, end_date)
                VALUES (?, make_date(?, 1, 1), make_date(?, 12, 31))
                ON CONFLICT DO NOTHING
                """, year, year, year);
        }
        UUID first = member("2000-first", "Early member");
        UUID second = member("2000-second", "Later member");
        entry(first, "SEED", "PRINCIPAL", "30000.00", LocalDate.of(2000, 7, 1));
        entry(first, "INCOME_ALLOCATION", "INCOME", "4796.87", LocalDate.of(2000, 12, 31));
        entry(first, "ADDITION", "PRINCIPAL", "500.00", LocalDate.of(2001, 2, 1));
        entry(second, "SEED", "PRINCIPAL", "1000.00", LocalDate.of(2001, 1, 1));
        entry(second, "INCOME_ALLOCATION", "INCOME", "100.00", LocalDate.of(2001, 2, 1));
        UUID admin = user("ADMIN");
        var dashboard = dashboards.asAt(admin, LocalDate.of(2001, 2, 1));
        assertThat(dashboard.members()).hasSize(2);
        var row = dashboard.members().stream().filter(item -> item.memberId().equals(first))
            .findFirst().orElseThrow();
        assertThat(row.balanceBf().equity()).isEqualByComparingTo("34796.87");
        assertThat(row.ytd().equity()).isEqualByComparingTo("500.00");
        assertThat(row.total().principal()).isEqualByComparingTo("30500.00");
        assertThat(row.equityRatioPercent()).isEqualByComparingTo("96.98");
        assertThat(dashboard.totals().balanceBf().equity()).isEqualByComparingTo("34796.87");
        assertThat(dashboard.totals().ytd().equity()).isEqualByComparingTo("1600.00");
        assertThat(dashboard.totals().total().equity()).isEqualByComparingTo("36396.87");
        assertThat(dashboard.totals().equityRatioPercent()).isEqualByComparingTo("100.00");
        assertThat(dashboard.trend()).hasSize(2);
        assertThat(dashboard.trend().getFirst().equity()).isEqualByComparingTo("35796.87");
        assertThat(dashboard.trend().getLast().equity()).isEqualByComparingTo("36396.87");
        assertThatThrownBy(() -> dashboards.asAt(user("MEMBER"), LocalDate.of(2001, 2, 1)))
            .isInstanceOf(SecurityException.class);
    }

    private UUID member(String number, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, ?, ?)",
            id, number + id.toString().substring(0, 8), name, LocalDate.of(2000, 1, 1));
        return id;
    }

    private void entry(UUID member, String type, String component, String amount, LocalDate date) {
        jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount, effective_date,
                                        fiscal_year, reference)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, member, type, component, new BigDecimal(amount), date, date.getYear(),
            "DASH-" + UUID.randomUUID());
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "dash-" + id + "@invalid.example");
        jdbc.update("INSERT INTO user_role_assignments (user_id, role_id, start_date) VALUES (?, ?, ?)",
            id, roleId, LocalDate.now());
        return id;
    }
}
