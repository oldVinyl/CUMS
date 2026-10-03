package org.harbourcu.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Function;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth/security") @ConditionalOnProperty(name={"AUTH_THROTTLE_KEY","APP_ENCRYPTION_KEY"})
public class AccountSecurityController {
    public record Change(AccountSecurityService.Proof proof,String newPassword) {}
    private final AccountSecurityService security;
    private final SessionService sessions;
    private final String origin;
    public AccountSecurityController(AccountSecurityService security,SessionService sessions,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}") String origin){this.security=security;this.sessions=sessions;this.origin=origin;}
    @GetMapping public ResponseEntity<?> summary(@CookieValue(name="cu_access",required=false) String access,HttpServletRequest request){
        return call(access,null,false,request,actor->ResponseEntity.ok(security.summary(actor)));
    }
    @PostMapping("/passkeys/{id}/remove") public ResponseEntity<?> remove(@PathVariable UUID id,@RequestBody Change body,@CookieValue(name="cu_access",required=false) String access,@RequestHeader(name="X-CSRF-Token",required=false) String csrf,HttpServletRequest request){
        return call(access,csrf,true,request,actor->{security.remove(actor,id,body.proof(),request.getRemoteAddr());return ResponseEntity.noContent().build();});
    }
    @PostMapping("/recovery-codes") public ResponseEntity<?> codes(@RequestBody Change body,@CookieValue(name="cu_access",required=false) String access,@RequestHeader(name="X-CSRF-Token",required=false) String csrf,HttpServletRequest request){
        return call(access,csrf,true,request,actor->ResponseEntity.ok(Map.of("recoveryCodes",security.regenerate(actor,body.proof(),request.getRemoteAddr()))));
    }
    @PostMapping("/password") public ResponseEntity<?> password(@RequestBody Change body,@CookieValue(name="cu_access",required=false) String access,@RequestHeader(name="X-CSRF-Token",required=false) String csrf,HttpServletRequest request){
        return call(access,csrf,true,request,actor->{security.changePassword(actor,body.newPassword(),body.proof(),request.getRemoteAddr());return ResponseEntity.noContent().build();});
    }
    private ResponseEntity<?> call(String access,String csrf,boolean mutation,HttpServletRequest request,Function<UUID,ResponseEntity<?>> action){
        var identity=sessions.resolveAccess(access).orElse(null);
        if(identity==null)return error(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Please sign in to manage your account.",request);
        String supplied=request.getHeader("Origin");
        if(mutation&&((supplied!=null&&!supplied.equals(origin))||!sessions.csrfMatches(identity.sessionId(),csrf)))return error(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Request not allowed.",request);
        try{return action.apply(identity.userId());}
        catch(BadCredentialsException e){return error(HttpStatus.UNAUTHORIZED,"VERIFICATION_FAILED",e.getMessage(),request);}
        catch(IllegalArgumentException e){return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",e.getMessage(),request);}
        catch(IllegalStateException e){return error(HttpStatus.CONFLICT,"SECURITY_CONFLICT",e.getMessage(),request);}
    }
    private ResponseEntity<ApiError> error(HttpStatus status,String code,String message,HttpServletRequest request){return ResponseEntity.status(status).body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(request)));}
}
