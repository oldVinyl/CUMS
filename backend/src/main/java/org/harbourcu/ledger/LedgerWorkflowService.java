package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerWorkflowService {
    public record Entry(UUID id, UUID memberId, String memberNumber, String fullName, String type, String component,
                        String amount, LocalDate effectiveDate, String reference, String narration, UUID reversalOf,
                        boolean reversed, boolean canReverse) {}
    public record Page(List<Entry> entries, int page, boolean hasNext) {}
    public record Pending(UUID id, UUID makerId, String actionType, JsonNode payload, String memberNumber,
                          String fullName, String originalAmount, int requiredApprovals, int approvals) {}
    public record Year(int year, String status) {}
    public record Snapshot(String memberNumber, String fullName, String principal, String income,
                           String equity, int revision, String ledgerEquity, boolean reconciled) {}
    public record SnapshotPage(List<Snapshot> entries, int page, boolean hasNext) {}
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final ApprovalService approvals;
    private final ObjectMapper json;
    public LedgerWorkflowService(JdbcTemplate jdbc, PermissionService permissions, ApprovalService approvals, ObjectMapper json) {
        this.jdbc = jdbc; this.permissions = permissions; this.approvals = approvals; this.json = json;
    }

    public Page entries(UUID actor, LocalDate from, LocalDate to, UUID member, int page) {
        require(actor, "LEDGER_READ");
        if (from == null || to == null || from.isBefore(LocalDate.of(2000, 1, 1)) || to.isAfter(today()) || from.isAfter(to) || page < 0 || page > 10000) {
            throw new IllegalArgumentException("Choose a valid ledger range and page");
        }
        boolean canReverse = permissions.hasPermission(actor, "LEDGER_REVERSE");
        List<Entry> entries = jdbc.query("""
            SELECT l.*, m.member_number, m.full_name,
                EXISTS (SELECT 1 FROM ledger_entries r WHERE r.reversal_of = l.id) AS reversed
            FROM ledger_entries l JOIN members m ON m.id = l.member_id
            WHERE l.effective_date BETWEEN ? AND ? AND (?::uuid IS NULL OR l.member_id = ?::uuid)
            ORDER BY l.effective_date DESC, l.recorded_at DESC, l.id DESC LIMIT 51 OFFSET ?
            """, (row, ignored) -> new Entry(row.getObject("id", UUID.class), row.getObject("member_id", UUID.class),
                row.getString("member_number"), row.getString("full_name"), row.getString("type"), row.getString("component"),
                row.getBigDecimal("amount").toPlainString(), row.getDate("effective_date").toLocalDate(), row.getString("reference"),
                row.getString("narration"), row.getObject("reversal_of", UUID.class), row.getBoolean("reversed"),
                canReverse && !row.getBoolean("reversed") && row.getDate("effective_date").toLocalDate().equals(today())
                    && List.of("SEED", "ADDITION").contains(row.getString("type")) && row.getObject("payment_id") == null
                    && row.getObject("investment_id") == null), from, to, member, member, page * 50);
        return new Page(entries.size() > 50 ? entries.subList(0, 50) : entries, page, entries.size() > 50);
    }

    public List<Year> years(UUID actor) {
        require(actor, "LEDGER_READ");
        return jdbc.query("SELECT year, status FROM fiscal_years WHERE year <= ? ORDER BY year DESC",
            (row, ignored) -> new Year(row.getInt("year"), row.getString("status")), today().getYear());
    }

    public SnapshotPage snapshots(UUID actor, int year, int page) {
        require(actor, "LEDGER_READ");
        if (year < 2000 || year > today().getYear() || page < 0 || page > 10000) throw new IllegalArgumentException("Choose a valid fiscal year and page");
        List<Snapshot> rows = jdbc.query("""
            SELECT m.member_number,m.full_name,s.principal,s.income,s.equity,s.revision,
              coalesce((SELECT sum(l.amount) FROM ledger_entries l WHERE l.member_id=m.id AND l.effective_date<=make_date(?,12,31)),0) ledger_equity,
              s.principal=coalesce((SELECT sum(l.amount) FROM ledger_entries l WHERE l.member_id=m.id AND l.component='PRINCIPAL' AND l.effective_date<=make_date(?,12,31)),0)
              AND s.income=coalesce((SELECT sum(l.amount) FROM ledger_entries l WHERE l.member_id=m.id AND l.component='INCOME' AND l.effective_date<=make_date(?,12,31)),0) reconciled
            FROM fiscal_year_snapshots s JOIN members m ON m.id=s.member_id WHERE s.fiscal_year=?
            ORDER BY m.member_number,m.id LIMIT 51 OFFSET ?
            """, (r,n) -> new Snapshot(r.getString("member_number"),r.getString("full_name"),r.getBigDecimal("principal").toPlainString(),
                r.getBigDecimal("income").toPlainString(),r.getBigDecimal("equity").toPlainString(),r.getInt("revision"),r.getBigDecimal("ledger_equity").toPlainString(),r.getBoolean("reconciled")),year,year,year,year,page*50);
        return new SnapshotPage(rows.stream().limit(50).toList(),page,rows.size()>50);
    }

    public List<Pending> pending(UUID actor) {
        require(actor, "LEDGER_READ");
        boolean checker = permissions.hasPermission(actor, "APPROVE");
        return jdbc.query("""
            SELECT r.id, r.made_by, r.action_type, r.payload::text, r.required_approvals,
                m.member_number, m.full_name, l.amount::text AS original_amount,
                (SELECT count(*) FROM approval_decisions d WHERE d.request_id = r.id AND d.decision = 'APPROVE') AS approvals
            FROM approval_requests r
            LEFT JOIN ledger_entries l ON l.id::text = r.payload ->> 'entryId'
            LEFT JOIN members m ON m.id::text = r.payload ->> 'memberId' OR m.id = l.member_id
            WHERE r.status = 'PENDING' AND r.action_type IN ('LEDGER_POST', 'LEDGER_BACKDATED_POST', 'LEDGER_REVERSE', 'FISCAL_YEAR_CLOSE', 'PERIOD_UNLOCK')
                AND (? OR r.made_by = ?) ORDER BY r.created_at LIMIT 100
            """, (row, ignored) -> new Pending(row.getObject("id", UUID.class), row.getObject("made_by", UUID.class),
                row.getString("action_type"), tree(row.getString("payload")), row.getString("member_number"),
                row.getString("full_name"), row.getString("original_amount"), row.getInt("required_approvals"), row.getInt("approvals")), checker, actor);
    }

    @Transactional public UUID contribution(UUID actor, UUID member, String type, String amount) {
        require(actor, "LEDGER_POST");
        if (member == null) throw new IllegalArgumentException("Choose a member");
        return approvals.submit("LEDGER_POST", json.createObjectNode().put("memberId", member.toString()).put("type", type).put("amount", amount), actor, 1);
    }
    @Transactional public UUID reverse(UUID actor, UUID entry, String reason) {
        require(actor, "LEDGER_REVERSE");
        jdbc.queryForList("SELECT id FROM ledger_entries WHERE id = ? FOR UPDATE", entry);
        Boolean pending = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM approval_requests WHERE action_type = 'LEDGER_REVERSE' AND status = 'PENDING' AND payload ->> 'entryId' = ?)", Boolean.class, entry.toString());
        if (Boolean.TRUE.equals(pending)) throw new IllegalStateException("This reversal already awaits approval");
        return approvals.submit("LEDGER_REVERSE", json.createObjectNode().put("entryId", entry.toString()).put("reason", reason), actor, 1);
    }
    @Transactional public UUID close(UUID actor, int year) {
        require(actor, "FISCAL_YEAR_CLOSE");
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        jdbc.queryForList("SELECT year FROM fiscal_years WHERE year = ? FOR UPDATE", year);
        Boolean pending = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM approval_requests WHERE action_type = 'FISCAL_YEAR_CLOSE' AND status = 'PENDING' AND payload ->> 'year' = ?)", Boolean.class, Integer.toString(year));
        if (Boolean.TRUE.equals(pending)) throw new IllegalStateException("This year already awaits close approval");
        return approvals.submit("FISCAL_YEAR_CLOSE", json.createObjectNode().put("year", year), actor, 1);
    }
    @Transactional public UUID unlock(UUID actor, int year, String reason) {
        require(actor, "PERIOD_UNLOCK");
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        jdbc.queryForList("SELECT year FROM fiscal_years WHERE year = ? FOR UPDATE", year);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE action_type='PERIOD_UNLOCK' AND status='PENDING' AND payload->>'year'=?)", Boolean.class, Integer.toString(year)))) {
            throw new IllegalStateException("This year already awaits unlock approval");
        }
        return approvals.submit("PERIOD_UNLOCK", json.createObjectNode().put("year", year).put("reason", reason == null ? null : reason.trim()), actor, 1);
    }
    @Transactional public void decide(UUID actor, UUID id, String decision, String reason) {
        require(actor, "LEDGER_READ"); require(actor, "APPROVE"); requireRequest(id);
        if (decision == null) throw new IllegalArgumentException("Choose a decision");
        approvals.decide(id, actor, ApprovalService.Decision.valueOf(decision), reason);
    }
    @Transactional public void cancel(UUID actor, UUID id) {
        require(actor, "LEDGER_READ"); requireRequest(id); approvals.cancel(id, actor);
    }
    private void requireRequest(UUID id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM approval_requests WHERE id = ? AND action_type IN ('LEDGER_POST', 'LEDGER_BACKDATED_POST', 'LEDGER_REVERSE', 'FISCAL_YEAR_CLOSE', 'PERIOD_UNLOCK'))", Boolean.class, id))) {
            throw new IllegalArgumentException("Ledger approval not found");
        }
    }
    private JsonNode tree(String value) {
        try { return json.readTree(value); } catch (java.io.IOException exception) { throw new IllegalStateException("Stored approval is invalid", exception); }
    }
    private void require(UUID actor, String permission) { if (!permissions.hasPermission(actor, permission)) throw new SecurityException("Access denied"); }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Africa/Accra")); }
}
