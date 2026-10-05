package org.harbourcu.investment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.harbourcu.approval.*;
import org.harbourcu.ledger.BackdatedContributionService;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revises received proceeds at original economic dates and chronologically reposts dependants. */
@Service
public class HistoricalIncomeCorrection implements ApprovalActionHandler {
  public record Pending(UUID id,UUID investmentId,UUID makerId,String actualProceeds,String reason,int approvals,int requiredApprovals,Integer archivedRevision) {}
  private final BackdatedContributionService historical;private final JdbcTemplate jdbc;private final ObjectMapper json;private final PermissionService permissions;
  public HistoricalIncomeCorrection(BackdatedContributionService historical,JdbcTemplate jdbc,ObjectMapper json,PermissionService permissions){this.historical=historical;this.jdbc=jdbc;this.json=json;this.permissions=permissions;}
  public String actionType(){return "INVESTMENT_INCOME_CORRECT";}
  public void validateSubmission(UUID maker,JsonNode payload){historical.checkIncomeCorrection(maker,input(payload),null);}
  public void validateApprover(UUID checker,JsonNode payload){requireReader(checker);}
  public void execute(UUID request,UUID checker,JsonNode payload){validateApprover(checker,payload);UUID maker=jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id=?",UUID.class,request);historical.correctIncome(request,maker,checker,input(payload));}
  @Transactional public UUID submit(UUID actor,BackdatedContributionService.IncomeCorrection input,ApprovalService approvals){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");return approvals.submit(actionType(),json.valueToTree(input),actor,1);}
  public List<Pending> pending(UUID actor){requireReader(actor);boolean checker=permissions.hasPermission(actor,"APPROVE");return jdbc.query("SELECT r.*,(SELECT count(*) FROM approval_decisions d WHERE d.request_id=r.id AND d.decision='APPROVE') approvals FROM approval_requests r WHERE r.action_type='INVESTMENT_INCOME_CORRECT' AND r.status='PENDING' AND (? OR r.made_by=?) ORDER BY r.created_at LIMIT 100",(r,n)->{JsonNode payload;try{payload=json.readTree(r.getString("payload"));}catch(Exception e){throw new IllegalStateException("Historical correction evidence is unavailable.");}var input=input(payload);return new Pending(r.getObject("id",UUID.class),input.investmentId(),r.getObject("made_by",UUID.class),input.actualProceeds(),input.reason(),r.getInt("approvals"),r.getInt("required_approvals"),input.archivedRevision());},checker,actor);}
  @Transactional(readOnly=true) public BackdatedContributionService.Preview preview(UUID actor,BackdatedContributionService.IncomeCorrection input){return historical.previewIncomeCorrection(actor,input,null);}
  @Transactional(readOnly=true) public BackdatedContributionService.Preview review(UUID actor,UUID request){requireReader(actor);var row=linked(request);UUID maker=(UUID)row.get("made_by");if(!actor.equals(maker)&&!permissions.hasPermission(actor,"APPROVE"))throw new SecurityException("Independent review permission required.");var input=input(tree((String)row.get("payload")));historical.checkIncomeCorrection(maker,input,request);return historical.previewIncomeCorrection(maker,input,request);}
  @Transactional public void decide(UUID actor,UUID request,String decision,String reason,ApprovalService approvals){requireReader(actor);jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");linked(request);approvals.decide(request,actor,ApprovalService.Decision.valueOf(decision),reason);}
  @Transactional public void cancel(UUID actor,UUID request,ApprovalService approvals){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");linked(request);approvals.cancel(request,actor);}
  private Map<String,Object> linked(UUID id){var rows=jdbc.queryForList("SELECT made_by,payload::text FROM approval_requests WHERE id=? AND action_type='INVESTMENT_INCOME_CORRECT' AND status='PENDING'",id);if(rows.isEmpty())throw new IllegalStateException("Historical income correction is no longer pending.");return rows.getFirst();}
  private void requireReader(UUID actor){if(!permissions.hasPermission(actor,"INVESTMENT_READ"))throw new SecurityException("Investment read permission required.");}
  private JsonNode tree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("Historical correction evidence is unavailable.");}}
  private BackdatedContributionService.IncomeCorrection input(JsonNode payload){try{return json.treeToValue(payload,BackdatedContributionService.IncomeCorrection.class);}catch(Exception e){throw new IllegalArgumentException("Historical proceeds correction is invalid.");}}
}
