package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for every expected failure in the system.
 * <p>
 * Carrying the {@link HttpStatus} and {@link ErrorCode} on the exception is what
 * lets {@link GlobalExceptionHandler} stay a single method with no {@code if}
 * chain: throwing sites decide the outcome, the handler only renders it.
 * <p>
 * The message on an AppException is written for the client and is safe to expose.
 * Anything not extending this class is treated as unexpected and rendered as a
 * generic 500 with its message withheld.
 */
@Getter
public abstract class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    protected AppException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    protected AppException(ErrorCode errorCode, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
    }
}
