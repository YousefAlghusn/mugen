package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Google's OpenID Connect user-info response.
 * <p>
 * The easy one: everything needed arrives in a single response, including
 * {@code email_verified}, so there is no follow-up call.
 */
@Component
public class GoogleProfileMapper implements OAuthProfileMapper {

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public OAuthUserProfile map(OAuth2User user, OAuth2AccessToken accessToken) {
        // "sub" is the OIDC subject identifier: stable for the life of the Google
        // account and never reissued, which is exactly what oauth_links needs. It is
        // what getName() returns here because the registration's
        // user-name-attribute is "sub".
        String subject = user.getName();
        String email = user.getAttribute("email");

        return new OAuthUserProfile(
                subject,
                email,
                verifiedFlag(user.getAttributes().get("email_verified")),
                UsernameSuggestions.fromEmailOrName(email, user.getAttribute("given_name")));
    }

    /**
     * Google sends a real JSON boolean, but the OIDC spec permits the string
     * {@code "true"} and some providers still do that. Anything else — including
     * absent — is read as not verified, because this flag decides whether the login
     * may be matched onto an existing account.
     */
    private static boolean verifiedFlag(Object claim) {
        return switch (claim) {
            case Boolean verified -> verified;
            case String verified -> Boolean.parseBoolean(verified);
            case null, default -> false;
        };
    }
}
