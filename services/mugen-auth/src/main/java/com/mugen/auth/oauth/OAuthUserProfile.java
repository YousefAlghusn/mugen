package com.mugen.auth.oauth;

import org.springframework.util.StringUtils;

/**
 * The only four things mugen needs from an identity provider, normalised so that
 * {@link com.mugen.auth.service.OAuthService} never has to know whose response it
 * is looking at. Everything else a provider returns — avatar, locale, plan,
 * follower count — is deliberately dropped: this service stores identity only.
 *
 * @param providerUserId the provider's stable id (Google {@code sub}, GitHub
 *                       numeric {@code id}). Never the email — see
 *                       {@link com.mugen.auth.entity.OAuthLink#getProviderUserId()}.
 * @param email          may be null. GitHub returns nothing when every address on
 *                       the account is private.
 * @param emailVerified  whether the <em>provider</em> has confirmed the address.
 *                       Load-bearing: it is what decides whether this login may be
 *                       matched onto an existing mugen account.
 * @param suggestedUsername a starting point for a username, not a guarantee — it
 *                       may collide, and is sanitised before use.
 */
public record OAuthUserProfile(
        String providerUserId,
        String email,
        boolean emailVerified,
        String suggestedUsername
) {

    public boolean hasEmail() {
        return StringUtils.hasText(email);
    }
}
