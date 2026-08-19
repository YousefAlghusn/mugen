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
@ApiError(code = ErrorCode.INVALID_CREDENTIALS, status = HttpStatus.UNAUTHORIZED,
        description = "The credential presented was missing, wrong or no longer valid.")
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
