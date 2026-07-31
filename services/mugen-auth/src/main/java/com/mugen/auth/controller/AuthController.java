package com.mugen.auth.controller;

import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.AuthService;
import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.dto.AuthResponse;
import com.mugen.auth.dto.LoginRequest;
import com.mugen.auth.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshCookies;
    private final RefreshCookieProperties cookieProperties;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        TokenPair tokens = authService.register(
                request.username(), request.email(), request.password(), contextOf(httpRequest));

        return respondWith(tokens, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        TokenPair tokens = authService.login(request.email(), request.password(), contextOf(httpRequest));

        return respondWith(tokens, HttpStatus.OK);
    }

    /**
     * Reads the refresh token from the cookie, never the body. A body parameter
     * would invite clients to store it somewhere reachable by script, which is
     * exactly what the HttpOnly cookie exists to prevent.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = "${mugen.auth.refresh-cookie.name}", required = false) String refreshToken) {

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthExceptions.TokenInvalid("Refresh token is not valid.");
        }
        return respondWith(authService.refresh(refreshToken), HttpStatus.OK);
    }

    /**
     * Always answers 204, whether or not a usable cookie arrived.
     * <p>
     * Logout must not be able to fail: reporting an error for an
     * already-expired token would leave the client believing it is still signed in,
     * and the cookie is cleared either way.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "${mugen.auth.refresh-cookie.name}", required = false) String refreshToken) {

        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                authService.logout(refreshToken);
            } catch (AuthExceptions.TokenInvalid | AuthExceptions.SessionNotFound ex) {
                log.debug("Logout with an unusable refresh token; clearing the cookie anyway");
            }
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> respondWith(TokenPair tokens, HttpStatus status) {
        ResponseCookie cookie = refreshCookies.issue(tokens.refreshToken());

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AuthResponse.bearer(tokens.accessToken(), tokens.accessTokenTtl().toSeconds()));
    }

    private static RequestContext contextOf(HttpServletRequest request) {
        return new RequestContext(request.getHeader(HttpHeaders.USER_AGENT), clientIpOf(request));
    }

    /**
     * Trusts {@code X-Forwarded-For} because the gateway is the only way in
     * (CLAUDE.md architecture rules) and it sets the header. If this service were
     * ever exposed directly the value would be caller-controlled and this would
     * need to become a trusted-proxy check.
     */
    private static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Leftmost entry is the original client; the rest are proxies.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
