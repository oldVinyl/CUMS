package org.harbourcu.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    private final Set<String> commonPasswords;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    public PasswordPolicy(org.springframework.jdbc.core.JdbcTemplate jdbc) { this(); this.jdbc = jdbc; }

    public PasswordPolicy() {
        try (InputStream stream = PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt")) {
            if (stream == null) {
                throw new IllegalStateException("Common-password list is missing");
            }
            commonPasswords = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .lines().map(line -> line.toLowerCase(Locale.ROOT).trim())
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .collect(Collectors.toUnmodifiableSet());
        } catch (IOException exception) {
            throw new IllegalStateException("Common-password list could not be read", exception);
        }
    }

    public void requireAcceptable(String password) {
        int minimum = jdbc == null ? 12 : jdbc.queryForObject("SELECT password_min_length FROM organisation_settings WHERE singleton=true", Integer.class);
        if (password == null || password.codePointCount(0, password.length()) < minimum
            || password.codePointCount(0, password.length()) > 128
            || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password must contain " + minimum + "–128 printable characters");
        }
        if (commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Choose a less common password");
        }
    }
}
