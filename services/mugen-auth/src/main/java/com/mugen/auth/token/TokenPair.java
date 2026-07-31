package com.mugen.auth.token;

import java.time.Duration;

/**
 * What a successful login, registration or refresh produces.
 *
 * @param accessToken       goes to the client in the response body, to be held in
 *                          memory only — never localStorage or sessionStorage,
 *                          both of which are readable by any XSS on the page
 * @param refreshToken      set by the controller as an HttpOnly, Secure,
 *                          SameSite=Strict cookie scoped to /api/v1/auth, so
 *                          JavaScript cannot read it and it is not sent anywhere else
 * @param accessTokenTtl    lets the client schedule a refresh before expiry rather
 *                          than discovering it through a failed request
 */
public record TokenPair(String accessToken, String refreshToken, Duration accessTokenTtl) {
}
