package com.mugen.web.error;

import com.mugen.shared.error.ErrorCode;

/**
 * 401 — the token verifies, but the session behind it has been revoked.
 * <p>
 * Shared because two places make this exact decision against the same Redis key: the
 * gateway on every request, and mugen-auth again on its own endpoints. One declaration
 * keeps the code and the explanation a client reads identical from both.
 */
@ApiError(code = ErrorCode.TOKEN_REVOKED,
        description = "The session was revoked — by a sign-out elsewhere, or by replay detection.")
public class TokenRevokedException extends UnauthorizedException {

    public TokenRevokedException() {
        super("Session has been revoked.");
    }
}
