package org.harbourcu.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    private final PasswordPolicy policy;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public PasswordHasher(PasswordPolicy policy) {
        this.policy = policy;
    }

    public String hashNewPassword(String password) {
        policy.requireAcceptable(password);
        return encoder.encode(password);
    }

    public boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || !storedHash.startsWith("$argon2id$")) {
            return false;
        }
        return encoder.matches(password, storedHash);
    }
}
