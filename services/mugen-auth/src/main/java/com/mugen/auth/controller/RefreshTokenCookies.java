package com.mugen.auth.controller;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.config.RefreshCookieProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the refresh-token cookie, in one place so its attributes cannot drift
 * between the endpoints that set it.
 * <p>
 * The refresh token is the only credential this system puts in a cookie. It is
 * {@code HttpOnly} so script on the page cannot read it — which is the whole reason
 * the access token is returned in the body instead and held in memory: an XSS can
 * steal a 15-minute access token, but not a 30-day refresh token.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    private final RefreshCookieProperties cookieProperties;
    private final JwtProperties jwtProperties;

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken)
                .maxAge(jwtProperties.refreshTokenTtl())
                .build();
    }

    /**
     * Expires the cookie on logout. Must carry identical name, path and attributes
     * to the one that was set, or the browser treats it as a different cookie and
     * leaves the original in place.
     */
    public ResponseCookie clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(cookieProperties.name(), value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .path(cookieProperties.path())
                .sameSite(cookieProperties.sameSite());
    }
}
