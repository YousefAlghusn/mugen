package com.mugen.auth.controller;

import com.mugen.auth.dto.MeResponse;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.web.openapi.Throws;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Token introspection", description = "Answers \"who am I\" and \"is this token still good\".")
// Every secured endpoint of this service can answer it, not only /validate — see
// RevokedSessionFilter.
@Throws(AuthExceptions.TokenRevoked.class)
public class TokenIntrospectController {

    /**
     * Identity as the access token asserts it.
     *
     * <p>Read straight off the verified token — no database call. Reaching this at all
     * means the signature, issuer, expiry and token type already passed.
     *
     * <p>Note the consequence: this reflects the token, not current state. A role granted
     * or revoked after the token was issued shows up at the next {@code /refresh}, not
     * here.
     *
     * @return the token's {@code userId}, {@code sessionId} and roles
     */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt token) {
        return new MeResponse(
                CurrentUser.userId(token),
                CurrentUser.sessionId(token),
                CurrentUser.roles(token));
    }

    /**
     * Is this access token still usable?
     *
     * <p>A valid signature is necessary but not sufficient: a session revoked in the last
     * few minutes still has unexpired access tokens in circulation. Reaching this handler
     * at all means both were checked — the signature by the resource server, the
     * revocation by {@code RevokedSessionFilter}, which refuses a revoked session on every
     * endpoint of this service and not only on this one.
     *
     * <p>So the answer is the status: 200 here, 401 with {@code TOKEN_REVOKED} if the
     * session has ended. Intended for a client deciding whether to bother refreshing.
     *
     * <p>A client that only wants a yes/no can branch on the status alone and never
     * read the body. The 401 still carries one, because a service with a single
     * endpoint answering 401 differently from every other is the kind of exception
     * that has to be remembered.
     */
    @ApiResponse(responseCode = "200", description = "Token is valid and its session has not been revoked. "
            + "Empty body.")
    @GetMapping("/validate")
    public ResponseEntity<Void> validate() {
        return ResponseEntity.ok().build();
    }
}
