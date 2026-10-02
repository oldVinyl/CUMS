package org.harbourcu.role;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads current role assignments on each check so revocation takes effect immediately. */
@Service
public class PermissionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasPermission(UUID userId, String permissionCode) {
        return hasPermission(userId, permissionCode, LocalDate.now(BUSINESS_ZONE));
    }

    public List<String> currentPermissions(UUID userId) {
        if (userId == null) return List.of();
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        return jdbc.queryForList("""
            SELECT DISTINCT p.code FROM user_role_assignments a
            JOIN role_permissions rp ON rp.role_id = a.role_id
            JOIN permissions p ON p.id = rp.permission_id
            JOIN users u ON u.id = a.user_id
            WHERE a.user_id = ? AND a.revoked_at IS NULL AND u.status = 'ACTIVE'
              AND a.start_date <= ? AND (a.end_date IS NULL OR a.end_date >= ?)
            ORDER BY p.code
            """, String.class, userId, date, date);
    }

    boolean hasPermission(UUID userId, String permissionCode, LocalDate date) {
        if (userId == null || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM user_role_assignments a
                JOIN role_permissions rp ON rp.role_id = a.role_id
                JOIN permissions p ON p.id = rp.permission_id
                JOIN users u ON u.id = a.user_id
                WHERE a.user_id = ? AND p.code = ?
                  AND u.status = 'ACTIVE'
                  AND a.revoked_at IS NULL
                  AND a.start_date <= ?
                  AND (a.end_date IS NULL OR a.end_date >= ?)
            )
            """, Boolean.class, userId, permissionCode, date, date));
    }
}
