package org.harbourcu.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/google")
@ConditionalOnBean(GoogleIdentityService.class)
public class GoogleIdentityController {
    public record Credential(@NotBlank String idToken) {}
    public record Enrollment(@NotBlank String challenge, @NotBlank String idToken) {}
    public record Options(String nonce) {}
    public record Linked(boolean linked) {}

    private final GoogleIdentityService google;
    private final SessionService sessions;
    private final AuthController auth;
    private final String allowedOrigin;

    public GoogleIdentityController(GoogleIdentityService google, SessionService sessions,
                                    AuthController auth,
                                    @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")
                                    String allowedOrigin) {
        this.google = google;
        this.sessions = sessions;
        this.auth = auth;
        this.allowedOrigin = allowedOrigin;
    }

    @PostMapping("/options")
    public ResponseEntity<?> options(HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        return ResponseEntity.ok(new Options(google.issueNonce()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Credential body, HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        try {
            return auth.authenticated(google.login(body.idToken()));
        } catch (BadCredentialsException exception) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", request);
        }
    }

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@Valid @RequestBody Enrollment body, HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        try {
            return auth.authenticated(google.enroll(body.challenge(), body.idToken()));
        } catch (BadCredentialsException exception) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", request);
        } catch (DuplicateKeyException | IllegalStateException exception) {
            return error(HttpStatus.CONFLICT, "GOOGLE_ALREADY_LINKED", request);
        }
    }

    @PostMapping("/link")
    public ResponseEntity<?> link(@Valid @RequestBody Credential body,
                                   @CookieValue(name = "cu_access", required = false) String access,
                                   @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                   HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        if (!sessions.csrfMatches(identity.sessionId(), csrf)) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        }
        try {
            google.link(identity.userId(), body.idToken());
            return ResponseEntity.ok(new Linked(true));
        } catch (BadCredentialsException exception) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", request);
        } catch (DuplicateKeyException | IllegalStateException exception) {
            return error(HttpStatus.CONFLICT, "GOOGLE_ALREADY_LINKED", request);
        }
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(allowedOrigin);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, HttpServletRequest request) {
        String message = switch (code) {
            case "INVALID_CREDENTIALS" -> "Google sign-in could not be completed.";
            case "GOOGLE_ALREADY_LINKED" -> "This Google account is linked to another user.";
            case "AUTH_REQUIRED" -> "Please sign in before linking Google.";
            default -> "Request not allowed.";
        };
        return ResponseEntity.status(status).body(new ApiError(code, message,
            List.of(), TraceIdFilter.traceId(request)));
    }
}
