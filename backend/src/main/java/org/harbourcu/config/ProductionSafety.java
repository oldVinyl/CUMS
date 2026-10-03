package org.harbourcu.config;

import java.net.URI;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fail closed before serving production with demonstration settings or weak key material. */
@Component
@Profile("prod")
public class ProductionSafety {
    public ProductionSafety(Environment environment) {
        validate(environment);
    }

    public static void validate(Environment env) {
        if (env.matchesProfiles("dev") || Boolean.parseBoolean(env.getProperty("DEV_SAMPLE_DATA", "false"))
                || env.containsProperty("DEV_BOOTSTRAP_EMAIL")) {
            throw new IllegalStateException("Production cannot enable development accounts or samples.");
        }
        requireKey(env, "APP_ENCRYPTION_KEY");
        requireKey(env, "AUTH_THROTTLE_KEY");
        if (env.getProperty("APP_ENCRYPTION_KEY").equals(env.getProperty("AUTH_THROTTLE_KEY"))) {
            throw new IllegalStateException("Production encryption and authentication throttle keys must be independent.");
        }
        String password = env.getProperty("DB_PASSWORD", "");
        if (password.length() < 20 || password.equals("local-dev-only")) {
            throw new IllegalStateException("Production requires a unique database password of at least 20 characters.");
        }
        if (!"true".equalsIgnoreCase(env.getProperty("APP_COOKIE_SECURE"))) {
            throw new IllegalStateException("Production requires secure cookies.");
        }
        URI origin;
        try {
            origin = URI.create(env.getProperty("APP_ALLOWED_ORIGIN", ""));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Production requires a valid HTTPS origin.");
        }
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || (origin.getPath() != null && !origin.getPath().isEmpty())
                || "localhost".equalsIgnoreCase(origin.getHost())
                || !origin.getHost().equalsIgnoreCase(env.getProperty("APP_PASSKEY_RP_ID", ""))) {
            throw new IllegalStateException("Production HTTPS origin and passkey relying-party host must match.");
        }
        if (!"paystack".equalsIgnoreCase(env.getProperty("PAYMENT_PROVIDER", ""))) {
            throw new IllegalStateException("Production requires the configured real payment provider.");
        }
        if (Boolean.parseBoolean(env.getProperty("APP_MAIL_ENABLED", "false"))
                && !Boolean.parseBoolean(env.getProperty("SMTP_STARTTLS", "false"))) {
            throw new IllegalStateException("Production SMTP requires TLS.");
        }
    }

    private static void requireKey(Environment env, String name) {
        try {
            byte[] decoded = Base64.getDecoder().decode(env.getProperty(name, ""));
            if (decoded.length != 32) throw new IllegalArgumentException();
            boolean identical = true;
            for (byte value : decoded) identical &= value == decoded[0];
            if (identical) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Production requires a random 32-byte Base64 key for " + name + ".");
        }
    }
}
