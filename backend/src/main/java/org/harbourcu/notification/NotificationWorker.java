package org.harbourcu.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "APP_MAIL_ENABLED", havingValue = "true")
public class NotificationWorker {
  private final org.harbourcu.common.FieldEncryption fields;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final Notifier notifier;
  private final String origin;

  public NotificationWorker(
      JdbcTemplate jdbc,
      org.harbourcu.common.FieldEncryption fields,
      com.fasterxml.jackson.databind.ObjectMapper json,
      Notifier notifier,
      @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin) {
    this.fields = fields;
    this.json = json;
    this.jdbc = jdbc;
    this.notifier = notifier;
    this.origin = origin;
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 15000)
  @Transactional
  public void deliver() {
    jdbc.update(
        "UPDATE notifications n SET status='FAILED' WHERE n.status='PENDING' AND NOT EXISTS(SELECT 1 FROM users u WHERE u.id=n.user_id AND "
            + NotificationAdminService.ELIGIBLE
            + ")");
    var rows =
        jdbc.queryForList(
            "SELECT n.*,u.email FROM notifications n JOIN users u ON u.id=n.user_id WHERE n.status='PENDING' AND n.next_attempt_at<=now() AND "
                + NotificationAdminService.ELIGIBLE
                + " ORDER BY n.created_at LIMIT 5 FOR UPDATE OF n SKIP LOCKED");
    for (var n : rows)
      try {
        String kind = n.get("kind").toString(), outcome = n.get("outcome").toString();
        String text =
            "Your credit union "
                + kind.toLowerCase()
                + " has status "
                + outcome
                + ".\n\nSign in: "
                + origin
                + (kind.equals("PAYMENT") ? "/payments" : "/approvals");
        if (kind.equals("INVITATION")) {
          try {
            var payload = json.readTree(n.get("payload").toString());
            text =
                "You have been invited to your credit union portal. Set your password within 72 hours:\n\n"
                    + fields.decrypt(
                        java.util.Base64.getDecoder()
                            .decode(payload.path("encryptedLink").asText()))
                    + "\n\nYou will choose a protected sign-in method on your first login.";
          } catch (java.io.IOException e) {
            throw new IllegalStateException("Invitation payload unavailable.");
          }
        }
        notifier.send(
            n.get("email").toString(),
            "Credit union: " + kind.toLowerCase() + " " + outcome.toLowerCase(),
            text);
        jdbc.update(
            "UPDATE notifications SET status='SENT',sent_at=now(),attempts=attempts+1 WHERE id=?",
            n.get("id"));
      } catch (RuntimeException e) {
        jdbc.update(
            "UPDATE notifications SET attempts=attempts+1,status=CASE WHEN attempts>=9 THEN 'FAILED' ELSE 'PENDING' END,next_attempt_at=now()+interval '10 minutes' WHERE id=?",
            n.get("id"));
      }
  }
}
