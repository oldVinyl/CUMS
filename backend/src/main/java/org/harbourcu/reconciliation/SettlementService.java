package org.harbourcu.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.role.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(FieldEncryption.class)
public class SettlementService {
  public record Input(
      String amount, LocalDate settledDate, String notes, String sourceFingerprint) {}

  public record Payment(
      UUID id,
      String reference,
      String gross,
      String fee,
      String expectedNet,
      LocalDate confirmedDate,
      String settledAmount,
      LocalDate settledDate,
      String sourceFingerprint,
      boolean pending) {}

  public record Match(
      UUID id,
      UUID paymentId,
      String reference,
      String amount,
      LocalDate settledDate,
      String notes,
      UUID makerId,
      UUID requestId,
      String status,
      int approvals,
      int requiredApprovals) {}

  public record Page(List<Payment> payments, List<Match> matches, int page, boolean hasNext) {}

  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final ApprovalService approvals;
  private final ObjectMapper json;
  private final FieldEncryption fields;

  public SettlementService(
      JdbcTemplate jdbc,
      PermissionService permissions,
      ApprovalService approvals,
      ObjectMapper json,
      FieldEncryption fields) {
    this.jdbc = jdbc;
    this.permissions = permissions;
    this.approvals = approvals;
    this.json = json;
    this.fields = fields;
  }

