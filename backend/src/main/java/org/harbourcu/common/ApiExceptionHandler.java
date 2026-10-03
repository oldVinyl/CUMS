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

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ApiError> malformed(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR",
            "Check the required fields, dates and file, then try again.", List.of(), TraceIdFilter.traceId(request)));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> oversized(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError("FILE_TOO_LARGE",
            "Receipt files must be no larger than 10 MB.", List.of(), TraceIdFilter.traceId(request)));
    }
    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    ResponseEntity<ApiError> missing(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND",
            "This record is unavailable.", List.of(), TraceIdFilter.traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
            "INTERNAL_ERROR", "Something went wrong. Please try again or contact support.",
            List.of(), TraceIdFilter.traceId(request)));
    }
}
