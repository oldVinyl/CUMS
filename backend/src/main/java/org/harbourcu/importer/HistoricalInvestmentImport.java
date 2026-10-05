package org.harbourcu.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.harbourcu.allocation.AllocationEngine;
import org.harbourcu.ledger.FiscalYearLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One source cycle and its exact member allocations form one atomic, reversible import batch. */
@Service
public class HistoricalInvestmentImport {
  public static final List<String> COLUMNS = List.of("member_number", "reference", "institution", "purchase_date", "maturity_date", "cost", "future_value", "actual_proceeds", "participation", "cost_share", "future_value_share", "income_share", "closing_balance");
  private record Member(UUID id, String number, Map<String,String> row) {}
  private record Plan(String reference, String institution, LocalDate purchase, LocalDate maturity,
      BigDecimal cost, BigDecimal future, BigDecimal actual, List<Member> members,
      List<AllocationEngine.Share> expected, List<AllocationEngine.Share> received) {}
  private final JdbcTemplate jdbc;
  private final FiscalYearLock years;
  public HistoricalInvestmentImport(JdbcTemplate jdbc, FiscalYearLock years) { this.jdbc=jdbc; this.years=years; }

  public ImportService.Report analyze(String hash, List<Map<String,String>> rows) {return analyze(hash,rows,true);}
  public ImportService.Report evidence(String hash,List<Map<String,String>> rows){return analyze(hash,rows,false);}
  private ImportService.Report analyze(String hash,List<Map<String,String>> rows,boolean eligibility) {
    var totals=rows.stream().filter(r->"TOTAL".equals(r.get("member_number"))).toList();
    var data=rows.stream().filter(r->!"TOTAL".equals(r.get("member_number"))).toList();
    Plan plan = null; String error = null;
    try { plan = plan(rows,eligibility); } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) { error = failure instanceof ArithmeticException ? "Financial totals exceed supported exact limits." : failure.getMessage(); }
    if(error==null&&!totals.isEmpty())try {
      if(totals.size()!=1||rows.getLast()!=totals.getFirst())throw new IllegalArgumentException("Supply at most one final TOTAL row.");
      var totalRow=totals.getFirst();BigDecimal pool=data.stream().map(r->new BigDecimal(r.get("participation"))).reduce(BigDecimal.ZERO,BigDecimal::add);
      for(var expected:Map.of("participation",pool,"cost_share",plan.cost(),"future_value_share",plan.future(),"income_share",plan.future().subtract(plan.cost()),"closing_balance",pool.add(plan.future().subtract(plan.cost()))).entrySet())if(!totalRow.get(expected.getKey()).isBlank()&&decimal(totalRow.get(expected.getKey())).compareTo(expected.getValue())!=0)throw new IllegalArgumentException("The supplied TOTAL row does not match the exact allocation totals.");
    }catch(IllegalArgumentException failure){error=failure.getMessage();}
    rows=data;
    List<ImportService.RowReport> reports = new ArrayList<>(); BigDecimal total=BigDecimal.ZERO;
    for (int i=0;i<rows.size();i++) {
      var r=rows.get(i); Map<String,String> details=new LinkedHashMap<>();
      if (plan!=null) {
        int position=-1; for(int j=0;j<plan.members().size();j++) if(plan.members().get(j).number().equals(r.get("member_number"))) position=j;
        var share=plan.expected().get(position);
        details.put("Investment",plan.reference()); details.put("Maturity date",plan.maturity().toString());
        details.put("Ratio (%)",BigDecimal.valueOf(share.ratioBasisPoints(),2).toPlainString());
        details.put("Cost share (GHS)",cedis(share.costPesewas()).toPlainString());
        details.put("Future value share (GHS)",cedis(share.maturityValuePesewas()).toPlainString());
        details.put("Expected income (GHS)",cedis(share.incomePesewas()).toPlainString());
        details.put("Closing balance (GHS)",cedis(share.closingBalancePesewas()).toPlainString());
        if(plan.actual()!=null) details.put("Received income to post (GHS)",cedis(plan.received().get(position).incomePesewas()).toPlainString());
        total=total.add(new BigDecimal(r.get("participation")));
      }
      reports.add(new ImportService.RowReport(i+1,r.get("member_number"),null,r.get("participation"),r.get("purchase_date"),error==null?List.of():List.of(error),details));
    }
    return new ImportService.Report("INVESTMENTS",hash,rows.size(),total.toPlainString(),error==null,
        List.of("Optional final TOTAL row checks participation and supplied allocation totals. One investment per file. Repeat its reference, institution, dates and totals on every participating member row. Import cycles oldest first.",
          "Shares are recomputed in whole pesewas. Supplied share columns, when present, must match exactly; rounded spreadsheet percentages are not used.",
          "Blank actual proceeds means expected income only. Actual proceeds posts earned income on the maturity date: do not also import that same income as an opening balance. Missing dates must be confirmed or explicitly documented as assumptions in the batch reason."),reports);
  }
  private Plan plan(List<Map<String,String>> rows) {return plan(rows,true);}
  private Plan plan(List<Map<String,String>> rows,boolean eligibility) {
    rows=rows.stream().filter(r->!"TOTAL".equals(r.get("member_number"))).toList();
    if(rows.isEmpty()||rows.size()>1000) throw new IllegalArgumentException("Include 1–1,000 participating member rows for one investment.");
    var head=rows.getFirst(); String ref=head.get("reference"), institution=head.get("institution");
    if(eligibility && (ref==null||ref.isBlank()||ref.length()>100||institution==null||institution.isBlank()||institution.length()>200)) throw new IllegalArgumentException("Provide a reference up to 100 characters and an institution up to 200 characters.");
    if(eligibility && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM investments WHERE reference_number=?)",Boolean.class,ref))) throw new IllegalStateException("Investment reference already exists; use its correction workflow or a new documented reference after reversal.");
    LocalDate purchase=date(head.get("purchase_date")), maturity=date(head.get("maturity_date"));
    if(purchase.isBefore(LocalDate.of(2000,1,1))||purchase.isAfter(today())||!maturity.isAfter(purchase)||ChronoUnit.DAYS.between(purchase,maturity)>36500) throw new IllegalArgumentException("Use a historical purchase date and a later maturity within 100 years.");
    BigDecimal cost=money(head.get("cost")), future=money(head.get("future_value"));
    if(cost.signum()<=0) throw new IllegalArgumentException("Investment cost must be positive.");
    BigDecimal actual=head.get("actual_proceeds").isBlank()?null:money(head.get("actual_proceeds"));
    if(eligibility)requireOpen(purchase.getYear()); if(actual!=null) { if(maturity.isAfter(today())) throw new IllegalArgumentException("Received proceeds require a maturity date no later than today."); if(eligibility)requireOpen(maturity.getYear()); }
    Set<String> seen=new HashSet<>(); List<Member> members=new ArrayList<>();
    for(var row:rows) {
      for(String key:List.of("reference","institution","purchase_date","maturity_date","cost","future_value","actual_proceeds")) if(eligibility&&!Objects.equals(row.get(key),head.get(key))) throw new IllegalArgumentException("Every row must describe the same investment and totals.");
      String number=row.get("member_number"); if(number==null||!seen.add(number)) throw new IllegalArgumentException("Member numbers must be distinct within the cycle.");
      var found=jdbc.queryForList("SELECT id,join_date,status FROM members WHERE member_number=?",number);
      if(found.isEmpty()||(eligibility&&!"ACTIVE".equals(found.getFirst().get("status")))||purchase.isBefore(((java.sql.Date)found.getFirst().get("join_date")).toLocalDate())) throw new IllegalArgumentException("Every participant must be active and joined by the purchase date.");
      UUID member=(UUID)found.getFirst().get("id"); BigDecimal participation=money(row.get("participation"));
      if(participation.signum()<=0) throw new IllegalArgumentException("Include only members with positive participation; omit zero rows.");
      if(eligibility && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM investment_allocations a JOIN investments i ON i.id=a.investment_id WHERE a.member_id=? AND i.status IN ('POSTED','MATURED','ROLLED_OVER','SETTLED') AND i.purchase_date>=?) OR EXISTS(SELECT 1 FROM investment_revision_history h WHERE (h.snapshot->'investment'->>'purchase_date')::date>=? AND EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(h.snapshot->'allocations','[]'::jsonb)) a WHERE a->>'member_id'=?))",Boolean.class,member,purchase,purchase,member.toString()))) throw new IllegalStateException("A later current or archived cycle involves these participants. Import in chronological order or correct its dependencies first.");
      BigDecimal available=jdbc.queryForObject("SELECT coalesce((SELECT sum(amount) FROM ledger_entries WHERE member_id=? AND effective_date<=?),0)-coalesce((SELECT sum(a.participation) FROM investment_allocations a JOIN investments i ON i.id=a.investment_id WHERE a.member_id=? AND i.purchase_date<=? AND i.status IN ('POSTED','MATURED','ROLLED_OVER','SETTLED') AND (i.actual_proceeds IS NULL OR i.maturity_date>?)),0)",BigDecimal.class,member,purchase,member,purchase,purchase);
      if(eligibility && participation.compareTo(available)>0) throw new IllegalStateException("Participation exceeds the member's uninvested ledger equity at purchase. Import opening balances first.");
      members.add(new Member(member,number,row));
    }
    members.sort(Comparator.comparing(Member::number).thenComparing(Member::id));
    List<AllocationEngine.Participant> participants=new ArrayList<>();for(int i=0;i<members.size();i++) participants.add(new AllocationEngine.Participant(i+1,AllocationEngine.toPesewas(money(members.get(i).row().get("participation")))));
    var expected=AllocationEngine.allocate(participants,AllocationEngine.toPesewas(cost),AllocationEngine.toPesewas(future));
    var received=actual==null?List.<AllocationEngine.Share>of():AllocationEngine.allocate(participants,AllocationEngine.toPesewas(cost),AllocationEngine.toPesewas(actual));
    for(int i=0;i<members.size();i++) { var r=members.get(i).row();var s=expected.get(i);
      for(var entry:Map.of("cost_share",s.costPesewas(),"future_value_share",s.maturityValuePesewas(),"income_share",s.incomePesewas(),"closing_balance",s.closingBalancePesewas()).entrySet()) {
        String supplied=r.get(entry.getKey()); if(!supplied.isBlank() && decimal(supplied).compareTo(cedis(entry.getValue()))!=0) throw new IllegalArgumentException("A supplied allocation share does not match the exact engine. Correct the source or leave optional shares blank after reviewing the calculated preview.");
      }
    }
    return new Plan(ref,institution,purchase,maturity,cost,future,actual,List.copyOf(members),expected,received);
  }
  public void post(UUID request,UUID maker,UUID checker,UUID batch,List<Map<String,String>> rows) {
    Plan p=plan(rows); years.requireOpen(p.purchase().getYear());if(p.actual()!=null) years.requireOpen(p.maturity().getYear());
    UUID id=UUID.randomUUID();
    jdbc.update("INSERT INTO investments(id,tenor,custom_days,institution,reference_number,purchase_date,maturity_date,cost,maturity_value,created_by,rollover_policy) VALUES(?,'CUSTOM',?,?,?,?,?,?,?,?, 'NO_ROLLOVER')",id,(int)ChronoUnit.DAYS.between(p.purchase(),p.maturity()),p.institution(),p.reference(),p.purchase(),p.maturity(),p.cost(),p.future(),maker);
    for(int i=0;i<p.members().size();i++) {
      UUID member=p.members().get(i).id();var s=p.expected().get(i);
      jdbc.update("INSERT INTO investment_allocations(investment_id,member_id,participation,ratio_basis_points,cost_share,maturity_value_share,income_share,closing_balance) VALUES(?,?,?,?,?,?,?,?)",id,member,cedis(s.participationPesewas()),s.ratioBasisPoints(),cedis(s.costPesewas()),cedis(s.maturityValuePesewas()),cedis(s.incomePesewas()),cedis(s.closingBalancePesewas()));
      if(p.actual()!=null) { var a=p.received().get(i);
        jdbc.update("INSERT INTO investment_maturity_allocations(investment_id,revision,member_id,actual_maturity_share,actual_income_share) VALUES(?,1,?,?,?)",id,member,cedis(a.maturityValuePesewas()),cedis(a.incomePesewas()));
        if(a.incomePesewas()!=0) jdbc.update("INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,investment_id,reference,narration,created_by,approved_by,batch_id) VALUES(?,'INCOME_ALLOCATION','INCOME',?,?,?,?,?,?,?,?,?)",member,cedis(a.incomePesewas()),p.maturity(),p.maturity().getYear(),id,"IIM-"+batch+"-"+member,"Reviewed historical investment income",maker,checker,batch);
      }
    }
    jdbc.update("UPDATE investments SET status=?,actual_proceeds=? WHERE id=?",p.actual()==null?"POSTED":"MATURED",p.actual(),id);
    jdbc.update("INSERT INTO import_investment_results(batch_id,investment_id) VALUES(?,?)",batch,id);
  }
  public void validateReverse(UUID batch) {
    var rows=jdbc.queryForList("SELECT i.* FROM import_investment_results r JOIN investments i ON i.id=r.investment_id WHERE r.batch_id=?",batch);
    if(rows.isEmpty()) throw new IllegalStateException("Imported investment not found."); var p=rows.getFirst();UUID id=(UUID)p.get("id");
    if(!List.of("POSTED","MATURED").contains(p.get("status"))||((Number)p.get("revision")).intValue()!=1) throw new IllegalStateException("This imported cycle was changed. Use its current cycle correction workflow.");
    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE status='PENDING' AND payload->>'investmentId'=?) OR EXISTS(SELECT 1 FROM investments WHERE rollover_source_id=? AND status NOT IN ('DISCARDED','REVERSED')) OR EXISTS(SELECT 1 FROM ledger_entries r JOIN ledger_entries l ON l.id=r.reversal_of WHERE l.batch_id=?)",Boolean.class,id.toString(),id,batch))) throw new IllegalStateException("Resolve pending decisions, dependent rollovers or existing compensation before reversing this import.");
    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM investment_allocations original WHERE original.investment_id=? AND coalesce((SELECT sum(l.amount) FROM ledger_entries l WHERE l.member_id=original.member_id AND l.effective_date<=?),0)-coalesce((SELECT sum(l.amount) FROM ledger_entries l WHERE l.member_id=original.member_id AND l.batch_id=? AND l.type='INCOME_ALLOCATION'),0)<coalesce((SELECT sum(a.participation) FROM investment_allocations a JOIN investments i ON i.id=a.investment_id WHERE a.member_id=original.member_id AND i.status='POSTED' AND i.id<>?),0))",Boolean.class,id,today(),batch,id))) throw new IllegalStateException("Imported income is committed or compensation would make equity negative. Resolve dependent cycles first.");
  }
  public void reverse(UUID request,UUID maker,UUID checker,UUID batch,String reason) {
    validateReverse(batch);years.requireOpen(today().getYear());UUID id=jdbc.queryForObject("SELECT investment_id FROM import_investment_results WHERE batch_id=?",UUID.class,batch);
    jdbc.update("INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,investment_id,reference,narration,reversal_of,created_by,approved_by,batch_id,original_effective_date) SELECT member_id,'REVERSAL',component,-amount,?,?,investment_id,'IIR-'||id::text,?,id,?,?,?,effective_date FROM ledger_entries WHERE batch_id=? AND type='INCOME_ALLOCATION'",today(),today().getYear(),reason,maker,checker,batch,batch);
    jdbc.update("INSERT INTO investment_revision_history(investment_id,revision,snapshot,reason,approval_request_id) SELECT i.id,i.revision,jsonb_build_object('investment',to_jsonb(i),'allocations',(SELECT jsonb_agg(to_jsonb(a)) FROM investment_allocations a WHERE a.investment_id=i.id),'maturityAllocations',(SELECT jsonb_agg(to_jsonb(a)) FROM investment_maturity_allocations a WHERE a.investment_id=i.id AND a.revision=i.revision)),?,? FROM investments i WHERE id=?",reason,request,id);
    jdbc.update("UPDATE investments SET status='REVERSED',actual_proceeds=NULL,updated_at=now() WHERE id=?",id);
  }
  private void requireOpen(int year) { if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fiscal_years WHERE year=? AND status='OPEN')",Boolean.class,year))) throw new IllegalArgumentException("Create or independently unlock the investment's affected fiscal years first."); }
  private static LocalDate date(String value) { try{return LocalDate.parse(value);}catch(Exception e){throw new IllegalArgumentException("Supply explicit ISO purchase and maturity dates; dates cannot be inferred from a year label.");} }
  private static BigDecimal decimal(String value) { if(value==null||!value.matches("-?[0-9]{1,13}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Use plain amounts with at most two decimal places.");return new BigDecimal(value); }
  private static BigDecimal money(String value) { BigDecimal amount=decimal(value);if(amount.signum()<0)throw new IllegalArgumentException("Cost, proceeds and participation cannot be negative.");return amount; }
  private static BigDecimal cedis(long value) { return BigDecimal.valueOf(value,2); }
  private static LocalDate today() { return LocalDate.now(ZoneId.of("Africa/Accra")); }
}