  public Page list(UUID actor, int page) {
    reader(actor);
    if (page < 0 || page > 10000) throw new IllegalArgumentException("Invalid page.");
    var rows =
        jdbc.queryForList(
            "SELECT p.*,EXISTS(SELECT 1 FROM payment_settlement_matches s JOIN approval_requests a ON a.id=s.approval_request_id WHERE s.payment_id=p.id AND a.status='PENDING') AS pending FROM payments p WHERE provider IS NOT NULL AND status='CONFIRMED' AND ledger_entry_id IS NOT NULL ORDER BY p.confirmed_at DESC,p.id DESC LIMIT 51 OFFSET ?",
            page * 50);
    var payments =
        rows.stream()
            .limit(50)
            .map(
                p ->
                    new Payment(
                        (UUID) p.get("id"),
                        (String) p.get("internal_reference"),
                        decimal(p, "amount"),
                        decimal(p, "fee_amount"),
                        ((BigDecimal) p.get("amount"))
                            .subtract((BigDecimal) p.get("fee_amount"))
                            .toPlainString(),
                        date(p, "effective_date"),
                        decimal(p, "settled_amount"),
                        date(p, "settled_date"),
                        fingerprint(p),
                        Boolean.TRUE.equals(p.get("pending"))))
            .toList();
    var matches =
        jdbc.query(
            "SELECT s.*,p.internal_reference,a.status,a.required_approvals,(SELECT count(*) FROM approval_decisions d WHERE d.request_id=a.id AND d.decision='APPROVE') AS approvals FROM payment_settlement_matches s JOIN payments p ON p.id=s.payment_id JOIN approval_requests a ON a.id=s.approval_request_id ORDER BY s.created_at DESC,s.id DESC LIMIT 51 OFFSET ?",
            (r, n) ->
                new Match(
                    r.getObject("id", UUID.class),
                    r.getObject("payment_id", UUID.class),
                    r.getString("internal_reference"),
                    r.getBigDecimal("settled_amount").toPlainString(),
                    r.getDate("settled_date").toLocalDate(),
                    fields.decrypt(r.getBytes("encrypted_notes")),
                    r.getObject("made_by", UUID.class),
                    r.getObject("approval_request_id", UUID.class),
                    r.getString("status"),
                    r.getInt("approvals"),
                    r.getInt("required_approvals")),
            page * 50);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type) VALUES(?,'SETTLEMENT_VIEWED','SETTLEMENT')",
        actor);
    return new Page(
        payments,
        matches.stream().limit(50).toList(),
        page,
        rows.size() > 50 || matches.size() > 50);
  }

  @Transactional
  public UUID submit(UUID actor, UUID payment, Input input) {
    require(actor, "RECONCILE");
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
    var p = locked(payment);
    validate(p, input);
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM payment_settlement_matches s JOIN approval_requests a ON a.id=s.approval_request_id WHERE s.payment_id=? AND a.status='PENDING')",
            Boolean.class,
            payment)))
      throw new IllegalStateException("A settlement match is already awaiting review.");
    UUID id = UUID.randomUUID();
    var payload =
        json.createObjectNode()
            .put("settlementId", id.toString())
            .put("paymentId", payment.toString())
            .put("amount", input.amount())
            .put("settledDate", input.settledDate().toString())
            .put("sourceFingerprint", input.sourceFingerprint());
    UUID req = approvals.submit("PAYMENT_SETTLEMENT_MATCH", payload, actor, 1);
    jdbc.update(
        "INSERT INTO payment_settlement_matches(id,payment_id,settled_amount,settled_date,source_fingerprint,encrypted_notes,made_by,approval_request_id) VALUES(?,?,?,?,?,?,?,?)",
        id,
        payment,
        new BigDecimal(input.amount()),
        input.settledDate(),
        input.sourceFingerprint(),
        fields.encrypt(input.notes().trim()),
        actor,
        req);
    return id;
  }

  @Transactional
  public void decide(UUID actor, UUID id, String decision, String comment) {
    reader(actor);
    require(actor, "APPROVE");
    approvals.decide(request(id), actor, ApprovalService.Decision.valueOf(decision), comment);
  }

  @Transactional
  public void cancel(UUID actor, UUID id) {
    reader(actor);
    approvals.cancel(request(id), actor);
  }

  private UUID request(UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT approval_request_id FROM payment_settlement_matches WHERE id=?",
            UUID.class,
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("Settlement match not found.");
    return rows.getFirst();
  }

  Map<String, Object> locked(UUID id) {
    var rows = jdbc.queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new IllegalArgumentException("Payment not found.");
    return rows.getFirst();
  }

  static void validate(Map<String, Object> p, Input input) {
    if (p.get("provider") == null
        || !"CONFIRMED".equals(p.get("status"))
        || p.get("ledger_entry_id") == null
        || !"GHS".equals(p.get("currency")))
      throw new IllegalStateException(
          "Only ledger-posted, gateway-confirmed GHS payments can be matched.");
    if (input == null
        || input.amount() == null
        || !input.amount().matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))
      throw new IllegalArgumentException("Enter a nonnegative GHS amount with up to two decimals.");
    if (input.settledDate() == null
        || date(p, "effective_date") == null
        || input.settledDate().isBefore(date(p, "effective_date"))
        || input.settledDate().isAfter(ReconciliationService.today()))
      throw new IllegalArgumentException(
          "Choose a settlement date from confirmation through today.");
    if (input.notes() == null || input.notes().isBlank() || input.notes().length() > 2000)
      throw new IllegalArgumentException(
          "Describe the bank evidence and any difference (up to 2,000 characters).");
    if (!fingerprint(p).equals(input.sourceFingerprint()))
      throw new IllegalStateException(
          "The payment or settlement changed. Refresh and review again.");
    if (p.get("settled_amount") != null
        && new BigDecimal(input.amount()).compareTo((BigDecimal) p.get("settled_amount")) == 0
        && input.settledDate().equals(date(p, "settled_date")))
      throw new IllegalStateException("These settlement values are already recorded.");
  }

  static String fingerprint(Map<String, Object> p) {
    try {
      String value =
          List.of(
                  "id",
                  "provider",
                  "status",
                  "currency",
                  "amount",
                  "fee_amount",
                  "effective_date",
                  "ledger_entry_id",
                  "settled_amount",
                  "settled_date")
              .stream()
              .map(k -> Objects.toString(p.get(k), ""))
              .collect(java.util.stream.Collectors.joining("|"));
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Payment review is unavailable.");
    }
  }

  static LocalDate date(Map<String, Object> p, String key) {
    var v = p.get(key);
    return v == null ? null : ((java.sql.Date) v).toLocalDate();
  }

  static String decimal(Map<String, Object> p, String key) {
    var v = p.get(key);
    return v == null ? null : ((BigDecimal) v).toPlainString();
  }

  private void reader(UUID actor) {
    if (!permissions.hasPermission(actor, "RECONCILE")
        && !permissions.hasPermission(actor, "APPROVE")
        && !permissions.hasPermission(actor, "AUDIT_READ"))
      throw new SecurityException("Access denied");
  }

  private void require(UUID actor, String code) {
    if (!permissions.hasPermission(actor, code)) throw new SecurityException("Access denied");
  }
}
