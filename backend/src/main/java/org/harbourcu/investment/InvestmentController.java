package org.harbourcu.investment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/investments")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class InvestmentController {
    public record Created(UUID id) {}
    public record Submitted(UUID approvalRequestId) {}
    public record ParticipationEdit(String amount, String reason) {}
    public record MaturityRequest(String actualProceeds) {}
    public record RolloverDraftRequest(String policy, InvestmentService.Create draft) {}
    public record DiscardRequest(String reason) {}
    public record DecisionRequest(String decision, String comment) {}

    private final InvestmentService investments;
    private final ApprovalService approvals;
    private final SessionService sessions;
    private final ObjectMapper json;
    private final String allowedOrigin;

    public InvestmentController(InvestmentService investments, ApprovalService approvals,
                                SessionService sessions, ObjectMapper json,
                                @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String allowedOrigin) {
        this.investments = investments;
        this.approvals = approvals;
        this.sessions = sessions;
        this.json = json;
        this.allowedOrigin = allowedOrigin;
    }

    @GetMapping
    public ResponseEntity<?> list(@CookieValue(name = "cu_access", required = false) String access,
                                  HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request, "Please sign in to continue.");
        try { return ResponseEntity.ok(investments.list(identity.userId())); }
        catch (SecurityException exception) { return denied(request); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id,
                                 @CookieValue(name = "cu_access", required = false) String access,
                                 HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request, "Please sign in to continue.");
        try { return ResponseEntity.ok(investments.get(identity.userId(), id)); }
        catch (SecurityException exception) { return denied(request); }
        catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", request, "Investment not found.");
        }
    }

    @GetMapping("/approvals/pending")
    public ResponseEntity<?> pending(@CookieValue(name = "cu_access", required = false) String access,
                                     HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request, "Please sign in to continue.");
        try { return ResponseEntity.ok(investments.pendingApprovals(identity.userId())); }
        catch (SecurityException exception) { return denied(request); }
    }

    @GetMapping("/{id}/maturity-preview")
    public ResponseEntity<?> maturityPreview(@PathVariable UUID id,
                                             @RequestParam String actualProceeds,
                                             @CookieValue(name = "cu_access", required = false) String access,
                                             HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request, "Please sign in to continue.");
        try { return ResponseEntity.ok(investments.previewMaturity(identity.userId(), id, actualProceeds)); }
        catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody InvestmentService.Create body,
                                    @CookieValue(name = "cu_access", required = false) String access,
                                    @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                    HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try { return ResponseEntity.status(HttpStatus.CREATED).body(new Created(investments.create(identity.userId(), body))); }
        catch (SecurityException exception) { return denied(request); }
        catch (DuplicateKeyException exception) {
            return error(HttpStatus.CONFLICT, "INVESTMENT_REFERENCE_EXISTS", request,
                "An investment already uses this reference.");
        }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable UUID id,
                                    @CookieValue(name = "cu_access", required = false) String access,
                                    @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                    HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            var payload = json.createObjectNode().put("investmentId", id.toString());
            return ResponseEntity.ok(new Submitted(approvals.submit("INVESTMENT_POST", payload,
                identity.userId(), 1)));
        } catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/{id}/allocations/{memberId}")
    public ResponseEntity<?> editParticipation(@PathVariable UUID id, @PathVariable UUID memberId,
                                               @RequestBody ParticipationEdit body,
                                               @CookieValue(name = "cu_access", required = false) String access,
                                               @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                               HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            investments.editParticipation(identity.userId(), id, memberId, body.amount(), body.reason());
            return ResponseEntity.noContent().build();
        } catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/{id}/mature")
    public ResponseEntity<?> mature(@PathVariable UUID id, @RequestBody MaturityRequest body,
                                    @CookieValue(name = "cu_access", required = false) String access,
                                    @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                    HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            var payload = json.createObjectNode().put("investmentId", id.toString())
                .put("actualProceeds", body.actualProceeds());
            return ResponseEntity.ok(new Submitted(approvals.submit("INVESTMENT_MATURE", payload,
                identity.userId(), 1)));
        } catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/{id}/rollover-draft")
    public ResponseEntity<?> rolloverDraft(@PathVariable UUID id,
                                           @RequestBody RolloverDraftRequest body,
                                           @CookieValue(name = "cu_access", required = false) String access,
                                           @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                           HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(new Created(investments.createRollover(
                identity.userId(), id, body.draft(), body.policy())));
        } catch (SecurityException exception) { return denied(request); }
        catch (DuplicateKeyException exception) {
            return error(HttpStatus.CONFLICT, "ROLLOVER_ALREADY_DRAFTED", request,
                "This investment already has an active rollover draft.");
        }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/{id}/settle")
    public ResponseEntity<?> settle(@PathVariable UUID id,
                                    @CookieValue(name = "cu_access", required = false) String access,
                                    @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                    HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            var payload = json.createObjectNode().put("investmentId", id.toString());
            return ResponseEntity.ok(new Submitted(approvals.submit("INVESTMENT_SETTLE", payload,
                identity.userId(), 1)));
        } catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/{id}/discard")
    public ResponseEntity<?> discard(@PathVariable UUID id, @RequestBody DiscardRequest body,
                                     @CookieValue(name = "cu_access", required = false) String access,
                                     @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                     HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            investments.discardDraft(identity.userId(), id, body == null ? null : body.reason());
            return ResponseEntity.noContent().build();
        } catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    @PostMapping("/approvals/{requestId}/decide")
    public ResponseEntity<?> decide(@PathVariable UUID requestId,
                                    @RequestBody DecisionRequest body,
                                    @CookieValue(name = "cu_access", required = false) String access,
                                    @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                    HttpServletRequest request) {
        var identity = verified(access, csrf, request);
        if (identity == null) return accessFailure(access, request);
        try {
            investments.requireInvestmentApproval(requestId);
            if (body == null || body.decision() == null) {
                throw new IllegalArgumentException("Approval decision is required");
            }
            ApprovalService.Decision decision = ApprovalService.Decision.valueOf(body.decision());
            approvals.decide(requestId, identity.userId(), decision, body.comment());
            return ResponseEntity.noContent().build();
        } catch (SecurityException exception) { return denied(request); }
        catch (IllegalStateException exception) { return conflict(exception, request); }
        catch (IllegalArgumentException exception) { return validation(exception, request); }
    }

    private SessionService.SessionIdentity verified(String access, String csrf, HttpServletRequest request) {
        if (request.getHeader("Origin") != null && !allowedOrigin.equals(request.getHeader("Origin"))) return null;
        var identity = sessions.resolveAccess(access).orElse(null);
        return identity != null && sessions.csrfMatches(identity.sessionId(), csrf) ? identity : null;
    }

    private ResponseEntity<ApiError> accessFailure(String access, HttpServletRequest request) {
        return access == null ? error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request, "Please sign in to continue.")
            : denied(request);
    }

    private ResponseEntity<ApiError> denied(HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request, "You do not have permission for this action.");
    }

    private ResponseEntity<ApiError> validation(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, exception.getMessage());
    }

    private ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "INVESTMENT_CONFLICT", request, exception.getMessage());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, HttpServletRequest request, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message,
            List.of(), TraceIdFilter.traceId(request)));
    }
}
