package org.harbourcu.investment;

import java.util.List;
import java.util.UUID;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RolloverSettingsService {
    public record Settings(String defaultRolloverPolicy) {}
    private static final List<String> POLICIES = List.of(
        "ROLL_PRINCIPAL_AND_INCOME", "ROLL_PRINCIPAL_ONLY", "NO_ROLLOVER");
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public RolloverSettingsService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public Settings get(UUID actor) {
        if (!permissions.hasPermission(actor, "SETTINGS_WRITE")
            && !permissions.hasPermission(actor, "INVESTMENT_READ")) {
            throw new SecurityException("Access denied");
        }
        return new Settings(defaultPolicy());
    }

    public String defaultPolicy() {
        return jdbc.queryForObject("""
            SELECT default_rollover_policy FROM organisation_settings WHERE singleton = true
            """, String.class);
    }

    @Transactional
    public Settings update(UUID actor, String policy) {
        if (!permissions.hasPermission(actor, "SETTINGS_WRITE")) {
            throw new SecurityException("Access denied");
        }
        if (!POLICIES.contains(policy)) {
            throw new IllegalArgumentException("Choose a valid rollover policy");
        }
        String previous = jdbc.queryForObject("""
            SELECT default_rollover_policy FROM organisation_settings
            WHERE singleton = true FOR UPDATE
            """, String.class);
        if (!policy.equals(previous)) {
            jdbc.update("""
                UPDATE organisation_settings SET default_rollover_policy = ?,
                    updated_at = now(), updated_by = ? WHERE singleton = true
                """, policy, actor);
            jdbc.update("""
                INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'ROLLOVER_DEFAULT_CHANGED', 'SETTINGS', NULL,
                    jsonb_build_object('previous', ?, 'next', ?))
                """, actor, previous, policy);
        }
        return new Settings(policy);
    }
}
