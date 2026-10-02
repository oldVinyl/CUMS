package org.harbourcu.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.harbourcu.approval.ApprovalService.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(ApprovalServiceIntegrationTest.HandlerConfig.class)
class ApprovalServiceIntegrationTest {
    @TestConfiguration
    static class HandlerConfig {
        @Bean
        ApprovalActionHandler fixtureActionHandler(JdbcTemplate jdbc) {
            return new ApprovalActionHandler() {
                @Override
                public String actionType() { return "FIXTURE_ACTION"; }

                @Override
                public void validateSubmission(UUID makerId, JsonNode payload) {
                    if (!payload.hasNonNull("reference")) {
                        throw new IllegalArgumentException("Reference is required");
                    }
                }

                @Override
                public void execute(UUID requestId, JsonNode payload) {
                    if (payload.path("fail").asBoolean()) {
                        throw new IllegalStateException("Fixture execution failed");
                    }
                    jdbc.update("""
                        INSERT INTO audit_log (action, entity_type, entity_id, details)
                        VALUES ('FIXTURE_EXECUTED', 'APPROVAL', ?, '{}'::jsonb)
                        """, requestId);
                }
            };
        }
    }

    @Autowired
    ApprovalService approvals;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    PlatformTransactionManager transactions;

    @Test
    @Transactional
    void makerCannotApproveAndAnotherTreasurerCanExecute() {
        UUID maker = userWithRole("TREASURER");
        UUID checker = userWithRole("TREASURER");
        UUID request = submit(maker, 1);
        assertThatThrownBy(() -> approvals.decide(request, maker, Decision.APPROVE, null))
            .hasMessageContaining("Maker cannot approve");
        approvals.decide(request, checker, Decision.APPROVE, null);
        assertThat(status(request)).isEqualTo("APPROVED");
        assertThat(executionCount(request)).isEqualTo(1);
        assertThatThrownBy(() -> approvals.decide(request, checker, Decision.APPROVE, null))
            .hasMessageContaining("no longer pending");
    }

    @Test
    @Transactional
    void twoDistinctCurrentApproversAreRequired() {
        UUID maker = userWithRole("MEMBER");
        UUID president = userWithRole("PRESIDENT");
        UUID vicePresident = userWithRole("VICE_PRESIDENT");
        UUID request = submit(maker, 2);
        approvals.decide(request, president, Decision.APPROVE, null);
        assertThat(status(request)).isEqualTo("PENDING");
        assertThat(executionCount(request)).isZero();
        assertThatThrownBy(() -> approvals.decide(request, president, Decision.APPROVE, null))
            .hasMessageContaining("already decided");
        approvals.decide(request, vicePresident, Decision.APPROVE, null);
        assertThat(status(request)).isEqualTo("APPROVED");
        assertThat(executionCount(request)).isEqualTo(1);
    }

    @Test
    @Transactional
    void revokedApproverIsRejectedImmediately() {
        UUID maker = userWithRole("MEMBER");
        UUID checker = userWithRole("TREASURER");
        UUID request = submit(maker, 1);
        jdbc.update("""
            UPDATE user_role_assignments
            SET revoked_at = now(), revoked_by = ?, reason = 'Fixture rotation'
            WHERE user_id = ?
            """, maker, checker);
        assertThatThrownBy(() -> approvals.decide(request, checker, Decision.APPROVE, null))
            .hasMessageContaining("not currently eligible");
        assertThat(status(request)).isEqualTo("PENDING");
    }

    @Test
    @Transactional
    void rejectionRequiresCommentAndMakerMayCancel() {
        UUID maker = userWithRole("MEMBER");
        UUID checker = userWithRole("TREASURER");
        UUID rejected = submit(maker, 1);
        assertThatThrownBy(() -> approvals.decide(rejected, checker, Decision.REJECT, " "))
            .hasMessageContaining("requires a comment");
        approvals.decide(rejected, checker, Decision.REJECT, "Incorrect source document");
        assertThat(status(rejected)).isEqualTo("REJECTED");
        assertThat(executionCount(rejected)).isZero();

        UUID cancelled = submit(maker, 1);
        assertThatThrownBy(() -> approvals.cancel(cancelled, checker))
            .hasMessageContaining("Only the maker");
        approvals.cancel(cancelled, maker);
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    @Transactional
    void unsupportedActionCannotBeSubmitted() {
        UUID maker = userWithRole("MEMBER");
        assertThatThrownBy(() -> approvals.submit("UNKNOWN", json.createObjectNode(), maker, 1))
            .hasMessageContaining("Unsupported approval action");
    }

    @Test
    void failedActionRollsBackFinalDecision() {
        AtomicReference<UUID> request = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            UUID maker = userWithRole("MEMBER");
            UUID checker = userWithRole("TREASURER");
            request.set(approvals.submit("FIXTURE_ACTION",
                json.createObjectNode().put("reference", "TEST").put("fail", true), maker, 1));
            approvals.decide(request.get(), checker, Decision.APPROVE, null);
        }))
            .hasMessageContaining("Fixture execution failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_requests WHERE id = ?",
            Integer.class, request.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions WHERE request_id = ?",
            Integer.class, request.get())).isZero();
    }

    private UUID submit(UUID maker, int required) {
        return approvals.submit("FIXTURE_ACTION", json.createObjectNode().put("reference", "TEST"),
            maker, required);
    }

    private UUID userWithRole(String role) {
        UUID userId = UUID.randomUUID();
        UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            userId, "test-" + userId + "@invalid.example");
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            VALUES (?, ?, ?)
            """, userId, roleId, LocalDate.now(ZoneId.of("Africa/Accra")));
        return userId;
    }

    private String status(UUID request) {
        return jdbc.queryForObject("SELECT status FROM approval_requests WHERE id = ?", String.class, request);
    }

    private int executionCount(UUID request) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM audit_log
            WHERE action = 'FIXTURE_EXECUTED' AND entity_id = ?
            """, Integer.class, request);
    }
}
