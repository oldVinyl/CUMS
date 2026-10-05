package org.harbourcu.member;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.flyway.default-schema=retention_tests", "spring.datasource.hikari.schema=retention_tests",
 "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Transactional
class MemberRetentionIntegrationTest {
 @Autowired MemberRetentionService retention;
 @Autowired ApprovalService approvals;
 @Autowired JdbcTemplate jdbc;
 UUID actor(boolean checker) {
  UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",id,id+"@invalid.example");
  jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code='ADMIN'",id);
  if(checker) jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code='TREASURER'",id);
  return id;
 }
 UUID departed() {
  UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO members(id,member_number,full_name,email,join_date,status,left_on) VALUES(?,?,'Synthetic Retention Sentinel','retention-sentinel@invalid.example',current_date-interval '10 years','INACTIVE',current_date-interval '8 years')",id,"RET-"+id);
  jdbc.update("INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference,narration) VALUES(?,'OPENING_BALANCE','PRINCIPAL',100,current_date,extract(year FROM current_date),?,'Synthetic Retention Sentinel opening')",id,"RET-ENTRY-"+id);
  return id;
 }
 @Test void reviewedAnonymizationRedactsIdentityAndKeepsExactFinancialFacts() {
  UUID maker=actor(false), checker=actor(true), member=departed();
  var preview=retention.preview(maker,member);
  UUID request=retention.submit(maker,new MemberRetentionService.Input(member,preview.sourceFingerprint(),"Synthetic retention review"),approvals);
  assertThatThrownBy(()->retention.decide(maker,request,"APPROVE",null,approvals)).hasMessageContaining("own request");
  retention.decide(checker,request,"APPROVE",null,approvals);
  var row=jdbc.queryForMap("SELECT full_name,email,anonymized_at FROM members WHERE id=?",member);
  assertThat(row.get("full_name")).isEqualTo("Former member");
  assertThat(row.get("email")).isNull();assertThat(row.get("anonymized_at")).isNotNull();
  assertThat(jdbc.queryForObject("SELECT amount FROM ledger_entries WHERE member_id=?",BigDecimal.class,member)).isEqualByComparingTo("100");
  assertThat(jdbc.queryForObject("SELECT narration FROM ledger_entries WHERE member_id=?",String.class,member)).doesNotContain("Sentinel");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM retention_redactions WHERE request_id=?",Long.class,request)).isPositive();
 }
 @Test void activeAccountAndStalePolicyPreventAnonymization() {
  UUID maker=actor(false), member=departed();
  var preview=retention.preview(maker,member);
  retention.policy(maker,9);
  assertThatThrownBy(()->retention.submit(maker,new MemberRetentionService.Input(member,preview.sourceFingerprint(),"Synthetic stale policy"),approvals)).isInstanceOf(IllegalStateException.class);
  retention.policy(maker,7);
  jdbc.update("INSERT INTO users(email,password_hash,member_id) VALUES('synthetic-retention-active@invalid.example','synthetic',?)",member);
  assertThatThrownBy(()->retention.preview(maker,member)).hasMessageContaining("active accounts");
 }
}
