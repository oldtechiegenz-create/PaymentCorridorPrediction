package com.corridoriq.backend.web;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.corridoriq.backend.prediction.PredictionServiceException;

/**
 * Every error body is {@code {"detail": ...}}, the shape the portal reads. Bad parameters are 422.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String detail) {
    }

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return reply(e.status(), e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missing(MissingServletRequestParameterException e) {
        return invalid("Missing query parameter " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException e) {
        return invalid("Invalid value " + e.getValue() + " for " + e.getName());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> body(MethodArgumentNotValidException e) {
        return invalid(e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; ")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return invalid("Request body is not valid JSON for this endpoint: " + rootMessage(e));
    }

    /** Errors from the prediction service are passed on unchanged, so its detail reaches the caller. */
    @ExceptionHandler(PredictionServiceException.class)
    ResponseEntity<String> prediction(PredictionServiceException e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
    }

    private static ResponseEntity<ApiError> invalid(String detail) {
        return reply(HttpStatus.UNPROCESSABLE_CONTENT, detail);
    }

    /** Logs why the request was rejected; the request line itself is logged by RequestLoggingFilter. */
    private static ResponseEntity<ApiError> reply(HttpStatus status, String detail) {
        log.warn("Rejected with {}: {}", status.value(), detail);
        return ResponseEntity.status(status).body(new ApiError(detail));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
