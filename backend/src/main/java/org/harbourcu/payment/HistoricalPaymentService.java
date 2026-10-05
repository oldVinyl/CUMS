package org.harbourcu.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.ledger.BackdatedContributionService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A receipt is evidence for the existing historical contribution workflow, never a second credit. */
@Service
public class HistoricalPaymentService {
  public record Verify(String amount, String mode, LocalDate nextCycleDate, String reason,
                       String sourceFingerprint) {}
  public record Review(BackdatedContributionService.Input input,
                       BackdatedContributionService.Preview impact) {}
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final BackdatedContributionService historical;
  private final ObjectMapper json;
  public HistoricalPaymentService(JdbcTemplate jdbc, PermissionService permissions,
      BackdatedContributionService historical, ObjectMapper json) {
    this.jdbc = jdbc; this.permissions = permissions; this.historical = historical; this.json = json;
  }
  @Transactional(readOnly = true)
  public BackdatedContributionService.Preview preview(UUID actor, UUID id, Verify body) {
    requireVerifier(actor);
    return historical.preview(actor, input(payment(id, false), body));
  }
  @Transactional
  public UUID verify(UUID actor, UUID id, Verify body, ApprovalService approvals) {
    requireVerifier(actor);
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
    var payment = payment(id, true);
    var input = input(payment, body);
    historical.check(actor, input);
    LocalDate effective = "FUTURE_ONLY".equals(input.mode()) ? input.nextCycleDate() : input.effectiveDate();
    jdbc.update("UPDATE payments SET verified_amount=?,effective_date=?,verified_by=?,status='VERIFIED_PENDING_APPROVAL',rejection_reason=NULL WHERE id=?",
        new BigDecimal(input.amount()), effective, actor, id);
    var payload = json.createObjectNode().put("paymentId", id.toString());
    payload.set("historical", json.valueToTree(input));
    UUID request = approvals.submit("PAYMENT_POST", payload, actor, 1);
    jdbc.update("UPDATE payments SET approval_request_id=? WHERE id=?", request, id);
    jdbc.update("INSERT INTO payment_events(payment_id,actor_id,action,details) VALUES(?,?,'HISTORICAL_VERIFIED',jsonb_build_object('approvalRequestId',?::text,'mode',?::text))",
        id, actor, request.toString(), input.mode());
    jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'HISTORICAL_PAYMENT_VERIFIED','PAYMENT',?)", actor, id);
    return request;
  }
  @Transactional(readOnly = true)
  public Review review(UUID actor, UUID id) {
    if (!permissions.hasPermission(actor, "APPROVE") && !permissions.hasPermission(actor, "PAYMENT_VERIFY"))
      throw new SecurityException("Payment review permission required.");
    var rows = jdbc.queryForList("SELECT r.made_by,r.payload::text FROM payments p JOIN approval_requests r ON r.id=p.approval_request_id WHERE p.id=? AND p.status='VERIFIED_PENDING_APPROVAL' AND r.status='PENDING'", id);
    if (rows.isEmpty()) throw new IllegalStateException("Payment is not awaiting approval.");
    UUID maker = (UUID) rows.getFirst().get("made_by");
    if (!maker.equals(actor) && !permissions.hasPermission(actor, "APPROVE")) throw new SecurityException("Independent approval permission required.");
    JsonNode payload;
    try { payload = json.readTree((String) rows.getFirst().get("payload")); }
    catch (Exception e) { throw new IllegalStateException("Stored payment request is invalid."); }
    if (!payload.has("historical")) return new Review(null, null);
    var input = decode(payload);
    requireVerifier(maker);
    historical.check(maker, input);
    return new Review(input, historical.preview(maker, input));
  }
  public BackdatedContributionService.Input decode(JsonNode payload) {
    try { return json.treeToValue(payload.path("historical"), BackdatedContributionService.Input.class); }
    catch (Exception e) { throw new IllegalStateException("Stored historical payment is invalid."); }
  }
  public void validate(UUID actor, Map<String,Object> payment, JsonNode payload) {
    requireVerifier(actor);
    var input = decode(payload);
    LocalDate effective = "FUTURE_ONLY".equals(input.mode()) ? input.nextCycleDate() : input.effectiveDate();
    if (!input.memberId().equals(payment.get("member_id")) || !input.type().equals(payment.get("purpose"))
        || new BigDecimal(input.amount()).compareTo((BigDecimal) payment.get("verified_amount")) != 0
        || !input.effectiveDate().equals(((java.sql.Date) payment.get("paid_date")).toLocalDate())
        || !effective.equals(((java.sql.Date) payment.get("effective_date")).toLocalDate())
        || payment.get("provider") != null)
      throw new IllegalStateException("Historical receipt no longer matches the reviewed contribution.");
    historical.check(actor, input);
  }
  public UUID post(UUID request, UUID maker, UUID checker, UUID payment, JsonNode payload) {
    return historical.postPayment(request, maker, checker, decode(payload), payment);
  }
  private Map<String,Object> payment(UUID id, boolean lock) {
    var rows = jdbc.queryForList("SELECT * FROM payments WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("Payment not found.");
    var p = rows.getFirst();
    if (p.get("provider") != null || !List.of("SUBMITTED", "UNDER_REVIEW").contains(p.get("status")))
      throw new IllegalStateException("Only an unverified manual receipt can use historical posting.");
    return p;
  }
  private BackdatedContributionService.Input input(Map<String,Object> p, Verify b) {
    LocalDate date = ((java.sql.Date) p.get("paid_date")).toLocalDate();
    if (!date.isBefore(LocalDate.now(ZoneId.of("Africa/Accra")))) throw new IllegalArgumentException("Use normal verification for a payment made today.");
    if (b == null || b.amount() == null || !b.amount().matches("[0-9]{1,10}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Enter the received amount with up to two decimals.");
    return new BackdatedContributionService.Input((UUID)p.get("member_id"), (String)p.get("purpose"), b.amount(), date, b.mode(), b.nextCycleDate(), b.reason(), b.sourceFingerprint());
  }
  private void requireVerifier(UUID actor) {
    if (!permissions.hasPermission(actor, "PAYMENT_VERIFY")) throw new SecurityException("Payment verification permission required.");
  }
}
