package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.harbourcu.allocation.AllocationEngine;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, checksum-bound historical posting and chronological allocation corrections. */
@Service
public class BackdatedContributionService {
  public record Input(
      UUID memberId,
      String type,
      String amount,
      LocalDate effectiveDate,
      String mode,
      LocalDate nextCycleDate,
      String reason,
      String sourceFingerprint) {}

  public record Share(
      UUID memberId,
      String memberNumber,
      String oldParticipation,
      String participation,
      String costShare,
      String expectedIncome,
      String oldActualIncome,
      String actualIncome) {}

  public record Impact(
      UUID id,
      int revision,
      String status,
      LocalDate purchaseDate,
      LocalDate maturityDate,
      List<Share> shares) {}

  public record Preview(
      String sourceFingerprint,
      LocalDate earliestNextCycleDate,
      boolean repostAllowed,
      String restriction,
      List<Impact> affectedCycles) {}

  private record Cycle(
      UUID id,
      int revision,
      String status,
      LocalDate purchase,
      LocalDate maturity,
      BigDecimal cost,
      BigDecimal expected,
      BigDecimal actual,
      UUID source,
      String policy) {}

  private record Basis(UUID member, long participation, long actualIncome, String editReason) {}

  private record Planned(
      Cycle cycle,
      List<Basis> basis,
      List<UUID> members,
      List<AllocationEngine.Share> expected,
      List<AllocationEngine.Share> actual) {}

  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final ObjectMapper json;
  private final FiscalYearLock years;
  private final FiscalSnapshotService snapshots;

  public BackdatedContributionService(
      JdbcTemplate jdbc,
      PermissionService permissions,
      ObjectMapper json,
      FiscalYearLock years,
      FiscalSnapshotService snapshots) {
    this.jdbc = jdbc;
    this.permissions = permissions;
    this.json = json;
    this.years = years;
    this.snapshots = snapshots;
  }

  @Transactional(readOnly = true)
  public Preview preview(UUID actor, Input input) {
    validate(actor, input, false);
    List<Cycle> cycles = cycles(input.effectiveDate());
    String restriction = restriction(cycles, input.effectiveDate());
    LocalDate earliest = earliest(cycles, input.effectiveDate());
    List<Impact> impacts = new ArrayList<>();
    if (restriction == null) {
      try {
        for (var p : plan(input, cycles)) impacts.add(impact(p));
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
        restriction =
            "Cycle recomputation requires individual review. Future-only posting remains available.";
        impacts.clear();
      }
    }
    if (restriction != null)
      for (var c : cycles)
        impacts.add(
            new Impact(c.id(), c.revision(), c.status(), c.purchase(), c.maturity(), List.of()));

    return new Preview(
        fingerprint(),
        earliest,
        restriction == null
            && permissions.hasPermission(actor, "CYCLE_REPOST")
            && permissions.hasPermission(actor, "INVESTMENT_WRITE"),
        restriction,
        List.copyOf(impacts));
  }

  @Transactional
  public UUID submit(UUID actor, Input input, ApprovalService approvals) {
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
    check(actor, input);
    var payload = json.valueToTree(input);
    return approvals.submit("LEDGER_BACKDATED_POST", payload, actor, 1);
  }

  @Transactional(readOnly = true)
  public Preview review(UUID actor, UUID request) {
    require(actor, "LEDGER_READ");
    var rows =
        jdbc.queryForList(
            "SELECT made_by,payload::text FROM approval_requests WHERE id=? AND action_type='LEDGER_BACKDATED_POST' AND status='PENDING'",
            request);
    if (rows.isEmpty())
      throw new IllegalArgumentException("Pending historical contribution not found.");
    UUID maker = (UUID) rows.getFirst().get("made_by");
    if (!maker.equals(actor) && !permissions.hasPermission(actor, "APPROVE"))
      throw new SecurityException("Review access required.");
    Input input;
    try {
      input = json.readValue((String) rows.getFirst().get("payload"), Input.class);
    } catch (Exception e) {
      throw new IllegalStateException("Stored historical request is invalid.");
    }
    check(maker, input);
    return preview(maker, input);
  }

