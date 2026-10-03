package org.harbourcu.security;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit one-time operator provisioning on an empty production database. */
@Component
@Profile("bootstrap & prod")
public class LeadershipBootstrap implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final Environment environment;

    public LeadershipBootstrap(JdbcTemplate jdbc, PasswordHasher passwords, Environment environment) {
        this.jdbc = jdbc; this.passwords = passwords; this.environment = environment;
    }

    @Override
    @Transactional
    public void run(String... args) {
        provision(environment.getRequiredProperty("BOOTSTRAP_ADMIN_EMAIL"),
            environment.getRequiredProperty("BOOTSTRAP_ADMIN_PASSWORD"),
            environment.getRequiredProperty("BOOTSTRAP_PRESIDENT_EMAIL"),
            environment.getRequiredProperty("BOOTSTRAP_PRESIDENT_PASSWORD"));
    }

    @Transactional
    public void provision(String adminEmail, String adminPassword, String presidentEmail, String presidentPassword) {
        String admin = email(adminEmail), president = email(presidentEmail);
        if (admin.equals(president) || adminPassword.equals(presidentPassword)) {
            throw new IllegalArgumentException("Initial administrator and president must be distinct with separate passwords.");
        }
        String adminHash = passwords.hashNewPassword(adminPassword);
        String presidentHash = passwords.hashNewPassword(presidentPassword);
        jdbc.execute("SELECT pg_advisory_xact_lock(78124511)");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM users)", Boolean.class))) {
            throw new IllegalStateException("Leadership bootstrap requires a fresh database with no accounts. Remove the bootstrap profile.");
        }
        create(admin, adminHash, "ADMIN");
        create(president, presidentHash, "PRESIDENT");
    }

    private void create(String email, String hash, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,force_password_change) VALUES(?,?,?,true)", id, email, hash);
        int assigned = jdbc.update("""
            INSERT INTO user_role_assignments(user_id,role_id,start_date)
            SELECT ?,id,? FROM roles WHERE code=?
            """, id, LocalDate.now(ZoneId.of("Africa/Accra")), role);
        if (assigned != 1) throw new IllegalStateException("Required leadership role is unavailable.");
        jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'LEADERSHIP_BOOTSTRAPPED','USER',?)", id, id);
    }

    private static String email(String value) {
        if (value == null || value.length() > 320 || !value.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Initial accounts require valid email addresses.");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
