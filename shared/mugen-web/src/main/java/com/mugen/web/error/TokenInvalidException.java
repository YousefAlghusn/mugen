package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;

/**
 * 401 — no access token was sent, or the one sent cannot be used.
 * <p>
 * Shared rather than per-service because it is the one failure every secured endpoint
 * in every service can answer with: {@code ProblemAuthenticationEntryPoint} throws it
 * for the requests the filter chain refuses, and {@code ApiErrorResponsesCustomizer}
 * documents it on every operation that is not {@code @PublicEndpoint} — both from this
 * one declaration, so the 401 a caller reads about is the 401 they get.
 * <p>
 * Missing, expired, malformed and revoked are deliberately one code. A client's answer
 * to all four is the same — refresh, then sign in if that fails — and separating them
 * reports on a token the caller may not have been meant to hold.
 */
@ApiError(code = ErrorCode.TOKEN_INVALID,
        description = "No access token was sent, or the one sent is expired, malformed or revoked. "
                + "Obtain a new one from `/refresh`, or sign in again if that also fails.")
public class TokenInvalidException extends UnauthorizedException {

    public TokenInvalidException(String message) {
        super(message);
    }
}
