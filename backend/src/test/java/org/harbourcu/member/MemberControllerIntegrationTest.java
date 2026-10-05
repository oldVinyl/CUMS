package org.harbourcu.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
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
class MemberControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;

    @Test
    @Transactional
    void requiresSessionPermissionAndCsrfForMemberWrites() throws Exception {
        mvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
        UUID officer = user("TREASURER");
        UUID ordinary = user("MEMBER");
        SessionService.Tokens officerTokens = sessions.create(officer);
        SessionService.Tokens memberTokens = sessions.create(ordinary);
        mvc.perform(get("/api/v1/members").cookie(new Cookie("cu_access", memberTokens.access())))
            .andExpect(status().isForbidden());
        String number = "CU-WEB-" + UUID.randomUUID().toString().substring(0, 12);
        String body = json.writeValueAsString(Map.of("memberNumber", number,
            "fullName", "Esi Example", "joinDate", LocalDate.of(2025, 1, 1).toString(),
            "phone", "0201234999"));
        mvc.perform(post("/api/v1/members")
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        String response = mvc.perform(post("/api/v1/members")
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .header("Origin", "http://localhost:5173")
            .header("X-CSRF-Token", officerTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asText());
        String generatedBody = json.writeValueAsString(Map.of("fullName", "Generated Member",
            "joinDate", LocalDate.of(2025, 1, 1).toString()));
        String generatedResponse = mvc.perform(post("/api/v1/members")
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .header("X-CSRF-Token", officerTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(generatedBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID generatedId = UUID.fromString(json.readTree(generatedResponse).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT member_number FROM members WHERE id = ?",
            String.class, generatedId)).matches("CU-[0-9]{6,}");
        mvc.perform(post("/api/v1/members/search").param("size", "1").header("X-CSRF-Token", officerTokens.csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("search",number)))
            .cookie(new Cookie("cu_access", officerTokens.access())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id.toString()))
            .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/members").param("page", "-1")
            .cookie(new Cookie("cu_access", officerTokens.access())))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/members/{id}", id)
            .cookie(new Cookie("cu_access", officerTokens.access())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("0201234999"));
        mvc.perform(get("/api/v1/members/{id}/balance", id).param("asAt", "2025-01-01")
            .cookie(new Cookie("cu_access", officerTokens.access())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.principal").value("0"))
            .andExpect(jsonPath("$.equity").value("0"));
        mvc.perform(get("/api/v1/members/{id}/balance", id).param("asAt", "9999-01-01")
            .cookie(new Cookie("cu_access", officerTokens.access())))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'SENSITIVE_MEMBER_VIEW'
            """, Integer.class, id)).isEqualTo(1);
        String edit = json.writeValueAsString(Map.of("version", 0, "fullName", "Esi Updated",
            "phone", "0209999000"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/members/{id}", id)
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/members/{id}", id)
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .header("X-CSRF-Token", officerTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/members/{id}", id)
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .header("X-CSRF-Token", officerTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_CHANGED"));
        String inactive = json.writeValueAsString(Map.of("version", 1, "status", "INACTIVE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                "/api/v1/members/{id}/status", id)
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .contentType(MediaType.APPLICATION_JSON).content(inactive))
            .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                "/api/v1/members/{id}/status", id)
            .cookie(new Cookie("cu_access", officerTokens.access()))
            .header("X-CSRF-Token", officerTokens.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(inactive))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "web-member-" + id + "@invalid.example");
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            SELECT ?, id, current_date - 1 FROM roles WHERE code = ?
            """, id, role);
        return id;
    }
}
