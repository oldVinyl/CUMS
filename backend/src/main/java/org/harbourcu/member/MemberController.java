package org.harbourcu.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class MemberController {
    public record CreateRequest(@NotBlank String memberNumber, @NotBlank String fullName,
                                String email, String phone, String idType, String idNumber,
                                LocalDate dateOfBirth, String address, String nextOfKinName,
                                String nextOfKinPhone, String nextOfKinRelationship,
                                @NotNull LocalDate joinDate) {
        MemberService.CreateMember toService() {
            return new MemberService.CreateMember(memberNumber, fullName, email, phone,
                idType, idNumber, dateOfBirth, address, nextOfKinName,
                nextOfKinPhone, nextOfKinRelationship, joinDate);
        }
    }
    public record Created(UUID id) {}

    private final MemberService members;
    private final SessionService sessions;
    private final String allowedOrigin;

    public MemberController(MemberService members, SessionService sessions,
                            @Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String allowedOrigin) {
        this.members = members;
        this.sessions = sessions;
        this.allowedOrigin = allowedOrigin;
    }

    @GetMapping
    public ResponseEntity<?> list(@CookieValue(name = "cu_access", required = false) String access,
                                   HttpServletRequest request) {
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        try {
            return ResponseEntity.ok(members.list(identity.userId()));
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id,
                                     @CookieValue(name = "cu_access", required = false) String access,
                                     HttpServletRequest request) {
        SessionService.SessionIdentity identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        try {
            return ResponseEntity.ok(members.get(identity.userId(), id, TraceIdFilter.traceId(request)));
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", request);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest body,
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
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Created(members.create(identity.userId(), body.toService())));
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
        } catch (DuplicateKeyException exception) {
            return error(HttpStatus.CONFLICT, "MEMBER_EXISTS", request);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR",
                exception.getMessage(), List.of(), TraceIdFilter.traceId(request)));
        }
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(allowedOrigin);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code,
            switch (code) {
                case "AUTH_REQUIRED" -> "Please sign in to continue.";
                case "MEMBER_EXISTS" -> "A member with this number or identity already exists.";
                case "NOT_FOUND" -> "Member not found.";
                default -> "You do not have permission to access this resource.";
            }, List.of(), TraceIdFilter.traceId(request)));
    }
}
