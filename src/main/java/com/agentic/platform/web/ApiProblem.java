package com.agentic.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the single error shape used by every endpoint, filter and handler (NFR-5):
 * RFC 9457 problem details plus two extension members:
 * <ul>
 *   <li>{@code errorCode}: stable, machine-readable reason clients can branch on</li>
 *   <li>{@code requestId}: correlates the response with server logs</li>
 * </ul>
 */
public final class ApiProblem {

    public static final String ERROR_CODE = "errorCode";
    public static final String REQUEST_ID = "requestId";

    private ApiProblem() {
    }

    public static ProblemDetail of(HttpStatusCode status, String errorCode, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(ERROR_CODE, errorCode);
        decorate(problem, request);
        return problem;
    }

    /** Adds instance + requestId to a problem created elsewhere (e.g. by Spring MVC). */
    public static ProblemDetail decorate(ProblemDetail problem, HttpServletRequest request) {
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
            problem.setProperty(REQUEST_ID, RequestIdFilter.current(request));
        }
        return problem;
    }
}
