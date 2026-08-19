package com.mugen.auth.controller;

import com.mugen.auth.config.AuthApiDocs;
import com.mugen.auth.dto.AuthResponse;
import com.mugen.auth.dto.LoginRequest;
import com.mugen.auth.dto.RegisterRequest;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.AuthService;
import com.mugen.web.openapi.Throws;
import com.mugen.web.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = """
        Obtaining, renewing and discarding credentials. Every response here returns \
        the access token in the body and the refresh token as a cookie — see the \
        `refreshCookie` security scheme for why they are split.""")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    /**
     * Register a new account and sign in.
     *
     * <p>Creates the account and returns a token pair immediately — there is no separate
     * login step and no email confirmation gate in front of it.
     *
     * <p>A {@code mugen.user.registered} event is written to the outbox in the same
     * transaction, so the profile in mugen-user follows asynchronously. Delivery is at
     * least once: a consumer must deduplicate on {@code eventId}.
     *
     * @return the access token; the refresh token is set as a cookie
     */
    @ApiResponse(responseCode = "201", description = "Account created")
    @Throws({AuthExceptions.EmailAlreadyRegistered.class, AuthExceptions.UsernameTaken.class})
    @PublicEndpoint
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        TokenPair tokens = authService.register(
                request.username(), request.email(), request.password(), RequestContexts.of(httpRequest));

        return respondWith(tokens, HttpStatus.CREATED);
    }

    /**
     * Sign in with email and password.
     *
     * <p>Opens a new session; existing sessions on other devices are untouched.
     *
     * <p>A wrong email and a wrong password are both reported as
     * {@code INVALID_CREDENTIALS}. The distinction is withheld on purpose — reporting it
     * would turn this endpoint into an account-enumeration oracle.
     *
     * @return the access token; the refresh token is set as a cookie
     */
    @ApiResponse(responseCode = "200", description = "Signed in")
    @Throws({AuthExceptions.InvalidCredentials.class, AuthExceptions.AccountDisabled.class})
    @PublicEndpoint
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        TokenPair tokens = authService.login(request.email(), request.password(), RequestContexts.of(httpRequest));

        return respondWith(tokens, HttpStatus.OK);
    }

    /**
     * Exchange the refresh cookie for a new token pair.
     *
     * <p><strong>Takes no request body.</strong> The refresh token is read from the
     * cookie and only from the cookie — accepting it in a body would invite clients to
     * keep it somewhere a script can reach, which is what {@code HttpOnly} prevents.
     *
     * <p>Rotates on every call: the old refresh token stops working the moment this
     * returns. Presenting one at an already-spent version is treated as replay and
     * revokes the entire session, including whoever holds the legitimate copy.
     *
     * <p>Not callable from Swagger UI's "Try it out" — the cookie is {@code HttpOnly},
     * so only a browser that has actually signed in can send it.
     *
     * @return a fresh access token; the rotated refresh token is set as a cookie
     */
    @ApiResponse(responseCode = "200", description = "New token pair")
    @Throws({AuthExceptions.TokenInvalid.class, AuthExceptions.SessionNotFound.class,
            AuthExceptions.SessionReplayDetected.class})
    @SecurityRequirement(name = AuthApiDocs.REFRESH_COOKIE_SCHEME)
    @PublicEndpoint
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            // Hidden because the refreshCookie security scheme already describes it,
            // reading the real name from configuration. springdoc renders the
            // annotation's value, so as a parameter it would publish this placeholder
            // string rather than the name Spring resolves it to.
            @Parameter(hidden = true)
            @CookieValue(name = "${mugen.auth.refresh-cookie.name}", required = false) String refreshToken) {

        if (!StringUtils.hasText(refreshToken)) {
            throw new AuthExceptions.TokenInvalid("Refresh token is not valid.");
        }
        return respondWith(authService.refresh(refreshToken), HttpStatus.OK);
    }

    /**
     * End the current session.
     *
     * <p>Revokes the session behind the refresh cookie and clears the cookie.
     *
     * <p><strong>Always answers 204</strong>, including when no usable cookie arrived.
     * Logout must not be able to fail: reporting an error for an already-expired token
     * would leave a client believing it is still signed in.
     *
     * <p>Access tokens already issued stay signature-valid until they expire. The
     * revocation goes to Redis, which is what the gateway checks on every request, so
     * the effective window is the access token's TTL at most.
     */
    @ApiResponse(responseCode = "204", description = "Session ended and cookie cleared — also the answer "
            + "when no cookie was sent")
    @SecurityRequirement(name = AuthApiDocs.REFRESH_COOKIE_SCHEME)
    @PublicEndpoint
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Parameter(hidden = true)
            @CookieValue(name = "${mugen.auth.refresh-cookie.name}", required = false) String refreshToken) {

        if (StringUtils.hasText(refreshToken)) {
            try {
                authService.logout(refreshToken);
            } catch (AuthExceptions.TokenInvalid | AuthExceptions.SessionNotFound ex) {
                log.debug("Logout with an unusable refresh token; clearing the cookie anyway");
            }
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> respondWith(TokenPair tokens, HttpStatus status) {
        ResponseCookie cookie = refreshTokenCookies.issue(tokens.refreshToken());

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AuthResponse.bearer(tokens.accessToken(), tokens.accessTokenTtl().toSeconds()));
    }
}
