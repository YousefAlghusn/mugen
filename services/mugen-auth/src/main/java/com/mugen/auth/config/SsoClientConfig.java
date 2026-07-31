package com.mugen.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The two OAuth protocol clients {@link com.mugen.auth.service.OAuthService} drives.
 * <p>
 * Spring Boot only declares these itself as part of {@code oauth2Login()}, which
 * this service does not use — so they are declared here instead of being
 * hand-written. Both are stateless and thread-safe, so one of each is enough.
 * <p>
 * Beans rather than {@code new} inside the service so a test can substitute a stub
 * for the provider without any network access.
 */
@Configuration(proxyBeanMethods = false)
public class SsoClientConfig {

    /** Redeems the authorization code, including sending the PKCE code_verifier. */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient() {
        return new RestClientAuthorizationCodeTokenResponseClient();
    }

    /**
     * Calls the registration's user-info endpoint with the access token. Provider
     * differences in the response are handled by
     * {@link com.mugen.auth.oauth.OAuthProfileMapper}, not here.
     */
    @Bean
    OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        return new DefaultOAuth2UserService();
    }
}
