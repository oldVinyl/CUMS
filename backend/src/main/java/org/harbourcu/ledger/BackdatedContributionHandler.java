package org.harbourcu.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BackdatedContributionHandler implements ApprovalActionHandler {
  private final BackdatedContributionService service;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;

  public BackdatedContributionHandler(
      BackdatedContributionService service, ObjectMapper json, JdbcTemplate jdbc) {
    this.service = service;
    this.json = json;
    this.jdbc = jdbc;
  }

  public String actionType() {
    return "LEDGER_BACKDATED_POST";
  }

  private BackdatedContributionService.Input input(JsonNode payload) {
    try {
      return json.treeToValue(payload, BackdatedContributionService.Input.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid historical contribution impact.");
    }
  }

  public void validateSubmission(UUID maker, JsonNode payload) {
    service.check(maker, input(payload));
  }

  public void execute(UUID request, UUID checker, JsonNode payload) {
    UUID maker =
        jdbc.queryForObject(
            "SELECT made_by FROM approval_requests WHERE id=?", UUID.class, request);
    service.post(request, maker, checker, input(payload));
  }
}
