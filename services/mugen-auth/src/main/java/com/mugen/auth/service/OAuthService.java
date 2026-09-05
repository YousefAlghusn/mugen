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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single sign-on: the OAuth 2.0 authorization code flow, driven explicitly rather
 * than through {@code oauth2Login()}, whose chain ends in a servlet session mugen
 * does not have. The protocol steps are still Spring Security's.
 * <p>
 * Reasoning in docs/dev/context.md, "Phase 2 — SSO design".
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

    private final OAuthClientRegistry oauthClientRegistry;
    private final AuthorizationRequestStore pendingAuthorizations;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService;
    private final UserRepository users;
    private final OAuthLinkRepository oauthLinks;
    private final AuthService authService;
    private final UserEventPublisher userEventPublisher;
    private final SsoProperties ssoProperties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Starts a sign-in: mints {@code state}, a PKCE verifier and a browser nonce,
     * remembers them, and returns the provider URL to send the browser to.
     *
     * @param requestedRedirectUri where to land afterwards; must be on the allowlist
     * @return the provider URL, and the nonce the caller must put in a cookie
     */
    public SsoRedirect begin(OAuthProvider provider, String requestedRedirectUri) {
        ClientRegistration registration = oauthClientRegistry.registrationFor(provider);
        String redirectUri = ssoProperties.resolveRedirectUri(requestedRedirectUri);
        String state = STATE_GENERATOR.generateKey();
        String browserNonce = STATE_GENERATOR.generateKey();

        OAuth2AuthorizationRequest request = authorizationRequest(registration, state, null);

        pendingAuthorizations.save(state, new PendingAuthorization(
                provider,
                request.getAttribute(PkceParameterNames.CODE_VERIFIER),
                redirectUri,
                browserNonce));

        log.debug("Starting SSO provider={}", provider);
        return new SsoRedirect(URI.create(request.getAuthorizationRequestUri()), browserNonce);
    }

    /**
     * Redeems the {@code state} from a callback, single-use, and checks it against
     * the browser that started the flow.
     * <p>
     * Separate from {@link #complete} because the caller needs the redirect target
     * before anything else can fail — so a later failure still reaches the user on
     * their own site rather than as a bare error page.
     *
     * @param browserNonce from the SSO cookie; the flow was issued to whoever holds it
     * @throws AuthExceptions.SsoStateInvalid if the state is missing, expired,
     *         already used, never issued, or belongs to a different browser
     */
    public PendingAuthorization consumeState(String state, String browserNonce) {
        if (!StringUtils.hasText(state)) {
            throw new AuthExceptions.SsoStateInvalid();
        }

        PendingAuthorization pending = pendingAuthorizations.consume(state)
                .orElseThrow(AuthExceptions.SsoStateInvalid::new);

        // Constant-time: a caller-supplied secret compared against one we hold.
        if (!StringUtils.hasText(browserNonce)
                || !MessageDigest.isEqual(
                        browserNonce.getBytes(StandardCharsets.UTF_8),
                        pending.browserNonce().getBytes(StandardCharsets.UTF_8))) {

            log.warn("SSO callback arrived without the browser nonce it was issued to — possible login CSRF");
            throw new AuthExceptions.SsoStateInvalid();
        }

        return pending;
    }

    /**
     * Finishes a sign-in: exchanges the code, reads the provider profile, resolves it
     * to a mugen user and issues a token pair.
     * <p>
     * Deliberately not {@code @Transactional}: two network calls happen here, and
     * holding a connection across a provider's latency is how a pool gets exhausted.
     * Only {@link #linkOrCreate}, which writes, runs in one — opened explicitly.
     */
    public TokenPair complete(OAuthProvider provider,
                              PendingAuthorization pending,
                              String code,
                              String state,
                              RequestContext context) {

        // Otherwise a state issued on one provider's flow could be presented at
        // another's callback, redeeming the code against a registration it was never
        // meant for.
        if (pending.provider() != provider) {
            log.warn("SSO state was presented at another provider's callback issuedFor={} presentedAt={}",
                    pending.provider(), provider);
            throw new AuthExceptions.SsoStateInvalid();
        }
        if (!StringUtils.hasText(code)) {
            throw new AuthExceptions.SsoStateInvalid();
        }

        ClientRegistration registration = oauthClientRegistry.registrationFor(provider);
        OAuth2AccessToken accessToken = exchange(registration, pending, code, state, provider);
        OAuthUserProfile profile = loadProfile(registration, accessToken, provider);

        // Through a TransactionTemplate, not a plain call: linkOrCreate is on this
        // same bean, so calling it directly bypasses the proxy and its @Transactional
        // never applies — which is how the writes below ran unatomically until the
        // first real provider sign-in threw on the MANDATORY publisher.
        SsoUser resolved = transactionTemplate.execute(status -> linkOrCreate(provider, profile));
        log.info("SSO sign-in provider={} userId={} newAccount={}",
                provider, resolved.user().getId(), resolved.created());

        return authService.issueTokens(resolved.user(), context);
    }

    /**
     * Maps a provider identity onto a mugen account, creating one if this is a first
     * sign-in.
     * <p>
     * The order of the checks is the security-relevant part: an existing link wins
     * outright (keyed on the provider's immutable account id, so it survives an email
     * change); otherwise a <em>verified</em> email is required, because an unverified
     * one is only a claim and would let anyone squat a stranger's account; and a
     * verified address matching an existing account links to it, on the same proof a
     * password reset relies on.
     * <p>
     * {@code MANDATORY} states the requirement the user row, the link row and the
     * outbox event all depend on. {@link #complete} supplies the transaction with a
     * {@code TransactionTemplate}; the annotation guards any caller reaching it
     * through the proxy.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SsoUser linkOrCreate(OAuthProvider provider, OAuthUserProfile profile) {
        Optional<OAuthLink> existingLink = oauthLinks.findByProviderAccount(provider, profile.providerUserId());
        if (existingLink.isPresent()) {
            User linked = existingLink.get().getUser();
            requireEnabled(linked);
            return new SsoUser(linked, false);
        }

        if (!profile.hasEmail()) {
            throw new AuthExceptions.SsoEmailUnavailable(provider.name());
        }
        if (!profile.emailVerified()) {
            // WARN, not INFO: this is a refused account takeover attempt as often
            // as it is a misconfigured provider account.
            log.warn("Refused SSO for an unverified email address provider={}", provider);
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
            if (oauthLinks.existsByUserIdAndProvider(user.getId(), provider)) {
                throw new AuthExceptions.SsoProviderAlreadyLinked(provider.name());
            }
            created = false;
        } else {
            user = users.saveAndFlush(User.fromSso(availableUsername(profile.suggestedUsername()), email));
            created = true;
        }

        try {
            oauthLinks.saveAndFlush(OAuthLink.link(user, provider, profile.providerUserId()));
        } catch (DataIntegrityViolationException ex) {
            // Two first-time sign-ins for the same provider account at once, refused by
            // uq_oauth_links_provider_account. Nothing is recoverable from here: after a
            // failed flush Hibernate replays the same insert before the next statement,
            // so even a read throws — this used to re-read the winner and could not have
            // worked. Rolling back is also the better answer, because it takes the
            // account this call had just created with it rather than leaving one nobody
            // can ever sign into. The person retries and finds the winner's link.
            log.warn("Concurrent first sign-in for one provider account, rolling back provider={}", provider);
            throw new AuthExceptions.SsoSignInConflict(provider.name());
        }

        // A first SSO sign-in creates an account exactly as registration does, so it
        // owes the same event — same transaction, same reasoning.
        if (created) {
            userEventPublisher.userRegistered(user);
        }

        return new SsoUser(user, created);
    }

    private OAuth2AccessToken exchange(ClientRegistration registration,
                                       PendingAuthorization pending,
                                       String code,
                                       String state,
                                       OAuthProvider provider) {

        // Rebuilt rather than stored: Spring reads redirect_uri and the PKCE verifier
        // back off this object, so it must carry what the outbound request carried.
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
            log.warn("Provider rejected the authorization code exchange provider={}: {}",
                    provider, ex.getMessage());
            throw new AuthExceptions.SsoExchangeFailed(provider.name(), ex);
        }
    }

    private OAuthUserProfile loadProfile(ClientRegistration registration,
                                         OAuth2AccessToken accessToken,
                                         OAuthProvider provider) {
        try {
            OAuth2User user = oauth2UserService.loadUser(new OAuth2UserRequest(registration, accessToken));
            return oauthClientRegistry.mapperFor(provider).map(user, accessToken);
        } catch (RuntimeException ex) {
            log.warn("Could not read the provider user profile provider={}: {}", provider, ex.getMessage());
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
            // CommonOAuth2Provider defaults to a template only Spring's login filter
            // resolves. Sent literally it fails at the provider, pointing nowhere near
            // here — so it is caught with a message that names the property.
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
            // verifier in the request attributes, where the exchange reads it back.
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        } else {
            builder.attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier));
        }

        return builder.build();
    }

    /**
     * Finds a free username near the provider's suggestion.
     * <p>
     * Suffixes are random, not sequential: {@code kaneki2} would tell the next person
     * how many accounts already share that handle. The unique index is the guarantee;
     * a lost race surfaces as the violation {@link #linkOrCreate} already handles.
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
     * @param authorizationUri where to send the browser
     * @param browserNonce     must be set as a {@code SameSite=Lax} cookie on the
     *                         same response, and is what the callback checks the
     *                         flow back against
     */
    public record SsoRedirect(URI authorizationUri, String browserNonce) {
    }

    /**
     * @param created whether this sign-in brought a new account into existence —
     *                the signal {@code mugen.user.registered} will be published on
     *                (tasks.md 2.8), and the reason this is not just a {@link User}
     */
    public record SsoUser(User user, boolean created) {
    }
}
