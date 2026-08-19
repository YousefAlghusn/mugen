package com.mugen.auth.controller;

import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.auth.service.OAuthService;
import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.AppException;
import com.mugen.web.openapi.Throws;
import com.mugen.web.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;

/** The two browser-facing endpoints of the SSO flow. */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
@Tag(name = "SSO", description = """
        Sign-in through Google, as an OAuth 2.0 authorization code flow with PKCE.

        **Neither endpoint is a JSON call, and neither can be exercised from this page.** \
        They are navigation targets: you send the browser to `/sso/{provider}` as a link or a \
        redirect, the user leaves for the provider's consent screen, and the provider brings \
        them back to `/callback`. "Try it out" will show you a 302 and nothing useful — the \
        flow only means anything in a real browser that keeps the cookies.

        Both endpoints exist only while the `sso` profile is active. Without it there are no \
        client credentials, so no provider is registered and both answer 404.""")
// Either endpoint can be reached for a provider this deployment does not have.
@Throws(AuthExceptions.SsoProviderNotConfigured.class)
public class SsoController {

    private final OAuthService oauthService;
    private final RefreshTokenCookies refreshTokenCookies;
    private final SsoStateCookies ssoStateCookies;

    /**
     * Start sign-in — redirects to the provider's consent screen.
     *
     * <p>Point a browser here. The 302 carries the provider's authorization URL, with
     * PKCE and a single-use {@code state}, and sets a short-lived {@code SameSite=Lax}
     * nonce cookie binding the rest of the flow to this browser.
     *
     * <p>Nothing is created and nobody is signed in at this point.
     *
     * <p>{@code redirect_uri} must match the configured allowlist exactly. That check is
     * the only thing between this endpoint and an open redirect handing a look-alike site
     * a freshly signed-in browser.
     *
     * @param provider {@code google}
     * @param redirectUri where to send the browser once sign-in finishes; defaults to the
     * configured front-end callback
     */
    @ApiResponse(responseCode = "302", description = "`Location` is the provider's consent screen; "
            + "`Set-Cookie` carries the browser nonce")
    @Throws(AuthExceptions.SsoRedirectNotAllowed.class)
    @PublicEndpoint
    @GetMapping("/{provider}")
    public ResponseEntity<Void> start(@Parameter(example = "google") @PathVariable String provider,
                                      @RequestParam(name = "redirect_uri", required = false) String redirectUri) {

        OAuthService.SsoRedirect redirect = oauthService.begin(providerOf(provider), redirectUri);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirect.authorizationUri())
                .header(HttpHeaders.SET_COOKIE, ssoStateCookies.issue(redirect.browserNonce()).toString())
                .build();
    }

    /**
     * Where the provider returns the browser — not called directly.
     *
     * <p><strong>The provider calls this, not your application.</strong> The URL must be
     * registered in the provider's console; the query parameters below are the
     * provider's, documented so the flow can be read end to end rather than because a
     * client ever supplies them.
     *
     * <p>Always answers 302 back to the {@code redirect_uri} the flow started with. A
     * sign-in that did not work out arrives there with an {@code error} parameter naming
     * the reason — the user is mid-navigation, so they are told on their own site rather
     * than shown raw JSON. Only requests broken before the flow can start get a problem
     * document, because those mean a bad client rather than a failed sign-in.
     *
     * <p>The {@code error} parameter is an {@code ErrorCode} name, the same vocabulary the
     * {@code code} field of a problem document uses — one list for a client to switch on,
     * not two. What can arrive here:
     *
     * <ul>
     *   <li>{@code SSO_ACCESS_DENIED} — the user declined consent, or the provider refused
     *   <li>{@code SSO_EXCHANGE_FAILED} — the provider would not exchange the code
     *   <li>{@code SSO_EMAIL_UNAVAILABLE} — no email address was shared
     *   <li>{@code SSO_EMAIL_NOT_VERIFIED} — the address shared is unverified
     *   <li>{@code SSO_PROVIDER_ALREADY_LINKED} — that provider is linked to another account
     * </ul>
     *
     * <p>On success the response sets <strong>only the refresh cookie</strong>. The
     * access token is deliberately absent: a token in a redirect URL would reach browser
     * history, {@code Referer}, and every proxy log in between. The application calls
     * {@code /refresh} to get one into memory.
     *
     * @param provider {@code google}
     * @param code authorization code, exchanged server-side for the provider's tokens
     * @param state single-use and provider-bound, issued at {@code /sso/{provider}} and
     * redeemed exactly once here
     * @param error present instead of {@code code} when the user declined consent or the
     * provider refused
     */
    @ApiResponse(responseCode = "302", description = "Back to the application — with the refresh cookie "
            + "set, or with an `error` parameter carrying an `ErrorCode` name if sign-in was refused")
    @Throws(AuthExceptions.SsoStateInvalid.class)
    @PublicEndpoint
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@Parameter(example = "google") @PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         // Set by this service and replayed by the browser, never
                                         // supplied by a caller — so hidden, like the refresh cookie.
                                         @Parameter(hidden = true)
                                         @CookieValue(name = SsoStateCookies.NAME, required = false) String nonce,
                                         HttpServletRequest httpRequest) {

        OAuthProvider resolved = providerOf(provider);
        PendingAuthorization pending = oauthService.consumeState(state, nonce);

        // The user declined consent, or the provider refused outright. Not an error
        // on our side, and its raw value is not echoed onward — it is attacker-
        // controlled text arriving in a query parameter.
        if (StringUtils.hasText(error)) {
            log.info("SSO was not granted provider={} reason={}", resolved, error);
            return redirectBack(pending.redirectUri(), ErrorCode.SSO_ACCESS_DENIED.name());
        }

        try {
            TokenPair tokens = oauthService.complete(
                    resolved, pending, code, state, RequestContexts.of(httpRequest));

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(pending.redirectUri()))
                    .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()).toString())
                    // The handshake is over; the nonce has no further use.
                    .header(HttpHeaders.SET_COOKIE, ssoStateCookies.clear().toString())
                    .build();

        } catch (AppException ex) {
            // Everything reachable here is a decision this service made about a real
            // person's sign-in — an unverified email, a provider already linked. They
            // are told on their own site, in their own application's language; the
            // machine-readable code is enough for it to say something useful.
            log.warn("SSO did not complete provider={} errorCode={}: {}",
                    resolved, ex.getErrorCode(), ex.getMessage());
            return redirectBack(pending.redirectUri(), ex.getErrorCode().name());
        }
    }

    private ResponseEntity<Void> redirectBack(String redirectUri, String errorCode) {
        URI target = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", errorCode)
                .build(true)
                .toUri();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .header(HttpHeaders.SET_COOKIE, ssoStateCookies.clear().toString())
                .build();
    }

    /**
     * {@code /sso/google} to {@link OAuthProvider#GOOGLE}.
     *
     * @throws AuthExceptions.SsoProviderNotConfigured for anything unrecognised —
     *         the same answer a real provider without credentials gets, so the URL
     *         space cannot be probed to learn which providers exist but are switched
     *         off
     */
    private static OAuthProvider providerOf(String provider) {
        try {
            return OAuthProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AuthExceptions.SsoProviderNotConfigured(provider);
        }
    }
}
