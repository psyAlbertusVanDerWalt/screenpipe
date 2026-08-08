package za.co.albertusvdw.graphiti.ingester.application.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.GraphitiException;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.InvalidUploadException;

/** Maps domain failures onto RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** A rejected upload is the caller's fault — 400, and the reason is safe to return. */
    @ExceptionHandler(InvalidUploadException.class)
    public ProblemDetail onInvalidUpload(InvalidUploadException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid upload");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A graph failure is this service's problem, not the caller's — 502.
     *
     * <p>The message is included deliberately: this API is LAN-only and its only human reader
     * is whoever is debugging why episodes are not landing.
     */
    @ExceptionHandler(GraphitiException.class)
    public ProblemDetail onGraphitiFailure(GraphitiException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Knowledge graph unavailable");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}
