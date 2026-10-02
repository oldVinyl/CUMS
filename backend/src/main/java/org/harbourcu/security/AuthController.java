package org.harbourcu.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.common.AuditService;
import org.harbourcu.role.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBean(AuthFlowService.class)
public class AuthController {
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record PasswordRequest(@NotBlank String challenge, @NotBlank String newPassword) {}
    public record ChallengeRequest(@NotBlank String challenge) {}
    public record CodeRequest(@NotBlank String challenge, @NotBlank String code) {}
    public record AuthStep(String stage, String challenge, String secret,
                           List<String> recoveryCodes) {}
    public record Profile(UUID userId, String email, List<String> permissions) {}

    private final AuthFlowService flow;
    private final SessionService sessions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PermissionService permissions;
    private final String allowedOrigin;
    private final boolean secureCookies;

    public AuthController(AuthFlowService flow, SessionService sessions, JdbcTemplate jdbc,
                          AuditService audit, PermissionService permissions,
                          @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String allowedOrigin,
                          @Value("${APP_COOKIE_SECURE:true}") boolean secureCookies) {
        this.flow = flow;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
        this.permissions = permissions;
        this.allowedOrigin = allowedOrigin;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        try {
            return step(flow.begin(body.email(), body.password(), request.getRemoteAddr()));
        } catch (org.springframework.security.authentication.BadCredentialsException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(
                "INVALID_CREDENTIALS", "Email or password was not accepted.", List.of(),
                TraceIdFilter.traceId(request)));
        }
    }

    @PostMapping("/password")
    public ResponseEntity<?> password(@Valid @RequestBody PasswordRequest body, HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        try {
            return flow.changePassword(body.challenge(), body.newPassword())
                .<ResponseEntity<?>>map(this::step).orElseGet(() -> unauthorized(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ApiError("PASSWORD_REJECTED",
                exception.getMessage(), List.of(), TraceIdFilter.traceId(request)));
        }
    }

    @PostMapping("/totp/setup")
    public ResponseEntity<?> totpSetup(@Valid @RequestBody ChallengeRequest body,
                                        HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        return flow.beginTotpSetup(body.challenge())
            .<ResponseEntity<?>>map(secret -> ResponseEntity.ok(
                new AuthStep("TOTP_SETUP", null, secret, null)))
            .orElseGet(() -> unauthorized(request));
    }

    @PostMapping("/totp/confirm")
    public ResponseEntity<?> totpConfirm(@Valid @RequestBody CodeRequest body,
                                          HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        return flow.confirmTotpSetup(body.challenge(), body.code())
            .<ResponseEntity<?>>map(enrollment -> authenticated(enrollment.tokens(),
                enrollment.recoveryCodes())).orElseGet(() -> unauthorized(request));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<?> totpVerify(@Valid @RequestBody CodeRequest body,
                                         HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        return flow.verifyTotp(body.challenge(), body.code())
            .<ResponseEntity<?>>map(this::authenticated).orElseGet(() -> unauthorized(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = "cu_refresh", required = false) String refresh,
                                      @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                      HttpServletRequest request) {
        if (!sameOrigin(request) || !sessions.csrfMatchesRefresh(refresh, csrf)) {
            return forbidden(request);
        }
        return sessions.rotate(refresh).<ResponseEntity<?>>map(this::authenticated)
            .orElseGet(() -> unauthorized(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@CookieValue(name = "cu_access", required = false) String access,
                                     @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                     HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return unauthorized(request);
        if (!sessions.csrfMatches(identity.sessionId(), csrf)) return forbidden(request);
        sessions.revoke(identity.sessionId());
        return clearCookies();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(@CookieValue(name = "cu_access", required = false) String access,
                                        @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
                                        HttpServletRequest request) {
        if (!sameOrigin(request)) return forbidden(request);
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return unauthorized(request);
        if (!sessions.csrfMatches(identity.sessionId(), csrf)) return forbidden(request);
        sessions.revokeAll(identity.userId());
        audit.record(identity.userId(), AuditService.Action.LOGOUT_ALL,
            "USER", identity.userId(), TraceIdFilter.traceId(request));
        return clearCookies();
    }

    private ResponseEntity<?> clearCookies() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, expired("cu_access", "/api/v1").toString());
        headers.add(HttpHeaders.SET_COOKIE, expired("cu_refresh", "/api/v1/auth").toString());
        headers.add(HttpHeaders.SET_COOKIE, expired("cu_csrf", "/").toString());
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@CookieValue(name = "cu_access", required = false) String access,
                                 HttpServletRequest request) {
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return unauthorized(request);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
            identity.userId());
        return ResponseEntity.ok(new Profile(identity.userId(), email,
            permissions.currentPermissions(identity.userId())));
    }

    private ResponseEntity<AuthStep> step(AuthFlowService.Step result) {
        if (result.stage() == AuthFlowService.Stage.AUTHENTICATED) {
            return authenticated(result.tokens());
        }
        return ResponseEntity.ok(new AuthStep(result.stage().name(), result.challenge(), null, null));
    }

    ResponseEntity<AuthStep> authenticated(SessionService.Tokens tokens) {
        return authenticated(tokens, null);
    }

    private ResponseEntity<AuthStep> authenticated(SessionService.Tokens tokens,
                                                   List<String> recoveryCodes) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie("cu_access", tokens.access(), "/api/v1", true,
            15 * 60).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookie("cu_refresh", tokens.refresh(), "/api/v1/auth", true,
            7 * 24 * 60 * 60).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookie("cu_csrf", tokens.csrf(), "/", false,
            7 * 24 * 60 * 60).toString());
        return new ResponseEntity<>(new AuthStep("AUTHENTICATED", null, null, recoveryCodes),
            headers, HttpStatus.OK);
    }

    private ResponseCookie cookie(String name, String value, String path, boolean httpOnly,
                                  long maxAgeSeconds) {
        return ResponseCookie.from(name, value).httpOnly(httpOnly).secure(secureCookies)
            .sameSite("Strict").path(path).maxAge(maxAgeSeconds).build();
    }

    private ResponseCookie expired(String name, String path) {
        return cookie(name, "", path, !"cu_csrf".equals(name), 0);
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(allowedOrigin);
    }

    private ResponseEntity<ApiError> unauthorized(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("AUTH_REQUIRED",
            "Authentication could not be completed. Please try again.", List.of(),
            TraceIdFilter.traceId(request)));
    }

    private ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("ACCESS_DENIED",
            "Request not allowed.", List.of(), TraceIdFilter.traceId(request)));
    }
}
