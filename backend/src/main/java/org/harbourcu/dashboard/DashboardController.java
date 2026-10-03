package org.harbourcu.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.security.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class DashboardController {
    private final DashboardService dashboard;
    private final SessionService sessions;

    public DashboardController(DashboardService dashboard, SessionService sessions) {
        this.dashboard = dashboard;
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<?> asAt(@RequestParam String asAt,
                                  @CookieValue(name = "cu_access", required = false) String access,
                                  HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED",
            "Please sign in to continue.", request);
        try {
            return ResponseEntity.ok(dashboard.asAt(identity.userId(), LocalDate.parse(asAt)));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Choose a valid as-at date.", request);
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to view all member balances.", request);
        }
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                           HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code, message, List.of(),
            TraceIdFilter.traceId(request)));
    }
}
