package org.harbourcu.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One-time, 128-bit recovery codes; only SHA-256 digests are retained. */
@Service
public class RecoveryCodeService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> issue(UUID userId) {
        List<String> codes = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            jdbc.update("INSERT INTO totp_recovery_codes (user_id, code_hash) VALUES (?, ?)",
                userId, hash(code));
            codes.add(code);
        }
        return List.copyOf(codes);
    }

    public boolean consume(UUID userId, String code) {
        if (userId == null || code == null || !code.matches("[A-Za-z0-9_-]{22}")) {
            return false;
        }
        try {
            if (Base64.getUrlDecoder().decode(code).length != 16) return false;
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return jdbc.update("""
            UPDATE totp_recovery_codes SET used_at = now()
            WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
            """, userId, hash(code)) == 1;
    }

    private byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
