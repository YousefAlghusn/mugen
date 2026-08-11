package com.mugen.auth.unit;

import com.mugen.auth.config.SsoProperties;
import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.OAuthLink;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.oauth.AuthorizationRequestStore;
import com.mugen.auth.oauth.OAuthClientRegistry;
import com.mugen.auth.oauth.OAuthProfileMapper;
import com.mugen.auth.oauth.OAuthUserProfile;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.auth.repository.OAuthLinkRepository;
import com.mugen.auth.repository.UserRepository;
import com.mugen.auth.service.AuthService;
import com.mugen.auth.service.OAuthService;
import com.mugen.auth.service.UserEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The SSO flow's decisions, as opposed to its plumbing. Two of them decide whether
 * a stranger can end up holding someone else's account:
 * <ul>
 *   <li>whether an unverified provider email may be matched onto a mugen account</li>
 *   <li>whether a {@code state} minted for one provider may be spent at another</li>
 * </ul>
 * Both get a test that fails loudly if the answer ever changes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthServiceTest {

    private static final String CALLBACK = "http://localhost:8080/api/v1/auth/sso/google/callback";
    private static final String FRONTEND = "http://localhost:4200/auth/callback";

    @Mock
    private OAuthClientRegistry registry;

    @Mock
    private AuthorizationRequestStore pendingAuthorizations;

    @Mock
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;

    @Mock
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> userService;

    @Mock
    private UserRepository users;

    @Mock
    private OAuthLinkRepository links;

    @Mock
    private AuthService authService;

    @Mock
    private UserEventPublisher userEvents;

    @Mock
    private OAuthProfileMapper profileMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SsoProperties properties;
    private OAuthService oauthService;

    @BeforeEach
    void setUp() {
        properties = new SsoProperties(Duration.ofMinutes(5), FRONTEND, List.of(FRONTEND));
        oauthService = new OAuthService(
                registry, pendingAuthorizations, tokenClient, userService, users, links,
                authService, userEvents, properties, transactionTemplate);

        // Runs the callback rather than returning null, so complete() still exercises
        // linkOrCreate. That the boundary is a real one is not observable here — it
        // needs a Spring context and a transaction manager.
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(new SimpleTransactionStatus()));

        when(registry.registrationFor(OAuthProvider.GOOGLE)).thenReturn(registration(CALLBACK));
        when(registry.mapperFor(OAuthProvider.GOOGLE)).thenReturn(profileMapper);
    }

    private static ClientRegistration registration(String redirectUri) {
        return ClientRegistration.withRegistrationId("google")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private static User userWithId(String username, String email) {
        User user = User.fromSso(username, email);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static OAuthUserProfile verifiedProfile() {
        return new OAuthUserProfile("google-sub-1", "kaneki@mugen.dev", true, "kaneki");
    }

    // ---------------------------------------------------------------- begin ----

    @Nested
    @DisplayName("begin")
    class Begin {

        @Test
        @DisplayName("sends the browser to the provider with state and a PKCE challenge")
        void buildsAuthorizationUrl() {
            URI authorizationUri = oauthService.begin(OAuthProvider.GOOGLE, FRONTEND).authorizationUri();

            assertThat(authorizationUri.toString())
                    .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                    .contains("client_id=client-id")
                    .contains("state=")
                    .contains("code_challenge=")
                    // S256, never "plain" — a plain challenge is the verifier in
                    // clear text and protects against nothing.
                    .contains("code_challenge_method=S256");
        }

        @Test
        @DisplayName("keeps the code verifier server-side, never in the redirect")
        void storesVerifierOutOfBand() {
            OAuthService.SsoRedirect redirect = oauthService.begin(OAuthProvider.GOOGLE, null);

            ArgumentCaptor<PendingAuthorization> stored = ArgumentCaptor.forClass(PendingAuthorization.class);
            verify(pendingAuthorizations).save(any(), stored.capture());

            PendingAuthorization pending = stored.getValue();
            assertThat(pending.provider()).isEqualTo(OAuthProvider.GOOGLE);
            assertThat(pending.codeVerifier()).isNotBlank();
            assertThat(pending.redirectUri()).isEqualTo(FRONTEND);

            // The whole point of PKCE: the secret must not be observable to anyone
            // who can see the URL the browser was sent to.
            assertThat(redirect.authorizationUri().toString()).doesNotContain(pending.codeVerifier());
        }

        @Test
        @DisplayName("issues a browser nonce, and does not send it to the provider either")
        void issuesBrowserNonce() {
            OAuthService.SsoRedirect redirect = oauthService.begin(OAuthProvider.GOOGLE, FRONTEND);

            ArgumentCaptor<PendingAuthorization> stored = ArgumentCaptor.forClass(PendingAuthorization.class);
            verify(pendingAuthorizations).save(any(), stored.capture());

            assertThat(redirect.browserNonce()).isNotBlank();
            assertThat(stored.getValue().browserNonce()).isEqualTo(redirect.browserNonce());
            assertThat(redirect.authorizationUri().toString()).doesNotContain(redirect.browserNonce());
        }

        @Test
        @DisplayName("refuses a post-login target that is not on the allowlist")
        void rejectsUnlistedRedirect() {
            assertThatThrownBy(() -> oauthService.begin(OAuthProvider.GOOGLE, "https://evil.example/steal"))
                    .isInstanceOf(AuthExceptions.SsoRedirectNotAllowed.class);

            verify(pendingAuthorizations, never()).save(any(), any());
        }

        @Test
        @DisplayName("refuses to start when the configured redirect-uri is still a template")
        void rejectsTemplatedRedirectUri() {
            when(registry.registrationFor(OAuthProvider.GOOGLE))
                    .thenReturn(registration("{baseUrl}/login/oauth2/code/google"));

            assertThatThrownBy(() -> oauthService.begin(OAuthProvider.GOOGLE, FRONTEND))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("absolute URL");
        }
    }

    // --------------------------------------------------------- consumeState ----

    @Nested
    @DisplayName("consumeState")
    class ConsumeState {

        @Test
        @DisplayName("a state that was never issued is refused")
        void unknownStateRejected() {
            when(pendingAuthorizations.consume("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> oauthService.consumeState("nope", "nonce"))
                    .isInstanceOf(AuthExceptions.SsoStateInvalid.class);
        }

        @Test
        @DisplayName("a missing state is refused without reaching Redis")
        void blankStateRejected() {
            assertThatThrownBy(() -> oauthService.consumeState("  ", "nonce"))
                    .isInstanceOf(AuthExceptions.SsoStateInvalid.class);

            verify(pendingAuthorizations, never()).consume(any());
        }

        @Test
        @DisplayName("a valid state from the wrong browser is refused — this is login CSRF")
        void mismatchedBrowserNonceRejected() {
            when(pendingAuthorizations.consume("state"))
                    .thenReturn(Optional.of(new PendingAuthorization(
                            OAuthProvider.GOOGLE, "verifier", FRONTEND, "issued-to-this-browser")));

            // The attacker holds a genuine code and state from their own sign-in and
            // has lured the victim's browser through the callback. The victim's
            // browser never received the nonce, so the flow stops here rather than
            // signing them into the attacker's account.
            assertThatThrownBy(() -> oauthService.consumeState("state", "some-other-browser"))
                    .isInstanceOf(AuthExceptions.SsoStateInvalid.class);
        }

        @Test
        @DisplayName("a callback with no SSO cookie at all is refused")
        void missingBrowserNonceRejected() {
            when(pendingAuthorizations.consume("state"))
                    .thenReturn(Optional.of(new PendingAuthorization(
                            OAuthProvider.GOOGLE, "verifier", FRONTEND, "issued-to-this-browser")));

            assertThatThrownBy(() -> oauthService.consumeState("state", null))
                    .isInstanceOf(AuthExceptions.SsoStateInvalid.class);
        }

        @Test
        @DisplayName("the browser that started the flow is let through")
        void matchingBrowserNonceAccepted() {
            PendingAuthorization issued =
                    new PendingAuthorization(OAuthProvider.GOOGLE, "verifier", FRONTEND, "nonce");
            when(pendingAuthorizations.consume("state")).thenReturn(Optional.of(issued));

            assertThat(oauthService.consumeState("state", "nonce")).isEqualTo(issued);
        }
    }

    // ------------------------------------------------------------- complete ----

    @Nested
    @DisplayName("complete")
    class Complete {

        private final PendingAuthorization pending =
                new PendingAuthorization(OAuthProvider.GOOGLE, "verifier", FRONTEND, "nonce");

        // The cross-provider state test lived here and could not survive GitHub's
        // removal: OAuthProvider has one value, so no second provider exists to mint a
        // state for. The check in OAuthService.complete stays — restore the test with
        // the next provider (docs/dev/context.md, "Removing GitHub SSO").

        @Test
        @DisplayName("a callback with no code is refused")
        void rejectsMissingCode() {
            assertThatThrownBy(() -> oauthService.complete(
                    OAuthProvider.GOOGLE, pending, null, "state", RequestContext.unknown()))
                    .isInstanceOf(AuthExceptions.SsoStateInvalid.class);
        }

        @Test
        @DisplayName("a provider that rejects the exchange never reaches the database")
        void exchangeFailureIsContained() {
            when(tokenClient.getTokenResponse(any())).thenThrow(new IllegalStateException("invalid_grant"));

            assertThatThrownBy(() -> oauthService.complete(
                    OAuthProvider.GOOGLE, pending, "code", "state", RequestContext.unknown()))
                    .isInstanceOf(AuthExceptions.SsoExchangeFailed.class)
                    // The provider's own wording must not reach the client.
                    .hasMessageNotContaining("invalid_grant");

            verify(users, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a completed handshake ends in the same token pair a password login would")
        void issuesTokensThroughAuthService() {
            User user = userWithId("kaneki", "kaneki@mugen.dev");
            TokenPair expected = new TokenPair("access", "refresh", Duration.ofMinutes(15));

            when(tokenClient.getTokenResponse(any())).thenReturn(accessTokenResponse());
            when(userService.loadUser(any())).thenReturn(googleUser());
            when(profileMapper.map(any(), any())).thenReturn(verifiedProfile());
            when(links.findByProviderAccount(OAuthProvider.GOOGLE, "google-sub-1"))
                    .thenReturn(Optional.of(OAuthLink.link(user, OAuthProvider.GOOGLE, "google-sub-1")));
            when(authService.issueTokens(any(), any())).thenReturn(expected);

            RequestContext context = new RequestContext("JUnit", "127.0.0.1");
            TokenPair tokens = oauthService.complete(OAuthProvider.GOOGLE, pending, "code", "state", context);

            assertThat(tokens).isEqualTo(expected);
            verify(authService).issueTokens(user, context);
        }

        private OAuth2AccessTokenResponse accessTokenResponse() {
            return OAuth2AccessTokenResponse.withToken("provider-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(3600)
                    .build();
        }

        private OAuth2User googleUser() {
            return new DefaultOAuth2User(
                    List.of(),
                    Map.of("sub", "google-sub-1", "email", "kaneki@mugen.dev", "email_verified", true),
                    "sub");
        }
    }

    // --------------------------------------------------------- linkOrCreate ----

    @Nested
    @DisplayName("linkOrCreate")
    class LinkOrCreate {

        @Test
        @DisplayName("an existing link wins outright, without consulting the email at all")
        void existingLinkShortCircuits() {
            User user = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(OAuthProvider.GOOGLE, "google-sub-1"))
                    .thenReturn(Optional.of(OAuthLink.link(user, OAuthProvider.GOOGLE, "google-sub-1")));

            // Deliberately unverified: once a link exists it is the identity, so the
            // person changing their Google email must not break their sign-in.
            OAuthUserProfile changedEmail =
                    new OAuthUserProfile("google-sub-1", "new-address@mugen.dev", false, "kaneki");

            OAuthService.SsoUser resolved = oauthService.linkOrCreate(OAuthProvider.GOOGLE, changedEmail);

            assertThat(resolved.user()).isSameAs(user);
            assertThat(resolved.created()).isFalse();
            verify(users, never()).findByEmail(any());
        }

        @Test
        @DisplayName("a disabled account cannot be signed into through SSO")
        void disabledAccountRefused() {
            User user = userWithId("kaneki", "kaneki@mugen.dev");
            user.setEnabled(false);
            when(links.findByProviderAccount(OAuthProvider.GOOGLE, "google-sub-1"))
                    .thenReturn(Optional.of(OAuthLink.link(user, OAuthProvider.GOOGLE, "google-sub-1")));

            assertThatThrownBy(() -> oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile()))
                    .isInstanceOf(AuthExceptions.AccountDisabled.class);
        }

        @Test
        @DisplayName("an unverified email is never matched onto an existing account")
        void unverifiedEmailIsRefused() {
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            OAuthUserProfile unverified =
                    new OAuthUserProfile("google-sub-1", "kaneki@mugen.dev", false, "kaneki");

            assertThatThrownBy(() -> oauthService.linkOrCreate(OAuthProvider.GOOGLE, unverified))
                    .isInstanceOf(AuthExceptions.SsoEmailNotVerified.class);

            // The account-takeover path this closes: an attacker puts a victim's
            // address on a throwaway provider account. Nothing may be read or
            // written on the strength of an unverified claim.
            verify(users, never()).findByEmail(any());
            verify(users, never()).saveAndFlush(any());
            verify(links, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a provider that shares no email cannot create an account")
        void missingEmailIsRefused() {
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            OAuthUserProfile noEmail = new OAuthUserProfile("sub-1", null, false, "kaneki");

            assertThatThrownBy(() -> oauthService.linkOrCreate(OAuthProvider.GOOGLE, noEmail))
                    .isInstanceOf(AuthExceptions.SsoEmailUnavailable.class);
        }

        @Test
        @DisplayName("a verified email links to the account that already owns it")
        void verifiedEmailLinksToExistingAccount() {
            User existing = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.of(existing));
            when(links.existsByUserIdAndProvider(existing.getId(), OAuthProvider.GOOGLE)).thenReturn(false);

            OAuthService.SsoUser resolved = oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            assertThat(resolved.user()).isSameAs(existing);
            assertThat(resolved.created()).isFalse();
            verify(users, never()).saveAndFlush(any());

            ArgumentCaptor<OAuthLink> saved = ArgumentCaptor.forClass(OAuthLink.class);
            verify(links).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getProviderUserId()).isEqualTo("google-sub-1");
        }

        @Test
        @DisplayName("a first-time sign-in creates a password-less account")
        void firstSignInCreatesAccount() {
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.empty());
            when(users.existsByUsername("kaneki")).thenReturn(false);
            when(users.saveAndFlush(any())).thenAnswer(invocation -> {
                User created = invocation.getArgument(0);
                ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
                return created;
            });

            OAuthService.SsoUser resolved = oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            assertThat(resolved.created()).isTrue();
            assertThat(resolved.user().getUsername()).isEqualTo("kaneki");
            assertThat(resolved.user().getEmail()).isEqualTo("kaneki@mugen.dev");
            // No hash to compare against means no password login on this account —
            // AuthService.login refuses it rather than comparing against null.
            assertThat(resolved.user().hasPassword()).isFalse();
        }

        @Test
        @DisplayName("a taken username is not a reason to fail the sign-in")
        void usernameCollisionIsResolved() {
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.empty());
            when(users.existsByUsername("kaneki")).thenReturn(true);
            when(users.saveAndFlush(any())).thenAnswer(invocation -> {
                User created = invocation.getArgument(0);
                ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
                return created;
            });

            OAuthService.SsoUser resolved = oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            assertThat(resolved.user().getUsername())
                    .isNotEqualTo("kaneki")
                    .startsWith("kaneki")
                    .hasSizeLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("a second provider account cannot be linked onto the same user")
        void secondAccountForSameProviderRejected() {
            User existing = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.of(existing));
            when(links.existsByUserIdAndProvider(existing.getId(), OAuthProvider.GOOGLE)).thenReturn(true);

            assertThatThrownBy(() -> oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile()))
                    .isInstanceOf(AuthExceptions.SsoProviderAlreadyLinked.class);
        }

        @Test
        @DisplayName("losing the race to create a link signs the user in anyway")
        void concurrentFirstSignInResolvesToTheWinner() {
            User winner = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(OAuthProvider.GOOGLE, "google-sub-1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(OAuthLink.link(winner, OAuthProvider.GOOGLE, "google-sub-1")));
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.empty());
            when(users.existsByUsername(any())).thenReturn(false);
            when(users.saveAndFlush(any())).thenAnswer(invocation -> {
                User created = invocation.getArgument(0);
                ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
                return created;
            });
            when(links.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_oauth_links"));

            OAuthService.SsoUser resolved = oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            // The unique index is the guarantee; the loser re-reads rather than
            // showing a legitimate user an error on their very first sign-in.
            assertThat(resolved.user()).isSameAs(winner);
            assertThat(resolved.created()).isFalse();
        }
    }

    /**
     * SSO creates accounts just as registration does, so it owes the same
     * {@code mugen.user.registered} event — and owes it exactly once. An account
     * that never emits it has no profile in mugen-user, permanently; one that emits
     * it twice would be fine (consumers deduplicate on eventId) but for the account
     * that does not exist, which is the case the race below covers.
     */
    @Nested
    @DisplayName("linkOrCreate → mugen.user.registered")
    class RegistrationEvent {

        @Test
        @DisplayName("a first-time sign-in announces the new account")
        void firstSignInPublishes() {
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.empty());
            when(users.existsByUsername(any())).thenReturn(false);
            when(users.saveAndFlush(any())).thenAnswer(invocation -> {
                User created = invocation.getArgument(0);
                ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
                return created;
            });

            OAuthService.SsoUser resolved = oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            verify(userEvents).userRegistered(resolved.user());
        }

        @Test
        @DisplayName("signing in again through an existing link announces nothing")
        void returningUserPublishesNothing() {
            User existing = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(any(), any()))
                    .thenReturn(Optional.of(OAuthLink.link(existing, OAuthProvider.GOOGLE, "google-sub-1")));

            oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            verifyNoInteractions(userEvents);
        }

        @Test
        @DisplayName("linking a provider to an account that already exists announces nothing")
        void linkingExistingAccountPublishesNothing() {
            User existing = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(any(), any())).thenReturn(Optional.empty());
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.of(existing));
            when(links.existsByUserIdAndProvider(existing.getId(), OAuthProvider.GOOGLE)).thenReturn(false);

            oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            // The account is not new. mugen-user already has its profile, and a
            // second event would be an update it has no way to interpret.
            verifyNoInteractions(userEvents);
        }

        /**
         * Losing the link race means signing in as the winner, so the account this
         * call created is unreachable. Announcing it would have mugen-user build a
         * profile for an account nobody can ever log into.
         */
        @Test
        @DisplayName("the loser of a first-sign-in race announces nothing")
        void raceLoserPublishesNothing() {
            User winner = userWithId("kaneki", "kaneki@mugen.dev");
            when(links.findByProviderAccount(OAuthProvider.GOOGLE, "google-sub-1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(OAuthLink.link(winner, OAuthProvider.GOOGLE, "google-sub-1")));
            when(users.findByEmail("kaneki@mugen.dev")).thenReturn(Optional.empty());
            when(users.existsByUsername(any())).thenReturn(false);
            when(users.saveAndFlush(any())).thenAnswer(invocation -> {
                User created = invocation.getArgument(0);
                ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
                return created;
            });
            when(links.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_oauth_links"));

            oauthService.linkOrCreate(OAuthProvider.GOOGLE, verifiedProfile());

            verifyNoInteractions(userEvents);
        }
    }
}
