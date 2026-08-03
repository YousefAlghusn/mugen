package com.mugen.auth.dto;

/**
 * The body returned by register, login and refresh.
 * <p>
 * Note what is absent: the refresh token. It travels only as an HttpOnly cookie.
 * Putting it in the body would hand it to any script on the page and defeat the
 * point of the cookie.
 *
 * @param accessToken to be kept in memory by the client — never localStorage or
 * sessionStorage, both readable by any XSS
 * @param tokenType always {@code Bearer}
 * @param expiresIn seconds until the access token expires, so a client can refresh ahead
 * of time rather than on a failed request
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
