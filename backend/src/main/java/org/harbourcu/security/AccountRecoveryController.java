package org.harbourcu.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = {"APP_ENCRYPTION_KEY", "AUTH_THROTTLE_KEY"})
public class AccountRecoveryController {
  public record Email(String email) {}

  public record Complete(String token, String password) {}

  public record Target(UUID userId) {}

  public record Decision(String decision, String comment) {}

  private final AccountRecoveryService service;
  private final SessionService sessions;
  private final ApprovalService approvals;
  private final String origin;

  public AccountRecoveryController(
      AccountRecoveryService service,
      SessionService sessions,
      ApprovalService approvals,
      @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin) {
    this.service = service;
    this.sessions = sessions;
    this.approvals = approvals;
    this.origin = origin;
  }

  @PostMapping("/api/v1/auth/password-reset/request")
  public ResponseEntity<?> request(@RequestBody Email body, HttpServletRequest request) {
    return publicCall(
        request,
        () -> {
          service.requestPassword(body.email());
          return ResponseEntity.accepted()
              .body(
                  Map.of(
                      "message", "If this account is eligible, a recovery email has been queued."));
        });
  }

  @PostMapping("/api/v1/auth/password-reset/complete")
  public ResponseEntity<?> complete(@RequestBody Complete body, HttpServletRequest request) {
    return publicCall(
        request,
        () -> {
          service.complete(body.token(), body.password());
          return ResponseEntity.noContent().build();
        });
  }

  @PostMapping("/api/v1/account-recovery/preview")
  public ResponseEntity<?> preview(@RequestBody Target body, HttpServletRequest request) {
    return call(request, true, actor -> ResponseEntity.ok(service.preview(actor, body.userId())));
  }

  @PostMapping("/api/v1/account-recovery")
  public ResponseEntity<?> submit(
      @RequestBody AccountRecoveryService.ResetInput body, HttpServletRequest request) {
    return call(
        request,
        true,
        actor ->
            ResponseEntity.ok(Map.of("approvalRequestId", service.submit(actor, body, approvals))));
  }

  @GetMapping("/api/v1/account-recovery")
  public ResponseEntity<?> pending(HttpServletRequest request) {
    return call(request, false, actor -> ResponseEntity.ok(service.pending(actor)));
  }

  @PostMapping("/api/v1/account-recovery/{id}/decide")
  public ResponseEntity<?> decide(
      @PathVariable UUID id, @RequestBody Decision body, HttpServletRequest request) {
    return call(
        request,
        true,
        actor -> {
          service.decide(actor, id, body.decision(), body.comment(), approvals);
          return ResponseEntity.noContent().build();
        });
  }

  @PostMapping("/api/v1/account-recovery/{id}/cancel")
  public ResponseEntity<?> cancel(@PathVariable UUID id, HttpServletRequest request) {
    return call(
        request,
        true,
        actor -> {
          service.cancel(actor, id, approvals);
          return ResponseEntity.noContent().build();
        });
  }

  private ResponseEntity<?> publicCall(
      HttpServletRequest r, java.util.function.Supplier<ResponseEntity<?>> action) {
    if (r.getHeader("Origin") != null && !origin.equals(r.getHeader("Origin")))
      return error(r, 403, "ACCESS_DENIED", "Use the credit union portal to recover your account.");
    return execute(r, action);
  }

  private ResponseEntity<?> call(
      HttpServletRequest r, boolean mutation, Function<UUID, ResponseEntity<?>> action) {
    String access = null;
    if (r.getCookies() != null)
      for (var cookie : r.getCookies())
        if (cookie.getName().equals("cu_access")) access = cookie.getValue();
    var identity = sessions.resolveAccess(access).orElse(null);
    if (identity == null) return error(r, 401, "AUTH_REQUIRED", "Please sign in to continue.");
    if (mutation
        && (!sessions.csrfMatches(identity.sessionId(), r.getHeader("X-CSRF-Token"))
            || (r.getHeader("Origin") != null && !origin.equals(r.getHeader("Origin")))))
      return error(r, 403, "ACCESS_DENIED", "Refresh this page and try again.");
    return execute(r, () -> action.apply(identity.userId()));
  }

  private ResponseEntity<?> execute(
      HttpServletRequest r, java.util.function.Supplier<ResponseEntity<?>> action) {
    try {
      var result = action.get();
      return ResponseEntity.status(result.getStatusCode())
          .headers(result.getHeaders())
          .header("Cache-Control", "no-store")
          .header("Referrer-Policy", "no-referrer")
          .body(result.getBody());
    } catch (SecurityException failure) {
      return error(r, 403, "ACCESS_DENIED", "You cannot administer account recovery.");
    } catch (IllegalArgumentException failure) {
      return error(r, 400, "VALIDATION_ERROR", failure.getMessage());
    } catch (IllegalStateException failure) {
      return error(r, 409, "RECOVERY_CONFLICT", failure.getMessage());
    }
  }

  private ResponseEntity<?> error(HttpServletRequest r, int status, String code, String message) {
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(new ApiError(code, message, List.of(), TraceIdFilter.traceId(r)));
  }
}
