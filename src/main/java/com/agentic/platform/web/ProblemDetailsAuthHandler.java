package com.agentic.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders 401 and 403 as RFC 9457 problem details, so security failures have the same
 * JSON shape as every other API error (NFR-5) instead of the container's default page.
 */
@Component
public class ProblemDetailsAuthHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemDetailWriter writer;

    public ProblemDetailsAuthHandler(ProblemDetailWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"agentic-url-shortener\"");
        writer.write(response, ApiProblem.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource.", request));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.write(response, ApiProblem.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action.", request));
    }
}
