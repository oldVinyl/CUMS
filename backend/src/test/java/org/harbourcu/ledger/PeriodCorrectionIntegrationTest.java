package org.harbourcu.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.importer.ImportService;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.flyway.default-schema=period_tests","spring.datasource.hikari.schema=period_tests",
    "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class PeriodCorrectionIntegrationTest {
    @Autowired LedgerWorkflowService ledger;
    @Autowired ApprovalService approvals;
    @Autowired ImportService imports;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;

    @Test void approvedUnlockImportAndRecloseRecomputeLaterSnapshotsWithoutLosingHistory() {
        UUID treasurer=user("TREASURER"), president=user("PRESIDENT"), checker=user("VICE_PRESIDENT");
        year(2001);year(2002);UUID member=member(2001);
        jdbc.update("INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference) VALUES(?,'SEED','PRINCIPAL',100,'2001-01-02',2001,?)",member,"PERIOD-"+member);
        approve(ledger.close(treasurer,2001),president);approve(ledger.close(treasurer,2002),president);
        assertThat(balance(2002,member)).isEqualByComparingTo("100");
        UUID unlock=ledger.unlock(president,2001,"Correct omitted source contribution");
        assertThat(periodStatus(2001)).isEqualTo("CLOSED");
        assertThatThrownBy(()->approve(unlock,president)).hasMessageContaining("own request");
        assertThatThrownBy(()->ledger.unlock(president,2001,"Duplicate request")).hasMessageContaining("already awaits");
        approve(unlock,checker);assertThat(periodStatus(2001)).isEqualTo("OPEN");assertThat(periodStatus(2002)).isEqualTo("CLOSED");
        String number=jdbc.queryForObject("SELECT member_number FROM members WHERE id=?",String.class,member);
        byte[] file=(String.join(",",ImportService.LEDGER_COLUMNS)+"\n"+number+",ADDITION,PRINCIPAL,10.25,2001-02-01,correction-"+UUID.randomUUID()+",Source reconciliation\nTOTAL,,,10.25,,,\n").getBytes(StandardCharsets.UTF_8);
        var report=imports.dryRun(treasurer,"LEDGER","fixture.csv",file);assertThat(report.valid()).isTrue();
        UUID batch=imports.submit(treasurer,"LEDGER","fixture.csv",file,report.fileHash(),"Reviewed original source",approvals);
        UUID request=jdbc.queryForObject("SELECT approval_request_id FROM import_batches WHERE id=?",UUID.class,batch);
        imports.decide(president,batch,request,"APPROVE","Compared with source",approvals);
        assertThat(balance(2001,member)).isEqualByComparingTo("110.25");assertThat(balance(2002,member)).isEqualByComparingTo("110.25");
        assertThat(jdbc.queryForObject("SELECT equity FROM fiscal_snapshot_history WHERE fiscal_year=2001 AND member_id=? AND revision=1",BigDecimal.class,member)).isEqualByComparingTo("100");
        assertThat(ledger.snapshots(president,2002,0).entries()).anySatisfy(row->{assertThat(row.memberNumber()).isEqualTo(number);assertThat(row.reconciled()).isTrue();assertThat(row.revision()).isEqualTo(3);});
        approve(ledger.close(treasurer,2001),president);assertThat(periodStatus(2001)).isEqualTo("CLOSED");assertThat(balance(2002,member)).isEqualByComparingTo("110.25");
        assertThatThrownBy(()->jdbc.update("DELETE FROM fiscal_snapshot_history WHERE member_id=?",member)).hasMessageContaining("append-only");
        assertThat(imports.dryRun(treasurer,"LEDGER","fixture.csv",file).valid()).isFalse();
    }

    @Test void revokedMakerPermissionRollsBackFinalDecisionAndUnlock() {
        year(2007);UUID maker=user("PRESIDENT"),checker=user("TREASURER");closeDirect(2007,maker);
        UUID request=ledger.unlock(maker,2007,"Review old period");
        jdbc.update("UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Test revocation' WHERE user_id=?",checker,maker);
        assertThatThrownBy(()->approve(request,checker)).hasMessageContaining("cannot unlock");
        assertThat(periodStatus(2007)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions WHERE request_id=?",Integer.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fiscal_snapshot_history WHERE fiscal_year=2007",Integer.class)).isZero();
    }

    @Test void rejectedAndCancelledUnlocksPreserveClosedPeriodAndRequireReason() {
        year(2008);year(2009);UUID maker=user("PRESIDENT"),checker=user("TREASURER");closeDirect(2008,maker);closeDirect(2009,maker);
        assertThatThrownBy(()->ledger.unlock(maker,2008," ")).hasMessageContaining("reason");
        assertThatThrownBy(()->ledger.unlock(checker,2008,"Test")).isInstanceOf(SecurityException.class);
        UUID rejected=ledger.unlock(maker,2008,"Review period");approvals.decide(rejected,checker,ApprovalService.Decision.REJECT,"Source missing");
        UUID cancelled=ledger.unlock(maker,2009,"Review period");ledger.cancel(maker,cancelled);
        assertThat(periodStatus(2008)).isEqualTo("CLOSED");assertThat(periodStatus(2009)).isEqualTo("CLOSED");
    }

    @Test void routesEnforcePermissionsCsrfOriginAndNoStore() throws Exception {
        UUID maker=user("PRESIDENT"),ordinary=user("MEMBER");var token=sessions.create(maker);var memberToken=sessions.create(ordinary);
        mvc.perform(get("/api/v1/ledger/fiscal-years/2001/snapshots")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ledger/fiscal-years/2001/snapshots").cookie(new Cookie("cu_access",memberToken.access()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ledger/fiscal-years/2001/snapshots").cookie(new Cookie("cu_access",token.access()))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/ledger/fiscal-years/2001/unlock").cookie(new Cookie("cu_access",token.access())).contentType("application/json").content("{\"reason\":\"Review\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ledger/fiscal-years/2001/unlock").cookie(new Cookie("cu_access",token.access())).header("X-CSRF-Token",token.csrf()).header("Origin","https://wrong.invalid").contentType("application/json").content("{\"reason\":\"Review\"}")).andExpect(status().isForbidden());
    }

    @Test @org.springframework.transaction.annotation.Transactional void historicalImportCannotSilentlyChangePostedCycleRatios() {
        year(2005);UUID maker=user("TREASURER"),member=member(2005);
        jdbc.update("INSERT INTO investments(tenor,purchase_date,maturity_date,cost,maturity_value,status) VALUES('91_DAY','2005-03-01','2005-06-01',100,110,'POSTED')");
        String number=jdbc.queryForObject("SELECT member_number FROM members WHERE id=?",String.class,member);
        byte[] file=(String.join(",",ImportService.LEDGER_COLUMNS)+"\n"+number+",ADDITION,PRINCIPAL,10,2005-02-01,cycle-impact," ).getBytes(StandardCharsets.UTF_8);
        var report=imports.dryRun(maker,"LEDGER","fixture.csv",file);
        assertThat(report.valid()).isFalse();assertThat(report.rows().getFirst().errors()).anyMatch(error->error.contains("cycle correction"));
    }
    private void approve(UUID request,UUID checker){approvals.decide(request,checker,ApprovalService.Decision.APPROVE,"Reviewed fixture");}
    private String periodStatus(int year){return jdbc.queryForObject("SELECT status FROM fiscal_years WHERE year=?",String.class,year);}
    private BigDecimal balance(int year,UUID member){return jdbc.queryForObject("SELECT equity FROM fiscal_year_snapshots WHERE fiscal_year=? AND member_id=?",BigDecimal.class,year,member);}
    private void year(int year){jdbc.update("INSERT INTO fiscal_years(year,start_date,end_date) VALUES(?,make_date(?,1,1),make_date(?,12,31)) ON CONFLICT DO NOTHING",year,year,year);}
    private void closeDirect(int year,UUID actor){jdbc.update("UPDATE fiscal_years SET status='CLOSED',closed_at=now(),closed_by=? WHERE year=?",actor,year);}
    private UUID member(int year){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Correction fixture',make_date(?,1,1))",id,"PC-"+id,year);return id;}
    private UUID user(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture-hash')",id,id+"@invalid.example");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",id,LocalDate.now(ZoneId.of("Africa/Accra")),role);return id;}
}
