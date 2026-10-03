package org.harbourcu.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.harbourcu.dashboard.DashboardService;
import org.harbourcu.investment.InvestmentService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.harbourcu.report.ReportRenderer.*;

@Service
public class FinancialReportService {
    public record Download(byte[] bytes, String filename, String contentType) {}
    private final DashboardService dashboard;
    private final InvestmentService investments;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final ReportRenderer renderer;

    public FinancialReportService(DashboardService dashboard, InvestmentService investments,
                                  PermissionService permissions, JdbcTemplate jdbc, ReportRenderer renderer) {
        this.dashboard = dashboard; this.investments = investments; this.permissions = permissions;
        this.jdbc = jdbc; this.renderer = renderer;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Download dashboard(UUID actor, LocalDate asAt, String format) {
        requireExport(actor);
        var data = dashboard.asAt(actor, asAt);
        var columns = new ArrayList<Column>();
        columns.add(new Column("Number", 16, false)); columns.add(new Column("Member", 32, false));
        for (int i = 0; i < 3; i++) {
            columns.add(new Column("Principal", 15, true)); columns.add(new Column("Income", 15, true));
            columns.add(new Column("Equity", 15, true));
        }
        columns.add(new Column("ROE (%)", 12, true)); columns.add(new Column("Equity share (%)", 14, true));
        List<List<Object>> rows = new ArrayList<>();
        for (var row : data.members()) rows.add(dashboardRow(row));
        rows.add(dashboardRow(data.totals()));
        Report report = new Report("Sheet B - Member equity", "As at " + asAt + " | Fiscal year " + data.fiscalYear()
            + " | Generated " + today() + (data.developmentData() ? " | DEVELOPMENT SAMPLE DATA" : ""), columns,
            List.of(new Group("Members", 0, 1), new Group("Balance forward", 2, 4),
                new Group("Year to date", 5, 7), new Group("Total", 8, 10), new Group("Ratios", 11, 12)), rows, true);
        return download(actor, report, "sheet-b-" + asAt, format, null);
    }

    private static List<Object> dashboardRow(DashboardService.MemberRow row) {
        return List.of(row.memberNumber() == null ? "" : row.memberNumber(), row.fullName(),
            row.balanceBf().principal(), row.balanceBf().income(), row.balanceBf().equity(),
            row.ytd().principal(), row.ytd().income(), row.ytd().equity(),
            row.total().principal(), row.total().income(), row.total().equity(), row.roePercent(), row.equityRatioPercent());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Download statement(UUID actor, UUID memberId, LocalDate from, LocalDate to, String format) {
        boolean staff = permissions.hasPermission(actor, "REPORT_EXPORT") && permissions.hasPermission(actor, "LEDGER_READ")
            && permissions.hasPermission(actor, "MEMBER_READ");
        boolean owner = permissions.hasPermission(actor, "OWN_DATA_READ") && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND member_id = ? AND status = 'ACTIVE')", Boolean.class, actor, memberId));
        if (!staff && !owner) throw new SecurityException("Access denied");
        if (from == null || to == null || from.isBefore(LocalDate.of(2000, 1, 1)) || to.isAfter(today()) || from.isAfter(to)) {
            throw new IllegalArgumentException("Choose a valid statement range through today");
        }
        var members = jdbc.query("SELECT member_number, full_name, join_date, join_date_basis FROM members WHERE id = ?",
            (row, ignored) -> new String[] { row.getString("member_number"), row.getString("full_name"), ("SOURCE_BASELINE".equals(row.getString("join_date_basis")) ? "Source baseline " : "") + row.getDate("join_date").toString() }, memberId);
        if (members.isEmpty()) throw new IllegalArgumentException("Member not found");
        var opening = jdbc.queryForObject("""
            SELECT coalesce(sum(amount) FILTER (WHERE component = 'PRINCIPAL'), 0) AS principal,
                coalesce(sum(amount) FILTER (WHERE component = 'INCOME'), 0) AS income
            FROM ledger_entries WHERE member_id = ? AND effective_date < ?
            """, (row, ignored) -> new BigDecimal[] { row.getBigDecimal("principal"), row.getBigDecimal("income") }, memberId, from);
        var entries = jdbc.query("""
            SELECT effective_date, reference, type, component, amount, narration FROM ledger_entries
            WHERE member_id = ? AND effective_date BETWEEN ? AND ? ORDER BY effective_date, recorded_at, id LIMIT 5001
            """, (row, ignored) -> new StatementEntry(row.getDate("effective_date").toLocalDate(), row.getString("reference"),
                row.getString("type"), row.getString("component"), row.getBigDecimal("amount"), row.getString("narration")), memberId, from, to);
        if (entries.size() > 5000) throw new IllegalArgumentException("Choose a shorter statement range");
        BigDecimal principal = opening[0], income = opening[1];
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(from.toString(), "", "Opening balance", "", "", principal, income, principal.add(income), "Before selected range"));
        for (var entry : entries) {
            if ("PRINCIPAL".equals(entry.component())) principal = principal.add(entry.amount());
            else income = income.add(entry.amount());
            rows.add(List.of(entry.date().toString(), entry.reference(), entry.type().replace('_', ' '), entry.component(),
                entry.amount(), principal, income, principal.add(income), entry.narration() == null ? "" : entry.narration()));
        }
        rows.add(List.of(to.toString(), "", "Closing balance", "", "", principal, income, principal.add(income), "As at " + to));
        var member = members.getFirst();
        Report report = new Report("Member statement", clean(member[1]) + " | " + member[0] + " | Joined " + member[2]
            + " | " + from + " to " + to + " | As at " + to + " | Generated " + today(),
            List.of(new Column("Date", 16, false), new Column("Reference", 25, false), new Column("Entry", 23, false),
                new Column("Component", 16, false), new Column("Amount", 16, true), new Column("Principal", 18, true),
                new Column("Income", 18, true), new Column("Equity", 18, true), new Column("Narration", 38, false)),
            List.of(new Group("Posted ledger entries", 0, 4), new Group("Running balance (GHS)", 5, 7), new Group("Details", 8, 8)), rows, true);
        return download(actor, report, "statement-" + memberId + "-" + from + "-" + to, format, memberId);
    }

