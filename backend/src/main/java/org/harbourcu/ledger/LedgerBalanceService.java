package org.harbourcu.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LedgerBalanceService {
    public record Balance(BigDecimal principal, BigDecimal income, BigDecimal equity) {}

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;

    public LedgerBalanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Balance asAt(UUID memberId, LocalDate date) {
        if (memberId == null || date == null || date.isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw new IllegalArgumentException("Member and an as-at date no later than today are required");
        }
        Boolean memberExists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM members WHERE id = ?)", Boolean.class, memberId);
        if (!Boolean.TRUE.equals(memberExists)) {
            throw new IllegalArgumentException("Member does not exist");
        }
        return jdbc.queryForObject("""
            SELECT coalesce(sum(amount) FILTER (WHERE component = 'PRINCIPAL'), 0) AS principal,
                   coalesce(sum(amount) FILTER (WHERE component = 'INCOME'), 0) AS income,
                   coalesce(sum(amount), 0) AS equity
            FROM ledger_entries
            WHERE member_id = ? AND effective_date <= ?
            """, (row, ignored) -> new Balance(row.getBigDecimal("principal"),
                row.getBigDecimal("income"), row.getBigDecimal("equity")), memberId, date);
    }
}
