package com.mugen.auth.controller;

import com.mugen.auth.service.RevocationCacheService;
import com.mugen.auth.dto.MeResponse;
import com.mugen.auth.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Token introspection", description = "Answers \"who am I\" and \"is this token still good\".")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class TokenIntrospectController {

    private final RevocationCacheService revocationCache;

    /**
     * Read straight off the verified token — no database call. Reaching this method
     * at all means the signature, issuer, expiry and token type already passed.
     */
    @Operation(summary = "Identity as the access token asserts it",
            description = """
                    Read straight off the verified token — no database call. Reaching this at \
                    all means the signature, issuer, expiry and token type already passed.

                    Note the consequence: this reflects the token, not current state. A role \
                    granted or revoked after the token was issued shows up at the next \
                    `/refresh`, not here.""")
    @ApiResponse(responseCode = "200", description = "The token's `userId`, `sessionId` and roles")
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
    @Operation(summary = "Is this access token still usable?",
            description = """
                    A valid signature is necessary but not sufficient: a session revoked in the \
                    last few minutes still has unexpired access tokens in circulation. This adds \
                    the Redis revocation check on top — the same check the gateway makes on \
                    every request.

                    Intended for a client deciding whether to bother refreshing. It is not a \
                    substitute for the gateway's own check: nothing stops a caller skipping it.""")
    @ApiResponse(responseCode = "200", description = "Token is valid and its session has not been revoked. Empty body.")
    @ApiResponse(responseCode = "401", description = """
            Token is unusable — either it failed verification, or its session has been revoked. \
            **Empty body, not a problem document**: this endpoint answers with a status code \
            only, so a client can branch on it without parsing anything.""",
            content = @Content)
    @GetMapping("/validate")
    public ResponseEntity<Void> validate(@AuthenticationPrincipal Jwt token) {
        UUID sessionId = CurrentUser.sessionId(token);

        return revocationCache.isRevoked(sessionId)
                ? ResponseEntity.status(401).build()
                : ResponseEntity.ok().build();
    }
}
