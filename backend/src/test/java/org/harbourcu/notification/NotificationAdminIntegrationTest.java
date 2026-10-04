package org.harbourcu.notification;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.flyway.default-schema=notification_admin_tests",
      "spring.datasource.hikari.schema=notification_admin_tests",
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@AutoConfigureMockMvc
@Transactional
class NotificationAdminIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired NotificationAdminService service;
  @Autowired SessionService sessions;
  @Autowired MockMvc mvc;
  @Autowired FieldEncryption fields;
  @Autowired ObjectMapper json;

  @Test
  void retryIsAuthorizedAuditedAndRejectsDuplicateOrSentMessages() {
    UUID admin = user("ADMIN"),
        ordinary = user("MEMBER"),
        id = notification(ordinary, "APPROVAL", "FAILED");
    assertThatThrownBy(() -> service.list(ordinary, "ALL", 0))
        .isInstanceOf(SecurityException.class);
    assertThat(service.list(admin, "FAILED", 0).items()).hasSize(1);
    service.retry(admin, id);
    assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id=?", String.class, id))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject("SELECT attempts FROM notifications WHERE id=?", Integer.class, id))
        .isZero();
    assertThatThrownBy(() -> service.retry(admin, id)).hasMessageContaining("Only failed");
    jdbc.update("UPDATE notifications SET status='SENT' WHERE id=?", id);
    assertThatThrownBy(() -> service.retry(admin, id)).hasMessageContaining("Only failed");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action='NOTIFICATION_RETRY_QUEUED' AND entity_id=?",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void workerNeverDeliversExpiredInvitationsAndRetriesTransportFailures() {
    UUID admin = user("ADMIN"),
        recipient = user("MEMBER"),
        invite = notification(recipient, "INVITATION", "PENDING"),
        normal = notification(recipient, "APPROVAL", "PENDING");
    jdbc.update("UPDATE notifications SET attempts=9 WHERE id=?", normal);
    AtomicInteger delivered = new AtomicInteger();
    var worker =
        new NotificationWorker(
            jdbc,
            fields,
            json,
            (to, subject, text) -> {
              delivered.incrementAndGet();
              throw new IllegalStateException("Synthetic transport failure");
            },
            "http://localhost:5173");
    worker.deliver();
    assertThat(delivered.get()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notifications WHERE id=?", String.class, invite))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notifications WHERE id=?", String.class, normal))
        .isEqualTo("FAILED");
    assertThatThrownBy(() -> service.retry(admin, invite))
        .hasMessageContaining("valid links");
    assertThat(
            service.list(admin, "FAILED", 0).items().stream()
                .filter(n -> n.id().equals(invite))
                .findFirst()
                .orElseThrow()
                .canRetry())
        .isFalse();
  }

  @Test
  void listIsBoundedAndExcludesPrivatePayloads() throws Exception {
    UUID admin = user("ADMIN"), recipient = user("MEMBER");
    for (int i = 0; i < 27; i++) notification(recipient, "PAYMENT", "FAILED");
    var page = service.list(admin, "ALL", 0);
    assertThat(page.items()).hasSize(25);
    assertThat(page.hasNext()).isTrue();
    assertThat(service.list(admin, "ALL", 1).items()).hasSize(2);
    assertThat(json.writeValueAsString(page))
        .doesNotContain("encryptedLink", "payload", "recipient@", "email");
    assertThatThrownBy(() -> service.list(admin, "INVALID", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void endpointsRequireSessionCurrentPermissionCsrfAndSameOrigin() throws Exception {
    UUID admin = user("ADMIN"),
        ordinary = user("MEMBER"),
        id = notification(ordinary, "PAYMENT", "FAILED");
    var tokens = sessions.create(admin);
    var memberTokens = sessions.create(ordinary);
    mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/notifications").cookie(new Cookie("cu_access", memberTokens.access())))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/notifications").cookie(new Cookie("cu_access", tokens.access())))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            post("/api/v1/notifications/" + id + "/retry")
                .cookie(new Cookie("cu_access", tokens.access())))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/notifications/" + id + "/retry")
                .cookie(new Cookie("cu_access", tokens.access()))
                .header("X-CSRF-Token", tokens.csrf())
                .header("Origin", "https://foreign.invalid"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/notifications/" + id + "/retry")
                .cookie(new Cookie("cu_access", tokens.access()))
                .header("X-CSRF-Token", tokens.csrf()))
        .andExpect(status().isNoContent());
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture')",
        id,
        id + "@invalid.example");
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code=?",
        id,
        role);
    return id;
  }

  private UUID notification(UUID user, String kind, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO notifications(id,user_id,entity_id,kind,outcome,status,attempts,payload) VALUES(?,?,? ,?,'PENDING',?,10,'{\"encryptedLink\":\"synthetic-private-value\"}'::jsonb)",
        id,
        user,
        UUID.randomUUID(),
        kind,
        status);
    return id;
  }
}
