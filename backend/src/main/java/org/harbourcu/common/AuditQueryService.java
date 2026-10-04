package org.harbourcu.common;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exposes event metadata only; raw details may contain sensitive change context. */
@Service
public class AuditQueryService {
    public record Filter(LocalDate from, LocalDate to, String action, String entityType, UUID actorId, UUID entityId) {}
    public record Event(UUID id, UUID actorId, String actorEmail, String action, String entityType,
                        UUID entityId, String occurredAt, String requestId, String requestIp, JsonNode financialChange) {}
    public record Page(List<Event> events, int page, boolean hasNext) {}
    private record Query(String sql, List<Object> args) {}
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json;
    public AuditQueryService(JdbcTemplate jdbc, PermissionService permissions, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc; this.permissions = permissions; this.audit = audit; this.json = json;
    }
    public Page list(UUID actor, Filter filter, int page) {
        require(actor);
        if (page < 0 || page > 10000) throw new IllegalArgumentException("Choose a valid page.");
        var events = read(filter, 51, page * 50);
        return new Page(events.stream().limit(50).toList(), page, events.size() > 50);
    }
    @Transactional
    public byte[] export(UUID actor, Filter filter, String requestId) {
        require(actor);
        var events = read(filter, 5001, 0);
        if (events.size() > 5000) throw new IllegalArgumentException("The export exceeds 5,000 events. Narrow the date range or filters.");
        var csv = new StringBuilder("Event ID,Occurred at (UTC),Actor ID,Actor email,Action,Entity type,Entity ID,Request ID,Request IP,Financial change\r\n");
        for (var e : events) csv.append(String.join(",", cell(e.id()), cell(e.occurredAt()), cell(e.actorId()),
            cell(e.actorEmail()), cell(e.action()), cell(e.entityType()), cell(e.entityId()), cell(e.requestId()), cell(e.requestIp()), cell(e.financialChange()))).append("\r\n");
        audit.record(actor, AuditService.Action.EXPORT_DOWNLOADED, "EXPORT", null, requestId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private List<Event> read(Filter filter, int limit, int offset) {
        Query q = query(filter);
        var args = new ArrayList<>(q.args()); args.add(limit); args.add(offset);
        return jdbc.query(q.sql() + " ORDER BY a.occurred_at DESC,a.id DESC LIMIT ? OFFSET ?", (rs, row) ->
            new Event(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getString("email"),
                rs.getString("action"), rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant().toString(), rs.getString("request_id"), rs.getString("request_ip"), financialChange(rs.getString("financial_change"))), args.toArray());
    }
    private JsonNode financialChange(String value) {
        if (value == null) return null;
        try { return json.readTree(value); } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Financial audit record could not be read."); }
    }
    private Query query(Filter f) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        if (f == null || f.from() == null || f.to() == null || f.from().isBefore(LocalDate.of(2000,1,1))
            || f.to().isAfter(today) || f.from().isAfter(f.to())) throw new IllegalArgumentException("Choose a valid date range from 2000 through today.");
        String sql = "SELECT a.id,a.actor_id,u.email,a.action,a.entity_type,a.entity_id,a.occurred_at,a.request_id,host(a.request_ip) request_ip, CASE WHEN f.audit_id IS NULL THEN NULL ELSE jsonb_build_object('operation',f.operation,'transactionId',f.transaction_id::text,'before',f.before_values,'after',f.after_values)::text END financial_change FROM audit_log a LEFT JOIN financial_audit_changes f ON f.audit_id=a.id LEFT JOIN users u ON u.id=a.actor_id WHERE a.occurred_at>=? AND a.occurred_at<?";
        var args = new ArrayList<Object>();
        args.add(f.from().atStartOfDay(ZoneId.of("Africa/Accra")).toOffsetDateTime());
        args.add(f.to().plusDays(1).atStartOfDay(ZoneId.of("Africa/Accra")).toOffsetDateTime());
        if (f.action() != null && !f.action().isBlank()) { validateCode(f.action()); sql += " AND a.action=?"; args.add(f.action()); }
        if (f.entityType() != null && !f.entityType().isBlank()) { validateCode(f.entityType()); sql += " AND a.entity_type=?"; args.add(f.entityType()); }
        if (f.actorId() != null) { sql += " AND a.actor_id=?"; args.add(f.actorId()); }
        if (f.entityId() != null) { sql += " AND a.entity_id=?"; args.add(f.entityId()); }
        return new Query(sql, args);
    }
    private void require(UUID actor) { if (!permissions.hasPermission(actor,"AUDIT_READ")) throw new SecurityException("Audit access required"); }
    private void validateCode(String value) { if (!value.matches("[A-Z][A-Z0-9_]{0,99}")) throw new IllegalArgumentException("Use the exact uppercase action or entity type."); }
    private String cell(Object value) {
        String s = value == null ? "" : value.toString();
        // Quoting alone does not prevent spreadsheet formula execution.
        if (s.stripLeading().matches("^[=+@\\-].*" ) || s.startsWith("\t") || s.startsWith("\r") || s.startsWith("\n")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
