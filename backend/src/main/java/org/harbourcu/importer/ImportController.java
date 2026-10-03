package org.harbourcu.importer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.*;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/imports") @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class ImportController {
    public record Decision(UUID requestId,String decision,String comment) {}
    public record Cancel(UUID requestId) {}
    public record Reverse(String reason) {}
    private final ImportService service;private final ApprovalService approvals;private final SessionService sessions;private final String origin;
    public ImportController(ImportService service,ApprovalService approvals,SessionService sessions,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.service=service;this.approvals=approvals;this.sessions=sessions;this.origin=origin;}
    @GetMapping public ResponseEntity<?> list(@RequestParam(defaultValue="0")int page,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.list(a,page)));}
    @GetMapping("/template.csv") public ResponseEntity<?> template(@RequestParam String kind,HttpServletRequest r){return call(r,false,a->{service.list(a,0);return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"import-"+kind.toLowerCase(Locale.ROOT)+".csv\"").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body((String.join(",",service.columns(kind))+"\r\n").getBytes(StandardCharsets.UTF_8));});}
    @PostMapping("/dry-run") public ResponseEntity<?> dry(@RequestParam String kind,@RequestParam MultipartFile file,HttpServletRequest r)throws IOException{byte[] data=bounded(file);return call(r,true,a->ResponseEntity.ok(service.dryRun(a,kind,file.getOriginalFilename(),data)));}
    @PostMapping public ResponseEntity<?> submit(@RequestParam String kind,@RequestParam MultipartFile file,@RequestParam String expectedHash,@RequestParam String reason,HttpServletRequest r)throws IOException{byte[] data=bounded(file);return call(r,true,a->ResponseEntity.ok(Map.of("id",service.submit(a,kind,file.getOriginalFilename(),data,expectedHash,reason,approvals))));}
    @GetMapping("/{id}/review") public ResponseEntity<?> review(@PathVariable UUID id,HttpServletRequest r){return call(r,false,a->ResponseEntity.ok(service.review(a,id)));}
    @PostMapping("/{id}/decide") public ResponseEntity<?> decide(@PathVariable UUID id,@RequestBody Decision b,HttpServletRequest r){return call(r,true,a->{service.decide(a,id,b.requestId(),b.decision(),b.comment(),approvals);return ResponseEntity.noContent().build();});}
    @PostMapping("/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable UUID id,@RequestBody Cancel b,HttpServletRequest r){return call(r,true,a->{service.cancel(a,id,b.requestId(),approvals);return ResponseEntity.noContent().build();});}
    @PostMapping("/{id}/reverse") public ResponseEntity<?> reverse(@PathVariable UUID id,@RequestBody Reverse b,HttpServletRequest r){return call(r,true,a->ResponseEntity.ok(Map.of("requestId",service.reverse(a,id,b.reason(),approvals))));}
    private byte[] bounded(MultipartFile file)throws IOException{if(file.getSize()==0||file.getSize()>5*1024*1024)throw new IllegalArgumentException("Choose a CSV or XLSX file up to 5 MB.");return file.getBytes();}
    private ResponseEntity<?> call(HttpServletRequest r,boolean mutation,Function<UUID,ResponseEntity<?>> action){
        String token=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))token=c.getValue();var identity=sessions.resolveAccess(token).orElse(null);
        if(identity==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");
        if(mutation&&(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");
        try{var response=action.apply(identity.userId());return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control","no-store").body(response.getBody());}
        catch(SecurityException e){return error(r,403,"ACCESS_DENIED","Your account cannot perform this import action.");}
        catch(IllegalArgumentException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}
        catch(IllegalStateException|DataIntegrityViolationException e){return error(r,409,"IMPORT_CONFLICT","The import changed or conflicts with existing records. Refresh and review the source before retrying.");}
    }
    private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
