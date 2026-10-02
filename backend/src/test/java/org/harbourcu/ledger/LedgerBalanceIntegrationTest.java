package org.harbourcu.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class LedgerBalanceIntegrationTest {
    @Autowired
    LedgerBalanceService balances;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void includesLeapDayAndAsAtBoundaryButNotLaterEntries() {
        year(2024);
        year(2025);
        UUID member = member();
        entry(member, "SEED", "PRINCIPAL", "100.00",
            LocalDate.of(2024, 2, 28), null);
        entry(member, "ADDITION", "PRINCIPAL", "20.00",
            LocalDate.of(2024, 2, 29), null);
        entry(member, "INCOME_ALLOCATION", "INCOME", "10.00",
            LocalDate.of(2024, 12, 31), null);
        UUID january = entry(member, "ADDITION", "PRINCIPAL", "5.00",
            LocalDate.of(2025, 1, 1), null);
        entry(member, "REVERSAL", "PRINCIPAL", "-5.00",
            LocalDate.of(2025, 1, 2), january);

        assertThat(balances.asAt(member, LocalDate.of(2024, 2, 27)).equity())
            .isEqualByComparingTo("0");
        assertThat(balances.asAt(member, LocalDate.of(2024, 2, 28)).principal())
            .isEqualByComparingTo("100.00");
        assertThat(balances.asAt(member, LocalDate.of(2024, 2, 29)).equity())
            .isEqualByComparingTo("120.00");
        LedgerBalanceService.Balance yearEnd = balances.asAt(member, LocalDate.of(2024, 12, 31));
        assertThat(yearEnd.principal()).isEqualByComparingTo("120.00");
        assertThat(yearEnd.income()).isEqualByComparingTo("10.00");
        assertThat(yearEnd.equity()).isEqualByComparingTo("130.00");
        assertThat(balances.asAt(member, LocalDate.of(2025, 1, 1)).equity())
            .isEqualByComparingTo("135.00");
        assertThat(balances.asAt(member, LocalDate.of(2025, 1, 2)).equity())
            .isEqualByComparingTo("130.00");
    }

    @Test
    @Transactional
    void zeroEntryMemberHasZeroBalanceAndFutureDateIsRejected() {
        UUID member = member();
        assertThat(balances.asAt(member, LocalDate.of(2024, 1, 1)).equity())
            .isEqualByComparingTo("0");
        assertThatThrownBy(() -> balances.asAt(member, LocalDate.now().plusYears(2)))
            .hasMessageContaining("no later than today");
        assertThatThrownBy(() -> balances.asAt(UUID.randomUUID(), LocalDate.of(2024, 1, 1)))
            .hasMessageContaining("Member does not exist");
    }

    private void year(int year) {
        jdbc.update("""
            INSERT INTO fiscal_years (year, start_date, end_date)
            VALUES (?, make_date(?, 1, 1), make_date(?, 12, 31))
            ON CONFLICT (year) DO NOTHING
            """, year, year, year);
    }

    private UUID member() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, 'Fixture Member', ?)",
            id, "T-" + id, LocalDate.of(2024, 1, 1));
        return id;
    }

    private UUID entry(UUID member, String type, String component, String amount,
                       LocalDate date, UUID reversalOf) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ledger_entries (id, member_id, type, component, amount, effective_date,
                                        fiscal_year, reference, reversal_of, narration)
            VALUES (?, ?, ?, ?, ?::numeric, ?, ?, ?, ?, ?)
            """, id, member, type, component, amount, date, date.getYear(),
            "TEST-" + id, reversalOf, reversalOf == null ? null : "Fixture correction");
        return id;
    }
}
