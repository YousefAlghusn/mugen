package com.mugen.auth.oauth;

import org.springframework.util.StringUtils;

/**
 * The only four things mugen needs from an identity provider, normalised so
 * {@link com.mugen.auth.service.OAuthService} never knows whose response it holds.
 * Everything else — avatar, locale, follower count — is dropped: identity only.
 *
 * @param providerUserId    the provider's stable id, never the email
 * @param email             may be null; a provider can withhold it entirely when the
 *                          account keeps its addresses private
 * @param emailVerified     load-bearing — it decides whether this login may be
 *                          matched onto an existing mugen account
 * @param suggestedUsername a starting point, not a guarantee; may collide
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
