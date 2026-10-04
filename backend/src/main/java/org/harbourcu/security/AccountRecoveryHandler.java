package org.harbourcu.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"APP_ENCRYPTION_KEY", "AUTH_THROTTLE_KEY"})
public class AccountRecoveryHandler implements ApprovalActionHandler {
  private final AccountRecoveryService service;
  private final ObjectMapper json;

  public AccountRecoveryHandler(AccountRecoveryService service, ObjectMapper json) {
    this.service = service;
    this.json = json;
  }

  public String actionType() {
    return "ACCOUNT_RECOVERY_RESET";
  }

  private AccountRecoveryService.ResetInput input(JsonNode payload) {
    try {
      return json.treeToValue(payload, AccountRecoveryService.ResetInput.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid recovery request.");
    }
  }

  public void validateSubmission(UUID actor, JsonNode payload) {
    service.check(actor, input(payload));
  }

  public void validateApprover(UUID actor, JsonNode payload) {
    service.validateApprover(actor, input(payload));
  }

  public void execute(UUID request, UUID checker, JsonNode payload) {
    service.execute(request, checker, input(payload));
  }
}
