package org.harbourcu.importer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.harbourcu.approval.ApprovalActionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class ImportReverseHandler implements ApprovalActionHandler {
    private final ImportService service;
    public ImportReverseHandler(ImportService service){this.service=service;}
    public String actionType(){return "IMPORT_REVERSE";}
    public void validateSubmission(UUID maker,JsonNode payload){service.validateReverse(maker,id(payload));}
    public void execute(UUID request,UUID checker,JsonNode payload){service.reversePost(request,checker,id(payload),payload.path("reason").textValue());}
    private UUID id(JsonNode payload){if(payload==null||payload.size()!=2||!payload.path("batchId").isTextual()||!payload.path("reason").isTextual()||payload.path("reason").textValue().isBlank()||payload.path("reason").textValue().length()>1000)throw new IllegalArgumentException("Batch and a reversal reason of up to 1,000 characters are required.");return UUID.fromString(payload.get("batchId").textValue());}
}
