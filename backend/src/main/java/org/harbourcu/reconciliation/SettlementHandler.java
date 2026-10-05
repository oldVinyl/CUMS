package org.harbourcu.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.role.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(FieldEncryption.class)
public class SettlementHandler implements ApprovalActionHandler {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;

  public SettlementHandler(JdbcTemplate jdbc, PermissionService permissions) {
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  public String actionType() {
    return "PAYMENT_SETTLEMENT_MATCH";
  }

  public void validateSubmission(UUID actor, JsonNode p) {
    if (!permissions.hasPermission(actor, "RECONCILE"))
      throw new SecurityException("Settlement matching permission required.");
    UUID.fromString(p.path("paymentId").asText());
    UUID.fromString(p.path("settlementId").asText());
  }

  public void execute(UUID request, UUID checker, JsonNode payload) {
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
    UUID maker =
        jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id=?", UUID.class, request);
    validateSubmission(maker, payload);
    UUID payment = UUID.fromString(payload.path("paymentId").asText()),
        id = UUID.fromString(payload.path("settlementId").asText());
    var p = jdbc.queryForMap("SELECT * FROM payments WHERE id=? FOR UPDATE", payment);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM payment_settlement_matches WHERE id=? AND approval_request_id=? AND payment_id=? AND made_by=?",
            id,
            request,
            payment,
            maker);
    if (rows.isEmpty()) throw new IllegalStateException("Settlement evidence not found.");
    var row = rows.getFirst();
    var input =
        new SettlementService.Input(
            row.get("settled_amount").toString(),
            SettlementService.date(row, "settled_date"),
            "Reviewed evidence",
            row.get("source_fingerprint").toString());
    SettlementService.validate(p, input);
    jdbc.update(
        "UPDATE payments SET settled_amount=?,settled_date=? WHERE id=?",
        row.get("settled_amount"),
        row.get("settled_date"),
        payment);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'PAYMENT_SETTLEMENT_MATCHED','PAYMENT',?,jsonb_build_object('settlementId',?::text,'requestId',?::text))",
        checker,
        payment,
        id.toString(),
        request.toString());
  }
}
