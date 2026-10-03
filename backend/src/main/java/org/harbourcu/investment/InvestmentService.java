package org.harbourcu.investment;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.harbourcu.allocation.AllocationEngine;
import org.harbourcu.ledger.FiscalYearLock;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestmentService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    public record Create(String tenor, Integer customDays, String institution, String referenceNumber,
                         LocalDate purchaseDate, LocalDate maturityDate, String cost,
                         String maturityValue) {}
    public record Summary(UUID id, String tenor, String institution, String referenceNumber,
                          LocalDate purchaseDate, LocalDate maturityDate,
                          @JsonSerialize(using = ToStringSerializer.class) BigDecimal cost,
                          @JsonSerialize(using = ToStringSerializer.class) BigDecimal maturityValue,
                          @JsonSerialize(using = ToStringSerializer.class) BigDecimal actualProceeds,
                          String status) {}
    public record Allocation(UUID memberId, String memberNumber, String fullName,
                             @JsonSerialize(using = ToStringSerializer.class) BigDecimal participation,
                             @JsonSerialize(using = ToStringSerializer.class) BigDecimal ratioPercent,
                             @JsonSerialize(using = ToStringSerializer.class) BigDecimal costShare,
                             @JsonSerialize(using = ToStringSerializer.class) BigDecimal maturityValueShare,
                             @JsonSerialize(using = ToStringSerializer.class) BigDecimal incomeShare,
                             @JsonSerialize(using = ToStringSerializer.class) BigDecimal closingBalance) {}
    public record MaturityAllocation(UUID memberId,
                                     @JsonSerialize(using = ToStringSerializer.class) BigDecimal actualMaturityShare,
                                     @JsonSerialize(using = ToStringSerializer.class) BigDecimal actualIncomeShare) {}
    public record MaturityPreview(@JsonSerialize(using = ToStringSerializer.class) BigDecimal actualProceeds,
                                  @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalIncome,
                                  List<MaturityAllocation> allocations) {}
    public record Detail(Summary investment, List<Allocation> allocations,
                         List<MaturityAllocation> maturityAllocations,
                         @JsonSerialize(using = ToStringSerializer.class) BigDecimal pool,
                         @JsonSerialize(using = ToStringSerializer.class) BigDecimal costDifference,
                         @JsonSerialize(using = ToStringSerializer.class) BigDecimal expectedIncome,
                         @JsonSerialize(using = ToStringSerializer.class) BigDecimal expectedClosing) {}
    public record PendingApproval(UUID id, UUID investmentId, UUID makerId, String actionType,
                                  String institution, LocalDate purchaseDate,
                                  @JsonSerialize(using = ToStringSerializer.class) BigDecimal cost,
                                  @JsonSerialize(using = ToStringSerializer.class) BigDecimal maturityValue,
                                  @JsonSerialize(using = ToStringSerializer.class) BigDecimal actualProceeds) {}

    private record AvailableMember(UUID id, String number, String name, BigDecimal available) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final FiscalYearLock fiscalYears;

    public InvestmentService(JdbcTemplate jdbc, PermissionService permissions, FiscalYearLock fiscalYears) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.fiscalYears = fiscalYears;
    }

    public List<Summary> list(UUID actor) {
        require(actor, "INVESTMENT_READ");
        return jdbc.query("""
            SELECT id, tenor, institution, reference_number, purchase_date, maturity_date,
                   cost, maturity_value, actual_proceeds, status FROM investments
            ORDER BY purchase_date DESC, created_at DESC LIMIT 100
            """, (row, ignored) -> summary(row));
    }

    public List<PendingApproval> pendingApprovals(UUID actor) {
        require(actor, "APPROVE");
        require(actor, "INVESTMENT_READ");
        return jdbc.query("""
            SELECT r.id, i.id AS investment_id, r.made_by, r.action_type, i.institution,
                   i.purchase_date, i.cost, i.maturity_value, r.payload ->> 'actualProceeds' AS actual_proceeds
            FROM approval_requests r
            JOIN investments i ON i.id::text = r.payload ->> 'investmentId'
            WHERE r.action_type IN ('INVESTMENT_POST', 'INVESTMENT_MATURE') AND r.status = 'PENDING'
            ORDER BY r.created_at LIMIT 100
            """, (row, ignored) -> new PendingApproval(row.getObject("id", UUID.class),
                row.getObject("investment_id", UUID.class), row.getObject("made_by", UUID.class),
                row.getString("action_type"),
                row.getString("institution"), row.getDate("purchase_date").toLocalDate(),
                row.getBigDecimal("cost"), row.getBigDecimal("maturity_value"),
                row.getString("actual_proceeds") == null ? null
                    : new BigDecimal(row.getString("actual_proceeds"))));
    }

    public void requireInvestmentApproval(UUID requestId) {
        Boolean valid = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM approval_requests
                WHERE id = ? AND action_type IN ('INVESTMENT_POST', 'INVESTMENT_MATURE'))
            """, Boolean.class, requestId);
        if (!Boolean.TRUE.equals(valid)) throw new IllegalArgumentException("Investment approval not found");
    }

    public Detail get(UUID actor, UUID investmentId) {
        require(actor, "INVESTMENT_READ");
        List<Summary> investments = jdbc.query("""
            SELECT id, tenor, institution, reference_number, purchase_date, maturity_date,
                   cost, maturity_value, actual_proceeds, status FROM investments WHERE id = ?
            """, (row, ignored) -> summary(row), investmentId);
        if (investments.isEmpty()) throw new IllegalArgumentException("Investment not found");
        List<Allocation> rows = jdbc.query("""
            SELECT a.member_id, m.member_number, m.full_name, a.participation,
                   a.ratio_basis_points, a.cost_share, a.maturity_value_share,
                   a.income_share, a.closing_balance
            FROM investment_allocations a JOIN members m ON m.id = a.member_id
            WHERE a.investment_id = ? ORDER BY m.member_number, m.id
            """, (row, ignored) -> new Allocation(row.getObject("member_id", UUID.class),
                row.getString("member_number"), row.getString("full_name"),
                row.getBigDecimal("participation"),
                BigDecimal.valueOf(row.getInt("ratio_basis_points"), 2),
                row.getBigDecimal("cost_share"), row.getBigDecimal("maturity_value_share"),
                row.getBigDecimal("income_share"), row.getBigDecimal("closing_balance")), investmentId);
        BigDecimal pool = rows.stream().map(Allocation::participation).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MaturityAllocation> actual = jdbc.query("""
            SELECT member_id, actual_maturity_share, actual_income_share
            FROM investment_maturity_allocations WHERE investment_id = ?
            """, (row, ignored) -> new MaturityAllocation(row.getObject("member_id", UUID.class),
                row.getBigDecimal("actual_maturity_share"), row.getBigDecimal("actual_income_share")),
            investmentId);
        BigDecimal income = investments.getFirst().maturityValue().subtract(investments.getFirst().cost());
        return new Detail(investments.getFirst(), rows, actual, pool,
            investments.getFirst().cost().subtract(pool), income, pool.add(income));
    }

    public MaturityPreview previewMaturity(UUID actor, UUID investmentId, String proceedsText) {
        require(actor, "INVESTMENT_READ");
        BigDecimal proceeds = money(proceedsText, false);
        List<BigDecimal> costs = jdbc.query("SELECT cost FROM investments WHERE id = ? AND status = 'POSTED'",
            (row, ignored) -> row.getBigDecimal("cost"), investmentId);
        if (costs.isEmpty()) throw new IllegalArgumentException("Posted investment not found");
        var rows = jdbc.query("""
            SELECT a.member_id, a.participation, a.cost_share
            FROM investment_allocations a JOIN members m ON m.id = a.member_id
            WHERE a.investment_id = ? ORDER BY m.member_number, m.id
            """, (row, ignored) -> new PreviewRow(row.getObject("member_id", UUID.class),
                row.getBigDecimal("participation"), row.getBigDecimal("cost_share")), investmentId);
        if (rows.isEmpty()) throw new IllegalStateException("Investment has no allocations");
        List<AllocationEngine.Participant> participants = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            participants.add(new AllocationEngine.Participant(i + 1,
                AllocationEngine.toPesewas(rows.get(i).participation())));
        }
        var shares = AllocationEngine.allocate(participants,
            AllocationEngine.toPesewas(costs.getFirst()), AllocationEngine.toPesewas(proceeds));
        List<MaturityAllocation> preview = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            BigDecimal share = AllocationEngine.toCedis(shares.get(i).maturityValuePesewas());
            preview.add(new MaturityAllocation(rows.get(i).memberId(), share,
                share.subtract(rows.get(i).costShare())));
        }
        return new MaturityPreview(proceeds, proceeds.subtract(costs.getFirst()), List.copyOf(preview));
    }

    private record PreviewRow(UUID memberId, BigDecimal participation, BigDecimal costShare) {}

    @Transactional
    public UUID create(UUID actor, Create input) {
        require(actor, "INVESTMENT_WRITE");
        if (input == null || input.purchaseDate() == null || input.maturityDate() == null
            || !input.purchaseDate().equals(LocalDate.now(BUSINESS_ZONE))
            || !input.maturityDate().isAfter(input.purchaseDate())) {
            throw new IllegalArgumentException("Purchase date must be today and maturity must follow it");
        }
        if (input.tenor() == null
            || !List.of("91_DAY", "182_DAY", "364_DAY", "CUSTOM").contains(input.tenor())
            || ("CUSTOM".equals(input.tenor()) != (input.customDays() != null))
            || (input.customDays() != null && input.customDays() < 1)) {
            throw new IllegalArgumentException("Choose a valid tenor and custom day count");
        }
        if ((input.institution() != null && input.institution().length() > 200)
            || (input.referenceNumber() != null && input.referenceNumber().length() > 100)) {
            throw new IllegalArgumentException("Institution or reference is too long");
        }
        BigDecimal cost = money(input.cost(), true);
        BigDecimal maturityValue = money(input.maturityValue(), false);
        fiscalYears.requireOpen(input.purchaseDate().getYear());
        List<AvailableMember> members = available(input.purchaseDate(), null);
        if (members.isEmpty()) throw new IllegalArgumentException("No members have uninvested funds today");
        List<AllocationEngine.Participant> participants = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            participants.add(new AllocationEngine.Participant(i + 1,
                AllocationEngine.toPesewas(members.get(i).available())));
        }
        List<AllocationEngine.Share> shares = AllocationEngine.allocate(participants,
            AllocationEngine.toPesewas(cost), AllocationEngine.toPesewas(maturityValue));
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO investments (id, tenor, custom_days, institution, reference_number,
                                     purchase_date, maturity_date, cost, maturity_value, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, input.tenor(), input.customDays(), blankToNull(input.institution()),
            blankToNull(input.referenceNumber()), input.purchaseDate(), input.maturityDate(),
            cost, maturityValue, actor);
        for (int i = 0; i < members.size(); i++) {
            AllocationEngine.Share share = shares.get(i);
            jdbc.update("""
                INSERT INTO investment_allocations
                    (investment_id, member_id, participation, ratio_basis_points, cost_share,
                     maturity_value_share, income_share, closing_balance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, members.get(i).id(), AllocationEngine.toCedis(share.participationPesewas()),
                share.ratioBasisPoints(), AllocationEngine.toCedis(share.costPesewas()),
                AllocationEngine.toCedis(share.maturityValuePesewas()),
                AllocationEngine.toCedis(share.incomePesewas()),
                AllocationEngine.toCedis(share.closingBalancePesewas()));
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'INVESTMENT_DRAFT_CREATED', 'INVESTMENT', ?,
                    jsonb_build_object('participants', ?))
            """, actor, id, members.size());
        return id;
    }

    @Transactional
    public void editParticipation(UUID actor, UUID investmentId, UUID memberId,
                                  String amountText, String reason) {
        require(actor, "INVESTMENT_WRITE");
        BigDecimal amount = money(amountText, false);
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("A short reason for the participation change is required");
        }
        var draft = jdbc.query("""
            SELECT status, created_by, purchase_date, cost, maturity_value
            FROM investments WHERE id = ? FOR UPDATE
            """, (row, ignored) -> new EditableDraft(row.getString("status"),
                row.getObject("created_by", UUID.class), row.getDate("purchase_date").toLocalDate(),
                row.getBigDecimal("cost"), row.getBigDecimal("maturity_value")), investmentId);
        if (draft.isEmpty() || !"DRAFT".equals(draft.getFirst().status())
            || !actor.equals(draft.getFirst().creator())) {
            throw new IllegalArgumentException("Only the creator can edit an unsubmitted draft");
        }
        if (!draft.getFirst().purchaseDate().equals(LocalDate.now(BUSINESS_ZONE))) {
            throw new IllegalStateException("Draft purchase date has passed; create a new draft");
        }
        Boolean pending = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM approval_requests WHERE action_type = 'INVESTMENT_POST'
                AND status = 'PENDING' AND payload ->> 'investmentId' = ?)
            """, Boolean.class, investmentId.toString());
        if (Boolean.TRUE.equals(pending)) throw new IllegalStateException("Draft awaits approval");
        var available = new java.util.HashMap<UUID, BigDecimal>();
        for (AvailableMember member : available(draft.getFirst().purchaseDate(), investmentId)) {
            available.put(member.id(), member.available());
        }
        if (amount.signum() > 0 && (!available.containsKey(memberId)
            || amount.compareTo(available.get(memberId)) > 0)) {
            throw new IllegalArgumentException("Participation exceeds this member's uninvested funds");
        }
        List<EditableRow> rows = jdbc.query("""
            SELECT a.member_id, m.member_number, a.participation, a.edit_reason
            FROM investment_allocations a JOIN members m ON m.id = a.member_id
            WHERE a.investment_id = ? ORDER BY m.member_number, m.id
            """, (row, ignored) -> new EditableRow(row.getObject("member_id", UUID.class),
                row.getString("member_number"), row.getBigDecimal("participation"),
                row.getString("edit_reason")), investmentId);
        if (rows.stream().noneMatch(row -> row.memberId().equals(memberId))) {
            throw new IllegalArgumentException("Member is not in this draft");
        }
        List<EditableRow> revised = rows.stream().map(row -> row.memberId().equals(memberId)
            ? new EditableRow(row.memberId(), row.number(), amount, reason.trim()) : row)
            .filter(row -> row.participation().signum() > 0).toList();
        if (revised.isEmpty()) throw new IllegalArgumentException("At least one member must participate");
        List<AllocationEngine.Participant> participants = new ArrayList<>();
        for (int i = 0; i < revised.size(); i++) {
            participants.add(new AllocationEngine.Participant(i + 1,
                AllocationEngine.toPesewas(revised.get(i).participation())));
        }
        List<AllocationEngine.Share> shares = AllocationEngine.allocate(participants,
            AllocationEngine.toPesewas(draft.getFirst().cost()),
            AllocationEngine.toPesewas(draft.getFirst().maturityValue()));
        jdbc.update("DELETE FROM investment_allocations WHERE investment_id = ?", investmentId);
        for (int i = 0; i < revised.size(); i++) {
            var row = revised.get(i);
            var share = shares.get(i);
            jdbc.update("""
                INSERT INTO investment_allocations
                    (investment_id, member_id, participation, ratio_basis_points, cost_share,
                     maturity_value_share, income_share, closing_balance, edit_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, investmentId, row.memberId(), row.participation(), share.ratioBasisPoints(),
                AllocationEngine.toCedis(share.costPesewas()),
                AllocationEngine.toCedis(share.maturityValuePesewas()),
                AllocationEngine.toCedis(share.incomePesewas()),
                AllocationEngine.toCedis(share.closingBalancePesewas()), row.reason());
        }
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details)
            VALUES (?, 'INVESTMENT_PARTICIPATION_EDITED', 'INVESTMENT', ?,
                    jsonb_build_object('memberId', ?::text, 'reason', ?))
            """, actor, investmentId, memberId.toString(), reason.trim());
    }

    private record EditableDraft(String status, UUID creator, LocalDate purchaseDate,
                                 BigDecimal cost, BigDecimal maturityValue) {}
    private record EditableRow(UUID memberId, String number, BigDecimal participation, String reason) {}

    List<AvailableMember> available(LocalDate date, UUID excludeInvestment) {
        return jdbc.query("""
            SELECT m.id, m.member_number, m.full_name,
                   coalesce(l.equity, 0) - coalesce(a.committed, 0) AS available
            FROM members m
            LEFT JOIN LATERAL (
                SELECT sum(amount) AS equity FROM ledger_entries
                WHERE member_id = m.id AND effective_date <= ?
            ) l ON true
            LEFT JOIN LATERAL (
                SELECT sum(ia.participation) AS committed
                FROM investment_allocations ia JOIN investments i ON i.id = ia.investment_id
                WHERE ia.member_id = m.id AND i.status = 'POSTED'
                  AND i.purchase_date <= ?
                  AND (?::uuid IS NULL OR i.id <> ?::uuid)
            ) a ON true
            WHERE m.status = 'ACTIVE'
              AND coalesce(l.equity, 0) - coalesce(a.committed, 0) > 0
            ORDER BY m.member_number, m.id
            """, (row, ignored) -> new AvailableMember(row.getObject("id", UUID.class),
                row.getString("member_number"), row.getString("full_name"),
                row.getBigDecimal("available")), date, date,
            excludeInvestment, excludeInvestment);
    }

    void assertDraftCanPost(UUID investmentId, LocalDate date) {
        List<AvailableMember> available = available(date, investmentId);
        var amounts = new java.util.HashMap<UUID, BigDecimal>();
        for (AvailableMember member : available) amounts.put(member.id(), member.available());
        List<Boolean> valid = jdbc.query("""
            SELECT member_id, participation FROM investment_allocations WHERE investment_id = ?
            """, (row, ignored) -> amounts.getOrDefault(row.getObject("member_id", UUID.class),
                BigDecimal.ZERO).compareTo(row.getBigDecimal("participation")) >= 0, investmentId);
        if (valid.isEmpty() || valid.contains(false)) {
            throw new IllegalStateException("Member uninvested funds changed; recreate the draft before approval");
        }
    }

    void require(UUID actor, String code) {
        if (!permissions.hasPermission(actor, code)) throw new SecurityException("Access denied");
    }

    static BigDecimal money(String value, boolean positive) {
        if (value == null || !value.matches("[0-9]+(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Use a GHS amount with at most two decimals");
        }
        BigDecimal amount = new BigDecimal(value);
        if ((positive && amount.signum() <= 0) || amount.precision() - amount.scale() > 15) {
            throw new IllegalArgumentException("Amount is outside the allowed range");
        }
        return amount;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Summary summary(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Summary(row.getObject("id", UUID.class), row.getString("tenor"),
            row.getString("institution"), row.getString("reference_number"),
            row.getDate("purchase_date").toLocalDate(), row.getDate("maturity_date").toLocalDate(),
            row.getBigDecimal("cost"), row.getBigDecimal("maturity_value"),
            row.getBigDecimal("actual_proceeds"), row.getString("status"));
    }
}
