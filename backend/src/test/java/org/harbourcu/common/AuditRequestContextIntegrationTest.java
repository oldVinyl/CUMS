package org.harbourcu.common;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(properties={"spring.flyway.default-schema=audit_request_context_tests","spring.datasource.hikari.schema=audit_request_context_tests","spring.datasource.hikari.maximum-pool-size=2"})
class AuditRequestContextIntegrationTest {
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    @Test void allAuditWritersReceiveRequestMetadataAndPooledBackgroundWorkDoesNotLeakIt() {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'unused-synthetic-hash')", actor, actor+"@example.invalid");
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        try {
            request("203.0.113.23", first);
            audit.record(actor, AuditService.Action.PASSWORD_CHANGED, "USER", actor, null);
            request("2001:db8::7", second);
            // Covers direct SQL writers as well as the central service.
            jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'DIRECT_FIXTURE','USER',?)", actor, actor);
        } finally { RequestContextHolder.resetRequestAttributes(); }
        audit.record(actor, AuditService.Action.LOGOUT_ALL, "USER", actor, null);
        assertThat(jdbc.queryForObject("SELECT host(request_ip) FROM audit_log WHERE actor_id=? AND action='PASSWORD_CHANGED'", String.class, actor)).isEqualTo("203.0.113.23");
        assertThat(jdbc.queryForObject("SELECT request_id FROM audit_log WHERE actor_id=? AND action='PASSWORD_CHANGED'", String.class, actor)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT host(request_ip) FROM audit_log WHERE actor_id=? AND action='DIRECT_FIXTURE'", String.class, actor)).isEqualTo("2001:db8::7");
        assertThat(jdbc.queryForObject("SELECT request_id FROM audit_log WHERE actor_id=? AND action='DIRECT_FIXTURE'", String.class, actor)).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT request_ip IS NULL AND request_id IS NULL FROM audit_log WHERE actor_id=? AND action='LOGOUT_ALL'", Boolean.class, actor)).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET request_ip='127.0.0.1' WHERE actor_id=?", actor)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private void request(String address, String trace) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(address);
        request.setAttribute(TraceIdFilter.ATTRIBUTE, trace);
        request.addHeader("X-Forwarded-For", "198.51.100.99");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
