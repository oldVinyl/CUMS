package org.harbourcu.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception,
                                        HttpServletRequest request) {
        List<ApiError.FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
            .toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR",
            "Please correct the highlighted fields.", fields, TraceIdFilter.traceId(request)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException exception,
                                        HttpServletRequest request) {
        List<ApiError.FieldError> fields = exception.getConstraintViolations().stream()
            .map(violation -> new ApiError.FieldError(violation.getPropertyPath().toString(),
                violation.getMessage()))
            .toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR",
            "Please correct the highlighted fields.", fields, TraceIdFilter.traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
            "INTERNAL_ERROR", "Something went wrong. Please try again or contact support.",
            List.of(), TraceIdFilter.traceId(request)));
    }
}
