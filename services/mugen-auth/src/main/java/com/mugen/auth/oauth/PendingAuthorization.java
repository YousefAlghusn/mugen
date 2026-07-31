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
 */
public record PendingAuthorization(
        OAuthProvider provider,
        String codeVerifier,
        String redirectUri
) {
}
