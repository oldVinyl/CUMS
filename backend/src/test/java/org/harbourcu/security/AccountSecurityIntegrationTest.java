package org.harbourcu.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.harbourcu.common.FieldEncryption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.flyway.default-schema=account_security_tests","spring.datasource.hikari.schema=account_security_tests","AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class AccountSecurityIntegrationTest {
    static final String PASSWORD="Security fixture compass 2026!",NEW_PASSWORD="Replacement fixture compass 2026!";
    @Autowired AccountSecurityService security; @Autowired PasswordHasher passwords;
    @Autowired RecoveryCodeService recovery; @Autowired SessionService sessions;
    @Autowired AuthFlowService flow; @Autowired JdbcTemplate jdbc; @Autowired FieldEncryption fields;
    @Autowired TotpCodes totp; @Autowired PasskeyCredentialRepository credentials; @Autowired MockMvc mvc;
    @Test void replacingCodesInvalidatesOldCodesAndStoresOnlyHashes(){
        UUID actor=user(true);var old=recovery.issue(actor);var result=security.regenerate(actor,proof(old.getFirst()),ip(actor));
        assertThat(result).hasSize(10).doesNotHaveDuplicates();
        assertThat(old).allMatch(code->!recovery.consume(actor,code));
        assertThat(security.summary(actor).recoveryCodesRemaining()).isEqualTo(10);
        assertThat(recovery.consume(actor,result.getFirst())).isTrue();assertThat(recovery.consume(actor,result.getFirst())).isFalse();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE actor_id=?",String.class,actor)).contains("RECOVERY_CODES_REPLACED","RECOVERY_CODE_USED");
        assertThat(jdbc.queryForObject("SELECT octet_length(code_hash) FROM totp_recovery_codes WHERE user_id=? LIMIT 1",Integer.class,actor)).isEqualTo(32);
    }
    @Test void removalRevokesCredentialAndSessionsAndChangesUnavailablePreference(){
        UUID actor=user(true);UUID key=key(actor);jdbc.update("UPDATE users SET preferred_sign_in_method='PASSKEY' WHERE id=?",actor);
        var access=sessions.create(actor);var other=sessions.create(actor);var codes=recovery.issue(actor);
        security.remove(actor,key,proof(codes.getFirst()),ip(actor));
        assertThat(security.summary(actor).passkeys()).isEmpty();
        assertThat(credentials.getCredentialIdsForUsername(email(actor))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM passkey_credentials WHERE id=?",Boolean.class,key)).isTrue();
        assertThat(sessions.resolveAccess(access.access())).isEmpty();assertThat(sessions.resolveAccess(other.access())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT preferred_sign_in_method FROM users WHERE id=?",String.class,actor)).isEqualTo("TOTP");
    }
    @Test void lastProtectedMethodAndForeignCredentialCannotBeRemoved(){
        UUID actor=user(false),other=user(true);UUID own=key(actor),foreign=key(other);
        assertThatThrownBy(()->security.remove(actor,own,proof("000000"),ip(actor))).isInstanceOf(IllegalStateException.class).hasMessageContaining("last sign-in");
        assertThatThrownBy(()->security.remove(actor,foreign,proof("000000"),ip(actor))).isInstanceOf(IllegalArgumentException.class);
        assertThat(security.summary(actor).passkeys()).hasSize(1);assertThat(security.summary(other).passkeys()).hasSize(1);
        assertThatThrownBy(()->security.regenerate(actor,proof("000000"),ip(actor))).isInstanceOf(IllegalStateException.class);
    }
    @Test void passwordChangeRequiresProofAndRevokesEverySessionAndChallenge(){
        UUID actor=user(true);var first=sessions.create(actor);var second=sessions.create(actor);
        var challenge=flow.begin(email(actor),PASSWORD,ip(actor));var codes=recovery.issue(actor);
        assertThatThrownBy(()->security.changePassword(actor,"short",proof(codes.getFirst()),ip(actor))).isInstanceOf(IllegalArgumentException.class);
        assertThat(security.summary(actor).recoveryCodesRemaining()).isEqualTo(10);
        assertThatThrownBy(()->security.changePassword(actor,PASSWORD,proof("invalid-code"),ip(actor))).isInstanceOf(BadCredentialsException.class);
        security.changePassword(actor,NEW_PASSWORD,proof(codes.getFirst()),ip(actor));
        assertThat(passwords.verify(NEW_PASSWORD,jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,actor))).isTrue();
        assertThat(sessions.resolveAccess(first.access())).isEmpty();assertThat(sessions.resolveAccess(second.access())).isEmpty();
        assertThat(flow.verifyTotp(challenge.challenge(),codes.get(1))).isEmpty();
    }
    @Test void wrongProofFailuresPersistAndLockoutCannotBeResetWithPasswordAlone(){
        UUID actor=user(true);var codes=recovery.issue(actor);
        for(int n=0;n<5;n++)assertThatThrownBy(()->security.regenerate(actor,proof("invalid-code"),ip(actor))).isInstanceOf(BadCredentialsException.class);
        assertThat(jdbc.queryForObject("SELECT locked_until>now() FROM users WHERE id=?",Boolean.class,actor)).isTrue();
        assertThatThrownBy(()->security.regenerate(actor,proof(codes.getFirst()),ip(actor))).isInstanceOf(BadCredentialsException.class);
        assertThat(security.summary(actor).recoveryCodesRemaining()).isEqualTo(10);
    }
    @Test void authenticatorVerificationCannotBeReplayed(){
        UUID actor=user(true);byte[] secret=Base64.getDecoder().decode(fields.decrypt(jdbc.queryForObject("SELECT totp_secret_ciphertext FROM users WHERE id=?",byte[].class,actor)));
        String code=totp.atStep(secret,Instant.now().getEpochSecond()/30,6);
        security.regenerate(actor,proof(code),ip(actor));
        assertThatThrownBy(()->security.regenerate(actor,proof(code),ip(actor))).isInstanceOf(BadCredentialsException.class);
        assertThat(security.summary(actor).recoveryCodesRemaining()).isEqualTo(10);
    }
    @Test void lostAuthenticatorUsesOneRecoveryCodeAndCanRemoveLostPasskey(){
        UUID actor=user(true);UUID lost=key(actor);var codes=recovery.issue(actor);
        var step=flow.begin(email(actor),PASSWORD,ip(actor));var recovered=flow.verifyTotp(step.challenge(),codes.getFirst()).orElseThrow();
        assertThat(sessions.resolveAccess(recovered.access())).isPresent();
        security.remove(actor,lost,proof(codes.get(1)),ip(actor));
        assertThat(security.summary(actor).passkeys()).isEmpty();assertThat(recovery.consume(actor,codes.getFirst())).isFalse();
    }
    @Test void routesDenyAnonymousCsrfCrossOriginAndForeignIdsWithoutLeakingCredentials()throws Exception{
        UUID actor=user(true),other=user(true);UUID foreign=key(other);var tokens=sessions.create(actor);Cookie cookie=new Cookie("cu_access",tokens.access());
        mvc.perform(get("/api/v1/auth/security")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/security").cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/api/v1/auth/security/recovery-codes").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/security/recovery-codes").cookie(cookie).header("X-CSRF-Token",tokens.csrf()).header("Origin","https://attacker.invalid").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/security/passkeys/"+foreign+"/remove").cookie(cookie).header("X-CSRF-Token",tokens.csrf()).header("Origin","http://localhost:5173").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This passkey is unavailable."));
    }
    private AccountSecurityService.Proof proof(String code){return new AccountSecurityService.Proof(PASSWORD,code,null,null);}
    private UUID user(boolean enabled){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash,force_password_change,totp_secret_ciphertext,totp_enabled_at) VALUES(?,?,?,false,?,"+(enabled?"now()":"null")+")",id,email(id),passwords.hashNewPassword(PASSWORD),enabled?fields.encrypt(Base64.getEncoder().encodeToString(totp.newSecret())):null);return id;}
    private UUID key(UUID actor){byte[] bytes=UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);return jdbc.queryForObject("INSERT INTO passkey_credentials(credential_id,user_id,public_key_cose) VALUES(?,?,?) RETURNING id",UUID.class,bytes,actor,new byte[]{1,2,3});}
    private String email(UUID id){return id+"@invalid.example";}
    private String ip(UUID id){return "fixture-"+id;}
}
