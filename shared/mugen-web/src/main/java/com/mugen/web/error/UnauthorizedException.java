package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 401 — not authenticated, or the credentials/token presented are not valid.
 * <p>
 * Messages on this type must stay vague. "Invalid email or password" rather than
 * "no such user": distinguishing the two turns the login endpoint into an oracle
 * for enumerating which email addresses have accounts.
 */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(errorCode, HttpStatus.UNAUTHORIZED, message);
    }

    public UnauthorizedException(String message) {
        super(ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, message);
    }

    /**
     * Keeps the underlying failure for the logs while the client still sees only
     * {@code message}. Used where the real reason is a third party's response, which
     * must never be echoed back.
     */
    public UnauthorizedException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, HttpStatus.UNAUTHORIZED, message, cause);
    }
}
