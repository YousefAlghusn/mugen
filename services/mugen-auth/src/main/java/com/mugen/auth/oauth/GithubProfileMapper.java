package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * GitHub's user response, which needs more work than Google's for two reasons.
 * <p>
 * First, {@code /user} omits the email entirely when the account keeps it private —
 * that is the default for a large share of accounts, so treating a null email as
 * "no email" would break sign-in for many real users. The address is instead read
 * from {@code /user/emails}, which needs the {@code user:email} scope.
 * <p>
 * Second, GitHub has no {@code email_verified} field on {@code /user}. The
 * verification flag only exists per-address on {@code /user/emails}, so that call
 * is the only way to answer the question that decides whether this login may be
 * matched onto an existing mugen account.
 */
@Slf4j
@Component
public class GithubProfileMapper implements OAuthProfileMapper {

    private static final String DEFAULT_EMAILS_URI = "https://api.github.com/user/emails";

    private final RestClient restClient;
    private final String emailsUri;

    // Explicit, because the test constructor below makes this an ambiguous choice:
    // with two constructors and neither marked, Spring falls back to looking for a
    // no-arg one and fails to instantiate the bean at all.
    @Autowired
    public GithubProfileMapper(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DEFAULT_EMAILS_URI);
    }

    /** The URI is injectable so a test can point it at a stub rather than GitHub. */
    GithubProfileMapper(RestClient.Builder restClientBuilder, String emailsUri) {
        this.restClient = restClientBuilder.build();
        this.emailsUri = emailsUri;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GITHUB;
    }

    @Override
    public OAuthUserProfile map(OAuth2User user, OAuth2AccessToken accessToken) {
        // GitHub's id is a JSON number, so getName() has already stringified it —
        // going through the raw attribute would hand us an Integer and a
        // ClassCastException. The login handle is deliberately not used as the id:
        // it is renameable, and a freed handle can be claimed by someone else.
        String accountId = user.getName();
        String login = user.getAttribute("login");

        GithubEmail primary = fetchPrimaryVerifiedEmail(accessToken);

        // Falls back to whatever /user exposed, but never claims it is verified —
        // GitHub does not say so there, and guessing yes is the one mistake that
        // would let this become an account-takeover path.
        String email = primary != null ? primary.email() : user.getAttribute("email");
        boolean verified = primary != null;

        return new OAuthUserProfile(
                accountId,
                email,
                verified,
                UsernameSuggestions.fromEmailOrName(login, user.getAttribute("name")));
    }

    /**
     * @return the account's primary <em>verified</em> address, or null if the call
     *         fails or no such address exists
     */
    private GithubEmail fetchPrimaryVerifiedEmail(OAuth2AccessToken accessToken) {
        try {
            List<GithubEmail> emails = restClient.get()
                    .uri(emailsUri)
                    .header("Authorization", "Bearer " + accessToken.getTokenValue())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GithubEmail>>() {
                    });

            if (emails == null) {
                return null;
            }

            // Primary and verified, in that order. A verified secondary address is
            // not a substitute: the primary is the one GitHub itself treats as the
            // account's identity, and picking a different one would let two GitHub
            // accounts that share a secondary address resolve inconsistently.
            return emails.stream()
                    .filter(GithubEmail::primary)
                    .filter(GithubEmail::verified)
                    .filter(candidate -> StringUtils.hasText(candidate.email()))
                    .findFirst()
                    .orElse(null);

        } catch (RestClientException ex) {
            // Not fatal on its own. Without the scope, or with GitHub having a bad
            // day, the caller simply ends up with an unverified email and is refused
            // by the linking rules — which is the correct outcome, just a less clear
            // one, so the real cause is logged here.
            log.warn("Could not read GitHub email addresses: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * One entry of {@code GET /user/emails}. Unknown fields are ignored by the
     * service's Jackson configuration, so GitHub adding one does not break this.
     */
    record GithubEmail(String email, boolean primary, boolean verified) {
    }
}
