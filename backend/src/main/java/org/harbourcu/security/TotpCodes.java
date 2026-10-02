package org.harbourcu.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** RFC 6238 HMAC-SHA1 TOTP with 30-second steps and a one-step clock window. */
@Component
public class TotpCodes {
    private static final long STEP_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();

    public byte[] newSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return secret;
    }

    public OptionalLong matchedStep(byte[] secret, Instant now, String code) {
        if (secret == null || secret.length < 20 || now == null || code == null
            || !code.matches("[0-9]{6}")) {
            return OptionalLong.empty();
        }
        long current = Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
        for (long step = current - 1; step <= current + 1; step++) {
            if (step >= 0 && constantTimeEquals(code, atStep(secret, step, 6))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    String atStep(byte[] secret, long step, int digits) {
        if (secret == null || secret.length < 20 || step < 0 || (digits != 6 && digits != 8)) {
            throw new IllegalArgumentException("Invalid TOTP parameters");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int value = ((digest[offset] & 0x7f) << 24)
                | ((digest[offset + 1] & 0xff) << 16)
                | ((digest[offset + 2] & 0xff) << 8)
                | (digest[offset + 3] & 0xff);
            int modulus = digits == 6 ? 1_000_000 : 100_000_000;
            return String.format(java.util.Locale.ROOT, "%0" + digits + "d", value % modulus);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP calculation failed", exception);
        }
    }

    private boolean constantTimeEquals(String submitted, String expected) {
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) {
            difference |= submitted.charAt(i) ^ expected.charAt(i);
        }
        return difference == 0;
    }
}
