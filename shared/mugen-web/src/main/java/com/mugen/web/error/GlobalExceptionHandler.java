package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import com.mugen.shared.trace.TraceIdHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

/**
 * Renders every failure as an RFC 9457 {@code application/problem+json} response.
 * <p>
 * Auto-configured for all services (see {@code MugenErrorHandlingAutoConfiguration}),
 * so error responses are identical everywhere without eleven copies of this file. A
 * service that needs its own handling adds its own {@code @RestControllerAdvice}
 * with a higher {@code @Order}; anything it does not handle falls through to here.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Namespace for the {@code type} URI. Stable, dereferenceable documentation ids. */
    private static final String ERROR_TYPE_BASE = "https://mugen.dev/errors/";

    /** One validation failure. Serialised into the {@code errors} array. */
    public record ValidationError(String field, String message) {
    }

    /**
     * Every expected failure. The exception already knows its status and code, so
     * this stays one method instead of a handler per exception type.
     */
    @ExceptionHandler(AppException.class)
    public ProblemDetail handleAppException(AppException ex) {
        // Expected failures are not errors in the operational sense — a wrong
        // password is the system working. Logged at WARN without a stack trace so
        // they do not drown out genuine faults.
        log.warn("{} -> {} {}", ex.getErrorCode(), ex.getStatus().value(), ex.getMessage());
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
    }

    /**
     * Anything that is not an {@link AppException} is a bug, not a handled case.
     * <p>
     * The real message is logged with its stack trace but deliberately withheld from
     * the response: raw exception text leaks table names, file paths and library
     * versions. The client gets the traceId, which is enough to correlate with the
     * log entry that has the detail.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        String traceId = TraceIdHolder.getOrCreate();
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote traceId %s when reporting it.".formatted(traceId));
    }

    /** Bean-validation failures on {@code @RequestBody}, rendered field by field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.");
        body.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Keeps Spring MVC's own exceptions (415, 405, malformed body...) on the same
     * shape as everything else, so a client never has to parse two error formats.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problemDetail) {
            problemDetail.setProperty("traceId", TraceIdHolder.getOrCreate());
            problemDetail.setProperty("code", codeFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(ERROR_TYPE_BASE + code.name().toLowerCase().replace('_', '-')));
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code.name());
        // Always present, per CLAUDE.md — it is the only handle a user can give
        // support to find the corresponding server-side log entry.
        body.setProperty("traceId", TraceIdHolder.getOrCreate());
        return body;
    }

    private static String codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.RESOURCE_NOT_FOUND.name();
        }
        return status.is4xxClientError()
                ? ErrorCode.VALIDATION_FAILED.name()
                : ErrorCode.INTERNAL_ERROR.name();
    }
}
