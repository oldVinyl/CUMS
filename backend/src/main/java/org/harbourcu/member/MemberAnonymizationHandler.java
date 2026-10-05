package org.harbourcu.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.harbourcu.role.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class MemberAnonymizationHandler implements ApprovalActionHandler {
  private final MemberRetentionService service;private final ObjectMapper json;private final PermissionService permissions;
  public MemberAnonymizationHandler(MemberRetentionService service,ObjectMapper json,PermissionService permissions){this.service=service;this.json=json;this.permissions=permissions;}
  public String actionType(){return "MEMBER_ANONYMIZE";}
  public void validateSubmission(UUID maker,JsonNode payload){service.validate(maker,input(payload));}
  public void validateApprover(UUID checker,JsonNode payload){if(!permissions.hasPermission(checker,"SETTINGS_WRITE"))throw new SecurityException("Organization administration permission required for retention decisions.");}
  public void execute(UUID request,UUID checker,JsonNode payload){validateApprover(checker,payload);service.execute(request,checker,input(payload));}
  private MemberRetentionService.Input input(JsonNode payload){try{return json.treeToValue(payload,MemberRetentionService.Input.class);}catch(Exception e){throw new IllegalArgumentException("Retention request is invalid.");}}
}
