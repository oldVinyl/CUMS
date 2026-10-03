package org.harbourcu.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.harbourcu.role.PermissionService;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    public record Figures(BigDecimal principal, BigDecimal income, BigDecimal equity) {}
    public record MemberRow(UUID memberId, String memberNumber, String fullName,
                            Figures balanceBf, Figures ytd, Figures total,
                            BigDecimal roePercent, BigDecimal equityRatioPercent) {}
    public record TrendPoint(LocalDate date, BigDecimal equity) {}
    public record Dashboard(LocalDate asAt, int fiscalYear, List<MemberRow> members,
                            MemberRow totals, List<TrendPoint> trend, boolean developmentData, SnapshotReconciliation balanceForwardSnapshot) {}
    public record SnapshotReconciliation(int fiscalYear, String state, int snapshotCount, int missingMembers, int mismatchedMembers) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final Environment environment;

    public DashboardService(JdbcTemplate jdbc, PermissionService permissions, Environment environment) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.environment = environment;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
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
        List<TrendPoint> trend = jdbc.query("""
            SELECT dates.as_at, coalesce(sum(l.amount), 0) AS equity
            FROM (
                SELECT least((month + interval '1 month - 1 day')::date, ?::date) AS as_at
                FROM generate_series(?::date, ?::date, interval '1 month') month
            ) dates
            LEFT JOIN ledger_entries l ON l.effective_date <= dates.as_at
                AND EXISTS (SELECT 1 FROM members m WHERE m.id = l.member_id
                    AND m.join_date <= dates.as_at)
            GROUP BY dates.as_at ORDER BY dates.as_at
            """, (row, ignored) -> new TrendPoint(row.getDate("as_at").toLocalDate(),
                row.getBigDecimal("equity")), asAt, yearStart, asAt);
        boolean samples = environment.matchesProfiles("dev") && Boolean.TRUE.equals(
            jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_entries WHERE reference LIKE 'DEV-SAMPLE-%' AND effective_date <= ?)",
                Boolean.class, asAt));
        return new Dashboard(asAt, asAt.getYear(), withRatios, totals, trend, samples, reconcile(yearStart.getYear()-1));
    }

    private SnapshotReconciliation reconcile(int year) {
        var periods=jdbc.queryForList("SELECT status FROM fiscal_years WHERE year=?",String.class,year);
        if(periods.isEmpty()) return new SnapshotReconciliation(year,"NOT_RECORDED",0,0,0);
        var counts=jdbc.queryForMap("""
            WITH balances AS (
                SELECT m.id,coalesce(sum(l.amount) FILTER(WHERE l.component='PRINCIPAL'),0) principal,
                  coalesce(sum(l.amount) FILTER(WHERE l.component='INCOME'),0) income
                FROM members m LEFT JOIN ledger_entries l ON l.member_id=m.id AND l.effective_date<=make_date(?,12,31)
                WHERE m.join_date<=make_date(?,12,31) GROUP BY m.id
            ) SELECT count(s.id) snapshots,count(*) FILTER(WHERE s.id IS NULL) missing,
              count(*) FILTER(WHERE s.id IS NOT NULL AND (s.principal<>b.principal OR s.income<>b.income OR s.equity<>b.principal+b.income)) mismatched
            FROM balances b LEFT JOIN fiscal_year_snapshots s ON s.member_id=b.id AND s.fiscal_year=?
            """,year,year,year);
        int snapshots=((Number)counts.get("snapshots")).intValue(),missing=((Number)counts.get("missing")).intValue(),mismatched=((Number)counts.get("mismatched")).intValue();
        String state="CLOSED".equals(periods.getFirst()) ? missing==0&&mismatched==0?"RECONCILED":"REVIEW_REQUIRED"
            : snapshots>0?"CORRECTION_OPEN":"NOT_RECORDED";
        return new SnapshotReconciliation(year,state,snapshots,missing,mismatched);
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
