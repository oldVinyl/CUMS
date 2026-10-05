package org.harbourcu.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.harbourcu.common.*;
import org.harbourcu.payment.ReceiptScanner;
import org.harbourcu.role.PermissionService;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organization")
@ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class OrganizationController {
 public record Settings(int passwordMinLength,int sessionIdleMinutes,String primaryColour,String currencyDisplay,String logoDataUrl) {}
 public record Change(int passwordMinLength,int sessionIdleMinutes,String primaryColour,String currencyDisplay,String logoDataUrl,boolean removeLogo) {}
 private final JdbcTemplate jdbc;private final SessionService sessions;private final PermissionService permissions;private final ReceiptScanner scanner;private final String origin;private final org.springframework.core.env.Environment environment;
 public OrganizationController(JdbcTemplate jdbc,SessionService sessions,PermissionService permissions,ReceiptScanner scanner,org.springframework.core.env.Environment environment,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.jdbc=jdbc;this.sessions=sessions;this.permissions=permissions;this.scanner=scanner;this.environment=environment;this.origin=origin;}
 @GetMapping("/branding") public ResponseEntity<?> branding(){var row=jdbc.queryForMap("SELECT organisation_name,password_min_length,primary_colour,currency_display,logo_png FROM organisation_settings WHERE singleton=true");return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("organisationName",row.get("organisation_name"),"passwordMinLength",row.get("password_min_length"),"primaryColour",row.get("primary_colour"),"currencyDisplay",row.get("currency_display"),"logoDataUrl",logo((byte[])row.get("logo_png"))));}
 @GetMapping("/operations") public ResponseEntity<?> operations(HttpServletRequest r){return call(r,false,()->Map.of("receiptStorage",environment.getProperty("APP_RECEIPT_STORAGE","local"),"scannerConfigured",!environment.getProperty("CLAMAV_HOST","").isBlank(),"emailEnabled",Boolean.parseBoolean(environment.getProperty("APP_MAIL_ENABLED","false")),"backupRetention","30 daily / 12 monthly","backupVerification","Managed by the operator; freshness and restore success are not established by application configuration."));}
 @GetMapping public ResponseEntity<?> get(HttpServletRequest r){return call(r,false,()->settings());}
 @PostMapping @Transactional public ResponseEntity<?> update(@RequestBody Change b,HttpServletRequest r){return call(r,true,()->{if(b.passwordMinLength()<12||b.passwordMinLength()>64||b.sessionIdleMinutes()<5||b.sessionIdleMinutes()>120||b.primaryColour()==null||!b.primaryColour().matches("#[0-9a-fA-F]{6}")||!List.of("SYMBOL","CODE").contains(Objects.toString(b.currencyDisplay(),"")))throw new IllegalArgumentException("Use 12–64 password characters, 5–120 idle minutes, a hex colour and a supported currency display.");
 int rgb=Integer.parseInt(b.primaryColour().substring(1),16);double luminance=0;for(int n=0;n<3;n++){double c=((rgb>>(16-8*n))&255)/255.0;luminance+=new double[]{.2126,.7152,.0722}[n]*(c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4));}if(1.05/(luminance+.05)<4.5)throw new IllegalArgumentException("Choose a primary colour with at least 4.5:1 contrast against white button text.");
 byte[] image=b.removeLogo()?null:b.logoDataUrl()==null?jdbc.queryForObject("SELECT logo_png FROM organisation_settings WHERE singleton=true",byte[].class):normalize(b.logoDataUrl());
 jdbc.update("UPDATE organisation_settings SET password_min_length=?,session_idle_minutes=?,primary_colour=?,currency_display=?,logo_png=?,updated_at=now(),updated_by=? WHERE singleton=true",b.passwordMinLength(),b.sessionIdleMinutes(),b.primaryColour().toLowerCase(Locale.ROOT),b.currencyDisplay(),image,actor(r));
 jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,details) VALUES(?,'ORGANIZATION_SECURITY_UPDATED','SETTINGS',jsonb_build_object('passwordMinLength',?::int,'sessionIdleMinutes',?::int,'primaryColour',?::text,'currencyDisplay',?::text,'logoPresent',?::boolean))",actor(r),b.passwordMinLength(),b.sessionIdleMinutes(),b.primaryColour(),b.currencyDisplay(),image!=null);return settings();});}
 private Settings settings(){return jdbc.queryForObject("SELECT * FROM organisation_settings WHERE singleton=true",(r,n)->new Settings(r.getInt("password_min_length"),r.getInt("session_idle_minutes"),r.getString("primary_colour"),r.getString("currency_display"),logo(r.getBytes("logo_png"))));}
 private String logo(byte[] bytes){return bytes==null?"":"data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes);}
 private byte[] normalize(String data){try{String prefix="data:image/png;base64,";if(data.length()>700000||!data.startsWith(prefix))throw new IllegalArgumentException();byte[] bytes=Base64.getDecoder().decode(data.substring(prefix.length()));if(bytes.length<8||bytes.length>524288||!Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))throw new IllegalArgumentException();scanner.requireClean(bytes);
 try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException();var reader=readers.next();try{reader.setInput(input);if(reader.getWidth(0)>2048||reader.getHeight(0)>2048)throw new IllegalArgumentException();var out=new ByteArrayOutputStream();ImageIO.write(reader.read(0),"png",out);byte[] clean=out.toByteArray();if(clean.length>524288)throw new IllegalArgumentException();scanner.requireClean(clean);return clean;}finally{reader.dispose();}}}catch(IOException|IllegalArgumentException e){throw new IllegalArgumentException("Use a PNG logo up to 512 KB and 2048 × 2048 pixels.");}}
 private UUID actor(HttpServletRequest r){String token=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))token=c.getValue();return sessions.resolveAccess(token).map(SessionService.SessionIdentity::userId).orElse(null);}
 private ResponseEntity<?> call(HttpServletRequest r,boolean change,java.util.function.Supplier<?> work){UUID actor=actor(r);if(actor==null)return error(r,401,"AUTH_REQUIRED","Please sign in to continue.");if(!permissions.hasPermission(actor,"SETTINGS_WRITE"))return error(r,403,"ACCESS_DENIED","Organization administration permission required.");if(change){String token=Arrays.stream(r.getCookies()).filter(c->c.getName().equals("cu_access")).findFirst().map(c->c.getValue()).orElse(null);var identity=sessions.resolveAccess(token).orElseThrow();if(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin"))))return error(r,403,"ACCESS_DENIED","Refresh the page and try again.");}try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}catch(IllegalArgumentException e){return error(r,400,"VALIDATION_ERROR",e.getMessage());}catch(IllegalStateException e){return error(r,409,"SETTINGS_UNAVAILABLE","Settings could not be updated. Please try again.");}}
 private ResponseEntity<?> error(HttpServletRequest r,int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ApiError(code,message,List.of(),TraceIdFilter.traceId(r)));}
}
