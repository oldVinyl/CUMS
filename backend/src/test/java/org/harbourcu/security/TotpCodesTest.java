package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpCodesTest {
    private final TotpCodes totp = new TotpCodes();
    private final byte[] rfcSecret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Sha1Vectors() {
        assertThat(totp.atStep(rfcSecret, 59 / 30, 8)).isEqualTo("94287082");
        assertThat(totp.atStep(rfcSecret, 1111111109L / 30, 8)).isEqualTo("07081804");
        assertThat(totp.atStep(rfcSecret, 1234567890L / 30, 8)).isEqualTo("89005924");
    }

    @Test
    void acceptsOneAdjacentStepButRejectsWrongOrMalformedCodes() {
        String code = totp.atStep(rfcSecret, 100, 6);
        assertThat(totp.matchedStep(rfcSecret, Instant.ofEpochSecond(100 * 30), code))
            .hasValue(100);
        assertThat(totp.matchedStep(rfcSecret, Instant.ofEpochSecond(101 * 30), code))
            .hasValue(100);
        assertThat(totp.matchedStep(rfcSecret, Instant.ofEpochSecond(102 * 30), code))
            .isEmpty();
        assertThat(totp.matchedStep(rfcSecret, Instant.ofEpochSecond(100 * 30), "12-456"))
            .isEmpty();
        assertThat(totp.matchedStep(rfcSecret, Instant.ofEpochSecond(100 * 30), "000000000"))
            .isEmpty();
    }

    @Test
    void generatesFreshTwentyByteSecrets() {
        byte[] first = totp.newSecret();
        byte[] second = totp.newSecret();
        assertThat(first).hasSize(20).isNotEqualTo(second);
    }
}
