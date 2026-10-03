package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "DEV_SAMPLE_DATA=true")
class DevSampleDataIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    @Transactional
    void samplesAreIdempotentAndNotActivatedOutsideDev() {
        assertThat(context.getBeansOfType(DevSampleDataRunner.class)).isEmpty();
        var seed = new DevSampleDataRunner(jdbc);
        seed.run();
        Integer first = jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE reference LIKE 'DEV-SAMPLE-%'", Integer.class);
        assertThat(first).isGreaterThan(0);
        seed.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE reference LIKE 'DEV-SAMPLE-%'", Integer.class)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM members WHERE member_number LIKE 'DEMO-%'", Integer.class)).isEqualTo(12);
    }
}
