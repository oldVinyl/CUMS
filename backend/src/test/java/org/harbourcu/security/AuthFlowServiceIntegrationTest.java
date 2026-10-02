package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.harbourcu.common.FieldEncryption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class AuthFlowServiceIntegrationTest {
    private static final String INITIAL_PASSWORD = "Initial compass boat 2026!";
    private static final String NEW_PASSWORD = "Second compass boat 2026!";

    @Autowired AuthFlowService flow;
    @Autowired PasswordHasher passwords;
    @Autowired TotpCodes totp;
    @Autowired FieldEncryption fields;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void officerChangesPasswordEnrollsTotpAndCannotReplayTheSameCode() {
        UUID officer = user(true);
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            SELECT ?, id, current_date - 1 FROM roles WHERE code = 'TREASURER'
            """, officer);
        String email = email(officer);
        AuthFlowService.Step first = flow.begin(email, INITIAL_PASSWORD, "192.0.2.41");
        assertThat(first.stage()).isEqualTo(AuthFlowService.Stage.PASSWORD_CHANGE);
        assertThat(first.tokens()).isNull();
        assertThatThrownBy(() -> flow.changePassword(first.challenge(), INITIAL_PASSWORD))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose a new password");
    }

    @Test
    @Transactional
    void officerCompletesEachStageAndChallengesAreSingleUse() {
        UUID officer = user(true);
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            SELECT ?, id, current_date - 1 FROM roles WHERE code = 'TREASURER'
            """, officer);
        AuthFlowService.Step first = flow.begin(email(officer), INITIAL_PASSWORD, "192.0.2.42");
        AuthFlowService.Step setup = flow.changePassword(first.challenge(), NEW_PASSWORD).orElseThrow();
        assertThat(setup.stage()).isEqualTo(AuthFlowService.Stage.TOTP_SETUP);
        assertThat(flow.changePassword(first.challenge(), NEW_PASSWORD)).isEmpty();
        assertThat(flow.beginTotpSetup(setup.challenge())).hasValueSatisfying(
            secret -> assertThat(secret).matches("[A-Z2-7]{32}"));
        byte[] stored = jdbc.queryForObject("SELECT totp_secret_ciphertext FROM users WHERE id = ?",
            byte[].class, officer);
        byte[] secret = Base64.getDecoder().decode(fields.decrypt(stored));
        String code = totp.atStep(secret, Instant.now().getEpochSecond() / 30, 6);
        SessionService.Tokens tokens = flow.confirmTotpSetup(setup.challenge(), code).orElseThrow();
        assertThat(sessions.resolveAccess(tokens.access())).isPresent();
        assertThat(flow.confirmTotpSetup(setup.challenge(), code)).isEmpty();

        AuthFlowService.Step verify = flow.begin(email(officer), NEW_PASSWORD, "192.0.2.43");
        assertThat(verify.stage()).isEqualTo(AuthFlowService.Stage.TOTP_VERIFY);
        assertThat(flow.verifyTotp(verify.challenge(), code)).isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT attempts FROM auth_challenges WHERE user_id = ? AND purpose = 'TOTP_VERIFY'
            ORDER BY created_at DESC LIMIT 1
            """, Integer.class, officer)).isEqualTo(1);
    }

    @Test
    @Transactional
    void ordinaryUserGetsSessionAfterPasswordAndBadCodesExhaustChallenge() {
        UUID member = user(false);
        AuthFlowService.Step direct = flow.begin(email(member), INITIAL_PASSWORD, "192.0.2.44");
        assertThat(direct.stage()).isEqualTo(AuthFlowService.Stage.AUTHENTICATED);
        assertThat(sessions.resolveAccess(direct.tokens().access())).isPresent();

        UUID officer = user(false);
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            SELECT ?, id, current_date - 1 FROM roles WHERE code = 'TREASURER'
            """, officer);
        AuthFlowService.Step setup = flow.begin(email(officer), INITIAL_PASSWORD, "192.0.2.45");
        assertThat(setup.stage()).isEqualTo(AuthFlowService.Stage.TOTP_SETUP);
        assertThat(flow.verifyTotp(setup.challenge(), "123456")).isEmpty();
        for (int i = 0; i < 5; i++) {
            assertThat(flow.confirmTotpSetup(setup.challenge(), "123456")).isEmpty();
        }
        assertThat(flow.beginTotpSetup(setup.challenge())).isEmpty();
    }

    private UUID user(boolean forcePasswordChange) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (id, email, password_hash, force_password_change)
            VALUES (?, ?, ?, ?)
            """, id, email(id), passwords.hashNewPassword(INITIAL_PASSWORD), forcePasswordChange);
        return id;
    }

    private String email(UUID id) {
        return "test-" + id + "@invalid.example";
    }
}
