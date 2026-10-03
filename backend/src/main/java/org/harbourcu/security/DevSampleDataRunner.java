package org.harbourcu.security;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fictitious, idempotent local fixtures. This bean cannot exist in production. */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "DEV_SAMPLE_DATA", havingValue = "true")
@Order(100)
public class DevSampleDataRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public DevSampleDataRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(String... args) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        int year = today.getYear();
        for (int fiscalYear : new int[] {year - 1, year}) {
            jdbc.update("""
                INSERT INTO fiscal_years (year, start_date, end_date)
                VALUES (?, make_date(?, 1, 1), make_date(?, 12, 31)) ON CONFLICT DO NOTHING
                """, fiscalYear, fiscalYear, fiscalYear);
        }
        String[] names = {"Ama", "Kojo", "Esi", "Kwame", "Abena", "Yaw",
            "Akua", "Kofi", "Adwoa", "Kwesi", "Efua", "Kweku"};
        for (int index = 0; index < names.length; index++) {
            UUID id = UUID.nameUUIDFromBytes(("cums-dev-member-" + index)
                .getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                INSERT INTO members (id, member_number, full_name, join_date)
                VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                """, id, "DEMO-" + String.format("%03d", index + 1),
                names[index] + " Demo", LocalDate.of(year - 1, 1, 1));
            seed(id, "BF-P", "SEED", "PRINCIPAL", new BigDecimal(8000 + index * 1700),
                LocalDate.of(year - 1, 12, 31));
            seed(id, "BF-I", "INCOME_ALLOCATION", "INCOME", new BigDecimal(800 + index * 140),
                LocalDate.of(year - 1, 12, 31));
            for (int month = 1; month <= today.getMonthValue(); month++) {
                LocalDate date = LocalDate.of(year, month, 1);
                seed(id, "P-" + month, "ADDITION", "PRINCIPAL",
                    new BigDecimal(150 + index * 25), date);
                if (month % 3 == 0) seed(id, "I-" + month, "INCOME_ALLOCATION", "INCOME",
                    new BigDecimal(120 + index * 15), date);
            }
        }
    }

    private void seed(UUID member, String key, String type, String component,
                       BigDecimal amount, LocalDate date) {
        String reference = "DEV-SAMPLE-" + member + "-"
            + (key.startsWith("BF-") ? "INITIAL" : date.getYear()) + "-" + key;
        Boolean open = jdbc.queryForObject("SELECT status = 'OPEN' FROM fiscal_years WHERE year = ? FOR SHARE",
            Boolean.class, date.getYear());
        if (!Boolean.TRUE.equals(open)) return;
        jdbc.update("""
            INSERT INTO ledger_entries (member_id, type, component, amount, effective_date,
                fiscal_year, reference, narration)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'Fictitious development sample; no real payment')
            ON CONFLICT (reference) DO NOTHING
            """, member, type, component, amount, date, date.getYear(), reference);
    }
}
