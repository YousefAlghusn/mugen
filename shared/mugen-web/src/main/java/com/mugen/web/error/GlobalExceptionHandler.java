package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import com.mugen.shared.trace.TraceIdHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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

    /** What {@link WebRequest#getDescription(boolean)} prefixes its path with. */
    private static final String URI_PREFIX = "uri=";

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
                    ex.getErrorCode(), ex.getStatus().value(), pathOf(request), ex);
        } else {
            // No message, on the same rule as the framework 4xx below: these are built
            // from the caller's input. EmailAlreadyRegistered names the address, which
            // is fine in the response and permanent in Loki.
            log.warn("Request failed errorCode={} status={} path={}",
                    ex.getErrorCode(), ex.getStatus().value(), pathOf(request));
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
        // Resolved before the log, and logged explicitly: with no active span the id
        // is freshly minted and never stored to MDC, so relying on the correlation
        // field would leave the traceId in the response quoted in no log line at all —
        // the decoration the logging rules forbid. Threaded through so the detail and
        // the log carry the same id.
        String traceId = TraceIdHolder.resolve();
        log.error("Unhandled exception traceId={}", traceId, ex);

        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote traceId %s when reporting it.".formatted(traceId), traceId);
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
     * The same failure arriving by the other route: constraints on a path variable or
     * request parameter rather than on a body. Spring answers those with a bare 400 and
     * no {@code errors[]}, so a caller could not tell which parameter was wrong and the
     * two halves of validation reported differently.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ValidationError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ValidationError(nameOf(result.getMethodParameter()),
                                error.getDefaultMessage())))
                .toList();

        log.warn("Request validation failed parameters={}", errors.stream().map(ValidationError::field).toList());

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.");
        body.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Null unless the build keeps parameter names; {@code -parameters} is on by default under Boot. */
    private static String nameOf(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
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
            log.error("Request failed status={} path={}", statusCode.value(), pathOf(request), ex);
        } else {
            log.warn("Request rejected status={} reason={} path={}",
                    statusCode.value(), ex.getClass().getSimpleName(), pathOf(request));
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
            problemDetail.setProperty("traceId", TraceIdHolder.resolve());
            problemDetail.setProperty("code", codeFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /** {@code getDescription} already returns {@code uri=/path}, which made the log read {@code path=uri=/path}. */
    private static String pathOf(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith(URI_PREFIX) ? description.substring(URI_PREFIX.length()) : description;
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        return problem(status, code, detail, TraceIdHolder.resolve());
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail, String traceId) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(ApiErrors.typeUri(code));
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code.name());
        // Always present — the only handle a user can give support to find the log line,
        // which carries the same id through the correlation field of every line.
        body.setProperty("traceId", traceId);
        return body;
    }

    /**
     * Never {@code VALIDATION_FAILED} — that code's published contract is an
     * {@code errors[]} naming each rejected field, and none of these carry one. A
     * client branching on {@code code} was being told to read an array that is
     * never there.
     */
    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND.name();
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED.name();
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE.name();
            default -> status.is4xxClientError()
                    ? ErrorCode.MALFORMED_REQUEST.name()
                    : ErrorCode.INTERNAL_ERROR.name();
        };
    }
}
