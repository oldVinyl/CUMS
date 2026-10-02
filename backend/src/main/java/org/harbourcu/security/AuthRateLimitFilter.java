package org.harbourcu.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.harbourcu.common.ApiError;
import org.harbourcu.common.TraceIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** In-process guard for public auth POSTs; account/IP lockouts remain in PostgreSQL. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_IPS = 4096;
    private final ObjectMapper json;
    private final Bucket global = Bucket.builder()
        .addLimit(limit -> limit.capacity(1000).refillGreedy(1000, Duration.ofMinutes(1)))
        .build();
    private final Map<String, Bucket> byIp = new LinkedHashMap<>(MAX_IPS, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_IPS;
        }
    };

    public AuthRateLimitFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
            || !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Bucket local;
        synchronized (byIp) {
            local = byIp.computeIfAbsent(request.getRemoteAddr(), ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(20).refillGreedy(20, Duration.ofMinutes(1)))
                .build());
        }
        if (!global.tryConsume(1) || !local.tryConsume(1)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            json.writeValue(response.getOutputStream(), new ApiError("RATE_LIMITED",
                "Too many requests. Please wait and try again.", List.of(),
                TraceIdFilter.traceId(request)));
            return;
        }
        chain.doFilter(request, response);
    }
}
