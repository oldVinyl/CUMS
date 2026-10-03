package org.harbourcu.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
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
class DashboardControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;

    @Test
    @Transactional
    void routesReachAuthenticatedControllersAndEnforceCsrf() throws Exception {
        mvc.perform(get("/api/v1/dashboard").param("asAt", "2026-10-03"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/settings/rollover"))
            .andExpect(status().isUnauthorized());
        var ordinary = sessions.create(user("MEMBER"));
        mvc.perform(get("/api/v1/dashboard").param("asAt", "2026-10-03")
            .cookie(new Cookie("cu_access", ordinary.access())))
            .andExpect(status().isForbidden());
        var admin = sessions.create(user("ADMIN"));
        mvc.perform(get("/api/v1/dashboard").param("asAt", "2026-10-03")
            .cookie(new Cookie("cu_access", admin.access())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fiscalYear").value(2026));
        mvc.perform(get("/api/v1/settings/rollover")
            .cookie(new Cookie("cu_access", admin.access())))
            .andExpect(status().isOk());
        mvc.perform(put("/api/v1/settings/rollover")
            .cookie(new Cookie("cu_access", admin.access()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"defaultRolloverPolicy\":\"ROLL_PRINCIPAL_ONLY\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings/rollover")
            .cookie(new Cookie("cu_access", admin.access()))
            .header("Origin", "http://localhost:5173")
            .header("X-CSRF-Token", admin.csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"defaultRolloverPolicy\":\"ROLL_PRINCIPAL_ONLY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultRolloverPolicy").value("ROLL_PRINCIPAL_ONLY"));
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "dash-web-" + id + "@invalid.example");
        jdbc.update("INSERT INTO user_role_assignments (user_id, role_id, start_date) VALUES (?, ?, ?)",
            id, roleId, LocalDate.now());
        return id;
    }
}
