package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HistoricalReversalHandler implements ApprovalActionHandler {
  private final BackdatedContributionService service;private final ObjectMapper json;private final JdbcTemplate jdbc;
  public HistoricalReversalHandler(BackdatedContributionService service,ObjectMapper json,JdbcTemplate jdbc){this.service=service;this.json=json;this.jdbc=jdbc;}
  public String actionType(){return "LEDGER_HISTORICAL_REVERSE";}
  public void validateSubmission(UUID actor,JsonNode payload){service.checkReversal(actor,input(payload),null);}
  public void execute(UUID request,UUID checker,JsonNode payload){UUID maker=jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id=?",UUID.class,request);service.postReversal(request,maker,checker,input(payload));}
  private BackdatedContributionService.HistoricalReversal input(JsonNode payload){try{return json.treeToValue(payload,BackdatedContributionService.HistoricalReversal.class);}catch(Exception e){throw new IllegalArgumentException("Historical reversal request is invalid.");}}
}
