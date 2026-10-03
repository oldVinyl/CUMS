package org.harbourcu.user;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.harbourcu.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @ConditionalOnProperty(name="APP_ENCRYPTION_KEY") public class InvitationController {
 public record Accept(String token,String password){}
 private final UserAdminService service;private final String origin;
 public InvitationController(UserAdminService service,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.service=service;this.origin=origin;}
 @PostMapping("/api/v1/auth/invitations/accept") public ResponseEntity<?> accept(@RequestBody Accept b,HttpServletRequest r){
  if(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))return ResponseEntity.status(403).body(new ApiError("ACCESS_DENIED","Open the invitation on the union website.",List.of(),TraceIdFilter.traceId(r)));
  try{service.accept(b.token(),b.password());return ResponseEntity.noContent().header("Cache-Control","no-store").build();}catch(IllegalArgumentException e){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(new ApiError("INVITATION_ERROR",e.getMessage(),List.of(),TraceIdFilter.traceId(r)));}
 }
}
