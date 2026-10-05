package org.harbourcu.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyTest {
    private MockEnvironment safe() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i + 1);
        String key = Base64.getEncoder().encodeToString(bytes);
        bytes[0] = 42;
        String throttle = Base64.getEncoder().encodeToString(bytes);
        return new MockEnvironment().withProperty("APP_ENCRYPTION_KEY", key)
            .withProperty("AUTH_THROTTLE_KEY", throttle)
            .withProperty("DB_PASSWORD", "synthetic-unique-long-password")
            .withProperty("APP_COOKIE_SECURE", "true")
            .withProperty("APP_ALLOWED_ORIGIN", "https://union.example.com")
            .withProperty("APP_PASSKEY_RP_ID", "union.example.com")
            .withProperty("CLAMAV_HOST", "scanner")
            .withProperty("PAYMENT_PROVIDER", "paystack");
    }

    @Test void acceptsSecureSettingsWithDeferredMail() {
        assertDoesNotThrow(() -> ProductionSafety.validate(safe()));
    }

    @Test void rejectsMissingReceiptScanner() {
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("CLAMAV_HOST", "")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("CLAMAV_HOST", "   ")));
    }

    @Test void rejectsDevelopmentSamplesAndBootstrap() {
        var dev = safe(); dev.setActiveProfiles("prod", "dev");
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(dev));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("DEV_SAMPLE_DATA", "true")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("DEV_BOOTSTRAP_EMAIL", "synthetic@example.invalid")));
    }

    @Test void rejectsWeakKeysPasswordsCookiesAndFakePayments() {
        for (String name : new String[]{"APP_ENCRYPTION_KEY", "AUTH_THROTTLE_KEY"}) {
            assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty(name, "invalid")));
            assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty(name,
                Base64.getEncoder().encodeToString(new byte[32]))));
        }
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("DB_PASSWORD", "local-dev-only")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("APP_COOKIE_SECURE", "false")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("PAYMENT_PROVIDER", "fake")));
        var shared = safe(); shared.withProperty("AUTH_THROTTLE_KEY", shared.getProperty("APP_ENCRYPTION_KEY"));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(shared));
    }

    @Test void rejectsBadOriginsAndInsecureMail() {
        for (String origin : new String[]{"http://union.example.com", "https://localhost", "https://union.example.com/path",
                "https://user@union.example.com", "https://other.example.com", "https://union.example.com?key=bad"}) {
            assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("APP_ALLOWED_ORIGIN", origin)));
        }
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(safe().withProperty("APP_MAIL_ENABLED", "true")));
        assertDoesNotThrow(() -> ProductionSafety.validate(safe().withProperty("APP_MAIL_ENABLED", "true").withProperty("SMTP_STARTTLS", "true")));
    }
}
