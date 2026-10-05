package org.harbourcu.member;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.*;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/retention") @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class MemberRetentionController {
  public record Policy(int retentionYears) {} public record Departure(UUID memberId,long version,LocalDate leftOn) {}
  public record Decision(String decision,String reason) {}
  private final MemberRetentionService service;private final ApprovalService approvals;private final SessionService sessions;private final JdbcTemplate jdbc;private final String origin;
  public MemberRetentionController(MemberRetentionService service,ApprovalService approvals,SessionService sessions,JdbcTemplate jdbc,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.service=service;this.approvals=approvals;this.sessions=sessions;this.jdbc=jdbc;this.origin=origin;}
  @GetMapping public ResponseEntity<?> overview(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.overview(a)));}
  @PostMapping("/policy") public ResponseEntity<?> policy(@RequestBody Policy b,HttpServletRequest r){return call(r,true,a->{service.policy(a,b.retentionYears());return ResponseEntity.noContent().build();});}
  @PostMapping("/departure") public ResponseEntity<?> departure(@RequestBody Departure b,HttpServletRequest r){return call(r,true,a->{service.departure(a,b.memberId(),b.version(),b.leftOn());return ResponseEntity.noContent().build();});}
  @GetMapping("/members/{id}/preview") public ResponseEntity<?> preview(@PathVariable UUID id,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.preview(a,id)));}
  @PostMapping public ResponseEntity<?> submit(@RequestBody MemberRetentionService.Input b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",service.submit(a,b,approvals))));}
  @PostMapping("/requests/{id}/decide") public ResponseEntity<?> decide(@PathVariable UUID id,@RequestBody Decision b,HttpServletRequest r){return call(r,true,a->{service.decide(a,id,b.decision(),b.reason(),approvals);return ResponseEntity.noContent().build();});}
  @PostMapping("/requests/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{service.cancel(a,id,approvals);return ResponseEntity.noContent().build();});}
  private void linked(UUID id){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE id=? AND action_type='MEMBER_ANONYMIZE')",Boolean.class,id)))throw new IllegalArgumentException("Retention request not found.");}
  private ResponseEntity<?> call(HttpServletRequest r,boolean mutation,Function<UUID,ResponseEntity<?>> work){
    String access=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))access=c.getValue();var identity=sessions.resolveAccess(access).orElse(null);
    if(identity==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");
    if(mutation&&(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");
    try{var response=work.apply(identity.userId());return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control","no-store").body(response.getBody());}
    catch(SecurityException e){return error(r,403,"ACCESS_DENIED","Retention administration permission required.");}
    catch(IllegalArgumentException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}
    catch(IllegalStateException e){return error(r,409,"RETENTION_CONFLICT",e.getMessage());}
  }
  private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
