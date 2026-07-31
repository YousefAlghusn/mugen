package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Turns one provider's user-info response into the shape this service works in.
 * <p>
 * Providers agree on the OAuth 2.0 handshake and on almost nothing after it: the
 * account id is {@code sub} at Google and a JSON number at GitHub, and only one of
 * them tells you whether the email is verified without a second request. Confining
 * that to one small class per provider is what keeps
 * {@link com.mugen.auth.service.OAuthService} free of {@code if (provider == ...)}.
 */
public interface OAuthProfileMapper {

    OAuthProvider provider();

    /**
     * @param user        the parsed user-info response
     * @param accessToken the provider token, for mappers that need a second call —
     *                    GitHub's private-email lookup is the reason this parameter
     *                    exists
     */
    OAuthUserProfile map(OAuth2User user, OAuth2AccessToken accessToken);
}