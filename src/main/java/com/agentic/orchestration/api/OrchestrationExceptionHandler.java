package com.agentic.orchestration.api;

import com.agentic.orchestration.engine.GovernanceException;
import com.agentic.orchestration.engine.UnknownRunException;
import com.agentic.orchestration.engine.UnknownWorkflowException;
import com.agentic.platform.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = OrchestrationExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrchestrationExceptionHandler {

    @ExceptionHandler(UnknownWorkflowException.class)
    ResponseEntity<ProblemDetail> unknownWorkflow(UnknownWorkflowException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(UnknownRunException.class)
    ResponseEntity<ProblemDetail> unknownRun(UnknownRunException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(GovernanceException.class)
    ResponseEntity<ProblemDetail> governance(GovernanceException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.violation()) {
            case SELF_APPROVAL_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case APPROVAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STALE_APPROVAL, APPROVAL_NOT_PENDING, RUN_NOT_ACTIVE, STAGE_NOT_REVISABLE -> HttpStatus.CONFLICT;
            case REVISION_BLOCKED_BY_POLICY -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return problem(status, ex.violation().name(), ex.getMessage(), request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiProblem.of(status, code, detail, request));
    }
}
