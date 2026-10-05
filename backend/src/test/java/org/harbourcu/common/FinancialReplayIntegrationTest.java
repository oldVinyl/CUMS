package org.harbourcu.common;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.Cookie;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.flyway.default-schema=replay_tests","spring.datasource.hikari.schema=replay_tests",
 "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Transactional
class FinancialReplayIntegrationTest {
 @Autowired FinancialReplayFilter filter; @Autowired SessionService sessions; @Autowired JdbcTemplate jdbc;
 MockHttpServletRequest request(SessionService.Tokens tokens,String body,String key) {
  var request=new MockHttpServletRequest("POST","/api/v1/investments");
  request.setCookies(new Cookie("cu_access",tokens.access()));request.addHeader("X-CSRF-Token",tokens.csrf());
  request.addHeader("Origin","http://localhost:5173");request.addHeader("Idempotency-Key",key);
  request.setContentType("application/json");request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return request;
 }
 UUID user() {
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",id,id+"@invalid.example");
  jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code='TREASURER'",id);return id;
 }
 @Test void retriesReturnOriginalOpaqueResultAndRequireUnchangedInputPermissionsAndCsrf() throws Exception {
  UUID actor=user(), result=UUID.randomUUID();var tokens=sessions.create(actor);var calls=new AtomicInteger();
  jakarta.servlet.FilterChain action=(req,res)->{calls.incrementAndGet();res.setContentType("application/json");res.getWriter().write("{\"id\":\""+result+"\"}");};
  String key=UUID.randomUUID().toString();var first=new MockHttpServletResponse();filter.doFilter(request(tokens,"{}",key),first,action);
  assertThat(first.getStatus()).isEqualTo(200);assertThat(first.getContentAsString()).contains(result.toString());
  var retry=new MockHttpServletResponse();filter.doFilter(request(tokens,"{}",key),retry,action);
  assertThat(retry.getHeader("Idempotency-Replayed")).isEqualTo("true");assertThat(retry.getContentAsString()).contains(result.toString());assertThat(calls.get()).isEqualTo(1);
  var conflict=new MockHttpServletResponse();filter.doFilter(request(tokens,"{\"changed\":true}",key),conflict,action);assertThat(conflict.getStatus()).isEqualTo(409);
  var invalid=request(tokens,"{}",key);invalid.removeHeader("X-CSRF-Token");var denied=new MockHttpServletResponse();filter.doFilter(invalid,denied,action);assertThat(denied.getStatus()).isEqualTo(403);
  jdbc.update("UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Synthetic revoked grant' WHERE user_id=?",actor,actor);
  var changed=new MockHttpServletResponse();filter.doFilter(request(tokens,"{}",key),changed,action);assertThat(changed.getStatus()).isEqualTo(403);
  jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?",actor);var disabled=new MockHttpServletResponse();filter.doFilter(request(tokens,"{}",key),disabled,action);assertThat(disabled.getStatus()).isEqualTo(401);
  assertThat(calls.get()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_request_replays WHERE actor_id=?",Long.class,actor)).isEqualTo(1);
 }
}
