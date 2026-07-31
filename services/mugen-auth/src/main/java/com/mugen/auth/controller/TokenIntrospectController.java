package com.mugen.auth.controller;

import com.mugen.auth.service.RevocationCacheService;
import com.mugen.auth.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Answers "who am I" and "is this token still good".
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class TokenIntrospectController {

    private final RevocationCacheService revocationCache;

    /**
     * Read straight off the verified token — no database call. Reaching this method
     * at all means the signature, issuer, expiry and token type already passed.
     */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt token) {
        return new MeResponse(
                CurrentUser.userId(token),
                CurrentUser.sessionId(token),
                CurrentUser.roles(token));
    }

    /**
     * Signature validity is necessary but not sufficient: a session revoked in the
     * last 15 minutes still has unexpired access tokens in circulation. This adds
     * the Redis revocation check on top, which is the same check the gateway makes
     * on every request.
     * <p>
     * 200 when usable, 401 when revoked. Intended for a client deciding whether to
     * bother refreshing, not as a substitute for the gateway's own check.
     */
    @GetMapping("/validate")
    public ResponseEntity<Void> validate(@AuthenticationPrincipal Jwt token) {
        UUID sessionId = CurrentUser.sessionId(token);

        return revocationCache.isRevoked(sessionId)
                ? ResponseEntity.status(401).build()
                : ResponseEntity.ok().build();
    }
}