    private record StatementEntry(LocalDate date, String reference, String type, String component, BigDecimal amount, String narration) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Download ledger(UUID actor, LocalDate from, LocalDate to, String format) {
        requireExport(actor);
        if (!permissions.hasPermission(actor, "LEDGER_READ")) throw new SecurityException("Access denied");
        if (from == null || to == null || from.isBefore(LocalDate.of(2000, 1, 1)) || from.isAfter(to) || to.isAfter(today())) throw new IllegalArgumentException("Choose a valid range");
        List<List<Object>> rows = jdbc.query("""
            SELECT m.member_number, m.full_name, l.effective_date, l.type, l.component, l.amount, l.reference,
                l.narration, l.reversal_of FROM ledger_entries l JOIN members m ON m.id = l.member_id
            WHERE l.effective_date BETWEEN ? AND ? ORDER BY l.effective_date, l.recorded_at, l.id LIMIT 5001
            """, (row, ignored) -> List.<Object>of(row.getString("member_number"), row.getString("full_name"), row.getDate("effective_date").toString(),
                row.getString("type"), row.getString("component"), row.getBigDecimal("amount"), row.getString("reference"),
                row.getString("narration") == null ? "" : row.getString("narration"), row.getObject("reversal_of") == null ? "" : row.getObject("reversal_of").toString()), from, to);
        if (rows.size() > 5000) throw new IllegalArgumentException("Choose a shorter ledger range");
        return download(actor, new Report("Posted ledger", from + " to " + to + " | Generated " + today(),
            List.of(new Column("Number", 16, false), new Column("Member", 28, false), new Column("Effective date", 17, false),
                new Column("Type", 23, false), new Column("Component", 17, false), new Column("Amount (GHS)", 18, true),
                new Column("Reference", 35, false), new Column("Narration", 40, false), new Column("Reversal of", 36, false)),
            List.of(new Group("Members", 0, 1), new Group("Immutable posted movements", 2, 8)), rows, false), "ledger-" + from + "-" + to, format, null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Download members(UUID actor, LocalDate asAt, String format) {
        requireExport(actor);
        if (!permissions.hasPermission(actor, "MEMBER_READ")) throw new SecurityException("Access denied");
        if (asAt == null || asAt.isBefore(LocalDate.of(2000, 1, 1)) || asAt.isAfter(today())) throw new IllegalArgumentException("Choose a valid date");
        List<List<Object>> rows = jdbc.query("SELECT member_number, full_name, status, join_date, join_date_basis FROM members WHERE join_date <= ? ORDER BY member_number, id LIMIT 5001",
            (row, ignored) -> List.<Object>of(row.getString("member_number"), row.getString("full_name"), row.getString("status"), ("SOURCE_BASELINE".equals(row.getString("join_date_basis")) ? "Source baseline " : "") + row.getDate("join_date").toString()), asAt);
        if (rows.size() > 5000) throw new IllegalArgumentException("Member directory is too large for one download");
        return download(actor, new Report("Member directory", "Joined through " + asAt + " | Current status | " + rows.size() + " members | Generated " + today(),
            List.of(new Column("Number", 20, false), new Column("Member", 45, false), new Column("Current status", 20, false), new Column("Joined", 20, false)),
            List.of(new Group("Public member directory fields", 0, 3)), rows, false), "members-" + asAt, format, null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Download investment(UUID actor, UUID id, String format) {
        requireExport(actor);
        var detail = investments.get(actor, id);
        var cycle = detail.investment();
        List<Column> columns = List.of(new Column("Number", 16, false), new Column("Member", 32, false),
            new Column("Participation", 17, true), new Column("Ratio (%)", 13, true), new Column("Cost share", 17, true),
            new Column("Expected maturity", 18, true), new Column("Expected income", 17, true), new Column("Expected closing", 18, true));
        List<List<Object>> rows = new ArrayList<>();
        for (var row : detail.allocations()) rows.add(List.of(row.memberNumber(), row.fullName(), row.participation(),
            row.ratioPercent(), row.costShare(), row.maturityValueShare(), row.incomeShare(), row.closingBalance()));
        rows.add(List.of("", "Total", detail.pool(), detail.pool().signum() == 0 ? BigDecimal.ZERO : new BigDecimal("100.00"),
            cycle.cost(), cycle.maturityValue(), detail.expectedIncome(), detail.expectedClosing()));
        Report report = new Report("Sheet A - Investment allocation", (cycle.institution() == null ? "Treasury bill" : clean(cycle.institution()))
            + " | " + cycle.status() + " | Revision " + cycle.revision() + " | " + cycle.purchaseDate() + " to " + cycle.maturityDate()
            + " | Cost less pool: GHS " + detail.costDifference().setScale(2).toPlainString() + " | Generated " + today(), columns,
            List.of(new Group("Members", 0, 1), new Group("Participation", 2, 3), new Group("Expected allocation (GHS)", 4, 7)), rows, true);
        return download(actor, report, "sheet-a-" + id, format, id);
    }

    private Download download(UUID actor, Report report, String stem, String format, UUID entity) {
        if (report.rows().size() > 5002) throw new IllegalArgumentException("Use a smaller report with at most 5,000 entries");
        byte[] bytes = renderer.render(report, format);
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'REPORT_EXPORTED', 'REPORT', ?, jsonb_build_object('report', ?, 'format', ?, 'rows', ?))
            """, actor, entity, stem, format, report.rows().size());
        return new Download(bytes, stem + "." + format, "pdf".equals(format) ? "application/pdf"
            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
    private void requireExport(UUID actor) {
        if (!permissions.hasPermission(actor, "REPORT_EXPORT")) throw new SecurityException("Access denied");
    }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Africa/Accra")); }
    private static String clean(String value) { return value.replaceAll("[\\p{Cntrl}]", " "); }
}
