package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;

/**
 * What has to survive between the redirect out to a provider and the callback
 * coming back — held server-side in Redis, keyed by the {@code state} value.
 * <p>
 * Server-side and not in a cookie, because the callback arrives as a cross-site
 * top-level navigation from the provider. A {@code SameSite=Strict} cookie would
 * not be sent on it at all, and relaxing that to {@code Lax} to make it work would
 * weaken the same protection the refresh cookie depends on.
 *
 * @param provider    which provider this exchange belongs to. Stored rather than
 *                    taken from the callback path, so a state minted for Google
 *                    cannot be redeemed at the GitHub callback.
 * @param codeVerifier the PKCE secret. It never leaves this service until it is
 *                    sent to the token endpoint, which is what stops an
 *                    intercepted authorization code from being redeemable.
 * @param redirectUri where to send the browser afterwards, already checked against
 *                    the allowlist when the flow started.
 * @param browserNonce ties this flow to the browser that started it. The matching
 *                    value goes out in a {@code SameSite=Lax} cookie, so a callback
 *                    is only honoured for the browser the redirect was issued to.
 *                    Without it the flow is open to login CSRF: an attacker starts
 *                    a sign-in, obtains a valid {@code code} and {@code state} for
 *                    their <em>own</em> provider account, then lures a victim
 *                    through the callback — silently signing the victim into the
 *                    attacker's account, where everything they then do is visible
 *                    to the attacker.
 */
public record PendingAuthorization(
        OAuthProvider provider,
        String codeVerifier,
        String redirectUri,
        String browserNonce
) {
}
