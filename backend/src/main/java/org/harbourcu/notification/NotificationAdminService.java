package org.harbourcu.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationAdminService {
  public static final String ELIGIBLE =
      "((n.kind NOT IN ('INVITATION','PASSWORD_RESET') AND u.status='ACTIVE') OR (n.kind='PASSWORD_RESET' AND u.status='ACTIVE' AND EXISTS(SELECT 1 FROM account_reset_tokens t WHERE t.id=n.entity_id AND t.user_id=u.id AND t.consumed_at IS NULL AND t.expires_at>now() AND t.email_digest=sha256(convert_to(u.id::text || '|' || lower(trim(u.email)),'UTF8')))) OR (n.kind='INVITATION' AND u.status='DISABLED' AND u.password_hash='INVITATION_PENDING' AND EXISTS(SELECT 1 FROM account_invitations i WHERE i.user_id=u.id AND i.consumed_at IS NULL AND i.expires_at>now())))";

  public record Item(
      UUID id,
      UUID accountId,
      String kind,
      String outcome,
      String status,
      int attempts,
      OffsetDateTime createdAt,
      OffsetDateTime sentAt,
      OffsetDateTime nextAttemptAt,
      boolean canRetry) {}

  public record Page(List<Item> items, int page, boolean hasNext) {}

  private final JdbcTemplate jdbc;
  private final PermissionService permissions;

  public NotificationAdminService(JdbcTemplate jdbc, PermissionService permissions) {
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  @Transactional(readOnly = true)
  public Page list(UUID actor, String status, int page) {
    require(actor);
    if (page < 0 || page > 100000 || !List.of("ALL", "PENDING", "FAILED", "SENT").contains(status))
      throw new IllegalArgumentException("Choose a valid notification status and page.");
    var rows =
        jdbc.query(
            "SELECT n.*, ('FAILED'=n.status AND "
                + ELIGIBLE
                + ") can_retry FROM notifications n JOIN users u ON u.id=n.user_id WHERE (?='ALL' OR n.status=?) ORDER BY n.created_at DESC,n.id DESC LIMIT 26 OFFSET ?",
            (r, i) ->
                new Item(
                    r.getObject("id", UUID.class),
                    r.getObject("user_id", UUID.class),
                    r.getString("kind"),
                    r.getString("outcome"),
                    r.getString("status"),
                    r.getInt("attempts"),
                    r.getObject("created_at", OffsetDateTime.class),
                    r.getObject("sent_at", OffsetDateTime.class),
                    r.getObject("next_attempt_at", OffsetDateTime.class),
                    r.getBoolean("can_retry")),
            status,
            status,
            page * 25);
    return new Page(
        List.copyOf(rows.subList(0, Math.min(rows.size(), 25))), page, rows.size() > 25);
  }

  @Transactional
  public void retry(UUID actor, UUID id) {
    require(actor);
    var rows =
        jdbc.queryForList(
            "SELECT n.status,n.attempts,"
                + ELIGIBLE
                + " eligible FROM notifications n JOIN users u ON u.id=n.user_id WHERE n.id=? FOR UPDATE OF n,u",
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("Notification not found.");
    var row = rows.getFirst();
    if (!"FAILED".equals(row.get("status")) || !Boolean.TRUE.equals(row.get("eligible")))
      throw new IllegalStateException(
          "Only failed notifications for eligible accounts with valid links can be retried. Request a new invitation or recovery link when one has expired.");
    jdbc.update(
        "UPDATE notifications SET status='PENDING',attempts=0,next_attempt_at=now(),sent_at=NULL WHERE id=?",
        id);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'NOTIFICATION_RETRY_QUEUED','NOTIFICATION',?,jsonb_build_object('beforeStatus','FAILED','afterStatus','PENDING','previousAttempts',?))",
        actor,
        id,
        row.get("attempts"));
  }

  private void require(UUID actor) {
    if (!permissions.hasPermission(actor, "USER_ADMIN"))
      throw new SecurityException("User administration permission required.");
  }
}
