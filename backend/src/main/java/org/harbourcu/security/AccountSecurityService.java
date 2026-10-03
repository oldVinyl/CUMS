package org.harbourcu.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.harbourcu.common.AuditService;
import org.harbourcu.common.FieldEncryption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Own-account operations require fresh password and a protected second factor. */
@Service @ConditionalOnProperty(name={"AUTH_THROTTLE_KEY","APP_ENCRYPTION_KEY"})
public class AccountSecurityService {
    public record Passkey(UUID id, String createdAt, String lastUsedAt, boolean backedUp) {}
    public record Summary(boolean totpEnabled, int recoveryCodesRemaining, List<Passkey> passkeys) {}
    public record Proof(String password, String code, String passkeyToken, JsonNode credential) {}
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final LoginThrottleService throttle;
    private final TotpCodes totp;
    private final FieldEncryption fields;
    private final RecoveryCodeService recovery;
    private final PasskeyService passkeys;
    private final SessionService sessions;
    private final AuditService audit;
    private final boolean googleConfigured;
    public AccountSecurityService(JdbcTemplate jdbc, PasswordHasher passwords, LoginThrottleService throttle,
            TotpCodes totp, FieldEncryption fields, RecoveryCodeService recovery, PasskeyService passkeys,
            SessionService sessions, AuditService audit, @Value("${APP_GOOGLE_CLIENT_ID:}") String googleClient) {
        this.jdbc=jdbc;this.passwords=passwords;this.throttle=throttle;this.totp=totp;this.fields=fields;
        this.recovery=recovery;this.passkeys=passkeys;this.sessions=sessions;this.audit=audit;
        this.googleConfigured=!googleClient.isBlank();
    }
    @Transactional(readOnly=true)
    public Summary summary(UUID actor) {
        var user=account(actor,false);
        var keys=jdbc.query("SELECT id,created_at,last_used_at,backed_up FROM passkey_credentials WHERE user_id=? AND revoked_at IS NULL ORDER BY created_at,id",
            (r,n)->new Passkey(r.getObject("id",UUID.class),r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("last_used_at")==null?null:r.getTimestamp("last_used_at").toInstant().toString(),r.getBoolean("backed_up")),actor);
        return new Summary(user.get("totp_enabled_at")!=null,jdbc.queryForObject("SELECT count(*) FROM totp_recovery_codes WHERE user_id=? AND used_at IS NULL",Integer.class,actor),keys);
    }
    @Transactional(noRollbackFor=BadCredentialsException.class)
    public void remove(UUID actor, UUID id, Proof proof, String ip) {
        var user=account(actor,true);
        boolean owns=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM passkey_credentials WHERE id=? AND user_id=? AND revoked_at IS NULL)",Boolean.class,id,actor));
        if(!owns)throw new IllegalArgumentException("This passkey is unavailable.");
        int remaining=jdbc.queryForObject("SELECT count(*) FROM passkey_credentials WHERE user_id=? AND id<>? AND revoked_at IS NULL",Integer.class,actor,id);
        boolean authenticator=user.get("totp_enabled_at")!=null;
        boolean google=googleConfigured&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM google_identity_links WHERE user_id=?)",Boolean.class,actor));
        if(remaining==0&&!authenticator&&!google)throw new IllegalStateException("Add another passkey or set up an authenticator before removing your last sign-in method.");
        verify(actor,user,proof,ip);
        jdbc.update("UPDATE passkey_credentials SET revoked_at=now() WHERE id=? AND user_id=?",id,actor);
        if(remaining==0&&"PASSKEY".equals(user.get("preferred_sign_in_method")))jdbc.update("UPDATE users SET preferred_sign_in_method=? WHERE id=?",authenticator?"TOTP":"GOOGLE",actor);
        invalidate(actor);
        audit.record(actor,AuditService.Action.PASSKEY_REMOVED,"USER",actor,null);
    }
    @Transactional(noRollbackFor=BadCredentialsException.class)
    public List<String> regenerate(UUID actor, Proof proof, String ip) {
        var user=account(actor,true);
        if(user.get("totp_enabled_at")==null)throw new IllegalStateException("Set up an authenticator before creating recovery codes.");
        verify(actor,user,proof,ip);
        jdbc.update("UPDATE totp_recovery_codes SET used_at=now() WHERE user_id=? AND used_at IS NULL",actor);
        var codes=recovery.issue(actor);
        audit.record(actor,AuditService.Action.RECOVERY_CODES_REPLACED,"USER",actor,null);
        return codes;
    }
    @Transactional(noRollbackFor=BadCredentialsException.class)
    public void changePassword(UUID actor, String newPassword, Proof proof, String ip) {
        var user=account(actor,true);
        verify(actor,user,proof,ip);
        if(passwords.verify(newPassword,(String)user.get("password_hash")))throw new IllegalArgumentException("Choose a new password.");
        String hash=passwords.hashNewPassword(newPassword);
        jdbc.update("UPDATE users SET password_hash=?,password_changed_at=now(),force_password_change=false WHERE id=?",hash,actor);
        invalidate(actor);
        audit.record(actor,AuditService.Action.PASSWORD_CHANGED,"USER",actor,null);
    }
    private Map<String,Object> account(UUID actor,boolean lock) {
        var rows=jdbc.queryForList("SELECT email,password_hash,totp_enabled_at,totp_secret_ciphertext,last_totp_step,preferred_sign_in_method FROM users WHERE id=? AND status='ACTIVE'"+(lock?" FOR UPDATE":""),actor);
        if(rows.isEmpty())throw new BadCredentialsException("Account verification failed.");
        return rows.getFirst();
    }
    private void verify(UUID actor,Map<String,Object> user,Proof proof,String ip) {
        String email=(String)user.get("email");
        if(throttle.blocked(email,ip,actor))throw new BadCredentialsException("Account verification failed. Please wait and try again.");
        boolean verified=false;
        if(proof!=null&&proof.password()!=null&&proof.password().length()<=1024&&passwords.verify(proof.password(),(String)user.get("password_hash"))) {
            if(proof.passkeyToken()!=null&&proof.credential()!=null&&proof.credential().isObject()) {
                try {
                    var tokens=passkeys.finishAssertion(proof.passkeyToken(),proof.credential().toString());
                    var identity=sessions.resolveAccess(tokens.access()).orElseThrow(()->new BadCredentialsException("Account verification failed."));
                    sessions.revoke(identity.sessionId());
                    verified=actor.equals(identity.userId());
                } catch(BadCredentialsException ignored) { verified=false; }
            } else if(user.get("totp_enabled_at")!=null) {
                if(recovery.consume(actor,proof.code())) {
                    verified=true;audit.record(actor,AuditService.Action.RECOVERY_CODE_USED,"USER",actor,null);
                } else {
                    byte[] secret=Base64.getDecoder().decode(fields.decrypt((byte[])user.get("totp_secret_ciphertext")));
                    var step=totp.matchedStep(secret,Instant.now(),proof.code());
                    long last=user.get("last_totp_step")==null?-1:((Number)user.get("last_totp_step")).longValue();
                    if(step.isPresent()&&step.getAsLong()>last) {
                        jdbc.update("UPDATE users SET last_totp_step=? WHERE id=?",step.getAsLong(),actor);verified=true;
                    }
                }
            }
        }
        if(!verified){throttle.failed(email,ip,actor);throw new BadCredentialsException("Account verification failed. Check your password and security verification.");}
        throttle.succeeded(email,ip,actor);
    }
    private void invalidate(UUID actor) {
        sessions.revokeAll(actor);
        jdbc.update("UPDATE auth_challenges SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",actor);
        jdbc.update("UPDATE passkey_ceremonies SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",actor);
    }
}
