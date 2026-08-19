package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for every expected failure in the system.
 * <p>
 * Carrying the {@link HttpStatus} and {@link ErrorCode} on the exception is what
 * lets {@link GlobalExceptionHandler} stay a single method with no {@code if}
 * chain: throwing sites decide the outcome, the handler only renders it. Both are
 * read from the subclass's {@link ApiError} rather than passed in, so the same
 * declaration answers a caller and documents the endpoint.
 * <p>
 * The message on an AppException is written for the client and is safe to expose.
 * Anything not extending this class is treated as unexpected and rendered as a
 * generic 500 with its message withheld.
 */
@Getter
@ApiError(code = ErrorCode.INTERNAL_ERROR, status = HttpStatus.INTERNAL_SERVER_ERROR,
        description = "Something failed that should not have. The `traceId` is what support needs.")
public abstract class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    protected AppException(String message) {
        this(message, null);
    }

    /**
     * Keeps the underlying failure for the logs while the client still sees only
     * {@code message}. Used where the real reason is a third party's response, which
     * must never be echoed back.
     */
    protected AppException(String message, Throwable cause) {
        super(message, cause);

        // getClass() is the concrete subclass even here, which is what makes the
        // annotation on it readable without the constructor being told twice.
        ApiErrorSpec spec = ApiErrors.of(getClass());
        this.errorCode = spec.code();
        this.status = spec.status();
    }
}
