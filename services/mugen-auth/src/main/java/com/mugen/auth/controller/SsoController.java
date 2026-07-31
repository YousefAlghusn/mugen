package com.mugen.auth.controller;

import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.auth.service.OAuthService;
import com.mugen.web.error.AppException;
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

/**
 * The two browser-facing endpoints of the SSO flow.
 * <p>
 * Both answer with a redirect rather than a body, because the only thing that ever
 * calls them is a browser following a link. That also decides how failures are
 * reported: once the flow is under way the user is mid-navigation, so a refusal
 * sends them back to their own application with an {@code error} parameter instead
 * of rendering raw JSON at them. Requests that are broken before the flow starts —
 * an unknown provider, a missing or forged {@code state} — do get the service's
 * normal RFC 9457 response, since those indicate a bad client rather than a user
 * whose sign-in did not work out.
 * <p>
 * Note what the success redirect does <em>not</em> carry: the access token. Only the
 * refresh cookie is set, and the application calls {@code /refresh} to get an access
 * token into memory. A token in a redirect URL would be written to browser history,
 * sent onward in {@code Referer}, and logged by every proxy in between.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoController {

    private final OAuthService oauthService;
    private final RefreshTokenCookies refreshCookies;
    private final SsoStateCookies ssoCookies;

    /**
     * Sends the browser to the provider's consent screen.
     *
     * @param redirectUri optional post-login target; must be on the allowlist
     */
    @GetMapping("/{provider}")
    public ResponseEntity<Void> start(@PathVariable String provider,
                                      @RequestParam(name = "redirect_uri", required = false) String redirectUri) {

        OAuthService.SsoRedirect redirect = oauthService.begin(providerOf(provider), redirectUri);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirect.authorizationUri())
                .header(HttpHeaders.SET_COOKIE, ssoCookies.issue(redirect.browserNonce()).toString())
                .build();
    }

    /**
     * Where the provider sends the browser back.
     * <p>
     * The state is redeemed first, before anything else is attempted, for two
     * reasons: it is single-use, so it must be spent whether or not the rest
     * succeeds; and it is what tells us where this user's application lives, which
     * every later branch needs in order to report anything to them at all.
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = SsoStateCookies.NAME, required = false) String nonce,
                                         HttpServletRequest httpRequest) {

        OAuthProvider resolved = providerOf(provider);
        PendingAuthorization pending = oauthService.consumeState(state, nonce);

        // The user declined consent, or the provider refused outright. Not an error
        // on our side, and its raw value is not echoed onward — it is attacker-
        // controlled text arriving in a query parameter.
        if (StringUtils.hasText(error)) {
            log.info("{} SSO was not granted: {}", resolved, error);
            return redirectBack(pending.redirectUri(), "sso_denied");
        }

        try {
            TokenPair tokens = oauthService.complete(
                    resolved, pending, code, state, RequestContexts.of(httpRequest));

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(pending.redirectUri()))
                    .header(HttpHeaders.SET_COOKIE, refreshCookies.issue(tokens.refreshToken()).toString())
                    // The handshake is over; the nonce has no further use.
                    .header(HttpHeaders.SET_COOKIE, ssoCookies.clear().toString())
                    .build();

        } catch (AppException ex) {
            // Everything reachable here is a decision this service made about a real
            // person's sign-in — an unverified email, a provider already linked. They
            // are told on their own site, in their own application's language; the
            // machine-readable code is enough for it to say something useful.
            log.info("{} SSO did not complete: {}", resolved, ex.getMessage());
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
                .header(HttpHeaders.SET_COOKIE, ssoCookies.clear().toString())
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
