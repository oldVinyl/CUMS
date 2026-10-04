package org.harbourcu.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.*;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "spring.flyway.default-schema=receipt_revision_tests",
      "spring.datasource.hikari.schema=receipt_revision_tests"
    })
@AutoConfigureMockMvc
class ReceiptRevisionIntegrationTest {
  @Autowired ReceiptRevisionService revisions;
  @Autowired PaymentService payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  final LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));

  record Fixture(UUID member, UUID owner, UUID payment, UUID proof) {}

  @Test
  void replacementRetainsEvidenceReplaysOnceAndDoesNotChangeMoney() {
    var f = fixture();
    UUID replacement = proof(f.member(), f.owner());
    jdbc.update(
        "UPDATE payments SET status='UNDER_REVIEW',rejection_reason='Synthetic request for a clearer receipt' WHERE id=?",
        f.payment());
    var input = new ReceiptRevisionService.Amendment(f.proof(), replacement);
    revisions.amend(f.owner(), f.payment(), input);
    revisions.amend(f.owner(), f.payment(), input);
    assertThat(revisions.history(f.owner(), f.payment())).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT proof_id FROM payments WHERE id=?", UUID.class, f.payment()))
        .isEqualTo(replacement);
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM payments WHERE id=?", String.class, f.payment()))
        .isEqualTo("100.0000");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments WHERE id=?", String.class, f.payment()))
        .isEqualTo("SUBMITTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT rejection_reason FROM payments WHERE id=?", String.class, f.payment()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM attachments WHERE id IN (?,?)",
                Integer.class,
                f.proof(),
                replacement))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE payment_id=?",
                Integer.class,
                f.payment()))
        .isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM payment_receipt_revisions WHERE payment_id=?", f.payment()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void foreignMembersAndForeignUploadsCannotReplaceOrReadEvidence() {
    var f = fixture();
    var other = fixture();
    UUID checker = user("PRESIDENT", null);
    assertThatThrownBy(
            () ->
                revisions.amend(
                    other.owner(),
                    f.payment(),
                    new ReceiptRevisionService.Amendment(f.proof(), other.proof())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> revisions.history(other.owner(), f.payment()))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                revisions.amend(
                    f.owner(),
                    f.payment(),
                    new ReceiptRevisionService.Amendment(f.proof(), other.proof())))
        .isInstanceOf(SecurityException.class);
    UUID sameMemberOtherActor = proof(f.member(), checker);
    assertThatThrownBy(
            () ->
                revisions.amend(
                    f.owner(),
                    f.payment(),
                    new ReceiptRevisionService.Amendment(f.proof(), sameMemberOtherActor)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                revisions.amend(
                    checker,
                    f.payment(),
                    new ReceiptRevisionService.Amendment(f.proof(), sameMemberOtherActor)))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void staleFormsArchivedUploadsAndTerminalPaymentsAreRejected() {
    var f = fixture();
    UUID replacement = proof(f.member(), f.owner()), second = proof(f.member(), f.owner());
    revisions.amend(
        f.owner(), f.payment(), new ReceiptRevisionService.Amendment(f.proof(), replacement));
    assertThatThrownBy(
            () ->
                revisions.amend(
                    f.owner(),
                    f.payment(),
                    new ReceiptRevisionService.Amendment(f.proof(), second)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                revisions.amend(
                    f.owner(),
                    f.payment(),
                    new ReceiptRevisionService.Amendment(replacement, f.proof())))
        .isInstanceOf(IllegalArgumentException.class);
    for (String status :
        new String[] {"VERIFIED_PENDING_APPROVAL", "POSTED", "REJECTED", "CONFIRMED"}) {
      jdbc.update("UPDATE payments SET status=? WHERE id=?", status, f.payment());
      assertThatThrownBy(
              () ->
                  revisions.amend(
                      f.owner(),
                      f.payment(),
                      new ReceiptRevisionService.Amendment(replacement, second)))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(revisions.history(f.owner(), f.payment())).hasSize(1);
  }

  @Test
  void concurrentVerificationWinsWithoutOverwritingItsReviewedProof() throws Exception {
    var f = fixture();
    UUID verifier = user("TREASURER", null), replacement = proof(f.member(), f.owner());
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var verifying =
          executor.submit(
              () ->
                  new TransactionTemplate(manager)
                      .execute(
                          status -> {
                            jdbc.queryForObject(
                                "SELECT id FROM payments WHERE id=? FOR UPDATE",
                                UUID.class,
                                f.payment());
                            locked.countDown();
                            try {
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Synthetic timeout");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                            return payments.verify(
                                verifier, f.payment(), new PaymentService.Verify("100", today));
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var amending =
          executor.submit(
              () ->
                  revisions.amend(
                      f.owner(),
                      f.payment(),
                      new ReceiptRevisionService.Amendment(f.proof(), replacement)));
      release.countDown();
      verifying.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> amending.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
    } finally {
      release.countDown();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT proof_id FROM payments WHERE id=?", UUID.class, f.payment()))
        .isEqualTo(f.proof());
    assertThat(revisions.history(f.owner(), f.payment())).isEmpty();
  }

  @Test
  void httpCsrfOwnershipHistoryAndOwnedExportAreEnforced() throws Exception {
    var f = fixture();
    var other = fixture();
    var token = sessions.create(f.owner());
    var otherToken = sessions.create(other.owner());
    UUID replacement = proof(f.member(), f.owner());
    String body =
        "{\"previousProofId\":\"" + f.proof() + "\",\"newProofId\":\"" + replacement + "\"}";
    mvc.perform(
            post("/api/v1/payments/" + f.payment() + "/receipt")
                .cookie(new Cookie("cu_access", token.access()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/payments/" + f.payment() + "/receipt")
                .cookie(new Cookie("cu_access", token.access()))
                .header("X-CSRF-Token", token.csrf())
                .header("Origin", "https://foreign.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/payments/" + f.payment() + "/receipt")
                .cookie(new Cookie("cu_access", token.access()))
                .header("X-CSRF-Token", token.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
    mvc.perform(
            get("/api/v1/payments/" + f.payment() + "/receipt-revisions")
                .cookie(new Cookie("cu_access", otherToken.access())))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/payments/" + f.payment() + "/receipt-revisions")
                .cookie(new Cookie("cu_access", token.access())))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    String exported =
        mvc.perform(
                get("/api/v1/portal/export.json").cookie(new Cookie("cu_access", token.access())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(exported)
        .contains("receiptRevisions", f.proof().toString(), replacement.toString())
        .doesNotContain(other.proof().toString());
  }

  private Fixture fixture() {
    UUID member = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Anonymous receipt fixture',?)",
        member,
        "R-" + member.toString().substring(0, 8),
        today);
    UUID owner = user("MEMBER", member), proof = proof(member, owner), payment = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payments(id,member_id,amount,method,internal_reference,status,purpose,proof_id,paid_date,submitted_by) VALUES(?,?,100,'Synthetic bank',?,'SUBMITTED','ADDITION',?,?,?)",
        payment,
        member,
        payment.toString(),
        proof,
        today,
        owner);
    return new Fixture(member, owner, payment, proof);
  }

  private UUID proof(UUID member, UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO attachments(id,member_id,uploaded_by,storage_key,sha256,content_type,byte_size) VALUES(?,?,?,?,?,'image/png',10)",
        id,
        member,
        owner,
        UUID.randomUUID(),
        "0".repeat(64));
    return id;
  }

  private UUID user(String role, UUID member) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,member_id) VALUES(?,?,'synthetic',?)",
        id,
        id + "@example.invalid",
        member);
    jdbc.update(
        "INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",
        id,
        today,
        role);
    return id;
  }
}
