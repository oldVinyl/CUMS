package org.harbourcu.notification;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class NotificationAdminController {
  private final NotificationAdminService service;
  private final SessionService sessions;
  private final String origin;

  public NotificationAdminController(
      NotificationAdminService service,
      SessionService sessions,
      @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin) {
    this.service = service;
    this.sessions = sessions;
    this.origin = origin;
  }

  @GetMapping
  public ResponseEntity<?> list(
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      HttpServletRequest request) {
    return call(request, false, actor -> ResponseEntity.ok(service.list(actor, status, page)));
  }

  @PostMapping("/{id}/retry")
  public ResponseEntity<?> retry(@PathVariable UUID id, HttpServletRequest request) {
    return call(
        request,
        true,
        actor -> {
          service.retry(actor, id);
          return ResponseEntity.noContent().build();
        });
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
    try {
      var result = action.apply(identity.userId());
      return ResponseEntity.status(result.getStatusCode())
          .headers(result.getHeaders())
          .header("Cache-Control", "no-store")
          .body(result.getBody());
    } catch (SecurityException failure) {
      return error(r, 403, "ACCESS_DENIED", "You cannot administer notifications.");
    } catch (IllegalArgumentException failure) {
      return error(r, 400, "VALIDATION_ERROR", failure.getMessage());
    } catch (IllegalStateException failure) {
      return error(r, 409, "NOTIFICATION_CONFLICT", failure.getMessage());
    }
  }

  private ResponseEntity<?> error(HttpServletRequest r, int status, String code, String message) {
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(new ApiError(code, message, List.of(), TraceIdFilter.traceId(r)));
  }
}
