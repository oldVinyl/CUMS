package org.harbourcu.report;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.harbourcu.security.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
@ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
public class ReportController {
    private final FinancialReportService reports;
    private final SessionService sessions;
    public ReportController(FinancialReportService reports, SessionService sessions) {
        this.reports = reports; this.sessions = sessions;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(@RequestParam String asAt, @RequestParam String format,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please sign in to continue.", request);
        return respond(() -> reports.dashboard(identity.userId(), LocalDate.parse(asAt), format), request);
    }

    @GetMapping("/investments/{id}")
    public ResponseEntity<?> investment(@PathVariable UUID id, @RequestParam String format,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please sign in to continue.", request);
        return respond(() -> reports.investment(identity.userId(), id, format), request);
    }

    @GetMapping("/members/{id}/statement")
    public ResponseEntity<?> statement(@PathVariable UUID id, @RequestParam String from, @RequestParam String to,
            @RequestParam(defaultValue = "pdf") String format,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please sign in to continue.", request);
        return respond(() -> reports.statement(identity.userId(), id, LocalDate.parse(from), LocalDate.parse(to), format), request);
    }

    @GetMapping("/ledger")
    public ResponseEntity<?> ledger(@RequestParam String from, @RequestParam String to, @RequestParam String format,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please sign in to continue.", request);
        return respond(() -> reports.ledger(identity.userId(), LocalDate.parse(from), LocalDate.parse(to), format), request);
    }
    @GetMapping("/members")
    public ResponseEntity<?> members(@RequestParam String asAt, @RequestParam String format,
            @CookieValue(name = "cu_access", required = false) String access, HttpServletRequest request) {
        var identity = sessions.resolveAccess(access).orElse(null);
        if (identity == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please sign in to continue.", request);
        return respond(() -> reports.members(identity.userId(), LocalDate.parse(asAt), format), request);
    }

    private ResponseEntity<?> respond(Supplier<FinancialReportService.Download> supplier, HttpServletRequest request) {
        try {
            var file = supplier.get();
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType())).body(file.bytes());
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to export this report.", request);
        } catch (IllegalArgumentException | DateTimeException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Choose a valid report, date and format (xlsx or pdf).", request);
        }
    }
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(new ApiError(code, message, List.of(), TraceIdFilter.traceId(request)));
    }
}
