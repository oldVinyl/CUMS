package org.harbourcu.config;

import org.harbourcu.common.FieldEncryption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class EncryptionConfig {
    @Bean
    @ConditionalOnProperty(name = "APP_ENCRYPTION_KEY")
    FieldEncryption fieldEncryption(Environment environment) {
        return new FieldEncryption(environment.getRequiredProperty("APP_ENCRYPTION_KEY"));
    }
}
