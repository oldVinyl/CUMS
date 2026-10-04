package org.harbourcu.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.investment.InvestmentService;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class LedgerControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionService sessions;
    @Autowired LedgerWorkflowService ledger;
    @Autowired InvestmentService investments;
    @Autowired ApprovalService approvals;
    final LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));

    @Test @Transactional
    void httpContributionAndReversalRequireCsrfAndIndependentApproval() throws Exception {
        UUID maker = user("TREASURER"), checker = user("TREASURER"), member = member();
        var makerTokens = sessions.create(maker); var checkerTokens = sessions.create(checker);
        String body = json.createObjectNode().put("memberId", member.toString()).put("type", "SEED").put("amount", "25.75").toString();
        mvc.perform(post("/api/v1/ledger/contributions").cookie(new Cookie("cu_access", makerTokens.access()))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ledger/contributions").cookie(new Cookie("cu_access", makerTokens.access())).header("X-CSRF-Token", makerTokens.csrf())
            .header("Origin", "https://foreign.invalid").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String result = mvc.perform(post("/api/v1/ledger/contributions").cookie(new Cookie("cu_access", makerTokens.access()))
            .header("X-CSRF-Token", makerTokens.csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(json.readTree(result).path("approvalRequestId").textValue());
        assertThat(balance(member)).isZero();
        mvc.perform(post("/api/v1/ledger/approvals/" + request + "/decide").cookie(new Cookie("cu_access", makerTokens.access()))
            .header("X-CSRF-Token", makerTokens.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ledger/approvals/" + request + "/decide").cookie(new Cookie("cu_access", checkerTokens.access()))
            .header("X-CSRF-Token", checkerTokens.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isNoContent());
        assertThat(balance(member)).isEqualByComparingTo("25.75");
        UUID original = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE reference = ?", UUID.class, "APR-" + request);
        UUID reversal = ledger.reverse(maker, original, "Duplicate corrected");
        assertThatThrownBy(() -> ledger.reverse(maker, original, "Duplicate corrected")).hasMessageContaining("already awaits approval");
        ledger.decide(checker, reversal, "APPROVE", null);
        assertThat(balance(member)).isZero();
        var entries = ledger.entries(maker, today, today, member, 0).entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().filter(entry -> entry.id().equals(original)).findFirst().orElseThrow().reversed()).isTrue();
    }

    @Test @Transactional
    void ledgerListIsPaginatedAndDeniedToMemberAccounts() throws Exception {
        UUID member = member(); UUID actor = user("AUDITOR");
        jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount, effective_date, fiscal_year, reference)
            SELECT ?, 'SEED', 'PRINCIPAL', 1, ?, ?, 'LEDGER-PAGE-' || ?::text || '-' || n FROM generate_series(1, 51) n
            """, member, today, today.getYear(), member.toString());
        assertThat(ledger.entries(actor, today, today, member, 0).entries()).hasSize(50);
        assertThat(ledger.entries(actor, today, today, member, 0).hasNext()).isTrue();
        assertThat(ledger.entries(actor, today, today, member, 1).entries()).hasSize(1);
        var tokens = sessions.create(user("MEMBER"));
        mvc.perform(get("/api/v1/ledger").param("from", today.toString()).param("to", today.toString())
            .cookie(new Cookie("cu_access", tokens.access()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ledger").param("from", today.toString()).param("to", today.toString())).andExpect(status().isUnauthorized());
    }

    @Test @Transactional
    void pendingReversalCannotRemoveFundsCommittedAfterSubmission() {
        UUID maker = user("TREASURER"), checker = user("TREASURER"), member = member();
        UUID post = ledger.contribution(maker, member, "SEED", "100.00");
        ledger.decide(checker, post, "APPROVE", null);
        UUID original = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE reference = ?", UUID.class, "APR-" + post);
        UUID reverse = ledger.reverse(maker, original, "Correction");
        UUID investment = investments.create(maker, new InvestmentService.Create("91_DAY", null, "Fixture", null, today, today.plusDays(91), "100.00", "110.00"));
        UUID submit = approvals.submit("INVESTMENT_POST", json.createObjectNode().put("investmentId", investment.toString()), maker, 1);
        approvals.decide(submit, checker, ApprovalService.Decision.APPROVE, null);
        assertThatThrownBy(() -> ledger.decide(checker, reverse, "APPROVE", null)).hasMessageContaining("committed");
        assertThat(balance(member)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT status FROM approval_requests WHERE id = ?", String.class, reverse)).isEqualTo("PENDING");
    }

    @Test @Transactional
    void databaseRejectsCompensationWithTheWrongAmount() {
        UUID member = member(); UUID original = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ledger_entries (id, member_id, type, component, amount, effective_date, fiscal_year, reference)
            VALUES (?, ?, 'SEED', 'PRINCIPAL', 10.00, ?, ?, ?)
            """, original, member, today, today.getYear(), "GUARD-" + original);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount, effective_date, fiscal_year, reference, narration, reversal_of)
            VALUES (?, 'REVERSAL', 'PRINCIPAL', -9.99, ?, ?, ?, 'Wrong amount', ?)
            """, member, today, today.getYear(), "GUARD-REV-" + original, original)).hasMessageContaining("exactly compensate");
    }

    @Test @Transactional
    void historicalEndpointsRequireAuthenticationCsrfOriginAndReviewPermission() throws Exception {
        UUID maker=user("TREASURER"), ordinary=user("MEMBER"), member=member();
        var tokens=sessions.create(maker);var ordinaryTokens=sessions.create(ordinary);
        String body=json.writeValueAsString(new BackdatedContributionService.Input(member,"ADDITION","5.25",today,"FUTURE_ONLY",today,"Fixture historical review",null));
        mvc.perform(post("/api/v1/ledger/contributions/preview").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ledger/contributions/preview").cookie(new Cookie("cu_access",tokens.access())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ledger/contributions/preview").cookie(new Cookie("cu_access",tokens.access())).header("X-CSRF-Token",tokens.csrf()).header("Origin","https://foreign.invalid").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ledger/contributions/preview").cookie(new Cookie("cu_access",ordinaryTokens.access())).header("X-CSRF-Token",ordinaryTokens.csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String preview=mvc.perform(post("/api/v1/ledger/contributions/preview").cookie(new Cookie("cu_access",tokens.access())).header("X-CSRF-Token",tokens.csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body);payload.put("sourceFingerprint",json.readTree(preview).path("sourceFingerprint").asText());
        mvc.perform(post("/api/v1/ledger/contributions/historical").cookie(new Cookie("cu_access",tokens.access())).contentType(MediaType.APPLICATION_JSON).content(payload.toString())).andExpect(status().isForbidden());
        String response=mvc.perform(post("/api/v1/ledger/contributions/historical").cookie(new Cookie("cu_access",tokens.access())).header("X-CSRF-Token",tokens.csrf()).contentType(MediaType.APPLICATION_JSON).content(payload.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("approvalRequestId").asText();
        mvc.perform(get("/api/v1/ledger/approvals/"+id+"/impact")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ledger/approvals/"+id+"/impact").cookie(new Cookie("cu_access",ordinaryTokens.access()))).andExpect(status().isForbidden());
        var auditor=sessions.create(user("AUDITOR"));
        mvc.perform(get("/api/v1/ledger/approvals/"+id+"/impact").cookie(new Cookie("cu_access",auditor.access()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ledger/approvals/"+id+"/impact").cookie(new Cookie("cu_access",tokens.access()))).andExpect(status().isOk());
        assertThat(balance(member)).isZero();
    }

    private BigDecimal balance(UUID member) { return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE member_id = ?", BigDecimal.class, member); }
    private UUID member() {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO members (id, member_number, full_name, join_date) VALUES (?, ?, 'Ledger fixture', ?)", id, "L-" + id, today); return id;
    }
    private UUID user(String role) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')", id, id + "@invalid.example");
        jdbc.update("INSERT INTO user_role_assignments (user_id, role_id, start_date) SELECT ?, id, ? FROM roles WHERE code = ?", id, today, role); return id;
    }
}
