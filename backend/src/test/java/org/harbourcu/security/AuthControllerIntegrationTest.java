package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "APP_COOKIE_SECURE=false"
})
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher passwords;

    @Test
    @Transactional
    void loginRefreshAndLogoutRequireCorrectOriginAndCsrf() throws Exception {
        UUID user = UUID.randomUUID();
        String email = "web-" + user + "@invalid.example";
        jdbc.update("INSERT INTO users (id, email, password_hash, force_password_change) VALUES (?, ?, ?, false)",
            user, email, passwords.hashNewPassword("Flow compass boat 2026!"));
        String body = json.writeValueAsString(Map.of("email", email,
            "password", "Flow compass boat 2026!"));
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://attacker.example")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
            .header("Origin", "http://localhost:5173")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("AUTHENTICATED"))
            .andReturn();
        String access = cookie(login, "cu_access");
        String refresh = cookie(login, "cu_refresh");
        String csrf = cookie(login, "cu_csrf");
        assertThat(login.getResponse().getHeaders("Set-Cookie"))
            .anySatisfy(header -> assertThat(header).contains("HttpOnly", "SameSite=Strict"));
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("cu_access", access)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(user.toString()));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("cu_refresh", refresh))
            .header("Origin", "http://localhost:5173")).andExpect(status().isForbidden());
        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new Cookie("cu_refresh", refresh)).header("X-CSRF-Token", csrf)
            .header("Origin", "http://localhost:5173"))
            .andExpect(status().isOk()).andReturn();
        String nextAccess = cookie(rotated, "cu_access");
        String nextCsrf = cookie(rotated, "cu_csrf");
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("cu_access", access)))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("cu_access", nextAccess))
            .header("Origin", "http://localhost:5173").header("X-CSRF-Token", nextCsrf))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("cu_access", nextAccess)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void logoutAllRevokesOtherSessions() throws Exception {
        UUID user = UUID.randomUUID();
        String email = "web-" + user + "@invalid.example";
        jdbc.update("INSERT INTO users (id, email, password_hash, force_password_change) VALUES (?, ?, ?, false)",
            user, email, passwords.hashNewPassword("Flow compass boat 2026!"));
        String body = json.writeValueAsString(Map.of("email", email,
            "password", "Flow compass boat 2026!"));
        MvcResult first = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        MvcResult second = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/auth/logout-all")
            .cookie(new Cookie("cu_access", cookie(first, "cu_access")))
            .header("X-CSRF-Token", cookie(first, "cu_csrf")))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me")
            .cookie(new Cookie("cu_access", cookie(second, "cu_access"))))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'LOGOUT_ALL'
            """, Integer.class, user)).isEqualTo(1);
    }

    @Test
    void tooManyAuthRequestsFromOneIpReturn429() throws Exception {
        String body = json.writeValueAsString(Map.of("challenge", "invalid", "code", "123456"));
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(post("/api/v1/auth/totp/verify")
                .with(request -> { request.setRemoteAddr("192.0.2.90"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/totp/verify")
            .with(request -> { request.setRemoteAddr("192.0.2.90"); return request; })
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private String cookie(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
            .filter(value -> value.startsWith(name + "="))
            .map(value -> value.substring(name.length() + 1).split(";", 2)[0])
            .findFirst().orElseThrow();
    }
}
