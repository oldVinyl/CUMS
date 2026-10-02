package org.harbourcu.config;

import org.harbourcu.security.LoginThrottleService;
import org.harbourcu.security.PasswordHasher;
import org.harbourcu.security.PasswordLoginService;
import org.harbourcu.security.AuthFlowService;
import org.harbourcu.security.SessionService;
import org.harbourcu.security.TotpCodes;
import org.harbourcu.common.FieldEncryption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AuthThrottleConfig {
    @Bean
    @ConditionalOnProperty(name = "AUTH_THROTTLE_KEY")
    LoginThrottleService loginThrottleService(JdbcTemplate jdbc, Environment environment) {
        return new LoginThrottleService(jdbc, environment.getRequiredProperty("AUTH_THROTTLE_KEY"));
    }

    @Bean
    @ConditionalOnProperty(name = "AUTH_THROTTLE_KEY")
    PasswordLoginService passwordLoginService(JdbcTemplate jdbc, PasswordHasher passwords,
                                              LoginThrottleService throttle) {
        return new PasswordLoginService(jdbc, passwords, throttle);
    }

    @Bean
    @ConditionalOnProperty(name = {"AUTH_THROTTLE_KEY", "APP_ENCRYPTION_KEY"})
    AuthFlowService authFlowService(JdbcTemplate jdbc, PasswordLoginService login,
                                    PasswordHasher passwords, SessionService sessions,
                                    TotpCodes totp, FieldEncryption fields) {
        return new AuthFlowService(jdbc, login, passwords, sessions, totp, fields);
    }
}
