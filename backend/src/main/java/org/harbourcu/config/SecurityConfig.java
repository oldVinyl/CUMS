package org.harbourcu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
                                            Environment environment) throws Exception {
        return http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/**", "/api/v1/members",
                "/api/v1/members/**", "/api/v1/investments", "/api/v1/investments/**",
                "/api/v1/settings/rollover", "/api/v1/ledger/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/v1/health", "/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/members", "/api/v1/members/*",
                    "/api/v1/members/*/status", "/api/v1/members/*/balance")
                    .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        environment.containsProperty("APP_ENCRYPTION_KEY")))
                .requestMatchers("/api/v1/investments", "/api/v1/investments/**")
                    .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        environment.containsProperty("APP_ENCRYPTION_KEY")))
                .requestMatchers("/api/v1/dashboard", "/api/v1/settings/rollover", "/api/v1/reports/**", "/api/v1/ledger", "/api/v1/ledger/**")
                    .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        environment.containsProperty("APP_ENCRYPTION_KEY")))
                .anyRequest().denyAll())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(errors -> errors
                .accessDeniedHandler((request, response, exception) -> accessDenied(request, response, json))
                .authenticationEntryPoint((request, response, exception) -> accessDenied(request, response, json)))
            .build();
    }

    private static void accessDenied(HttpServletRequest request, HttpServletResponse response,
                                     ObjectMapper json) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError("ACCESS_DENIED",
            "You do not have permission to access this resource.", List.of(),
            TraceIdFilter.traceId(request)));
    }
}
