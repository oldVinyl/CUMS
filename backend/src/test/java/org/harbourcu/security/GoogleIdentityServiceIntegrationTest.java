package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "APP_GOOGLE_CLIENT_ID=test-client",
    "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class GoogleIdentityServiceIntegrationTest {
    @Autowired GoogleIdentityService google;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @MockitoBean JwtDecoder googleJwtDecoder;

    @Test
    @Transactional
    void linkedVerifiedGoogleSubjectCanSignInWithFreshNonce() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            userId, "google-" + userId + "@invalid.example");
        AtomicReference<String> nonce = new AtomicReference<>(google.issueNonce());
        when(googleJwtDecoder.decode(anyString())).thenAnswer(ignored -> jwt(nonce.get(), true));
        google.link(userId, "signed-token");
        assertThat(jdbc.queryForObject("SELECT preferred_sign_in_method FROM users WHERE id = ?",
            String.class, userId)).isEqualTo("GOOGLE");
        assertThatThrownBy(() -> google.login("signed-token"))
            .isInstanceOf(BadCredentialsException.class);
        nonce.set(google.issueNonce());
        SessionService.Tokens tokens = google.login("signed-token");
        assertThat(sessions.resolveAccess(tokens.access())).isPresent();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'GOOGLE_LOGIN'
            """, Integer.class, userId)).isEqualTo(1);
    }

    @Test
    @Transactional
    void rejectsUnverifiedGoogleEmailAndUnlinkedSubject() {
        AtomicReference<String> nonce = new AtomicReference<>(google.issueNonce());
        when(googleJwtDecoder.decode(anyString())).thenAnswer(ignored -> jwt(nonce.get(), false));
        assertThatThrownBy(() -> google.login("signed-token"))
            .isInstanceOf(BadCredentialsException.class);
        nonce.set(google.issueNonce());
        when(googleJwtDecoder.decode(anyString())).thenAnswer(ignored -> jwt(nonce.get(), true));
        assertThatThrownBy(() -> google.login("signed-token"))
            .isInstanceOf(BadCredentialsException.class);
    }

    private Jwt jwt(String nonce, boolean emailVerified) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("signed-token")
            .header("alg", "RS256")
            .subject("google-subject-123")
            .audience(List.of("test-client"))
            .issuedAt(now.minusSeconds(10))
            .expiresAt(now.plusSeconds(300))
            .claim("nonce", nonce)
            .claim("email_verified", emailVerified)
            .build();
    }
}
