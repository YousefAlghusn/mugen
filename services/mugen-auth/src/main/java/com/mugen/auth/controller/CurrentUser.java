package com.mugen.auth.controller;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/**
 * Reads the caller's identity out of a verified access token.
 * <p>
 * Every value here comes from a token whose signature, issuer, expiry and type
 * have already been checked by the resource server — by the time a controller sees
 * the {@link Jwt}, these claims are trustworthy.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }

    public static UUID sessionId(Jwt token) {
        return UUID.fromString(token.getClaimAsString("sessionId"));
    }

    public static List<String> roles(Jwt token) {
        List<String> roles = token.getClaimAsStringList("roles");
        return roles == null ? List.of() : roles;
    }
}
