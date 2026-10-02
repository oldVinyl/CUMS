package org.harbourcu.security;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.harbourcu.common.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/** WebAuthn ceremonies use database-backed, one-use requests and verified RP origins. */
@Service
public class PasskeyService {
    public record Ceremony(String token, String options) {}
    private record Stored(UUID userId, String requestJson) {}

    private final JdbcTemplate jdbc;
    private final RelyingParty relyingParty;
    private final SessionService sessions;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public PasskeyService(JdbcTemplate jdbc, PasskeyCredentialRepository credentials,
                          SessionService sessions, AuditService audit,
                          @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin,
                          @Value("${APP_PASSKEY_RP_ID:localhost}") String rpId) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.relyingParty = RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder().id(rpId).name("Harbour Credit Union").build())
            .credentialRepository(credentials).origins(Set.of(origin)).build();
    }

    public Ceremony beginRegistration(UUID userId) {
        String email = jdbc.query("SELECT email FROM users WHERE id = ? AND status = 'ACTIVE'",
            (row, ignored) -> row.getString(1), userId).stream().findFirst()
            .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
        try {
            UserIdentity user = UserIdentity.builder().name(email).displayName(email)
                .id(PasskeyCredentialRepository.handle(userId)).build();
            PublicKeyCredentialCreationOptions request = relyingParty.startRegistration(
                StartRegistrationOptions.builder().user(user)
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED).build())
                    .timeout(120_000).build());
            return store("REGISTER", userId, request.toJson(), request.toCredentialsCreateJson());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start passkey registration", exception);
        }
    }

    public void finishRegistration(UUID userId, String token, String credentialJson) {
        Stored ceremony = consume(token, "REGISTER");
        if (!userId.equals(ceremony.userId())) throw new BadCredentialsException("Invalid passkey ceremony");
        try {
            RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                .request(PublicKeyCredentialCreationOptions.fromJson(ceremony.requestJson()))
                .response(PublicKeyCredential.parseRegistrationResponseJson(credentialJson)).build());
            if (!result.isUserVerified()) throw new BadCredentialsException("User verification required");
            jdbc.update("""
                INSERT INTO passkey_credentials
                    (credential_id, user_id, public_key_cose, signature_count, backup_eligible, backed_up)
                VALUES (?, ?, ?, ?, ?, ?)
                """, result.getKeyId().getId().getBytes(), userId,
                result.getPublicKeyCose().getBytes(), result.getSignatureCount(),
                result.isBackupEligible(), result.isBackedUp());
            jdbc.update("""
                UPDATE users SET preferred_sign_in_method = 'PASSKEY'
                WHERE id = ? AND preferred_sign_in_method IS NULL
                """, userId);
            audit.record(userId, AuditService.Action.PASSKEY_REGISTERED, "USER", userId, null);
        } catch (BadCredentialsException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadCredentialsException("Passkey registration failed", exception);
        }
    }

    public Ceremony beginAssertion() {
        try {
            AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED).timeout(120_000).build());
            return store("ASSERT", null, request.toJson(), request.toCredentialsGetJson());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start passkey sign-in", exception);
        }
    }

    public SessionService.Tokens finishAssertion(String token, String credentialJson) {
        Stored ceremony = consume(token, "ASSERT");
        try {
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                .request(AssertionRequest.fromJson(ceremony.requestJson()))
                .response(PublicKeyCredential.parseAssertionResponseJson(credentialJson)).build());
            if (!result.isSuccess() || !result.isUserVerified() || !result.isSignatureCounterValid()) {
                throw new BadCredentialsException("Passkey verification failed");
            }
            UUID userId = jdbc.query("SELECT id FROM users WHERE email = ? AND status = 'ACTIVE'",
                (row, ignored) -> row.getObject(1, UUID.class), result.getUsername())
                .stream().findFirst().orElseThrow(() -> new BadCredentialsException("Account unavailable"));
            int updated = jdbc.update("""
                UPDATE passkey_credentials SET signature_count = ?, backed_up = ?, last_used_at = now()
                WHERE credential_id = ? AND user_id = ? AND signature_count <= ?
                """, result.getSignatureCount(), result.isBackedUp(),
                result.getCredential().getCredentialId().getBytes(), userId, result.getSignatureCount());
            if (updated != 1) throw new BadCredentialsException("Passkey counter changed");
            audit.record(userId, AuditService.Action.PASSKEY_LOGIN, "USER", userId, null);
            return sessions.create(userId);
        } catch (BadCredentialsException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadCredentialsException("Passkey verification failed", exception);
        }
    }

    private Ceremony store(String kind, UUID userId, String requestJson, String options) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM passkey_ceremonies WHERE expires_at < now() - interval '1 day'");
        jdbc.update("""
            INSERT INTO passkey_ceremonies (token_hash, kind, user_id, request_json, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, digest(token), kind, userId, requestJson,
            OffsetDateTime.ofInstant(Instant.now().plusSeconds(300), ZoneOffset.UTC));
        return new Ceremony(token, options);
    }

    private Stored consume(String token, String kind) {
        if (token == null || token.length() > 128) throw new BadCredentialsException("Invalid passkey ceremony");
        List<Stored> rows = jdbc.query("""
            UPDATE passkey_ceremonies SET consumed_at = now()
            WHERE token_hash = ? AND kind = ? AND consumed_at IS NULL AND expires_at > now()
            RETURNING user_id, request_json
            """, (row, ignored) -> new Stored(row.getObject("user_id", UUID.class),
                row.getString("request_json")), digest(token), kind);
        if (rows.isEmpty()) throw new BadCredentialsException("Passkey ceremony expired");
        return rows.getFirst();
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
