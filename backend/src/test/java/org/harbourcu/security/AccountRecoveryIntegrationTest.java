package org.harbourcu.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.FieldEncryption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.flyway.default-schema=account_recovery_tests",
      "spring.datasource.hikari.schema=account_recovery_tests",
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@AutoConfigureMockMvc
class AccountRecoveryIntegrationTest {
  @Autowired AuthFlowService flow;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccountRecoveryService service;
  @Autowired ApprovalService approvals;
  @Autowired SessionService sessions;
  @Autowired PasswordHasher passwords;
  @Autowired FieldEncryption fields;
  @Autowired RecoveryCodeService codes;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  static final String OLD = "Synthetic Original Password! 2026",
      NEW = "Synthetic Replacement Password! 2026";

  @Test
  void publicPasswordRecoveryIsAccountNeutralAndRetainsProtectedFactors() throws Exception {
    UUID target = user("MEMBER");
    factors(target);
    var session = sessions.create(target);
    service.requestPassword(email(target));
    service.requestPassword(email(target));
    service.requestPassword("missing-fixture@invalid.example");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_reset_tokens WHERE user_id=?", Integer.class, target))
        .isEqualTo(1);
    String token = token(target);
    assertThat(sessions.resolveAccess(session.access())).isPresent();
    service.complete(token, NEW);
    assertThat(
            passwords.verify(
                NEW,
                jdbc.queryForObject(
                    "SELECT password_hash FROM users WHERE id=?", String.class, target)))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT totp_enabled_at IS NOT NULL FROM users WHERE id=?", Boolean.class, target))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM passkey_credentials WHERE user_id=? AND revoked_at IS NULL",
                Integer.class,
                target))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM google_identity_links WHERE user_id=?",
                Integer.class,
                target))
        .isEqualTo(1);
    assertThat(sessions.resolveAccess(session.access())).isEmpty();
    assertThat(flow.begin(email(target), NEW, "192.0.2.71").stage())
        .isEqualTo(AuthFlowService.Stage.TOTP_VERIFY);
    assertThatThrownBy(() -> service.complete(token, OLD)).hasMessageContaining("already used");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM notifications WHERE user_id=? AND kind='PASSWORD_RESET'",
                String.class,
                target))
        .isEqualTo("{}");
  }

  @Test
  void independentRecoveryRevokesEveryOldMethodAndRequiresNewSetup() throws Exception {
    UUID maker = user("ADMIN"), checker = user("PRESIDENT"), target = user("MEMBER");
    factors(target);
    var oldSession = sessions.create(target);
    String original = codes.issue(target).getFirst();
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code='PRESIDENT'",
        target);
    var preview = service.preview(maker, target);
    UUID request =
        service.submit(
            maker,
            new AccountRecoveryService.ResetInput(
                target,
                "Offline identity verified with union leadership",
                preview.securityFingerprint()),
            approvals);
    assertThat(service.pending(checker))
        .extracting(AccountRecoveryService.Pending::id)
        .contains(request);
    assertThatThrownBy(
            () -> approvals.decide(request, maker, ApprovalService.Decision.APPROVE, null))
        .hasMessageContaining("own request");
    assertThatThrownBy(
            () -> approvals.decide(request, target, ApprovalService.Decision.APPROVE, null))
        .hasMessageContaining("own account");
    approvals.decide(request, checker, ApprovalService.Decision.APPROVE, null);
    assertThat(sessions.resolveAccess(oldSession.access())).isEmpty();
    assertThat(codes.consume(target, original)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM passkey_credentials WHERE user_id=? AND revoked_at IS NULL",
                Integer.class,
                target))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM google_identity_links WHERE user_id=?",
                Integer.class,
                target))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT totp_secret_ciphertext IS NULL AND totp_enabled_at IS NULL AND preferred_sign_in_method IS NULL FROM users WHERE id=?",
                Boolean.class,
                target))
        .isTrue();
    String token = token(target);
    service.complete(token, NEW);
    assertThat(flow.begin(email(target), NEW, "192.0.2.72").stage())
        .isEqualTo(AuthFlowService.Stage.TOTP_SETUP);
    assertThat(
            jdbc.queryForObject(
                "SELECT force_password_change FROM users WHERE id=?", Boolean.class, target))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action='ACCOUNT_RECOVERY_RESET_APPROVED' AND entity_id=?",
                Integer.class,
                target))
        .isEqualTo(1);
  }

  @Test
  void sourceChangesAndRevokedMakerBlockFinalApprovalWithoutPartialDecisions() {
    UUID maker = user("ADMIN"), checker = user("PRESIDENT"), target = user("MEMBER");
    var p = service.preview(maker, target);
    UUID request =
        service.submit(
            maker,
            new AccountRecoveryService.ResetInput(
                target, "Synthetic verified request", p.securityFingerprint()),
            approvals);
    jdbc.update(
        "UPDATE users SET password_hash=? WHERE id=?", passwords.hashNewPassword(NEW), target);
    assertThatThrownBy(
            () -> approvals.decide(request, checker, ApprovalService.Decision.APPROVE, null))
        .hasMessageContaining("security changed");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM approval_decisions WHERE request_id=?",
                Integer.class,
                request))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM approval_requests WHERE id=?", String.class, request))
        .isEqualTo("PENDING");
    UUID target2 = user("MEMBER");
    var p2 = service.preview(maker, target2);
    UUID r2 =
        service.submit(
            maker,
            new AccountRecoveryService.ResetInput(
                target2, "Synthetic verified request", p2.securityFingerprint()),
            approvals);
    jdbc.update(
        "UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Synthetic revoked-maker test' WHERE user_id=?",
        checker,
        maker);
    assertThatThrownBy(() -> approvals.decide(r2, checker, ApprovalService.Decision.APPROVE, null))
        .isInstanceOf(SecurityException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_reset_tokens WHERE user_id=?",
                Integer.class,
                target2))
        .isZero();
  }

  @Test
  void expiredDisabledWeakPasswordAndDuplicatePendingRequestsAreRejected() throws Exception {
    UUID maker = user("ADMIN"), target = user("MEMBER");
    service.requestPassword(email(target));
    String token = token(target);
    assertThatThrownBy(() -> service.complete(token, "password"))
        .isInstanceOf(IllegalArgumentException.class);
    jdbc.update(
        "UPDATE account_reset_tokens SET expires_at=now()-interval '30 minutes',created_at=now()-interval '1 hour' WHERE user_id=?",
        target);
    assertThatThrownBy(() -> service.complete(token, NEW)).hasMessageContaining("invalid");
    service.requestPassword(email(target));
    String fresh = token(target);
    jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", target);
    assertThatThrownBy(() -> service.complete(fresh, NEW)).hasMessageContaining("invalid");
    assertThatThrownBy(() -> service.preview(maker, target)).hasMessageContaining("active account");
    UUID active = user("MEMBER");
    var p = service.preview(maker, active);
    var input =
        new AccountRecoveryService.ResetInput(
            active, "Synthetic verification", p.securityFingerprint());
    service.submit(maker, input, approvals);
    assertThatThrownBy(() -> service.submit(maker, input, approvals))
        .hasMessageContaining("already has");
    assertThatThrownBy(() -> service.preview(maker, maker)).hasMessageContaining("own security");
  }

  @Test
  void duplicateConcurrentCompletionConsumesTheLinkExactlyOnce() throws Exception {
    UUID target = user("MEMBER");
    service.requestPassword(email(target));
    String token = token(target);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> attempt =
          () -> {
            try {
              service.complete(token, NEW);
              return true;
            } catch (IllegalArgumentException failure) {
              return false;
            }
          };
      int successes = 0;
      for (var future : pool.invokeAll(java.util.List.of(attempt, attempt)))
        if (future.get()) successes++;
      assertThat(successes).isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action='PASSWORD_RESET_COMPLETED' AND entity_id=?",
                Integer.class,
                target))
        .isEqualTo(1);
  }

  @Test
  void publicRequestsAreNeutralAndAdminRoutesEnforceSessionPermissionsOriginCsrf()
      throws Exception {
    UUID maker = user("ADMIN"), ordinary = user("MEMBER");
    var tokens = sessions.create(maker);
    var memberTokens = sessions.create(ordinary);
    String unknown =
        json.writeValueAsString(
            new AccountRecoveryController.Email("missing-http@invalid.example"));
    mvc.perform(
            post("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(unknown))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            post("/api/v1/auth/password-reset/request")
                .header("Origin", "https://foreign.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(unknown))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/account-recovery")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/account-recovery").cookie(new Cookie("cu_access", memberTokens.access())))
        .andExpect(status().isForbidden());
    String body = json.writeValueAsString(new AccountRecoveryController.Target(ordinary));
    mvc.perform(
            post("/api/v1/account-recovery/preview")
                .cookie(new Cookie("cu_access", tokens.access()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/account-recovery/preview")
                .cookie(new Cookie("cu_access", tokens.access()))
                .header("X-CSRF-Token", tokens.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void changingRegisteredEmailInvalidatesPreviouslyIssuedRecoveryLink() throws Exception {
    UUID target = user("MEMBER");
    service.requestPassword(email(target));
    String token = token(target);
    jdbc.update(
        "UPDATE users SET email=? WHERE id=?", "changed-" + target + "@invalid.example", target);
    assertThat(
            jdbc.queryForObject(
                "SELECT "
                    + org.harbourcu.notification.NotificationAdminService.ELIGIBLE
                    + " FROM notifications n JOIN users u ON u.id=n.user_id WHERE n.user_id=? AND n.kind='PASSWORD_RESET'",
                Boolean.class,
                target))
        .isFalse();
    assertThatThrownBy(() -> service.complete(token, NEW)).hasMessageContaining("invalid");
    assertThat(
            passwords.verify(
                OLD,
                jdbc.queryForObject(
                    "SELECT password_hash FROM users WHERE id=?", String.class, target)))
        .isTrue();
  }

  @Test
  void concurrentPasswordCompletionAndAdministrativeResetDoNotInvertLocks() throws Exception {
    UUID maker = user("ADMIN"), checker = user("PRESIDENT"), target = user("MEMBER");
    service.requestPassword(email(target));
    String token = token(target);
    var preview = service.preview(maker, target);
    UUID request =
        service.submit(
            maker,
            new AccountRecoveryService.ResetInput(
                target, "Synthetic independent verification", preview.securityFingerprint()),
            approvals);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> complete =
          () -> {
            try {
              service.complete(token, NEW);
              return true;
            } catch (IllegalArgumentException invalidated) {
              return false;
            }
          };
      Callable<Boolean> approve =
          () -> {
            try {
              approvals.decide(request, checker, ApprovalService.Decision.APPROVE, null);
              return true;
            } catch (IllegalStateException stale) {
              return false;
            }
          };
      int changes = 0;
      for (var result : pool.invokeAll(java.util.List.of(complete, approve)))
        if (result.get()) changes++;
      assertThat(changes).isEqualTo(1);
    }
  }

  private UUID user(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,force_password_change) VALUES(?,?,?,false)",
        id,
        email(id),
        passwords.hashNewPassword(OLD));
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code=?",
        id,
        role);
    return id;
  }

  private String email(UUID user) {
    return user + "@invalid.example";
  }

  private void factors(UUID user) {
    jdbc.update(
        "UPDATE users SET totp_secret_ciphertext=?,totp_enabled_at=now(),preferred_sign_in_method='TOTP' WHERE id=?",
        fields.encrypt(Base64.getEncoder().encodeToString(new byte[20])),
        user);
    jdbc.update(
        "INSERT INTO passkey_credentials(user_id,credential_id,public_key_cose) VALUES(?,?,?)",
        user,
        UUID.randomUUID().toString().getBytes(),
        new byte[] {1});
    jdbc.update(
        "INSERT INTO google_identity_links(user_id,google_subject) VALUES(?,?)",
        user,
        "synthetic-" + user);
  }

  private String token(UUID user) throws Exception {
    String payload =
        jdbc.queryForObject(
            "SELECT n.payload::text FROM notifications n JOIN account_reset_tokens t ON t.id=n.entity_id WHERE n.user_id=? AND n.kind='PASSWORD_RESET' AND t.consumed_at IS NULL ORDER BY t.created_at DESC LIMIT 1",
            String.class,
            user);
    String link =
        fields.decrypt(
            Base64.getDecoder().decode(json.readTree(payload).path("encryptedLink").asText()));
    return URI.create(link).getFragment().substring("token=".length());
  }
}
