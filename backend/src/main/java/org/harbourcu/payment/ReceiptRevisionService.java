package org.harbourcu.payment;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Preserves receipt evidence while serializing amendments against verification. */
@Service
public class ReceiptRevisionService {
  public record Amendment(UUID previousProofId, UUID newProofId) {}

  public record Revision(
      UUID id, UUID previousProofId, UUID newProofId, UUID amendedBy, String amendedAt) {}

  private final JdbcTemplate jdbc;
  private final ProofStorage proofs;

  public ReceiptRevisionService(JdbcTemplate jdbc, ProofStorage proofs) {
    this.jdbc = jdbc;
    this.proofs = proofs;
  }

  public List<Revision> history(UUID actor, UUID payment) {
    var rows = jdbc.queryForList("SELECT member_id FROM payments WHERE id=?", UUID.class, payment);
    if (rows.isEmpty()) throw new IllegalArgumentException("Payment not found.");
    proofs.ownOrStaff(actor, rows.getFirst(), false);
    return jdbc.query(
        "SELECT * FROM payment_receipt_revisions WHERE payment_id=? ORDER BY amended_at,id",
        (r, n) ->
            new Revision(
                r.getObject("id", UUID.class),
                r.getObject("previous_proof_id", UUID.class),
                r.getObject("new_proof_id", UUID.class),
                r.getObject("amended_by", UUID.class),
                r.getTimestamp("amended_at").toInstant().toString()),
        payment);
  }

  @Transactional
  public void amend(UUID actor, UUID payment, Amendment input) {
    var rows =
        jdbc.queryForList(
            "SELECT member_id,status,proof_id,provider FROM payments WHERE id=? FOR UPDATE",
            payment);
    if (rows.isEmpty()) throw new IllegalArgumentException("Payment not found.");
    var current = rows.getFirst();
    UUID member = (UUID) current.get("member_id");
    proofs.ownOrStaff(actor, member, true);
    if (current.get("provider") != null
        || !List.of("SUBMITTED", "UNDER_REVIEW").contains(current.get("status")))
      throw new IllegalStateException(
          "Receipts can only be replaced before verification. A verified or posted payment cannot be changed.");
    if (input == null
        || input.previousProofId() == null
        || input.newProofId() == null
        || input.previousProofId().equals(input.newProofId()))
      throw new IllegalArgumentException(
          "Upload a replacement receipt and retain the current receipt reference.");
    if (Objects.equals(current.get("proof_id"), input.newProofId())
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM payment_receipt_revisions WHERE payment_id=? AND previous_proof_id=? AND new_proof_id=? AND amended_by=?)",
                Boolean.class,
                payment,
                input.previousProofId(),
                input.newProofId(),
                actor))) return;
    if (!Objects.equals(current.get("proof_id"), input.previousProofId()))
      throw new IllegalStateException(
          "The receipt changed while this form was open. Refresh before replacing it.");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM attachments WHERE id=? AND member_id=? AND uploaded_by=?)",
            Boolean.class,
            input.newProofId(),
            member,
            actor)))
      throw new SecurityException(
          "Use a new receipt uploaded by this account for the same member.");
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM payments WHERE proof_id=?) OR EXISTS(SELECT 1 FROM payment_receipt_revisions WHERE new_proof_id=? OR previous_proof_id=?)",
            Boolean.class,
            input.newProofId(),
            input.newProofId(),
            input.newProofId())))
      throw new IllegalArgumentException(
          "Upload a new receipt rather than reusing archived evidence.");
    jdbc.update(
        "INSERT INTO payment_receipt_revisions(payment_id,previous_proof_id,new_proof_id,amended_by) VALUES(?,?,?,?)",
        payment,
        input.previousProofId(),
        input.newProofId(),
        actor);
    jdbc.update(
        "UPDATE payments SET proof_id=?,status='SUBMITTED',rejection_reason=NULL WHERE id=?",
        input.newProofId(),
        payment);
    jdbc.update(
        "INSERT INTO payment_events(payment_id,actor_id,action,details) VALUES(?,?,'RECEIPT_REPLACED',jsonb_build_object('previousProofId',?::text,'newProofId',?::text))",
        payment,
        actor,
        input.previousProofId().toString(),
        input.newProofId().toString());
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'PAYMENT_RECEIPT_REPLACED','PAYMENT',?,jsonb_build_object('previousProofId',?::text,'newProofId',?::text))",
        actor,
        payment,
        input.previousProofId().toString(),
        input.newProofId().toString());
  }
}
