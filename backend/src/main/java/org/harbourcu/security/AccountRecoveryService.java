package org.harbourcu.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.role.PermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = {"APP_ENCRYPTION_KEY", "AUTH_THROTTLE_KEY"})
public class AccountRecoveryService {
  public record ResetInput(UUID userId, String reason, String securityFingerprint) {}

  public record Preview(UUID userId, String securityFingerprint) {}

  public record Pending(
      UUID id, UUID makerId, UUID userId, String reason, int requiredApprovals, int approvals) {}

  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final PasswordHasher passwords;
  private final SessionService sessions;
  private final FieldEncryption fields;
  private final ObjectMapper json;
  private final String origin;
  private final SecureRandom random = new SecureRandom();

  public AccountRecoveryService(
      JdbcTemplate jdbc,
      PermissionService permissions,
      PasswordHasher passwords,
      SessionService sessions,
      FieldEncryption fields,
      ObjectMapper json,
      @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin) {
    this.jdbc = jdbc;
    this.permissions = permissions;
    this.passwords = passwords;
    this.sessions = sessions;
    this.fields = fields;
    this.json = json;
    this.origin = origin;
  }

  @Transactional
  public void requestPassword(String email) {
    if (email == null || email.length() > 320 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw new IllegalArgumentException("Enter a valid email address.");
    var users =
        jdbc.queryForList(
            "SELECT u.id FROM users u LEFT JOIN members m ON m.id=u.member_id WHERE lower(u.email)=? AND u.status='ACTIVE' AND u.password_hash<>'RECOVERY_PENDING' AND (u.member_id IS NULL OR m.status='ACTIVE') FOR UPDATE OF u",
            email.trim().toLowerCase(Locale.ROOT));
    if (users.isEmpty()) return;
    UUID user = (UUID) users.getFirst().get("id");
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM account_reset_tokens WHERE user_id=? AND consumed_at IS NULL AND expires_at>now())",
            Boolean.class,
            user))) return;
    issue(user, "PASSWORD_ONLY", null);
  }

  @Transactional(readOnly = true)
  public Preview preview(UUID actor, UUID target) {
    require(actor, "USER_ADMIN");
    if (actor.equals(target))
      throw new IllegalArgumentException("Use your own security settings for your account.");
    account(target, false);
    return new Preview(target, fingerprint(target));
  }

  public void check(UUID maker, ResetInput input) {
    require(maker, "USER_ADMIN");
    if (input == null
        || input.userId() == null
        || maker.equals(input.userId())
        || input.reason() == null
        || input.reason().isBlank()
        || input.reason().length() > 1000)
      throw new IllegalArgumentException(
          "Choose another active account and record the recovery verification reason.");
    account(input.userId(), false);
    if (!fingerprint(input.userId()).equals(input.securityFingerprint()))
      throw new IllegalStateException(
          "Account security changed. Review the account and request a fresh recovery preview.");
  }

  @Transactional
  public UUID submit(UUID actor, ResetInput input, ApprovalService approvals) {
    if (input != null && input.userId() != null) account(input.userId(), true);
    check(actor, input);
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM approval_requests WHERE action_type='ACCOUNT_RECOVERY_RESET' AND status='PENDING' AND payload->>'userId'=?)",
            Boolean.class,
            input.userId().toString())))
      throw new IllegalStateException("This account already has a pending recovery request.");
    return approvals.submit("ACCOUNT_RECOVERY_RESET", json.valueToTree(input), actor, 1);
  }

  @Transactional(readOnly = true)
  public List<Pending> pending(UUID actor) {
    if (!permissions.hasPermission(actor, "USER_ADMIN")
        && !(permissions.hasPermission(actor, "APPROVE")
            && permissions.hasPermission(actor, "ROLE_ASSIGN")))
      throw new SecurityException("Account recovery review permission required.");
    boolean reviewer =
        permissions.hasPermission(actor, "APPROVE")
            && permissions.hasPermission(actor, "ROLE_ASSIGN");
    return jdbc.query(
        "SELECT r.id,r.made_by,(r.payload->>'userId')::uuid user_id,r.payload->>'reason' reason,r.required_approvals,(SELECT count(*) FROM approval_decisions d WHERE d.request_id=r.id AND d.decision='APPROVE') approvals FROM approval_requests r WHERE r.action_type='ACCOUNT_RECOVERY_RESET' AND r.status='PENDING' AND (? OR r.made_by=?) ORDER BY r.created_at,r.id LIMIT 100",
        (r, n) ->
            new Pending(
                r.getObject("id", UUID.class),
                r.getObject("made_by", UUID.class),
                r.getObject("user_id", UUID.class),
                r.getString("reason"),
                r.getInt("required_approvals"),
                r.getInt("approvals")),
        reviewer,
        actor);
  }

  public void validateApprover(UUID actor, ResetInput input) {
    require(actor, "ROLE_ASSIGN");
    if (actor.equals(input.userId()))
      throw new IllegalArgumentException("You cannot approve recovery of your own account.");
  }

  @Transactional
  public void decide(
      UUID actor, UUID id, String decision, String comment, ApprovalService approvals) {
    request(id);
    if (!List.of("APPROVE", "REJECT").contains(decision))
      throw new IllegalArgumentException("Choose APPROVE or REJECT.");
    approvals.decide(id, actor, ApprovalService.Decision.valueOf(decision), comment);
  }

  @Transactional
  public void cancel(UUID actor, UUID id, ApprovalService approvals) {
    request(id);
    approvals.cancel(id, actor);
  }

  public void execute(UUID request, UUID checker, ResetInput input) {
    account(input.userId(), true);
    UUID maker =
        jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id=?", UUID.class, request);
    check(maker, input);
    validateApprover(checker, input);
    UUID user = input.userId();
    invalidate(user);
    jdbc.update(
        "UPDATE passkey_credentials SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL",
        user);
    jdbc.update(
        "UPDATE totp_recovery_codes SET used_at=now() WHERE user_id=? AND used_at IS NULL", user);
    jdbc.update("DELETE FROM google_identity_links WHERE user_id=?", user);
    jdbc.update(
        "UPDATE users SET password_hash='RECOVERY_PENDING',totp_secret_ciphertext=NULL,totp_enabled_at=NULL,last_totp_step=NULL,preferred_sign_in_method=NULL,force_password_change=true,locked_until=NULL,updated_at=now() WHERE id=?",
        user);
    issue(user, "ALL_FACTORS", request);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'ACCOUNT_RECOVERY_RESET_APPROVED','USER',?,jsonb_build_object('approvalRequestId',?::text,'secondFactorsRevoked',true))",
        checker,
        user,
        request.toString());
  }

  @Transactional
  public void complete(String token, String password) {
    byte[] digest = tokenDigest(token);
    String passwordHash = passwords.hashNewPassword(password);
    var owner =
        jdbc.queryForList("SELECT user_id FROM account_reset_tokens WHERE token_hash=?", digest);
    if (owner.isEmpty())
      throw new IllegalArgumentException("Recovery link is invalid, expired or already used.");
    try {
      account((UUID) owner.getFirst().get("user_id"), true);
    } catch (IllegalArgumentException unavailable) {
      throw new IllegalArgumentException("Recovery link is invalid, expired or already used.");
    }
    // All recovery flows lock the account before its tokens, avoiding cross-flow lock inversion.

    var rows =
        jdbc.queryForList(
            "SELECT t.id,t.user_id,t.mode,t.email_digest,u.email FROM account_reset_tokens t JOIN users u ON u.id=t.user_id LEFT JOIN members m ON m.id=u.member_id WHERE t.token_hash=? AND t.consumed_at IS NULL AND t.expires_at>now() AND u.status='ACTIVE' AND (u.member_id IS NULL OR m.status='ACTIVE') FOR UPDATE OF t",
            digest);
    if (rows.isEmpty())
      throw new IllegalArgumentException("Recovery link is invalid, expired or already used.");
    var row = rows.getFirst();
    UUID user = (UUID) row.get("user_id");
    if (!MessageDigest.isEqual((byte[]) row.get("email_digest"), emailDigest(user)))
      throw new IllegalArgumentException("Recovery link is invalid, expired or already used.");
    jdbc.update(
        "UPDATE users SET password_hash=?,force_password_change=false,locked_until=NULL,updated_at=now() WHERE id=?",
        passwordHash,
        user);
    invalidate(user);
    jdbc.update(
        "UPDATE account_reset_tokens SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",
        user);
    jdbc.update(
        "UPDATE notifications SET payload='{}'::jsonb WHERE user_id=? AND kind='PASSWORD_RESET'",
        user);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'PASSWORD_RESET_COMPLETED','USER',?,jsonb_build_object('mode',?::text))",
        user,
        user,
        row.get("mode").toString());
  }

  private void issue(UUID user, String mode, UUID approval) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "UPDATE account_reset_tokens SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",
        user);
    jdbc.update(
        "INSERT INTO account_reset_tokens(id,user_id,token_hash,email_digest,mode,approval_request_id,expires_at) VALUES(?,?,?,?,?,?,now()+interval '30 minutes')",
        id,
        user,
        tokenDigest(token),
        emailDigest(user),
        mode,
        approval);
    var payload =
        json.createObjectNode()
            .put(
                "encryptedLink",
                Base64.getEncoder()
                    .encodeToString(fields.encrypt(origin + "/reset-password#token=" + token)))
            .put("mode", mode);
    jdbc.update(
        "INSERT INTO notifications(user_id,entity_id,kind,outcome,payload) VALUES(?,?,'PASSWORD_RESET','PENDING',?::jsonb)",
        user,
        id,
        payload.toString());
  }

  private void invalidate(UUID user) {
    sessions.revokeAll(user);
    jdbc.update(
        "UPDATE auth_challenges SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",
        user);
    jdbc.update(
        "UPDATE passkey_ceremonies SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",
        user);
  }

  private void account(UUID id, boolean lock) {
    if (id == null
        || jdbc.queryForList(
                "SELECT u.id FROM users u LEFT JOIN members m ON m.id=u.member_id WHERE u.id=? AND u.status='ACTIVE' AND (u.member_id IS NULL OR m.status='ACTIVE')"
                    + (lock ? " FOR UPDATE OF u" : ""),
                id)
            .isEmpty())
      throw new IllegalArgumentException("Choose an active account with an active membership.");
  }

  private void request(UUID id) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM approval_requests WHERE id=? AND action_type='ACCOUNT_RECOVERY_RESET')",
            Boolean.class,
            id))) throw new IllegalArgumentException("Recovery request not found.");
  }

  private String fingerprint(UUID user) {
    String source =
        jdbc.queryForObject(
            "SELECT jsonb_build_object('account',(SELECT jsonb_build_array(id,email,status,password_hash,totp_enabled_at,preferred_sign_in_method,totp_secret_ciphertext,last_totp_step,force_password_change) FROM users WHERE id=?),'keys',(SELECT jsonb_agg(jsonb_build_array(credential_id,revoked_at,signature_count,last_used_at) ORDER BY credential_id) FROM passkey_credentials WHERE user_id=?),'google',(SELECT google_subject FROM google_identity_links WHERE user_id=?),'roles',(SELECT jsonb_agg(jsonb_build_array(id,role_id,start_date,end_date,revoked_at) ORDER BY id) FROM user_role_assignments WHERE user_id=?),'recovery',(SELECT jsonb_agg(jsonb_build_array(code_hash,used_at) ORDER BY code_hash) FROM totp_recovery_codes WHERE user_id=?))::text",
            String.class,
            user,
            user,
            user,
            user,
            user);
    return HexFormat.of().formatHex(hash(source.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] tokenDigest(String token) {
    try {
      if (token == null || Base64.getUrlDecoder().decode(token).length != 32)
        throw new IllegalArgumentException();
      return hash(token.getBytes(StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("Recovery link is invalid, expired or already used.");
    }
  }

  private byte[] emailDigest(UUID user) {
    return jdbc.queryForObject(
        "SELECT sha256(convert_to(id::text || '|' || lower(trim(email)),'UTF8')) FROM users WHERE id=?",
        byte[].class,
        user);
  }

  private static byte[] hash(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Recovery digest unavailable.");
    }
  }

  private void require(UUID actor, String permission) {
    if (!permissions.hasPermission(actor, permission))
      throw new SecurityException("Required permission: " + permission);
  }
}
