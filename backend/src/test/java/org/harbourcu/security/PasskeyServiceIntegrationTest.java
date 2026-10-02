package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "APP_COOKIE_SECURE=false"
})
@AutoConfigureMockMvc
class PasskeyServiceIntegrationTest {
    @Autowired PasskeyService passkeys;
    @Autowired PasskeyCredentialRepository credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    @Transactional
    void registrationOptionsRequireAnActiveAccountAndUseDiscoverableVerifiedCredentials() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            userId, "passkey-" + userId + "@invalid.example");
        PasskeyService.Ceremony ceremony = passkeys.beginRegistration(userId);
        assertThat(ceremony.token()).hasSize(43);
        assertThat(ceremony.options()).contains("\"id\":\"localhost\"",
            "\"residentKey\":\"required\"", "\"userVerification\":\"required\"");
        assertThat(credentials.getUserHandleForUsername("passkey-" + userId + "@invalid.example"))
            .contains(PasskeyCredentialRepository.handle(userId));
        assertThat(PasskeyCredentialRepository.userId(PasskeyCredentialRepository.handle(userId)))
            .isEqualTo(userId);
    }

    @Test
    @Transactional
    void malformedAssertionConsumesItsCeremonyAndCannotBeReplayed() {
        PasskeyService.Ceremony ceremony = passkeys.beginAssertion();
        assertThat(ceremony.options()).contains("\"userVerification\":\"required\"");
        assertThatThrownBy(() -> passkeys.finishAssertion(ceremony.token(), "{}"))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> passkeys.finishAssertion(ceremony.token(), "{}"))
            .isInstanceOf(BadCredentialsException.class).hasMessage("Passkey ceremony expired");
    }

    @Test
    void browserEndpointsRejectCrossOriginAndUnverifiedRegistration() throws Exception {
        mvc.perform(post("/api/v1/auth/passkey/login/options")
            .header("Origin", "https://attacker.example"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/passkey/register/options")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post("/api/v1/auth/passkey/login/options"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString())
            .andExpect(jsonPath("$.options.publicKey.challenge").isString());
    }
}
