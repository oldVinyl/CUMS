package org.harbourcu.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "AUTH_THROTTLE_KEY=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
      "spring.flyway.default-schema=protected_search_tests",
      "spring.datasource.hikari.schema=protected_search_tests"
    })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProtectedSearchIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  final LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));

  @Test
  void bodySearchRequiresSessionPermissionCsrfAndOriginWithoutLoggingItsInput(CapturedOutput output)
      throws Exception {
    var admin = sessions.create(user("ADMIN"));
    var member = sessions.create(user("MEMBER"));
    String body = "{\"search\":\"SYNTHETIC_PRIVATE_SEARCH_SENTINEL\"}";
    for (String path : List.of("/api/v1/members/search", "/api/v1/users/search")) {
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              post(path)
                  .cookie(new Cookie("cu_access", admin.access()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(path)
                  .cookie(new Cookie("cu_access", admin.access()))
                  .header("X-CSRF-Token", admin.csrf())
                  .header("Origin", "https://foreign.invalid")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(path)
                  .cookie(new Cookie("cu_access", member.access()))
                  .header("X-CSRF-Token", member.csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(path)
                  .cookie(new Cookie("cu_access", admin.access()))
                  .header("X-CSRF-Token", admin.csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    assertThat(output.getAll()).doesNotContain("SYNTHETIC_PRIVATE_SEARCH_SENTINEL");
  }

  @Test
  void legacyQuerySearchIsRejectedWithoutReflectingTheIdentifier() throws Exception {
    var admin = sessions.create(user("ADMIN"));
    for (String path : List.of("/api/v1/members", "/api/v1/users")) {
      String response =
          mvc.perform(
                  get(path)
                      .param("search", "SYNTHETIC_PRIVATE_IDENTIFIER")
                      .cookie(new Cookie("cu_access", admin.access())))
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(response).doesNotContain("SYNTHETIC_PRIVATE_IDENTIFIER");
    }
  }

  @Test
  void currentUnionReadRoutesRejectUnsignedAndOrdinaryMembers() throws Exception {
    var member = sessions.create(user("MEMBER"));
    String uuid = UUID.randomUUID().toString();
    for (String path :
        List.of(
            "/members",
            "/members/" + uuid,
            "/ledger",
            "/ledger/fiscal-years",
            "/ledger/approvals",
            "/investments",
            "/investments/" + uuid,
            "/investments/approvals/pending",
            "/dashboard",
            "/reconciliations",
            "/reconciliations/preview",
            "/users",
            "/users/roles/pending",
            "/account-recovery",
            "/notifications",
            "/imports",
            "/audit",
            "/audit/export.csv",
            "/reports/dashboard",
            "/reports/ledger",
            "/reports/members")) {
      var unsigned =
          get("/api/v1" + path)
              .param("asAt", today.toString())
              .param("from", today.toString())
              .param("to", today.toString())
              .param("format", "xlsx");
      mvc.perform(unsigned).andExpect(status().isUnauthorized());
      var own =
          get("/api/v1" + path)
              .param("asAt", today.toString())
              .param("from", today.toString())
              .param("to", today.toString())
              .param("format", "xlsx")
              .cookie(new Cookie("cu_access", member.access()));
      mvc.perform(own).andExpect(status().isForbidden());
    }
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",
        id,
        id + "@example.invalid");
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",
        id,
        today,
        role);
    return id;
  }
}
