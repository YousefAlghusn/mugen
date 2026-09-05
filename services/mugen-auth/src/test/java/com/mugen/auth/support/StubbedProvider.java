package com.mugen.auth.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google, as far as an integration test is concerned: a registration to resolve, a code
 * to exchange, and a user-info response the test chooses.
 * <p>
 * <b>A profile-scoped bean rather than {@code @MockitoBean}</b>, and the reason is the
 * context cache. Every distinct set of bean overrides is a distinct cache key, and the
 * containers are beans in that context — so a single {@code @MockitoBean} on one
 * integration class is a second application context, a second SQL Server and a second
 * Redis. Declared under the {@code test} profile it is in the one context every
 * integration test already shares.
 * <p>
 * The service's real clients are still defined ({@code SsoClientConfig}); these are
 * {@code @Primary}, so nothing in a test can reach the network by accident.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class StubbedProvider {

    /**
     * Boot only builds this from {@code spring.security.oauth2.client.*}, which lives in
     * the {@code sso} profile — so without one here every SSO path answers "provider not
     * configured" before reaching anything worth testing.
     */
    @Bean
    ClientRegistrationRepository stubClientRegistrations() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                .clientId("stub-client-id")
                .clientSecret("stub-client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8081/api/v1/auth/sso/google/callback")
                .scope("openid", "email", "profile")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName("sub")
                .build());
    }

    @Bean
    @Primary
    ProviderStub providerStub() {
        return new ProviderStub();
    }

    /**
     * Both halves of the handshake in one bean, because a test that stubs one always
     * stubs the other: the code exchange, then the user-info call its token is for.
     */
    public static class ProviderStub
            implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> {

        private Map<String, Object> attributes = Map.of();

        /** The user-info response the next sign-in receives. */
        public void signsInAs(String subject, String email, boolean emailVerified) {
            Map<String, Object> response = new HashMap<>();
            response.put("sub", subject);
            response.put("email", email);
            response.put("email_verified", emailVerified);
            response.put("given_name", "Ken");
            this.attributes = response;
        }

        @Override
        public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest request) {
            return OAuth2AccessTokenResponse.withToken("stub-provider-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(3600)
                    .build();
        }

        @Override
        public OAuth2User loadUser(OAuth2UserRequest request) {
            return new DefaultOAuth2User(List.of(), attributes, "sub");
        }
    }
}
