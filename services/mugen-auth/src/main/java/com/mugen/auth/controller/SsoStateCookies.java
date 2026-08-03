package com.mugen.auth.controller;

import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.auth.config.SsoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The short-lived cookie tying an in-flight SSO handshake to one browser. It carries
 * no authority of its own and is cleared the moment the callback lands.
 * <p>
 * {@code SameSite=Lax}, not {@code Strict} as {@link RefreshTokenCookies} uses: the
 * callback is a cross-site top-level navigation, on which Strict is never sent, so the
 * check would never run. Lax covers exactly that case and nothing else.
 */
@Component
@RequiredArgsConstructor
public class SsoStateCookies {

    public static final String NAME = "mugen_sso";

    private static final String PATH = "/api/v1/auth/sso";

    private final RefreshCookieProperties refreshCookieProperties;
    private final SsoProperties ssoProperties;

    public ResponseCookie issue(String browserNonce) {
        return base(browserNonce)
                // The same window the pending authorization is valid for in Redis.
                .maxAge(ssoProperties.stateTtl())
                .build();
    }

    /** Must match {@link #issue} in name, path and attributes or it will not clear. */
    public ResponseCookie clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                // One switch for "this environment is plain HTTP", not two.
                .secure(refreshCookieProperties.secure())
                .path(PATH)
                .sameSite("Lax");
    }
}
