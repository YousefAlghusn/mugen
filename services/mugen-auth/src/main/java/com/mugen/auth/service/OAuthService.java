package com.mugen.auth.service;

import com.mugen.auth.config.SsoProperties;
import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.OAuthLink;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.oauth.AuthorizationRequestStore;
import com.mugen.auth.oauth.OAuthClientRegistry;
import com.mugen.auth.oauth.OAuthUserProfile;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.auth.oauth.UsernameSuggestions;
import com.mugen.auth.repository.OAuthLinkRepository;
import com.mugen.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single sign-on: the OAuth 2.0 authorization code flow, driven explicitly rather
 * than through {@code oauth2Login()}.
 * <p>
 * Spring's built-in login filter chain is built to end in an authenticated servlet
 * session. mugen has no sessions — a sign-in has to end in a mugen access token and
 * a rotating refresh cookie, exactly as {@link AuthService#login} does, so the two
 * paths converge on the same session and token machinery. Driving the flow here
 * also puts the two decisions that matter — where the browser may be sent
 * afterwards, and when a provider identity may be attached to an existing mugen
 * account — in readable code rather than in filter configuration.
 * <p>
 * The individual protocol steps are still Spring Security's: the token exchange,
 * PKCE generation and the user-info call are all its implementations, so this
 * class contains no hand-written OAuth protocol handling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    /** 256 bits, url-safe. The same width Spring's own resolver uses for state. */
    private static final StringKeyGenerator STATE_GENERATOR =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    /** Bounded so a pathologically popular handle cannot spin here. */
    private static final int USERNAME_ATTEMPTS = 5;

    private final OAuthClientRegistry registry;
    private final AuthorizationRequestStore pendingAuthorizations;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> userService;
    private final UserRepository users;
    private final OAuthLinkRepository links;
    private final AuthService authService;
    private final SsoProperties properties;

    /**
     * Starts a sign-in: mints {@code state} and a PKCE verifier, remembers them, and
     * returns the provider URL to send the browser to.
     *
     * @param requestedRedirectUri where to land afterwards; must be on the allowlist
     */
    public URI begin(OAuthProvider provider, String requestedRedirectUri) {
        ClientRegistration registration = registry.registrationFor(provider);
        String redirectUri = properties.resolveRedirectUri(requestedRedirectUri);
        String state = STATE_GENERATOR.generateKey();

        OAuth2AuthorizationRequest request = authorizationRequest(registration, state, null);

        pendingAuthorizations.save(state, new PendingAuthorization(
                provider,
                request.getAttribute(PkceParameterNames.CODE_VERIFIER),
                redirectUri));

        log.debug("Starting {} SSO, state {}", provider, state);
        return URI.create(request.getAuthorizationRequestUri());
    }

    /**
     * Redeems the {@code state} from a callback, single-use.
     * <p>
     * Separate from {@link #complete} because the caller needs the redirect target
     * out of it before anything else can go wrong — a failure after this point is
     * still shown to the user on their own site rather than as a bare error page.
     *
     * @throws AuthExceptions.SsoStateInvalid if it is missing, expired, already used
     *         or was never issued
     */
    public PendingAuthorization consumeState(String state) {
        if (!StringUtils.hasText(state)) {
            throw new AuthExceptions.SsoStateInvalid();
        }
        return pendingAuthorizations.consume(state).orElseThrow(AuthExceptions.SsoStateInvalid::new);
    }

    /**
     * Finishes a sign-in: exchanges the code, reads the provider profile, resolves it
     * to a mugen user and issues a token pair.
     * <p>
     * Deliberately not {@code @Transactional}. Two network calls happen here, and
     * holding a database connection open across a third party's latency is how a
     * connection pool gets exhausted by a provider having a slow day. Only
     * {@link #linkOrCreate} — the part that actually writes — is transactional.
     */
    public TokenPair complete(OAuthProvider provider,
                              PendingAuthorization pending,
                              String code,
                              String state,
                              RequestContext context) {

        // The state was minted for one provider. Without this, a state issued on the
        // Google flow could be presented at the GitHub callback, and the code would
        // then be redeemed against a registration it was never meant for.
        if (pending.provider() != provider) {
            log.warn("SSO state issued for {} was presented at the {} callback", pending.provider(), provider);
            throw new AuthExceptions.SsoStateInvalid();
        }
        if (!StringUtils.hasText(code)) {
            throw new AuthExceptions.SsoStateInvalid();
        }

        ClientRegistration registration = registry.registrationFor(provider);
        OAuth2AccessToken accessToken = exchange(registration, pending, code, state, provider);
        OAuthUserProfile profile = loadProfile(registration, accessToken, provider);

        SsoUser resolved = linkOrCreate(provider, profile);
        log.info("SSO sign-in via {} for user {} ({})",
                provider, resolved.user().getId(), resolved.created() ? "new account" : "existing account");

        return authService.issueTokens(resolved.user(), context);
    }

    /**
     * Maps a provider identity onto a mugen account, creating one if this is a first
     * sign-in.
     * <p>
     * The order of the checks is the security-relevant part:
     * <ol>
     *   <li>An existing link wins outright. It was established by an earlier
     *       verified sign-in and is keyed on the provider's immutable account id, so
     *       it stays correct even after the person changes their email.</li>
     *   <li>Otherwise an email is required, and it must be one the provider has
     *       <em>verified</em>. An unverified address is only a claim, and honouring
     *       it would let anyone put a stranger's address on a throwaway provider
     *       account to take over — or pre-emptively squat — the matching mugen
     *       account.</li>
     *   <li>A verified address matching an existing account links to it. This is
     *       intentional: the provider has confirmed control of the mailbox that
     *       account was registered with, which is the same proof a password reset
     *       would rely on.</li>
     * </ol>
     */
    @Transactional
    public SsoUser linkOrCreate(OAuthProvider provider, OAuthUserProfile profile) {
        Optional<OAuthLink> existingLink = links.findByProviderAccount(provider, profile.providerUserId());
        if (existingLink.isPresent()) {
            User linked = existingLink.get().getUser();
            requireEnabled(linked);
            return new SsoUser(linked, false);
        }

        if (!profile.hasEmail()) {
            throw new AuthExceptions.SsoEmailUnavailable(provider.name());
        }
        if (!profile.emailVerified()) {
            log.info("Refused {} SSO for an unverified email address", provider);
            throw new AuthExceptions.SsoEmailNotVerified(provider.name());
        }

        String email = normalise(profile.email());
        Optional<User> byEmail = users.findByEmail(email);

        User user;
        boolean created;
        if (byEmail.isPresent()) {
            user = byEmail.get();
            requireEnabled(user);

            // uq_oauth_links_user_provider allows one link per provider per user.
            // Reached when someone signs in with a second Google account whose
            // verified email is already on a mugen account linked to a first one.
            if (links.existsByUserIdAndProvider(user.getId(), provider)) {
                throw new AuthExceptions.SsoProviderAlreadyLinked(provider.name());
            }
            created = false;
        } else {
            user = users.saveAndFlush(User.fromSso(availableUsername(profile.suggestedUsername()), email));
            created = true;
        }

        try {
            links.saveAndFlush(OAuthLink.link(user, provider, profile.providerUserId()));
        } catch (DataIntegrityViolationException ex) {
            // Two first-time sign-ins for the same provider account arriving at once.
            // uq_oauth_links_provider_account is the real guarantee; the loser of the
            // race re-reads the winner's link rather than failing a legitimate login.
            log.debug("Lost the race creating a {} link; re-reading", provider);
            User winner = links.findByProviderAccount(provider, profile.providerUserId())
                    .map(OAuthLink::getUser)
                    .orElseThrow(() -> ex);
            return new SsoUser(winner, false);
        }

        return new SsoUser(user, created);
    }

    private OAuth2AccessToken exchange(ClientRegistration registration,
                                       PendingAuthorization pending,
                                       String code,
                                       String state,
                                       OAuthProvider provider) {

        // Rebuilt rather than stored: Spring reads redirect_uri and the PKCE
        // code_verifier back off this object when it builds the token request, so it
        // has to carry the same values the authorization request went out with.
        OAuth2AuthorizationRequest authorizationRequest =
                authorizationRequest(registration, state, pending.codeVerifier());

        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success(code)
                .redirectUri(registration.getRedirectUri())
                .state(state)
                .build();

        try {
            OAuth2AccessTokenResponse response = tokenClient.getTokenResponse(
                    new OAuth2AuthorizationCodeGrantRequest(
                            registration,
                            new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse)));
            return response.getAccessToken();

        } catch (RuntimeException ex) {
            // The provider's own error text can name the client id or echo the code,
            // so it is logged and not propagated to the browser.
            log.warn("{} rejected the authorization code exchange: {}", provider, ex.getMessage());
            throw new AuthExceptions.SsoExchangeFailed(provider.name(), ex);
        }
    }

    private OAuthUserProfile loadProfile(ClientRegistration registration,
                                         OAuth2AccessToken accessToken,
                                         OAuthProvider provider) {
        try {
            OAuth2User user = userService.loadUser(new OAuth2UserRequest(registration, accessToken));
            return registry.mapperFor(provider).map(user, accessToken);
        } catch (RuntimeException ex) {
            log.warn("Could not read the {} user profile: {}", provider, ex.getMessage());
            throw new AuthExceptions.SsoExchangeFailed(provider.name(), ex);
        }
    }

    /**
     * Builds the authorization request. With {@code codeVerifier} null a fresh PKCE
     * pair is generated (the outbound leg); passing one back reconstructs the same
     * request for the token exchange.
     */
    private OAuth2AuthorizationRequest authorizationRequest(ClientRegistration registration,
                                                            String state,
                                                            String codeVerifier) {
        String redirectUri = registration.getRedirectUri();
        if (redirectUri.contains("{")) {
            // CommonOAuth2Provider's default is the template
            // "{baseUrl}/login/oauth2/code/{registrationId}", which only Spring's own
            // login filter resolves. Left in place it would be sent to the provider
            // literally and fail there, with an error that points nowhere near here.
            throw new IllegalStateException((
                    "spring.security.oauth2.client.registration.%s.redirect-uri must be an absolute URL, not the "
                            + "template %s — this flow does not run through oauth2Login() and cannot resolve it")
                    .formatted(registration.getRegistrationId(), redirectUri));
        }

        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                .clientId(registration.getClientId())
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .redirectUri(redirectUri)
                .scopes(registration.getScopes())
                .state(state);

        if (codeVerifier == null) {
            // Generates the verifier, derives the S256 challenge, and stashes the
            // verifier in the request's attributes — which is where the token
            // exchange later reads it from.
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        } else {
            builder.attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier));
        }

        return builder.build();
    }

    /**
     * Finds a free username near the provider's suggestion.
     * <p>
     * Suffixes are random rather than sequential: {@code kaneki2} would tell the next
     * person to try that handle exactly how many accounts already share it.
     * {@code existsByUsername} is a courtesy check — the unique index on
     * {@code users.username} is the guarantee, and a lost race surfaces as the
     * constraint violation {@link #linkOrCreate} already handles.
     */
    private String availableUsername(String suggestion) {
        if (!users.existsByUsername(suggestion)) {
            return suggestion;
        }

        for (int attempt = 0; attempt < USERNAME_ATTEMPTS; attempt++) {
            String candidate = UsernameSuggestions.withSuffix(
                    suggestion, ThreadLocalRandom.current().nextInt(1_000, 10_000));
            if (!users.existsByUsername(candidate)) {
                return candidate;
            }
        }

        // Wide enough that a collision here is not worth another round trip.
        return UsernameSuggestions.withSuffix(suggestion, ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static void requireEnabled(User user) {
        if (!user.isEnabled()) {
            throw new AuthExceptions.AccountDisabled();
        }
    }

    /** Same canonical form {@link AuthService} stores, or the match would miss. */
    private static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param created whether this sign-in brought a new account into existence —
     *                the signal {@code mugen.user.registered} will be published on
     *                (tasks.md 2.8), and the reason this is not just a {@link User}
     */
    public record SsoUser(User user, boolean created) {
    }
}
