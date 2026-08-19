package com.mugen.auth.controller;

import com.mugen.auth.dto.MeResponse;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.RevocationCacheService;
import com.mugen.web.openapi.Throws;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Token introspection", description = "Answers \"who am I\" and \"is this token still good\".")
public class TokenIntrospectController {

    private final RevocationCacheService revocationCache;

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
     * few minutes still has unexpired access tokens in circulation. This adds the Redis
     * revocation check on top — the same check the gateway makes on every request.
     *
     * <p>Intended for a client deciding whether to bother refreshing. It is not a
     * substitute for the gateway's own check: nothing stops a caller skipping it.
     *
     * <p>A client that only wants a yes/no can branch on the status alone and never
     * read the body. The 401 still carries one, because a service with a single
     * endpoint answering 401 differently from every other is the kind of exception
     * that has to be remembered.
     */
    @ApiResponse(responseCode = "200", description = "Token is valid and its session has not been revoked. "
            + "Empty body.")
    @Throws(AuthExceptions.TokenRevoked.class)
    @GetMapping("/validate")
    public ResponseEntity<Void> validate(@AuthenticationPrincipal Jwt token) {
        UUID sessionId = CurrentUser.sessionId(token);

        if (revocationCache.isRevoked(sessionId)) {
            throw new AuthExceptions.TokenRevoked();
        }
        return ResponseEntity.ok().build();
    }
}
