package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;

/**
 * What survives between the redirect out to a provider and the callback coming back.
 * Held in Redis, keyed by {@code state}.
 *
 * @param provider     stored, not read from the callback path, so a state minted for
 *                     Google cannot be redeemed at the GitHub callback
 * @param codeVerifier the PKCE secret; never leaves this service until the token call
 * @param redirectUri  already checked against the allowlist when the flow started
 * @param browserNonce binds the flow to the browser that started it, via a matching
 *                     {@code SameSite=Lax} cookie. Without it the flow is open to
 *                     login CSRF — see docs/dev/context.md, "Phase 2 — SSO design".
 */
public record PendingAuthorization(
        OAuthProvider provider,
        String codeVerifier,
        String redirectUri,
        String browserNonce
) {
}
