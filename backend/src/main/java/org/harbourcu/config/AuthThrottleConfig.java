package org.harbourcu.config;

import org.harbourcu.security.LoginThrottleService;
import org.harbourcu.security.PasswordHasher;
import org.harbourcu.security.PasswordLoginService;
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
}
