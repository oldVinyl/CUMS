package org.harbourcu.importer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class ImportPostHandler implements ApprovalActionHandler {
    private final ImportService service;
    public ImportPostHandler(ImportService service){this.service=service;}
    public String actionType(){return "IMPORT_POST";}
    public void validateSubmission(UUID maker,JsonNode payload){service.validatePost(maker,id(payload));}
    public void execute(UUID request,UUID checker,JsonNode payload){service.post(request,checker,id(payload));}
    private UUID id(JsonNode payload){if(payload==null||payload.size()!=1||!payload.path("batchId").isTextual())throw new IllegalArgumentException("Import batch is required.");return UUID.fromString(payload.get("batchId").textValue());}
}
