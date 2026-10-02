package org.harbourcu.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
@RequestMapping("/api/v1/auth/passkey")
@ConditionalOnBean(AuthFlowService.class)
public class PasskeyController {
    public record StartRegistration(String challenge) {}
    public record FinishRegistration(String challenge, @NotBlank String token, JsonNode credential) {}
    public record FinishAssertion(@NotBlank String token, JsonNode credential) {}
    public record Options(String token, JsonNode options) {}

    private final PasskeyService passkeys;
    private final AuthFlowService flow;
    private final SessionService sessions;
    private final AuthController auth;
    private final ObjectMapper json;
    private final String allowedOrigin;

    public PasskeyController(PasskeyService passkeys, AuthFlowService flow, SessionService sessions,
                             AuthController auth, ObjectMapper json,
                             @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String allowedOrigin) {
        this.passkeys = passkeys;
        this.flow = flow;
        this.sessions = sessions;
        this.auth = auth;
        this.json = json;
        this.allowedOrigin = allowedOrigin;
    }

    @PostMapping("/register/options")
    public ResponseEntity<?> registerOptions(@RequestBody StartRegistration body,
                                              @CookieValue(name = "cu_access", required = false) String access,
                                              @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                              HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        UUID userId = registrationUser(body.challenge(), access, csrf);
        if (userId == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        try {
            PasskeyService.Ceremony ceremony = passkeys.beginRegistration(userId);
            return ResponseEntity.ok(new Options(ceremony.token(), json.readTree(ceremony.options())));
        } catch (Exception exception) {
            return error(HttpStatus.BAD_REQUEST, "PASSKEY_FAILED", request);
        }
    }

    @PostMapping("/register/finish")
    public ResponseEntity<?> registerFinish(@Valid @RequestBody FinishRegistration body,
                                             @CookieValue(name = "cu_access", required = false) String access,
                                             @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                             HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        UUID userId = registrationUser(body.challenge(), access, csrf);
        if (userId == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        if (body.credential() == null || !body.credential().isObject()) {
            return error(HttpStatus.BAD_REQUEST, "PASSKEY_FAILED", request);
        }
        try {
            passkeys.finishRegistration(userId, body.token(), body.credential().toString());
            if (body.challenge() != null && !body.challenge().isBlank()) {
                UUID completed = flow.consumeAlternativeSetup(body.challenge()).orElse(null);
                if (!userId.equals(completed)) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
                return auth.authenticated(sessions.create(userId));
            }
            return ResponseEntity.ok(new AuthController.AuthStep("AUTHENTICATED", null, null, null));
        } catch (Exception exception) {
            return error(HttpStatus.UNAUTHORIZED, "PASSKEY_FAILED", request);
        }
    }

    @PostMapping("/login/options")
    public ResponseEntity<?> loginOptions(HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        try {
            PasskeyService.Ceremony ceremony = passkeys.beginAssertion();
            return ResponseEntity.ok(new Options(ceremony.token(), json.readTree(ceremony.options())));
        } catch (Exception exception) {
            return error(HttpStatus.BAD_REQUEST, "PASSKEY_FAILED", request);
        }
    }

    @PostMapping("/login/finish")
    public ResponseEntity<?> loginFinish(@Valid @RequestBody FinishAssertion body,
                                          HttpServletRequest request) {
        if (!sameOrigin(request)) return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        if (body.credential() == null || !body.credential().isObject()) {
            return error(HttpStatus.BAD_REQUEST, "PASSKEY_FAILED", request);
        }
        try {
            return auth.authenticated(passkeys.finishAssertion(body.token(), body.credential().toString()));
        } catch (BadCredentialsException exception) {
            return error(HttpStatus.UNAUTHORIZED, "PASSKEY_FAILED", request);
        }
    }

    private UUID registrationUser(String challenge, String access, String csrf) {
        if (challenge != null && !challenge.isBlank()) {
            return flow.alternativeSetupUser(challenge).orElse(null);
        }
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        return identity != null && sessions.csrfMatches(identity.sessionId(), csrf)
            ? identity.userId() : null;
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(allowedOrigin);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, HttpServletRequest request) {
        String message = code.equals("PASSKEY_FAILED")
            ? "Passkey could not be verified. Please try again."
            : code.equals("AUTH_REQUIRED") ? "Please sign in before adding a passkey."
                : "Request not allowed.";
        return ResponseEntity.status(status).body(new ApiError(code, message,
            List.of(), TraceIdFilter.traceId(request)));
    }
}
