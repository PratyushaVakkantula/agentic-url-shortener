package com.agentic.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Fallback error handling for all modules. Module-specific advice (e.g. the shortener's)
 * runs first; this class covers framework exceptions and anything unexpected.
 *
 * <p>Unexpected exceptions return a generic 500 that never echoes internals (no stack trace,
 * no exception message), only the requestId that operators can look up in the logs.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Request validation failed.");
        problem.setProperty(ApiProblem.ERROR_CODE, "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Do not echo the parser message: it can contain fragments of the request body.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request body is missing or not valid JSON.");
        problem.setProperty(ApiProblem.ERROR_CODE, "MALFORMED_REQUEST");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Adds instance/requestId (and a default errorCode) to every problem built by Spring MVC. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey(ApiProblem.ERROR_CODE)) {
                problem.setProperty(ApiProblem.ERROR_CODE, HttpStatus.valueOf(status.value()).name());
            }
            if (request instanceof ServletWebRequest servlet) {
                ApiProblem.decorate(problem, servlet.getRequest());
            }
        }
        return response;
    }

    /**
     * Method-security denials ({@code @PreAuthorize}) surface inside MVC; map them to the same
     * 403 the security filter chain produces instead of letting the catch-all turn them into 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action.", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiProblem.of(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Quote the requestId when reporting this.", request));
    }
}
