package org.harbourcu.reconciliation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.DateTimeException;
import java.util.function.Function;
import org.harbourcu.common.*;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/reconciliations") @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class ReconciliationController {
 public record Decision(String decision,String comment){}
 private final SettlementService settlements;private final ReconciliationService service;private final SessionService sessions;private final String origin;
 public ReconciliationController(SettlementService settlements,ReconciliationService service,SessionService sessions,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin){this.settlements=settlements;this.service=service;this.sessions=sessions;this.origin=origin;}
 @GetMapping("/settlements") public ResponseEntity<?> settlements(@RequestParam(defaultValue="0")int page,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(settlements.list(a,page)));}
 @PostMapping("/settlements/payments/{id}") public ResponseEntity<?> match(@PathVariable UUID id,@RequestBody SettlementService.Input b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",settlements.submit(a,id,b))));}
 @PostMapping("/settlements/{id}/decide") public ResponseEntity<?> matchDecision(@PathVariable UUID id,@RequestBody Decision b,HttpServletRequest r){return call(r,true,a->{settlements.decide(a,id,b.decision(),b.comment());return ResponseEntity.noContent().build();});}
 @PostMapping("/settlements/{id}/cancel") public ResponseEntity<?> matchCancel(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{settlements.cancel(a,id);return ResponseEntity.noContent().build();});}
 @GetMapping("/preview") public ResponseEntity<?> preview(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.preview(a)));}
 @GetMapping public ResponseEntity<?> list(@RequestParam(defaultValue="0")int page,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.list(a,page)));}
 @PostMapping public ResponseEntity<?> submit(@RequestBody ReconciliationService.Input b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("id",service.submit(a,b))));}
 @PostMapping("/{id}/decide") public ResponseEntity<?> decide(@PathVariable UUID id,@RequestBody Decision b,HttpServletRequest r){return call(r,true,a->{service.decide(a,id,b.decision(),b.comment());return ResponseEntity.noContent().build();});}
 @PostMapping("/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable UUID id,HttpServletRequest r){return call(r,true,a->{service.cancel(a,id);return ResponseEntity.noContent().build();});}
 private ResponseEntity<?> call(HttpServletRequest r,boolean mutation,Function<UUID,ResponseEntity<?>> action){
  String access=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))access=c.getValue();var identity=sessions.resolveAccess(access).orElse(null);
  if(identity==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");
  if(mutation&&(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");
  try {var result=action.apply(identity.userId());return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).header("Cache-Control","no-store").body(result.getBody());}
  catch(SecurityException e){return error(r,403,"ACCESS_DENIED","You do not have permission for reconciliation.");}
  catch(IllegalArgumentException|DateTimeException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}
  catch(IllegalStateException e){return error(r,409,"RECONCILIATION_CONFLICT",e.getMessage());}
 }
 private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
