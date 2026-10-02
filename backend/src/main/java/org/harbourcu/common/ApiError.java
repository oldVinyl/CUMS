package org.harbourcu.common;

import java.util.List;

public record ApiError(String code, String message, List<FieldError> fieldErrors, String traceId) {
    public record FieldError(String field, String message) {}
}
