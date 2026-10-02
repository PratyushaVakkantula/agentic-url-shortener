package com.agentic.shortener.api;

import com.agentic.platform.web.ApiProblem;
import com.agentic.shortener.domain.AliasConflictException;
import com.agentic.shortener.domain.CodeSpaceExhaustedException;
import com.agentic.shortener.domain.InvalidLinkRequestException;
import com.agentic.shortener.domain.LinkNotFoundException;
import com.agentic.shortener.domain.LinkUnavailableException;
import com.agentic.shortener.domain.ShortenerException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps shortener business errors to HTTP. One place, one table: easy to review and to test. */
@RestControllerAdvice(basePackageClasses = ShortenerExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShortenerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ShortenerExceptionHandler.class);

    @ExceptionHandler(ShortenerException.class)
    ResponseEntity<ProblemDetail> handle(ShortenerException ex, HttpServletRequest request) {
        HttpStatus status = statusFor(ex);
        if (status.is5xxServerError()) {
            log.error("Shortener failure: {}", ex.getMessage(), ex);
        }
        ProblemDetail problem = ApiProblem.of(status, ex.errorCode(), ex.getMessage(), request);
        if (ex instanceof InvalidLinkRequestException invalid) {
            problem.setProperty("field", invalid.field());
        }
        return ResponseEntity.status(status).body(problem);
    }

    static HttpStatus statusFor(ShortenerException ex) {
        return switch (ex) {
            case InvalidLinkRequestException e -> HttpStatus.BAD_REQUEST;
            case AliasConflictException e -> HttpStatus.CONFLICT;
            case LinkNotFoundException e -> HttpStatus.NOT_FOUND;
            case LinkUnavailableException e -> HttpStatus.GONE;
            case CodeSpaceExhaustedException e -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
