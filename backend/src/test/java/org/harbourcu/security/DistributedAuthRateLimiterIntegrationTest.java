package org.harbourcu.security;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.flyway.default-schema=distributed_rate_tests",
      "spring.datasource.hikari.schema=distributed_rate_tests",
      "spring.datasource.hikari.maximum-pool-size=8",
      "AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
class DistributedAuthRateLimiterIntegrationTest {
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clear() {
    jdbc.update("DELETE FROM auth_rate_buckets");
  }

  static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Test
  void independentInstancesShareLimitsAndOnlyStoreDigests() {
    var a = new DistributedAuthRateLimiter(jdbc, KEY);
    var b = new DistributedAuthRateLimiter(jdbc, KEY);
    for (int i = 0; i < 20; i++) assertThat((i % 2 == 0 ? a : b).allow("192.0.2.21")).isTrue();
    assertThat(a.allow("192.0.2.21")).isFalse();
    assertThat(b.allow("192.0.2.21")).isFalse();
    assertThat(b.allow("192.0.2.22")).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT bool_and(octet_length(bucket_digest)=32) FROM auth_rate_buckets",
                Boolean.class))
        .isTrue();
  }

  @Test
  void concurrentRequestsCannotOverspendAndIdleBucketsExpire() throws Exception {
    var a = new DistributedAuthRateLimiter(jdbc, KEY);
    var b = new DistributedAuthRateLimiter(jdbc, KEY);
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        var limiter = i % 2 == 0 ? a : b;
        calls.add(() -> limiter.allow("2001:db8::99"));
      }
      long accepted = 0;
      for (var result : executor.invokeAll(calls)) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(20);
    }
    jdbc.update("UPDATE auth_rate_buckets SET updated_at=now()-interval '3 hours'");
    a.expireIdle();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_rate_buckets", Integer.class))
        .isZero();
    assertThat(b.allow("2001:db8::99")).isTrue();
  }

  @Test
  void refillUsesDatabaseTimeAndGlobalBudgetIsShared() {
    var a = new DistributedAuthRateLimiter(jdbc, KEY);
    for (int i = 0; i < 20; i++) assertThat(a.allow("192.0.2.23")).isTrue();
    assertThat(a.allow("192.0.2.23")).isFalse();
    jdbc.update("UPDATE auth_rate_buckets SET updated_at=now()-interval '61 seconds'");
    assertThat(a.allow("192.0.2.23")).isTrue();
    jdbc.update("UPDATE auth_rate_buckets SET tokens=0,updated_at=now()");
    assertThat(new DistributedAuthRateLimiter(jdbc, KEY).allow("192.0.2.24")).isFalse();
  }
}
