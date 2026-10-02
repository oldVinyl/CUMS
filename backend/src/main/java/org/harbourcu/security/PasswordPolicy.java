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
        if (password == null || password.codePointCount(0, password.length()) < 12
            || password.codePointCount(0, password.length()) > 128
            || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password must contain 12–128 printable characters");
        }
        if (commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Choose a less common password");
        }
    }
}
