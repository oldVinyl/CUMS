package org.harbourcu.investment;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Function;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.*;
import org.harbourcu.ledger.BackdatedContributionService;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/investment-corrections") @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class HistoricalIncomeCorrectionController {
  public record Decision(String decision,String reason) {}
  private final HistoricalIncomeCorrection service;private final ApprovalService approvals;private final SessionService sessions;private final String origin;
  public HistoricalIncomeCorrectionController(HistoricalIncomeCorrection service,ApprovalService approvals,SessionService sessions,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.service=service;this.approvals=approvals;this.sessions=sessions;this.origin=origin;}
  @GetMapping public ResponseEntity<?> pending(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.pending(a)));}
  @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody BackdatedContributionService.IncomeCorrection input,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(service.preview(a,input)));}
  @PostMapping public ResponseEntity<?> submit(@RequestBody BackdatedContributionService.IncomeCorrection input,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",service.submit(a,input,approvals))));}
  @GetMapping("/{id}/impact") public ResponseEntity<?> review(@PathVariable UUID id,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.review(a,id)));}
  @PostMapping("/{id}/decide") public ResponseEntity<?> decide(@PathVariable UUID id,@RequestBody Decision b,HttpServletRequest r){return call(r,true,a->{service.decide(a,id,b.decision(),b.reason(),approvals);return ResponseEntity.noContent().build();});}
  @PostMapping("/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{service.cancel(a,id,approvals);return ResponseEntity.noContent().build();});}
  private ResponseEntity<?> call(HttpServletRequest r,boolean mutation,Function<UUID,ResponseEntity<?>> work){String access=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))access=c.getValue();var identity=sessions.resolveAccess(access).orElse(null);if(identity==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");if(mutation&&(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");try{var result=work.apply(identity.userId());return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).header("Cache-Control","no-store").body(result.getBody());}catch(SecurityException e){return error(r,403,"ACCESS_DENIED","Historical investment permission required.");}catch(IllegalArgumentException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}catch(IllegalStateException e){return error(r,409,"CORRECTION_CONFLICT",e.getMessage());}}
  private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
