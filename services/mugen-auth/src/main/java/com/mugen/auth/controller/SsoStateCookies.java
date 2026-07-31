package com.mugen.auth.controller;

import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.auth.config.SsoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The short-lived cookie that ties an in-flight SSO handshake to one browser.
 * <p>
 * Note the two attributes that differ from {@link RefreshTokenCookies}, both
 * forced by what this cookie has to survive:
 * <ul>
 *   <li>{@code SameSite=Lax}, not {@code Strict}. The callback arrives as a
 *       top-level navigation <em>from the provider</em>, which is cross-site — a
 *       Strict cookie would simply not be sent, and the check it exists for could
 *       never run. Lax is sent on exactly this case (a top-level GET) and on
 *       nothing else, so it is the weakest relaxation that works.</li>
 *   <li>Path {@code /api/v1/auth/sso}, narrower than the refresh cookie's, so it is
 *       not attached to any other endpoint.</li>
 * </ul>
 * It carries no authority of its own: it proves only that this browser is the one
 * the redirect was issued to, and it is cleared the moment the callback lands.
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
                // Outlives nothing: the same window in which the pending
                // authorization is valid in Redis.
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
                // Reuses the refresh cookie's setting so there is one switch for
                // "this environment is plain HTTP", not two that can disagree.
                .secure(refreshCookieProperties.secure())
                .path(PATH)
                .sameSite("Lax");
    }
}