  public void check(UUID actor, Input input) {
    validate(actor, input, true);
    if (!fingerprint().equals(input.sourceFingerprint()))
      throw new IllegalStateException(
          "Financial source changed. Run and review a fresh impact preview.");
    List<Cycle> cycles = cycles(input.effectiveDate());
    if ("REPOST".equals(input.mode())) {
      require(actor, "CYCLE_REPOST");
      require(actor, "INVESTMENT_WRITE");
      String restriction = restriction(cycles, input.effectiveDate());
      if (restriction != null) throw new IllegalStateException(restriction);
      plan(input, cycles); // All exact allocation arithmetic is validated before any write.
    } else if ("FUTURE_ONLY".equals(input.mode())) {
      LocalDate date = input.nextCycleDate();
      if (date == null
          || date.isAfter(today())
          || date.isBefore(earliest(cycles, input.effectiveDate())))
        throw new IllegalArgumentException(
            "Choose the next cycle's start date after every affected purchase and no later than today. If that start is in the future, wait until it arrives.");
      ensureOpen(date.getYear());
    } else throw new IllegalArgumentException("Choose REPOST or FUTURE_ONLY explicitly.");
  }

  public void post(UUID request, UUID maker, UUID checker, Input input) {
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
    check(maker, input);
    List<Planned> planned =
        "REPOST".equals(input.mode()) ? plan(input, cycles(input.effectiveDate())) : List.of();
    LocalDate effective =
        "FUTURE_ONLY".equals(input.mode()) ? input.nextCycleDate() : input.effectiveDate();
    years.requireOpen(effective.getYear());
    for (var p : planned) {
      years.requireOpen(p.cycle().purchase().getYear());
      if (p.cycle().actual() != null) years.requireOpen(p.cycle().maturity().getYear());
    }
    jdbc.update(
        """
            INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference,narration,original_effective_date,created_by,approved_by)
            VALUES(?,?,'PRINCIPAL',?,?,?,?,?,?,?,?)
            """,
        input.memberId(),
        input.type(),
        new BigDecimal(input.amount()),
        effective,
        effective.getYear(),
        "APR-" + request,
        input.reason(),
        input.effectiveDate(),
        maker,
        checker);
    for (var p : planned) {
      var c = p.cycle();
      jdbc.update(
          """
                INSERT INTO investment_revision_history(investment_id,revision,snapshot,reason,approval_request_id)
                SELECT i.id,i.revision,jsonb_build_object('investment',to_jsonb(i),'allocations',
                 (SELECT jsonb_agg(to_jsonb(a)) FROM investment_allocations a WHERE a.investment_id=i.id),'maturityAllocations',
                 (SELECT jsonb_agg(to_jsonb(a)) FROM investment_maturity_allocations a WHERE a.investment_id=i.id AND a.revision=i.revision)),?,?
                FROM investments i WHERE id=?
                """,
          input.reason(),
          request,
          c.id());
      jdbc.update(
          """
                INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,investment_id,reference,narration,original_effective_date,reversal_of,created_by,approved_by)
                SELECT l.member_id,'REVERSAL',l.component,-l.amount,l.effective_date,l.fiscal_year,l.investment_id,
                 'BDR-'||?::text||'-'||l.id::text,?,l.effective_date,l.id,?,? FROM ledger_entries l
                WHERE l.investment_id=? AND l.type='INCOME_ALLOCATION' AND NOT EXISTS(SELECT 1 FROM ledger_entries r WHERE r.reversal_of=l.id)
                """,
          request.toString(),
          input.reason(),
          maker,
          checker,
          c.id());
      jdbc.update(
          "UPDATE investments SET status='DRAFT',actual_proceeds=NULL,revision=revision+1 WHERE id=?",
          c.id());
      jdbc.update("DELETE FROM investment_allocations WHERE investment_id=?", c.id());
      for (int i = 0; i < p.members().size(); i++) {
        UUID member = p.members().get(i);
        var share = p.expected().get(i);
        jdbc.update(
            """
                    INSERT INTO investment_allocations(investment_id,member_id,participation,ratio_basis_points,cost_share,maturity_value_share,income_share,closing_balance,edit_reason)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """,
            c.id(),
            member,
            cedis(share.participationPesewas()),
            share.ratioBasisPoints(),
            cedis(share.costPesewas()),
            cedis(share.maturityValuePesewas()),
            cedis(share.incomePesewas()),
            cedis(share.closingBalancePesewas()),
            p.basis().stream()
                .filter(b -> b.member().equals(member))
                .map(Basis::editReason)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
        if (c.actual() != null) {
          var actual = p.actual().get(i);
          jdbc.update(
              "INSERT INTO investment_maturity_allocations(investment_id,revision,member_id,actual_maturity_share,actual_income_share) VALUES(?,?,?,?,?)",
              c.id(),
              c.revision() + 1,
              member,
              cedis(actual.maturityValuePesewas()),
              cedis(actual.incomePesewas()));
          if (actual.incomePesewas() != 0)
            jdbc.update(
                """
                        INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,investment_id,reference,narration,created_by,approved_by)
                        VALUES(?,'INCOME_ALLOCATION','INCOME',?,?,?,?,?,?,?,?)
                        """,
                member,
                cedis(actual.incomePesewas()),
                c.maturity(),
                c.maturity().getYear(),
                c.id(),
                "BDM-" + request + "-" + c.id() + "-" + member,
                input.reason(),
                maker,
                checker);
        }
      }
      jdbc.update(
          "UPDATE investments SET status=?,actual_proceeds=?,updated_at=now() WHERE id=?",
          c.status(),
          c.actual(),
          c.id());
      jdbc.update(
          "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'INVESTMENT_HISTORICAL_REPOST','INVESTMENT',?,jsonb_build_object('beforeRevision',?,'afterRevision',?,'approvalRequestId',?::text))",
          checker,
          c.id(),
          c.revision(),
          c.revision() + 1,
          request.toString());
    }
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM investment_participation p WHERE p.invested>0 AND p.invested>coalesce((SELECT sum(amount) FROM ledger_entries l WHERE l.member_id=p.member_id AND l.effective_date<=current_date),0))",
            Boolean.class)))
      throw new IllegalStateException(
          "Corrected income is committed to an investment. Review its participation before posting.");
    snapshots.recomputeFrom(effective.getYear(), checker, request, false);
    jdbc.update(
        "INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'BACKDATED_CONTRIBUTION_POSTED','MEMBER',?,jsonb_build_object('mode',?::text,'originalDate',?::text,'effectiveDate',?::text,'approvalRequestId',?::text))",
        checker,
        input.memberId(),
        input.mode(),
        input.effectiveDate().toString(),
        effective.toString(),
        request.toString());
  }

  private List<Planned> plan(Input input, List<Cycle> cycles) {
    List<Planned> result = new ArrayList<>();
    for (var c : cycles) {
      List<Basis> original =
          jdbc.query(
              """
                SELECT a.member_id,a.participation,a.edit_reason,coalesce(ma.actual_income_share,0) actual_income
                FROM investment_allocations a JOIN members m ON m.id=a.member_id
                LEFT JOIN investment_maturity_allocations ma ON ma.investment_id=a.investment_id AND ma.member_id=a.member_id AND ma.revision=?
                WHERE a.investment_id=? ORDER BY m.member_number,m.id
                """,
              (r, n) ->
                  new Basis(
                      r.getObject("member_id", UUID.class),
                      pesewas(r.getBigDecimal("participation")),
                      signedPesewas(r.getBigDecimal("actual_income")),
                      r.getString("edit_reason")),
              c.revision(),
              c.id());
      var basis = new LinkedHashMap<UUID, Basis>();
      for (var b : original) basis.put(b.member(), b);
      if (!basis.containsKey(input.memberId()) && c.source() == null) {
        BigDecimal prior =
            jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entries WHERE member_id=? AND effective_date<=?",
                BigDecimal.class,
                input.memberId(),
                c.purchase());
        boolean excluded =
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM audit_log WHERE entity_id=? AND action='INVESTMENT_PARTICIPATION_EDITED' AND details->>'memberId'=?)",
                    Boolean.class,
                    c.id(),
                    input.memberId().toString()));
        if (prior.signum() == 0 && !excluded)
          basis.put(input.memberId(), new Basis(input.memberId(), 0, 0, null));
      }
      List<UUID> ids = new ArrayList<>();
      List<AllocationEngine.Participant> participants = new ArrayList<>();
      Map<UUID, String> memberNumbers = new HashMap<>();
      jdbc.query(
          "SELECT id,member_number FROM members",
          r -> {
            memberNumbers.put(r.getObject("id", UUID.class), r.getString("member_number"));
          });
      List<Basis> orderedBasis =
          basis.values().stream()
              .sorted(
                  Comparator.comparing((Basis b) -> memberNumbers.get(b.member()))
                      .thenComparing(Basis::member))
              .toList();
      for (var b : orderedBasis) {
        long delta =
            b.member().equals(input.memberId()) ? pesewas(new BigDecimal(input.amount())) : 0;
        for (var previous : result) {
          int index = previous.members().indexOf(b.member());
          Basis old =
              previous.basis().stream()
                  .filter(x -> x.member().equals(b.member()))
                  .findFirst()
                  .orElse(new Basis(b.member(), 0, 0, null));
          if (previous.cycle().actual() != null
              && !previous.cycle().maturity().isAfter(c.purchase()))
            delta =
                Math.addExact(
                    delta,
                    (index < 0 ? 0 : previous.actual().get(index).incomePesewas())
                        - old.actualIncome());
          if (previous.cycle().maturity().isAfter(c.purchase())
              || previous.cycle().actual() == null)
            delta =
                Math.subtractExact(
                    delta,
                    (index < 0 ? 0 : previous.expected().get(index).participationPesewas())
                        - old.participation());
        }
        long amount =
            b.editReason() != null ? b.participation() : Math.addExact(b.participation(), delta);
        if (c.source() != null) {
          var source =
              result.stream()
                  .filter(p -> p.cycle().id().equals(c.source()))
                  .findFirst()
                  .orElse(null);
          amount = b.participation();
          if (source != null) {
            int ix = source.members().indexOf(b.member());
            long cap = ix < 0 ? 0 : source.expected().get(ix).participationPesewas();
            Basis old =
                source.basis().stream()
                    .filter(x -> x.member().equals(b.member()))
                    .findFirst()
                    .orElse(new Basis(b.member(), 0, 0, null));
            long oldCap = old.participation();
            if ("ROLL_PRINCIPAL_AND_INCOME".equals(c.policy())) {
              oldCap = Math.addExact(oldCap, old.actualIncome());
              if (ix >= 0) cap = Math.addExact(cap, source.actual().get(ix).incomePesewas());
            }
            if (b.editReason() == null) amount = Math.addExact(b.participation(), cap - oldCap);
            amount = Math.min(amount, cap);
          }
        }
        if (amount < 0)
          throw new IllegalStateException(
              "Correction would produce negative investment participation. Review the cycle separately.");
        if (amount > 0) {
          ids.add(b.member());
          participants.add(new AllocationEngine.Participant(ids.size(), amount));
        }
      }
      var expected =
          AllocationEngine.allocate(participants, pesewas(c.cost()), pesewas(c.expected()));
      var actual =
          c.actual() == null
              ? List.<AllocationEngine.Share>of()
              : AllocationEngine.allocate(participants, pesewas(c.cost()), pesewas(c.actual()));
      result.add(new Planned(c, original, List.copyOf(ids), expected, actual));
    }
    return result;
  }

  private Impact impact(Planned p) {
    List<Share> shares = new ArrayList<>();
    Map<UUID, String> numbers = new HashMap<>();
    jdbc.query(
        "SELECT id,member_number FROM members",
        r -> {
          numbers.put(r.getObject("id", UUID.class), r.getString("member_number"));
        });
    for (int i = 0; i < p.members().size(); i++) {
      UUID id = p.members().get(i);
      var b =
          p.basis().stream()
              .filter(x -> x.member().equals(id))
              .findFirst()
              .orElse(new Basis(id, 0, 0, null));
      var s = p.expected().get(i);
      shares.add(
          new Share(
              id,
              numbers.get(id),
              cedis(b.participation()).toPlainString(),
              cedis(s.participationPesewas()).toPlainString(),
              cedis(s.costPesewas()).toPlainString(),
              cedis(s.incomePesewas()).toPlainString(),
              p.cycle().actual() == null ? null : cedis(b.actualIncome()).toPlainString(),
              p.cycle().actual() == null
                  ? null
                  : cedis(p.actual().get(i).incomePesewas()).toPlainString()));
    }
    return new Impact(
        p.cycle().id(),
        p.cycle().revision(),
        p.cycle().status(),
        p.cycle().purchase(),
        p.cycle().maturity(),
        List.copyOf(shares));
  }

  private List<Cycle> cycles(LocalDate date) {
    return jdbc.query(
        "SELECT * FROM investments WHERE purchase_date>=? AND status IN ('POSTED','MATURED','ROLLED_OVER','SETTLED') ORDER BY purchase_date,created_at,id",
        (r, n) ->
            new Cycle(
                r.getObject("id", UUID.class),
                r.getInt("revision"),
                r.getString("status"),
                r.getObject("purchase_date", LocalDate.class),
                r.getObject("maturity_date", LocalDate.class),
                r.getBigDecimal("cost"),
                r.getBigDecimal("maturity_value"),
                r.getBigDecimal("actual_proceeds"),
                r.getObject("rollover_source_id", UUID.class),
                r.getString("rollover_policy")),
        date);
  }

  private LocalDate earliest(List<Cycle> cycles, LocalDate original) {
    LocalDate current =
        cycles.stream()
            .map(c -> c.purchase().plusDays(1))
            .max(LocalDate::compareTo)
            .orElse(original);
    LocalDate archived =
        jdbc.queryForObject(
            "SELECT max((snapshot->'investment'->>'purchase_date')::date)+1 FROM investment_revision_history WHERE (snapshot->'investment'->>'purchase_date')::date>=?",
            LocalDate.class,
            original);
    return archived != null && archived.isAfter(current) ? archived : current;
  }

  private String restriction(List<Cycle> cycles, LocalDate date) {
    if (!open(date.getYear()))
      return "The original fiscal year must be independently unlocked before historical reposting.";
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM investment_revision_history h JOIN investments i ON i.id=h.investment_id WHERE (h.snapshot->'investment'->>'purchase_date')::date>=? AND (h.snapshot->'investment'->>'purchase_date')::date<>i.purchase_date)",
            Boolean.class,
            date)))
      return "Previously moved historical cycles require individual evidence review. Use future-only posting or resolve their history first.";
    for (var c : cycles) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM approval_requests WHERE status='PENDING' AND payload->>'investmentId'=?::text) OR EXISTS(SELECT 1 FROM investments WHERE rollover_source_id=? AND status='DRAFT')",
              Boolean.class,
              c.id().toString(),
              c.id())))
        return "An affected cycle has pending/draft dependencies. Resolve them before reposting; future-only posting remains available.";
      if (!open(c.purchase().getYear()) || (c.actual() != null && !open(c.maturity().getYear())))
        return "Every affected purchase and maturity year must be independently unlocked before historical reposting.";
    }
    return null;
  }

  private String fingerprint() {
    try {
      String source =
          jdbc.queryForObject(
              """
            SELECT jsonb_build_object('ledger',(SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id) FROM ledger_entries l),
             'cycles',(SELECT jsonb_agg(to_jsonb(i) ORDER BY i.id) FROM investments i),
             'allocations',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.investment_id,a.member_id) FROM investment_allocations a),
             'maturity',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.investment_id,a.revision,a.member_id) FROM investment_maturity_allocations a),
             'members',(SELECT jsonb_agg(jsonb_build_array(id,member_number,status,version,join_date) ORDER BY id) FROM members),
             'history',(SELECT jsonb_agg(to_jsonb(h) ORDER BY investment_id,revision) FROM investment_revision_history h),
             'participationEdits',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM audit_log a WHERE action='INVESTMENT_PARTICIPATION_EDITED'),
             'years',(SELECT jsonb_agg(to_jsonb(y) ORDER BY year) FROM fiscal_years y))::text
            """,
              String.class);
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Impact checksum unavailable.");
    }
  }

  private void validate(UUID actor, Input input, boolean submit) {
    require(actor, "LEDGER_POST");
    require(actor, "LEDGER_BACKDATE");
    if (input == null
        || input.memberId() == null
        || input.effectiveDate() == null
        || input.effectiveDate().isBefore(LocalDate.of(2000, 1, 1))
        || input.effectiveDate().isAfter(today())
        || !("SEED".equals(input.type()) || "ADDITION".equals(input.type()))
        || input.amount() == null
        || !input.amount().matches("[0-9]+(\\.[0-9]{1,2})?")
        || new BigDecimal(input.amount()).signum() <= 0
        || new BigDecimal(input.amount()).precision() - new BigDecimal(input.amount()).scale() > 15)
      throw new IllegalArgumentException(
          "Choose a member, valid past date and positive contribution with at most two decimals.");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM members WHERE id=? AND status='ACTIVE' AND join_date<=?)",
            Boolean.class,
            input.memberId(),
            input.effectiveDate())))
      throw new IllegalArgumentException("Member must be active and joined by the original date.");
    if (submit
        && (input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000))
      throw new IllegalArgumentException("A correction reason is required.");
    if (!"FUTURE_ONLY".equals(input.mode())) ensureOpen(input.effectiveDate().getYear());
  }

  private boolean open(int year) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM fiscal_years WHERE year=? AND status='OPEN')",
            Boolean.class,
            year));
  }

  private void ensureOpen(int year) {
    if (!open(year))
      throw new IllegalStateException(
          "Fiscal year is not open. Request independent period unlock first.");
  }

  private void require(UUID actor, String permission) {
    if (!permissions.hasPermission(actor, permission))
      throw new SecurityException("Required permission: " + permission);
  }

  private static long pesewas(BigDecimal value) {
    return AllocationEngine.toPesewas(value);
  }

  private static long signedPesewas(BigDecimal value) {
    return value.movePointRight(2).longValueExact();
  }

  private static BigDecimal cedis(long value) {
    return AllocationEngine.toCedis(value);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Africa/Accra"));
  }
}
