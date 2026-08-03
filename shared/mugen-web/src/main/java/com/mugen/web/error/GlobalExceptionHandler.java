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
 * Renders every failure as an RFC 9457 {@code application/problem+json} response,
 * auto-configured for all services. One with its own needs adds a
 * {@code @RestControllerAdvice} at a higher {@code @Order} and falls through to here.
 */
@Slf4j
@RestControllerAdvice
// Must outrank Boot's own ProblemDetailsExceptionHandler at order 0, which would
// otherwise claim MethodArgumentNotValidException and answer with a bare
// ProblemDetail — no traceId, no code, no errors[].
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Namespace for the {@code type} URI. Stable, dereferenceable documentation ids. */
    private static final String ERROR_TYPE_BASE = "https://mugen.dev/errors/";

    /** One validation failure. Serialised into the {@code errors} array. */
    public record ValidationError(String field, String message) {
    }

    /**
     * Every expected failure. The exception already knows its status and code, so this
     * stays one method rather than a handler per type.
     */
    @ExceptionHandler(AppException.class)
    public ProblemDetail handleAppException(AppException ex, WebRequest request) {
        // A wrong password is the system working, so 4xx is WARN without a stack.
        if (ex.getStatus().is5xxServerError()) {
            log.error("Request failed errorCode={} status={} path={}",
                    ex.getErrorCode(), ex.getStatus().value(), request.getDescription(false), ex);
        } else {
            // No message, on the same rule as the framework 4xx below: these are built
            // from the caller's input. EmailAlreadyRegistered names the address, which
            // is fine in the response and permanent in Loki.
            log.warn("Request failed errorCode={} status={} path={}",
                    ex.getErrorCode(), ex.getStatus().value(), request.getDescription(false));
        }
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
    }

    /**
     * Anything that is not an {@link AppException} is a bug. The real message is logged
     * but withheld from the response — raw exception text leaks table names, file paths
     * and library versions. The traceId is enough to correlate.
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

        // Field names only — the rejected value is routinely an email or a password.
        log.warn("Request validation failed fields={}", errors.stream().map(ValidationError::field).toList());

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.");
        body.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * The one funnel for everything Spring MVC handles itself — 405, 415, a malformed
     * body, no handler found. Without this they answered with a {@code traceId} that
     * appeared in no log line at all.
     * <p>
     * 4xx logs the exception type, never its message: Spring builds those from the
     * offending input, and {@code HttpMessageNotReadableException} quotes the request
     * body — here a registration payload with a password in it.
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
        // Always present — the only handle a user can give support to find the log line.
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
