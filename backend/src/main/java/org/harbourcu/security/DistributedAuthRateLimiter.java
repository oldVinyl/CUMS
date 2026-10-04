package org.harbourcu.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Shared authentication limits; the database stores only keyed digests of addresses. */
@Component
@ConditionalOnProperty(name = "AUTH_THROTTLE_KEY")
public class DistributedAuthRateLimiter {
  private final JdbcTemplate jdbc;
  private final SecretKeySpec key;

  public DistributedAuthRateLimiter(
      JdbcTemplate jdbc, @Value("${AUTH_THROTTLE_KEY}") String encoded) {
    this.jdbc = jdbc;
    byte[] bytes = Base64.getDecoder().decode(encoded);
    if (bytes.length != 32)
      throw new IllegalArgumentException("AUTH_THROTTLE_KEY must decode to 32 bytes");
    key = new SecretKeySpec(bytes, "HmacSHA256");
  }

  public boolean allow(String ip) {
    if (ip == null || ip.isBlank()) return false;
    // Always take the global lock first. Denied attempts count towards the global budget too.
    return consume("global", 1000) && consume("ip:" + ip, 20);
  }

  private boolean consume(String identifier, int capacity) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT consume_auth_rate_token(?,?)", Boolean.class, digest(identifier), capacity));
  }

  private byte[] digest(String identifier) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(("http-auth:" + identifier).getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Authentication limiter unavailable.");
    }
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  public void expireIdle() {
    jdbc.update("DELETE FROM auth_rate_buckets WHERE updated_at<now()-interval '2 hours'");
  }
}
