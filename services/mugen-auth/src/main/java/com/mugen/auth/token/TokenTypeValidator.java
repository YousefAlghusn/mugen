package com.mugen.auth.token;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token whose {@code type} claim is not the one the caller expects.
 * <p>
 * Applied per decoder, so the resource server accepts only access tokens and the
 * refresh endpoint accepts only refresh tokens. A token is then usable in exactly
 * one place, no matter that both are signed by the same key.
 */
public record TokenTypeValidator(String expectedType) implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token", "Token is not valid for this endpoint", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return expectedType.equals(token.getClaimAsString(TokenType.CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(ERROR);
    }
}
