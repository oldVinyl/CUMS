package org.harbourcu.common;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes fixed, data-minimal events to the append-only audit table. */
@Service
public class AuditService {
    public enum Action {
        PASSWORD_CHANGED, TOTP_ENABLED, RECOVERY_CODE_USED, LOGOUT_ALL, MEMBER_CREATED, MEMBER_UPDATED,
        SENSITIVE_MEMBER_VIEW, FILE_DOWNLOADED, EXPORT_DOWNLOADED
    }

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void record(UUID actorId, Action action, String entityType, UUID entityId,
                       String requestId) {
        if (actorId == null || action == null || entityType == null
            || !entityType.matches("USER|MEMBER|ATTACHMENT|EXPORT")) {
            throw new IllegalArgumentException("Invalid audit event");
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, request_id)
            VALUES (?, ?, ?, ?, ?)
            """, actorId, action.name(), entityType, entityId, requestId);
    }
}
