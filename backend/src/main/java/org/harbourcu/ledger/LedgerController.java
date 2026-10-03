package org.harbourcu.ledger;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/ledger")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class LedgerController {
    public record Contribution(UUID memberId, String type, String amount) {}
    public record Reason(String reason) {}
    public record Decision(String decision, String comment) {}
    public record Submitted(UUID approvalRequestId) {}
    private final LedgerWorkflowService ledger;
    private final SessionService sessions;
    private final String origin;
    public LedgerController(LedgerWorkflowService ledger, SessionService sessions, @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin) {
        this.ledger = ledger; this.sessions = sessions; this.origin = origin;
    }
    @GetMapping public ResponseEntity<?> entries(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) UUID memberId, @RequestParam(defaultValue = "0") int page,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        return call(access, null, false, request, actor -> ResponseEntity.ok(ledger.entries(actor, LocalDate.parse(from), LocalDate.parse(to), memberId, page)));
    }
    @GetMapping("/fiscal-years") public ResponseEntity<?> years(@CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        return call(access, null, false, request, actor -> ResponseEntity.ok(ledger.years(actor)));
    }
    @GetMapping("/fiscal-years/{year}/snapshots") public ResponseEntity<?> snapshots(@PathVariable int year, @RequestParam(defaultValue = "0") int page,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        return call(access, null, false, request, actor -> ResponseEntity.ok(ledger.snapshots(actor, year, page)));
    }
    @GetMapping("/approvals") public ResponseEntity<?> pending(@CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        return call(access, null, false, request, actor -> ResponseEntity.ok(ledger.pending(actor)));
    }
    @PostMapping("/contributions") public ResponseEntity<?> contribution(@RequestBody Contribution body,
            @CookieValue(name = "cu_access", required = false) String access, @RequestHeader(name = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request) {
        return call(access, csrf, true, request, actor -> ResponseEntity.ok(new Submitted(ledger.contribution(actor, body.memberId(), body.type(), body.amount()))));
    }
    @PostMapping("/{id}/reverse") public ResponseEntity<?> reverse(@PathVariable UUID id, @RequestBody Reason body,
            @CookieValue(name = "cu_access", required = false) String access, @RequestHeader(name = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request) {
        return call(access, csrf, true, request, actor -> ResponseEntity.ok(new Submitted(ledger.reverse(actor, id, body.reason()))));
    }
    @PostMapping("/fiscal-years/{year}/close") public ResponseEntity<?> close(@PathVariable int year,
            @CookieValue(name = "cu_access", required = false) String access, @RequestHeader(name = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request) {
        return call(access, csrf, true, request, actor -> ResponseEntity.ok(new Submitted(ledger.close(actor, year))));
    }
    @PostMapping("/fiscal-years/{year}/unlock") public ResponseEntity<?> unlock(@PathVariable int year, @RequestBody Reason body,
            @CookieValue(name = "cu_access", required = false) String access, @RequestHeader(name = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request) {
        return call(access, csrf, true, request, actor -> ResponseEntity.ok(new Submitted(ledger.unlock(actor, year, body.reason()))));
    }
    @PostMapping("/approvals/{id}/decide") public ResponseEntity<?> decide(@PathVariable UUID id, @RequestBody Decision body,
            @CookieValue(name = "cu_access", required = false) String access, @RequestHeader(name = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request) {
        return call(access, csrf, true, request, actor -> { ledger.decide(actor, id, body.decision(), body.comment()); return ResponseEntity.noContent().build(); });
    }
    @PostMapping("/approvals/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable UUID id,
            @CookieValue(name = "cu_access", required = false) String access, @RequestHeader(name = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request) {
        return call(access, csrf, true, request, actor -> { ledger.cancel(actor, id); return ResponseEntity.noContent().build(); });
    }
    private ResponseEntity<?> call(String access, String csrf, boolean mutation, HttpServletRequest request, Function<UUID, ResponseEntity<?>> action) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please sign in to continue.", request);
        if (mutation && (!sessions.csrfMatches(identity.sessionId(), csrf) || (request.getHeader("Origin") != null && !origin.equals(request.getHeader("Origin"))))) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Refresh this page and try again.", request);
        }
        try { var response = action.apply(identity.userId());
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody()); }
        catch (SecurityException exception) { return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission for this ledger action.", request); }
        catch (IllegalArgumentException | DateTimeException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), request); }
        catch (IllegalStateException exception) { return error(HttpStatus.CONFLICT, "LEDGER_CONFLICT", exception.getMessage(), request); }
    }
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code, message, List.of(), TraceIdFilter.traceId(request)));
    }
}
