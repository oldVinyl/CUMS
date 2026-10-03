package org.harbourcu.investment;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/rollover")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class RolloverSettingsController {
    public record Update(String defaultRolloverPolicy) {}
    private final RolloverSettingsService settings;
    private final SessionService sessions;
    private final String allowedOrigin;

    public RolloverSettingsController(RolloverSettingsService settings, SessionService sessions,
                                      @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")
                                      String allowedOrigin) {
        this.settings = settings;
        this.sessions = sessions;
        this.allowedOrigin = allowedOrigin;
    }

    @GetMapping
    public ResponseEntity<?> get(@CookieValue(name = "cu_access", required = false) String access,
                                 HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        try { return ResponseEntity.ok(settings.get(identity.userId())); }
        catch (SecurityException exception) { return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request); }
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody Update body,
                                    @CookieValue(name = "cu_access", required = false) String access,
                                    @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                    HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        if (request.getHeader("Origin") != null && !allowedOrigin.equals(request.getHeader("Origin"))
            || !sessions.csrfMatches(identity.sessionId(), csrf)) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        }
        try { return ResponseEntity.ok(settings.update(identity.userId(),
            body == null ? null : body.defaultRolloverPolicy())); }
        catch (SecurityException exception) { return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request); }
        catch (IllegalArgumentException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request); }
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code,
            status == HttpStatus.BAD_REQUEST ? "Choose a valid rollover policy."
                : status == HttpStatus.UNAUTHORIZED ? "Please sign in to continue."
                : "You do not have permission for this action.", List.of(), TraceIdFilter.traceId(request)));
    }
}
