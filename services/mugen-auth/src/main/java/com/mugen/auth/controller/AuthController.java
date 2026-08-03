package com.mugen.auth.controller;

import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.AuthService;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.dto.AuthResponse;
import com.mugen.auth.dto.LoginRequest;
import com.mugen.auth.dto.RegisterRequest;
import com.mugen.auth.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    private final RefreshCookieProperties cookieProperties;

    @Operation(summary = "Register a new account and sign in",
            description = """
                    Creates the account and returns a token pair immediately — there is no \
                    separate login step and no email confirmation gate in front of it.

                    A `mugen.user.registered` event is written to the outbox in the same \
                    transaction, so the profile in mugen-user follows asynchronously. It is \
                    delivered at least once: a consumer must deduplicate on `eventId`.""")
    @ApiResponse(responseCode = "201", description = "Account created; access token in the body, refresh token in the cookie")
    @ApiResponse(responseCode = "400", description = "Validation failed — `errors[]` names the offending fields",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Email or username already taken",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        TokenPair tokens = authService.register(
                request.username(), request.email(), request.password(), RequestContexts.of(httpRequest));

        return respondWith(tokens, HttpStatus.CREATED);
    }

    @Operation(summary = "Sign in with email and password",
            description = """
                    Opens a new session; existing sessions on other devices are untouched.

                    A wrong email and a wrong password are reported identically, as \
                    `INVALID_CREDENTIALS`. The distinction is withheld on purpose — reporting \
                    it would turn this endpoint into an account-enumeration oracle.""")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Wrong credentials, or the account is disabled",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        TokenPair tokens = authService.login(request.email(), request.password(), RequestContexts.of(httpRequest));

        return respondWith(tokens, HttpStatus.OK);
    }

    /**
     * Reads the refresh token from the cookie, never the body. A body parameter
     * would invite clients to store it somewhere reachable by script, which is
     * exactly what the HttpOnly cookie exists to prevent.
     */
    @Operation(summary = "Exchange the refresh cookie for a new token pair",
            description = """
                    **Takes no request body.** The refresh token is read from the cookie and \
                    only from the cookie — accepting it in a body would invite clients to keep \
                    it somewhere a script can reach, which is exactly what `HttpOnly` prevents.

                    Rotates on every call: the old refresh token stops working the moment this \
                    returns, and the response sets a replacement cookie. Presenting a token at \
                    an already-spent version is treated as replay and revokes the entire \
                    session — including whoever is holding the legitimate copy.

                    Not callable from Swagger UI's "Try it out": the cookie is `HttpOnly`, so \
                    only a browser that has actually signed in can send it.""",
            security = @SecurityRequirement(name = OpenApiConfig.REFRESH_COOKIE_SCHEME))
    @ApiResponse(responseCode = "200", description = "New access token in the body; rotated refresh token in the cookie")
    @ApiResponse(responseCode = "401", description = "Cookie absent, expired, invalid, or replayed after rotation",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            // Hidden because it is already described by the refreshCookie security
            // scheme, which reads the real name from configuration. Rendered as a
            // parameter it would show this placeholder string literally — springdoc
            // reads the annotation, not the value Spring resolves it to.
            @Parameter(hidden = true)
            @CookieValue(name = "${mugen.auth.refresh-cookie.name}", required = false) String refreshToken) {

        if (!StringUtils.hasText(refreshToken)) {
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
    @Operation(summary = "End the current session",
            description = """
                    Revokes the session behind the refresh cookie and clears the cookie.

                    **Always answers 204**, including when no usable cookie arrived. Logout \
                    must not be able to fail: reporting an error for an already-expired token \
                    would leave a client believing it is still signed in.

                    Access tokens already issued stay signature-valid until they expire. The \
                    revocation is published to Redis, which is what the gateway checks on \
                    every request — so the effective window is the access token's TTL at \
                    most, and normally nothing.""",
            security = @SecurityRequirement(name = OpenApiConfig.REFRESH_COOKIE_SCHEME))
    @ApiResponse(responseCode = "204", description = "Session ended and cookie cleared — also the answer when no cookie was sent")
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
