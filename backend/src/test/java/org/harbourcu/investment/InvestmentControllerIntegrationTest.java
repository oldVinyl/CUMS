package org.harbourcu.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class InvestmentControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @Autowired ObjectMapper json;

    @Test
    @Transactional
    void adminCanReadButOnlyFinancialRoleCanCreateDraft() throws Exception {
        mvc.perform(get("/api/v1/investments")).andExpect(status().isUnauthorized());
        UUID admin = user("ADMIN");
        UUID treasurer = user("TREASURER");
        var adminTokens = sessions.create(admin);
        var treasurerTokens = sessions.create(treasurer);
        mvc.perform(get("/api/v1/investments")
            .cookie(new Cookie("cu_access", adminTokens.access()))).andExpect(status().isOk());
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        UUID member = UUID.randomUUID();
        jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, 'Fixture', ?)",
            member, "WEB-" + member, today);
        jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount,
                                        effective_date, fiscal_year, reference)
            VALUES (?, 'SEED', 'PRINCIPAL', 100.00, ?, ?, ?)
            """, member, today, today.getYear(), "WEB-SEED-" + member);
        String body = json.writeValueAsString(Map.of("tenor", "91_DAY",
            "purchaseDate", today.toString(), "maturityDate", today.plusDays(91).toString(),
            "cost", "100.00", "maturityValue", "110.00"));
        mvc.perform(post("/api/v1/investments")
            .cookie(new Cookie("cu_access", adminTokens.access()))
            .header("X-CSRF-Token", adminTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String response = mvc.perform(post("/api/v1/investments")
            .cookie(new Cookie("cu_access", treasurerTokens.access()))
            .header("X-CSRF-Token", treasurerTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID investment = UUID.fromString(json.readTree(response).get("id").asText());
        String detail = mvc.perform(get("/api/v1/investments/{id}", investment)
            .cookie(new Cookie("cu_access", adminTokens.access())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(new java.math.BigDecimal(json.readTree(detail).path("pool").asText()))
            .isEqualByComparingTo("100.00");
        assertThat(json.readTree(detail).path("pool").isTextual()).isTrue();
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
