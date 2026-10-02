package org.harbourcu.security;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local-only first account for exercising onboarding; never runs outside the dev profile. */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "DEV_BOOTSTRAP_EMAIL")
public class DevBootstrapRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final Environment environment;

    public DevBootstrapRunner(JdbcTemplate jdbc, PasswordHasher passwords, Environment environment) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(String... args) {
        String email = environment.getRequiredProperty("DEV_BOOTSTRAP_EMAIL").trim()
            .toLowerCase(Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 320) {
            throw new IllegalArgumentException("DEV_BOOTSTRAP_EMAIL must be a valid email address");
        }
        String role = environment.getProperty("DEV_BOOTSTRAP_ROLE", "ADMIN");
        if (!role.equals("ADMIN") && !role.equals("PRESIDENT")) {
            throw new IllegalArgumentException("DEV_BOOTSTRAP_ROLE must be ADMIN or PRESIDENT");
        }
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = ?)",
            Boolean.class, email);
        if (Boolean.TRUE.equals(exists)) return;
        String hash = passwords.hashNewPassword(environment.getRequiredProperty("DEV_BOOTSTRAP_PASSWORD"));
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (id, email, password_hash, force_password_change)
            VALUES (?, ?, ?, true)
            """, id, email, hash);
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            SELECT ?, id, (now() AT TIME ZONE 'Africa/Accra')::date
            FROM roles WHERE code = ?
            """, id, role);
    }
}
