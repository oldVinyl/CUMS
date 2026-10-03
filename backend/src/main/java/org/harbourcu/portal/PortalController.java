package org.harbourcu.portal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.*;
import java.util.function.Function;
import org.harbourcu.common.*;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/portal") @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class PortalController {
 private final PortalService portal;private final SessionService sessions;private final String origin;
 public PortalController(PortalService portal,SessionService sessions,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin){this.portal=portal;this.sessions=sessions;this.origin=origin;}
 @GetMapping public ResponseEntity<?> overview(@RequestParam String asAt,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(portal.overview(a,LocalDate.parse(asAt))));}
 @GetMapping("/ledger") public ResponseEntity<?> ledger(@RequestParam String asAt,@RequestParam(defaultValue="0")int page,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(portal.history(a,LocalDate.parse(asAt),page)));}
 @GetMapping("/profile") public ResponseEntity<?> profile(HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(portal.profile(a)));}
 @PostMapping("/profile") public ResponseEntity<?> update(@RequestBody PortalService.Profile b,HttpServletRequest r){return call(r,true,a->{portal.update(a,b);return ResponseEntity.noContent().build();});}
 private ResponseEntity<?> call(HttpServletRequest r,boolean mutation,Function<UUID,ResponseEntity<?>> action){
  String access=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))access=c.getValue();var identity=sessions.resolveAccess(access).orElse(null);
  if(identity==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");
  if(mutation&&(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");
  try {var result=action.apply(identity.userId());return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).header("Cache-Control","no-store").body(result.getBody());}
  catch(SecurityException e){return error(r,403,"ACCESS_DENIED","You do not have permission for your membership.");}
  catch(IllegalArgumentException|DateTimeException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}
  catch(IllegalStateException e){return error(r,409,"PORTAL_CONFLICT",e.getMessage());}
 }
 private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
