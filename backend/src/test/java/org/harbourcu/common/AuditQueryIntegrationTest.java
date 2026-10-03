package org.harbourcu.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class AuditQueryIntegrationTest {
    @Autowired AuditQueryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    final LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
    @Test @Transactional void filtersAndPaginationExposeOnlyEventMetadata() {
        UUID actor=user("AUDITOR"), entity=UUID.randomUUID();
        for(int i=0;i<52;i++) event(actor,entity,"FIXTURE_EVENT","MEMBER");
        event(actor,entity,"OTHER_EVENT","MEMBER");
        var filter=new AuditQueryService.Filter(today,today,"FIXTURE_EVENT","MEMBER",actor,entity);
        var first=service.list(actor,filter,0);
        assertThat(first.events()).hasSize(50);assertThat(first.hasNext()).isTrue();
        assertThat(service.list(actor,filter,1).events()).hasSize(2);
        assertThat(first.events()).allSatisfy(e->{assertThat(e.actorId()).isEqualTo(actor);assertThat(e.entityId()).isEqualTo(entity);});
        assertThat(service.list(actor,new AuditQueryService.Filter(today,today,"NO_EVENT",null,null,null),0).events()).isEmpty();
        assertThatThrownBy(()->service.list(actor,new AuditQueryService.Filter(today.plusDays(1),today,null,null,null,null),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(actor,new AuditQueryService.Filter(today,today,"' OR 1=1",null,null,null),0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @Transactional void exportIsAuditedFormulaSafeAndNeverContainsSensitiveDetails() throws Exception {
        UUID actor=user("AUDITOR"),entity=UUID.randomUUID();
        jdbc.update("UPDATE users SET email='=HYPERLINK(\"bad\")@invalid.example' WHERE id=?",actor);
        event(actor,entity,"FIXTURE_EXPORT","MEMBER");
        var filter=new AuditQueryService.Filter(today,today,"FIXTURE_EXPORT",null,actor,null);
        String csv=new String(service.export(actor,filter,"safe-request"),StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"bad\"\")@invalid.example\"").doesNotContain("PRIVATE_SECRET_VALUE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id=? AND action='EXPORT_DOWNLOADED' AND request_id='safe-request'",Integer.class,actor)).isEqualTo(1);
        var token=sessions.create(actor);
        String body=mvc.perform(get("/api/v1/audit").param("from",today.toString()).param("to",today.toString()).param("actorId",actor.toString()).cookie(new Cookie("cu_access",token.access())))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("PRIVATE_SECRET_VALUE","details");
        mvc.perform(get("/api/v1/audit/export.csv").param("from",today.toString()).param("to",today.toString()).cookie(new Cookie("cu_access",token.access())))
            .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"));
    }
    @Test @Transactional void authorizationRevocationAndImmutabilityAreEnforced() throws Exception {
        UUID member=user("MEMBER"),auditor=user("AUDITOR"),entity=UUID.randomUUID();
        var filter=new AuditQueryService.Filter(today,today,null,null,null,null);
        assertThatThrownBy(()->service.list(member,filter,0)).isInstanceOf(SecurityException.class);
        mvc.perform(get("/api/v1/audit").param("from",today.toString()).param("to",today.toString())).andExpect(status().isUnauthorized());
        var token=sessions.create(member);
        mvc.perform(get("/api/v1/audit/export.csv").param("from",today.toString()).param("to",today.toString()).cookie(new Cookie("cu_access",token.access()))).andExpect(status().isForbidden());
        jdbc.update("UPDATE user_role_assignments SET revoked_at=now(),revoked_by=?,reason='Fixture revocation' WHERE user_id=?",member,auditor);
        assertThatThrownBy(()->service.export(auditor,filter,"request")).isInstanceOf(SecurityException.class);
        event(member,entity,"FIXTURE_LOCK","MEMBER");
        // Use a savepoint so the expected PostgreSQL failure does not poison this test transaction.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
            var point=connection.setSavepoint();
            assertThatThrownBy(()->{try(var statement=connection.prepareStatement("DELETE FROM audit_log WHERE entity_id=?")){statement.setObject(1,entity);statement.executeUpdate();}}).hasMessageContaining("append-only");
            connection.rollback(point);return null;
        });
    }
    private void event(UUID actor,UUID entity,String action,String type) { jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,?,?,?,jsonb_build_object('secret','PRIVATE_SECRET_VALUE'))",actor,action,type,entity); }
    private UUID user(String role) {UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture')",id,id+"@invalid.example");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",id,today,role);return id;}
}
