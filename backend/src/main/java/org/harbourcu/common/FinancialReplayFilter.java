package org.harbourcu.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.harbourcu.role.PermissionService;
import org.harbourcu.security.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Stores only UUID acknowledgements; financial effects and replay evidence commit together. */
@Component @Order(10) @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class FinancialReplayFilter extends OncePerRequestFilter {
 private final JdbcTemplate jdbc;private final SessionService sessions;private final PermissionService permissions;private final ObjectMapper json;private final TransactionTemplate tx;private final String origin;
 private static final String ID="[0-9a-fA-F-]{36}";
 public FinancialReplayFilter(JdbcTemplate jdbc,SessionService sessions,PermissionService permissions,ObjectMapper json,PlatformTransactionManager manager,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.jdbc=jdbc;this.sessions=sessions;this.permissions=permissions;this.json=json;this.tx=new TransactionTemplate(manager);this.origin=origin;}
 protected boolean shouldNotFilter(HttpServletRequest r){return !"POST".equals(r.getMethod())||r.getHeader("Idempotency-Key")==null||!covered(r.getRequestURI());}
 private boolean covered(String p){return p.matches("/api/v1/ledger/(contributions(?:/historical)?|historical-reversals|"+ID+"/reverse|fiscal-years/[0-9]{4}/(close|unlock|create)|approvals/"+ID+"/(decide|cancel))")||p.matches("/api/v1/investments(?:/"+ID+"/(submit|mature|rollover-draft|settle|reverse|discard|allocations/"+ID+")|/approvals/"+ID+"/(decide|cancel))?")||p.matches("/api/v1/investment-corrections(?:/"+ID+"/(decide|cancel))?")||p.matches("/api/v1/retention(?:/requests/"+ID+"/(decide|cancel))?")||p.matches("/api/v1/imports/"+ID+"/(decide|cancel|reverse)")||p.matches("/api/v1/payments/"+ID+"/(verify|historical-verify|decide|cancel|review)")||p.matches("/api/v1/reconciliations/settlements/payments/"+ID)||p.matches("/api/v1/reconciliations(?:/"+ID+"/(decide|cancel)|/settlements(?:/"+ID+"/(decide|cancel))?)?");}
 protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String key=r.getHeader("Idempotency-Key");if(!key.matches("[A-Za-z0-9_-]{8,128}")){error(r,response,400,"IDEMPOTENCY_KEY_INVALID","Use a unique request key containing 8–128 letters, digits, underscores or hyphens.");return;}
  String token=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cu_access"))token=c.getValue();var identity=sessions.resolveAccess(token).orElse(null);
  if(identity==null){error(r,response,401,"AUTH_REQUIRED","Please sign in to continue.");return;}if(!sessions.csrfMatches(identity.sessionId(),r.getHeader("X-CSRF-Token"))||(r.getHeader("Origin")!=null&&!origin.equals(r.getHeader("Origin")))){error(r,response,403,"ACCESS_DENIED","Refresh the page and try again.");return;}
  byte[] body=r.getInputStream().readNBytes(65537);if(body.length>65536){error(r,response,413,"REQUEST_TOO_LARGE","This financial request is too large.");return;}
  var wrapped=new HttpServletRequestWrapper(r){public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Asynchronous financial request bodies are not supported.");}};}public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}public int getContentLength(){return body.length;}public long getContentLengthLong(){return body.length;}};
  String scope=digest(r.getRequestURI()+"\n"+key),source=digest(r.getRequestURI()+"\n"+Objects.toString(r.getQueryString(),"")+"\n"+Base64.getEncoder().encodeToString(body));
  var buffered=new ContentCachingResponseWrapper(response);
  try{tx.executeWithoutResult(status->{
   jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");String grants=digest(String.join("\n",permissions.currentPermissions(identity.userId())));
   var prior=jdbc.queryForList("SELECT request_digest,permission_digest,status,result::text FROM financial_request_replays WHERE actor_id=? AND key_digest=?",identity.userId(),scope);
   try{if(!prior.isEmpty()){var saved=prior.getFirst();if(!source.equals(saved.get("request_digest"))){error(r,buffered,409,"IDEMPOTENCY_CONFLICT","This request key already belongs to different input. Review the form before starting a new request.");return;}if(!grants.equals(saved.get("permission_digest"))){error(r,buffered,403,"ACCESS_DENIED","Your permissions changed. Refresh the page before reviewing this action.");return;}buffered.setStatus((Integer)saved.get("status"));buffered.setHeader("Cache-Control","no-store");buffered.setHeader("Idempotency-Replayed","true");if(buffered.getStatus()!=204){buffered.setContentType("application/json");buffered.getWriter().write((String)saved.get("result"));}return;}
    chain.doFilter(wrapped,buffered);if(buffered.getStatus()<200||buffered.getStatus()>=300){status.setRollbackOnly();return;}
    var value=buffered.getContentAsByteArray().length==0?json.createObjectNode():json.readTree(buffered.getContentAsByteArray());if(!value.isObject()||value.size()>3)throw new IllegalStateException("Financial acknowledgement must contain only opaque request identifiers.");for(var fields=value.fields();fields.hasNext();){var field=fields.next();if(!Set.of("id","requestId","approvalRequestId").contains(field.getKey())||!field.getValue().isTextual()||!field.getValue().asText().matches(ID))throw new IllegalStateException("Financial acknowledgement must contain only opaque request identifiers.");}
    jdbc.update("INSERT INTO financial_request_replays(actor_id,key_digest,request_digest,permission_digest,status,result) VALUES(?,?,?,?,?,?::jsonb)",identity.userId(),scope,source,grants,buffered.getStatus(),value.toString());buffered.setHeader("Cache-Control","no-store");
   }catch(IOException e){throw new UncheckedIOException(e);}catch(ServletException e){throw new IllegalStateException("Financial request processing failed.",e);}
  });buffered.copyBodyToResponse();}catch(UncheckedIOException e){throw e.getCause();}catch(RuntimeException e){buffered.resetBuffer();error(r,buffered,500,"FINANCIAL_REQUEST_FAILED","The action could not be completed. Retry with the same request key.");buffered.copyBodyToResponse();}
 }
 private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Request fingerprint unavailable.");}}
 private void error(HttpServletRequest r,HttpServletResponse response,int code,String name,String message)throws IOException{response.setStatus(code);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");json.writeValue(response.getOutputStream(),new ApiError(name,message,List.of(),TraceIdFilter.traceId(r)));}
}
