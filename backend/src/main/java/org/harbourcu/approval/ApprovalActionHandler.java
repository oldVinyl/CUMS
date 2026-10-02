package org.harbourcu.approval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** A financial action is committed only when its final approval succeeds. */
public interface ApprovalActionHandler {
    String actionType();

    void validateSubmission(UUID makerId, JsonNode payload);

    void execute(UUID requestId, JsonNode payload);
}
