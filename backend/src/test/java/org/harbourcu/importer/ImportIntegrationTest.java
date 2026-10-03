package org.harbourcu.importer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;


@SpringBootTest(properties={"spring.flyway.default-schema=import_tests","spring.datasource.hikari.schema=import_tests","APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}) @AutoConfigureMockMvc
class ImportIntegrationTest {
 @Autowired ImportService imports;@Autowired ApprovalService approvals;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;@Autowired MockMvc mvc;
 LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
 @Test void dryRunWritesNothingAndRejectsMismatchedTotalsUnknownMembersAndDates(){
  UUID maker=user("TREASURER");String number=member();byte[] file=ledger(number,"source-one","12.25","12.25");
  int entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class),batches=jdbc.queryForObject("SELECT count(*) FROM import_batches",Integer.class),audit=jdbc.queryForObject("SELECT count(*) FROM audit_log",Integer.class);
  var report=imports.dryRun(maker,"LEDGER","fixture.csv",file);assertThat(report.valid()).isTrue();assertThat(report.total()).isEqualTo("12.25");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class)).isEqualTo(entries);assertThat(jdbc.queryForObject("SELECT count(*) FROM import_batches",Integer.class)).isEqualTo(batches);assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log",Integer.class)).isEqualTo(audit);
  assertThat(imports.dryRun(maker,"LEDGER","fixture.csv",ledger(number,"source-two","12.25","13.25")).valid()).isFalse();
  assertThat(imports.dryRun(maker,"LEDGER","fixture.csv",ledger("DOES-NOT-EXIST","source-three","1.00","1.00")).rows().getFirst().errors()).isNotEmpty();
  assertThatThrownBy(()->imports.submit(maker,"LEDGER","fixture.csv",file,"changed-hash","Test import",approvals)).hasMessageContaining("changed");
 }
 @Test void postingAndReversalAreIndependentAtomicAndExactlyCompensate(){
  UUID maker=user("TREASURER"),checker=user("PRESIDENT");String number=member();byte[] file=ledger(number,"source-"+UUID.randomUUID(),"12.25","12.25");
  UUID id=submit(maker,"LEDGER",file),request=request(id);assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE batch_id=?",Integer.class,id)).isZero();
  assertThatThrownBy(()->imports.decide(maker,id,request,"APPROVE",null,approvals)).hasMessageContaining("own request");
  imports.decide(checker,id,request,"APPROVE",null,approvals);
  assertThat(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entries WHERE batch_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("12.25");
  assertThatThrownBy(()->imports.decide(checker,id,request,"APPROVE",null,approvals)).hasMessageContaining("no longer pending");
  assertThatThrownBy(()->imports.submit(maker,"LEDGER","fixture.csv",file,imports.dryRun(maker,"LEDGER","fixture.csv",file).fileHash(),"Reviewed source",approvals)).hasMessageContaining("already");
  UUID reverse=imports.reverse(maker,id,"Duplicate source removed",approvals);
  assertThatThrownBy(()->imports.decide(maker,id,reverse,"APPROVE",null,approvals)).hasMessageContaining("own request");
  imports.decide(checker,id,reverse,"APPROVE",null,approvals);
  assertThat(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entries WHERE batch_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("0");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE batch_id=? AND reversal_of IS NOT NULL",Integer.class,id)).isEqualTo(1);
  assertThat(imports.list(maker,0).batches().getFirst().status()).isEqualTo("REVERSED");
 }
 @Test void memberRowsRemainEncryptedAndReversalDeactivatesWithoutDeleting(){
  UUID maker=user("ADMIN"),checker=user("PRESIDENT");byte[] file=memberFile("Import fixture","+233209876123");UUID id=submit(maker,"MEMBERS",file);
  assertThat(new String(jdbc.queryForObject("SELECT encrypted_data FROM import_rows WHERE batch_id=?",byte[].class,id),StandardCharsets.UTF_8)).doesNotContain("233209876123");
  assertThat(imports.review(checker,id).toString()).doesNotContain("233209876123");
  imports.decide(checker,id,request(id),"APPROVE",null,approvals);
  UUID member=jdbc.queryForObject("SELECT member_id FROM import_member_results WHERE batch_id=?",UUID.class,id);
  assertThat(jdbc.queryForObject("SELECT member_number FROM members WHERE id=?",String.class,member)).startsWith("CU-");
  UUID reverse=imports.reverse(maker,id,"Remove a duplicate onboarding batch",approvals);imports.decide(checker,id,reverse,"APPROVE",null,approvals);
  assertThat(jdbc.queryForObject("SELECT status FROM members WHERE id=?",String.class,member)).isEqualTo("INACTIVE");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=?",Integer.class,id)).isEqualTo(1);
 }
 @Test void staleSourcesAndClosedPeriodsPreventPostingWithNoPartialDecision(){
  UUID maker=user("TREASURER"),checker=user("PRESIDENT");String number=member();byte[] file=ledger(number,"stale-"+UUID.randomUUID(),"12.25","12.25");UUID id=submit(maker,"LEDGER",file);
  jdbc.update("UPDATE fiscal_years SET status='CLOSED',closed_at=now(),closed_by=? WHERE year=?",checker,today.getYear());
  try {
  assertThatThrownBy(()->imports.decide(checker,id,request(id),"APPROVE",null,approvals)).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions WHERE request_id=?",Integer.class,request(id))).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE batch_id=?",Integer.class,id)).isZero();
  assertThat(imports.dryRun(maker,"LEDGER","fixture.csv",file).valid()).isFalse();
  } finally { jdbc.update("UPDATE fiscal_years SET status='OPEN',closed_at=null,closed_by=null WHERE year=?",today.getYear()); }
 }
 @Test void routesEnforcePermissionsCsrfOriginAndBatchOwnership()throws Exception {
  UUID maker=user("ADMIN"),ordinary=user("MEMBER");var token=sessions.create(maker);byte[] file=memberFile("HTTP fixture","+233209876125");
  mvc.perform(get("/api/v1/imports")).andExpect(status().isUnauthorized());
  var memberToken=sessions.create(ordinary);mvc.perform(get("/api/v1/imports").cookie(new Cookie("cu_access",memberToken.access()))).andExpect(status().isForbidden());
  mvc.perform(multipart("/api/v1/imports/dry-run").file(new MockMultipartFile("file","fixture.csv","text/csv",file)).param("kind","MEMBERS").cookie(new Cookie("cu_access",token.access()))).andExpect(status().isForbidden());
  mvc.perform(multipart("/api/v1/imports/dry-run").file(new MockMultipartFile("file","fixture.csv","text/csv",file)).param("kind","MEMBERS").cookie(new Cookie("cu_access",token.access())).header("X-CSRF-Token",token.csrf()).header("Origin","https://wrong.invalid")).andExpect(status().isForbidden());
  mvc.perform(multipart("/api/v1/imports/dry-run").file(new MockMultipartFile("file","fixture.csv","text/csv",file)).param("kind","MEMBERS").cookie(new Cookie("cu_access",token.access())).header("X-CSRF-Token",token.csrf()).header("Origin","http://localhost:5173")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  UUID id=submit(maker,"MEMBERS",file);assertThatThrownBy(()->imports.cancel(ordinary,id,request(id),approvals)).hasMessageContaining("maker");
  imports.cancel(maker,id,request(id),approvals);assertThat(imports.list(maker,0).batches().getFirst().status()).isEqualTo("CANCELLED");
 }
 private UUID submit(UUID maker,String kind,byte[] file){var report=imports.dryRun(maker,kind,"fixture.csv",file);assertThat(report.valid()).isTrue();return imports.submit(maker,kind,"fixture.csv",file,report.fileHash(),"Reviewed source fixture",approvals);}
 private UUID request(UUID batch){return jdbc.queryForObject("SELECT approval_request_id FROM import_batches WHERE id=?",UUID.class,batch);}
 private byte[] ledger(String member,String ref,String amount,String total){return bytes(String.join(",",ImportService.LEDGER_COLUMNS)+"\n"+member+",ADDITION,PRINCIPAL,"+amount+","+today+","+ref+",\"Source, fixture\"\nTOTAL,,,"+total+",,,\n");}
 private byte[] memberFile(String name,String phone){return bytes(String.join(",",ImportService.MEMBER_COLUMNS)+"\n,"+name+",,"+phone+",,,,,,,,"+today+"\n");}
 private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 private String member(){String number="IM-"+UUID.randomUUID();jdbc.update("INSERT INTO members(member_number,full_name,join_date) VALUES(?,'Import fixture',?)",number,today.minusYears(1));return number;}
 private UUID user(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture')",id,id+"@invalid.example");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",id,today,role);return id;}
}
