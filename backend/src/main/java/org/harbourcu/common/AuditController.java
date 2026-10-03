package org.harbourcu.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.harbourcu.security.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
@ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class AuditController {
    private final AuditQueryService service;
    private final SessionService sessions;
    public AuditController(AuditQueryService service, SessionService sessions) { this.service=service; this.sessions=sessions; }
    @GetMapping({"", "/export.csv"})
    public ResponseEntity<?> get(@RequestParam String from, @RequestParam String to,
        @RequestParam(required=false) String action, @RequestParam(required=false) String entityType,
        @RequestParam(required=false) UUID actorId, @RequestParam(required=false) UUID entityId,
        @RequestParam(defaultValue="0") int page, @CookieValue(name="cu_access",required=false) String access,
        HttpServletRequest request) {
        var identity=sessions.resolveAccess(access).orElse(null);
        if(identity==null)return error(request,401,"AUTH_REQUIRED","Please sign in to continue.");
        try {
            var filter=new AuditQueryService.Filter(LocalDate.parse(from),LocalDate.parse(to),action,entityType,actorId,entityId);
            if(request.getRequestURI().endsWith("/export.csv")) return ResponseEntity.ok()
                .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=\"audit-"+from+"-"+to+".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.export(identity.userId(),filter,TraceIdFilter.traceId(request)));
            return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(identity.userId(),filter,page));
        } catch(SecurityException e) {return error(request,403,"ACCESS_DENIED","Your account cannot view audit records.");}
        catch(IllegalArgumentException|DateTimeException e){return error(request,400,"VALIDATION_ERROR",e.getMessage());}
    }
    private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));
    }
}
