package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Turns one provider's user-info response into the shape this service works in.
 * <p>
 * Providers agree on the OAuth 2.0 handshake and on almost nothing after it: where the
 * account id lives, what the email field is called, and whether verification is
 * reported at all without a second request. Confining that to one small class per
 * provider is what keeps {@link com.mugen.auth.service.OAuthService} free of
 * {@code if (provider == ...)}.
 */
public interface OAuthProfileMapper {

    OAuthProvider provider();

    /**
     * @param user        the parsed user-info response
     * @param accessToken the provider token, for mappers whose user-info response is
     *                    not self-sufficient and need a second call to complete it
     */
    OAuthUserProfile map(OAuth2User user, OAuth2AccessToken accessToken);
}