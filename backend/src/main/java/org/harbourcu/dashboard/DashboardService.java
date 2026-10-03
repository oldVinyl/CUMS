package org.harbourcu.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    public record Figures(BigDecimal principal, BigDecimal income, BigDecimal equity) {}
    public record MemberRow(UUID memberId, String memberNumber, String fullName,
                            Figures balanceBf, Figures ytd, Figures total,
                            BigDecimal roePercent, BigDecimal equityRatioPercent) {}
    public record Dashboard(LocalDate asAt, int fiscalYear, List<MemberRow> members,
                            MemberRow totals) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public DashboardService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Dashboard asAt(UUID actor, LocalDate asAt) {
        if (!permissions.hasPermission(actor, "DASHBOARD_READ_ALL")) {
            throw new SecurityException("Access denied");
        }
        if (asAt == null || asAt.isAfter(LocalDate.now(ZoneId.of("Africa/Accra")))
            || asAt.isBefore(LocalDate.of(2000, 1, 1))) {
            throw new IllegalArgumentException("Choose an as-at date no later than today");
        }
        LocalDate yearStart = asAt.withDayOfYear(1);
        List<MemberRow> rows = jdbc.query("""
            SELECT m.id, m.member_number, m.full_name,
                   coalesce(sum(l.amount) FILTER (WHERE l.effective_date < ?
                       AND l.component = 'PRINCIPAL'), 0) AS bf_principal,
                   coalesce(sum(l.amount) FILTER (WHERE l.effective_date < ?
                       AND l.component = 'INCOME'), 0) AS bf_income,
                   coalesce(sum(l.amount) FILTER (WHERE l.effective_date >= ?
                       AND l.component = 'PRINCIPAL'), 0) AS ytd_principal,
                   coalesce(sum(l.amount) FILTER (WHERE l.effective_date >= ?
                       AND l.component = 'INCOME'), 0) AS ytd_income
            FROM members m
            LEFT JOIN ledger_entries l ON l.member_id = m.id AND l.effective_date <= ?
            WHERE m.join_date <= ?
            GROUP BY m.id, m.member_number, m.full_name
            ORDER BY m.member_number, m.id
            """, (result, ignored) -> {
                Figures bf = figures(result.getBigDecimal("bf_principal"),
                    result.getBigDecimal("bf_income"));
                Figures ytd = figures(result.getBigDecimal("ytd_principal"),
                    result.getBigDecimal("ytd_income"));
                Figures total = figures(bf.principal().add(ytd.principal()),
                    bf.income().add(ytd.income()));
                return new MemberRow(result.getObject("id", UUID.class),
                    result.getString("member_number"), result.getString("full_name"),
                    bf, ytd, total, percent(total.income(), total.principal()), BigDecimal.ZERO);
            }, yearStart, yearStart, yearStart, yearStart, asAt, asAt);
        BigDecimal equity = rows.stream().map(row -> row.total().equity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MemberRow> withRatios = new ArrayList<>();
        for (MemberRow row : rows) {
            withRatios.add(new MemberRow(row.memberId(), row.memberNumber(), row.fullName(),
                row.balanceBf(), row.ytd(), row.total(), row.roePercent(),
                percent(row.total().equity(), equity)));
        }
        Figures bfTotal = sum(rows.stream().map(MemberRow::balanceBf).toList());
        Figures ytdTotal = sum(rows.stream().map(MemberRow::ytd).toList());
        Figures total = sum(rows.stream().map(MemberRow::total).toList());
        MemberRow totals = new MemberRow(null, null, "Total", bfTotal, ytdTotal, total,
            percent(total.income(), total.principal()),
            equity.signum() == 0 ? BigDecimal.ZERO : new BigDecimal("100.00"));
        return new Dashboard(asAt, asAt.getYear(), withRatios, totals);
    }

    private static Figures figures(BigDecimal principal, BigDecimal income) {
        return new Figures(principal, income, principal.add(income));
    }

    private static Figures sum(List<Figures> figures) {
        return figures(figures.stream().map(Figures::principal).reduce(BigDecimal.ZERO, BigDecimal::add),
            figures.stream().map(Figures::income).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? BigDecimal.ZERO
            : numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }
}
