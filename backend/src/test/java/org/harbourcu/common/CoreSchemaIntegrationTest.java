package org.harbourcu.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class CoreSchemaIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    @Test
    void protectedRouteReturnsStructuredAccessError() throws Exception {
        mvc.perform(get("/api/v1/members"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void migrationCreatesCurrentFiscalYearAndCoreTables() {
        int year = LocalDate.now(ZoneId.of("Africa/Accra")).getYear();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fiscal_years WHERE year = ?", Integer.class, year))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'members' AND column_name = 'phone'", Integer.class))
            .isZero();
    }

    @Test
    @Transactional
    void ledgerBalanceIsDerivedAndPostedEntryCannotBeEdited() {
        UUID memberId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, ?, ?)",
            memberId, "T-" + memberId, "Fixture Member", today);
        jdbc.update("""
            INSERT INTO ledger_entries (id, member_id, type, component, amount, effective_date, fiscal_year, reference)
            VALUES (?, ?, 'SEED', 'PRINCIPAL', 125.5000, ?, ?, ?)
            """, entryId, memberId, today, today.getYear(), "TEST-" + entryId);
        assertThat(jdbc.queryForObject("SELECT equity FROM member_balances WHERE member_id = ?", BigDecimal.class, memberId))
            .isEqualByComparingTo("125.5000");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE id = ?", entryId))
            .hasMessageContaining("append-only");
    }

    @Test
    @Transactional
    void auditRecordCannotBeDeleted() {
        UUID auditId = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_log (id, action, entity_type) VALUES (?, 'TEST', 'FIXTURE')", auditId);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?", auditId))
            .hasMessageContaining("append-only");
    }
}
