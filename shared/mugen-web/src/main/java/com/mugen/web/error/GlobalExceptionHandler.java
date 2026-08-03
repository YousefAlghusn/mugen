package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import com.mugen.shared.trace.TraceIdHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
// Must outrank Spring Boot's own ProblemDetailsExceptionHandler, which
// spring.mvc.problemdetails.enabled registers as a @ControllerAdvice at order 0.
// Without this, Boot's handler claims MethodArgumentNotValidException first and
// answers with a bare ProblemDetail — no traceId, no code, no errors[] — for
// exactly the validation failures clients most need those fields on.
@Order(Ordered.HIGHEST_PRECEDENCE)
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
    public ProblemDetail handleAppException(AppException ex, WebRequest request) {
        // Expected failures are not errors in the operational sense — a wrong
        // password is the system working. Logged at WARN without a stack trace so
        // they do not drown out genuine faults. A 5xx AppException is a different
        // matter and keeps its stack.
        if (ex.getStatus().is5xxServerError()) {
            log.error("Request failed errorCode={} status={} path={}",
                    ex.getErrorCode(), ex.getStatus().value(), request.getDescription(false), ex);
        } else {
            // The message is deliberately absent, on the same rule as the 4xx
            // framework exceptions below: these messages are built out of the
            // caller's own input. EmailAlreadyRegistered names the address that was
            // rejected, which is fine in the response — the caller just typed it —
            // and permanent in Loki. The code and the path say what happened, and
            // the traceId ties the line to the response that carries the detail.
            log.warn("Request failed errorCode={} status={} path={}",
                    ex.getErrorCode(), ex.getStatus().value(), request.getDescription(false));
        }
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
        log.error("Unhandled exception traceId={}", traceId, ex);
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

        // Field names only. The rejected value is whatever the caller typed, which
        // for this service is routinely an email address or a password.
        log.warn("Request validation failed fields={}", errors.stream().map(ValidationError::field).toList());

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.");
        body.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * The one funnel every exception Spring MVC handles itself passes through — 405,
     * 415, a malformed JSON body, a missing request parameter, no handler found.
     * <p>
     * Without this they answered with a {@code traceId} that appeared in no log line
     * anywhere, so the id a user quotes to support led to nothing.
     * <p>
     * 4xx is logged without {@code ex.getMessage()} on purpose. Spring builds those
     * messages from the offending input — {@code HttpMessageNotReadableException}
     * quotes the request body back, which on this service is a registration payload
     * with a password in it. The type and the path say what went wrong without
     * copying the credential into Loki.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Request failed status={} path={}",
                    statusCode.value(), request.getDescription(false), ex);
        } else {
            log.warn("Request rejected status={} reason={} path={}",
                    statusCode.value(), ex.getClass().getSimpleName(), request.getDescription(false));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
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
