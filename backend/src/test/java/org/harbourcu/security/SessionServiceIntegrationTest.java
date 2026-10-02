package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class SessionServiceIntegrationTest {
    @Autowired
    SessionService sessions;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void rotationInvalidatesOldTokensAndReplayRevokesTheSession() {
        UUID userId = user();
        SessionService.Tokens first = sessions.create(userId);
        SessionService.SessionIdentity identity = sessions.resolveAccess(first.access()).orElseThrow();
        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(sessions.csrfMatches(identity.sessionId(), first.csrf())).isTrue();
        assertThat(sessions.csrfMatchesRefresh(first.refresh(), first.csrf())).isTrue();
        assertThat(sessions.csrfMatches(identity.sessionId(), "wrong")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ?",
            Integer.class, userId)).isEqualTo(1);

        SessionService.Tokens second = sessions.rotate(first.refresh()).orElseThrow();
        assertThat(sessions.resolveAccess(first.access())).isEmpty();
        assertThat(sessions.resolveAccess(second.access())).isPresent();
        assertThat(sessions.csrfMatches(identity.sessionId(), first.csrf())).isFalse();
        assertThat(sessions.csrfMatches(identity.sessionId(), second.csrf())).isTrue();
        assertThat(sessions.csrfMatchesRefresh(first.refresh(), first.csrf())).isTrue();
        assertThat(sessions.csrfMatchesRefresh(first.refresh(), second.csrf())).isFalse();

        assertThat(sessions.rotate(first.refresh())).isEmpty();
        assertThat(sessions.resolveAccess(second.access())).isEmpty();
        assertThat(sessions.rotate(second.refresh())).isEmpty();
    }

    @Test
    @Transactional
    void revokeAllAndDisabledAccountStopAccess() {
        UUID userId = user();
        SessionService.Tokens first = sessions.create(userId);
        SessionService.Tokens second = sessions.create(userId);
        sessions.revokeAll(userId);
        assertThat(sessions.resolveAccess(first.access())).isEmpty();
        assertThat(sessions.resolveAccess(second.access())).isEmpty();

        SessionService.Tokens third = sessions.create(userId);
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", userId);
        assertThat(sessions.resolveAccess(third.access())).isEmpty();
        assertThat(sessions.rotate(third.refresh())).isEmpty();
        assertThatThrownBy(() -> sessions.create(userId)).hasMessageContaining("not active");
    }

    @Test
    @Transactional
    void rejectsMalformedTokens() {
        UUID userId = user();
        SessionService.Tokens tokens = sessions.create(userId);
        assertThat(sessions.resolveAccess("not-a-token")).isEmpty();
        assertThat(sessions.rotate("not-a-token")).isEmpty();
        assertThat(sessions.resolveAccess(tokens.access())).isPresent();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "test-" + id + "@invalid.example");
        return id;
    }
}
