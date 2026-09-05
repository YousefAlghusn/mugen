package com.mugen.auth.integration;

import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.entity.User;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.auth.repository.OAuthLinkRepository;
import com.mugen.auth.repository.OutboxEventRepository;
import com.mugen.auth.repository.UserRepository;
import com.mugen.auth.service.OAuthService;
import com.mugen.auth.support.StubbedProvider;
import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A first SSO sign-in, end to end, against the real database — and the reason it is at
 * this tier and not with the twenty-odd unit tests of the same flow.
 * <p>
 * {@code OAuthService.complete} calls {@code linkOrCreate} on itself. Spring's
 * {@code @Transactional} is implemented by the proxy <em>around</em> a bean, so a call
 * that never leaves the bean does not pass through it: {@code linkOrCreate}'s annotation
 * had never once taken effect, and the user row, the link row and the outbox event were
 * three separate commits. A test that builds its subject with {@code new} has no proxy
 * and cannot see any of that — twenty-three of them passed while it was broken. The
 * transaction is now opened explicitly with a {@code TransactionTemplate} at the call
 * site, and what follows is what would have caught the original bug.
 * <p>
 * Deliberately not {@code @Transactional}: these assertions are about what was
 * <em>committed</em>, and a test transaction rolling back around them would erase the
 * behaviour under test.
 */
@IntegrationTest
class SsoSignInTest {

    @Autowired
    private OAuthService oauthService;

    @Autowired
    private StubbedProvider.ProviderStub provider;

    @Autowired
    private UserRepository users;

    @Autowired
    private OAuthLinkRepository oauthLinks;

    @Autowired
    private OutboxEventRepository outboxEvents;

    private static PendingAuthorization pending() {
        return new PendingAuthorization(
                OAuthProvider.GOOGLE, "code-verifier", "http://localhost:4200/auth/callback", "nonce");
    }

    private TokenPair signIn() {
        return oauthService.complete(
                OAuthProvider.GOOGLE, pending(), "authorization-code", "state",
                new RequestContext("JUnit", "203.0.113.5"));
    }

    /**
     * The three writes a first sign-in owes, and the one that used to be missing: no
     * {@code mugen.user.registered} event means no profile in mugen-user, forever. They
     * are one transaction, so an account that exists always has one waiting.
     */
    @Test
    @DisplayName("a first sign-in commits the account, the link and the event together")
    void firstSignInCommitsAllThree() {
        String subject = "google-sub-" + UUID.randomUUID();
        String email = "sso-" + UUID.randomUUID() + "@mugen.dev";
        provider.signsInAs(subject, email, true);

        TokenPair tokens = signIn();

        assertThat(tokens.accessToken()).isNotBlank();
        User created = users.findByEmail(email).orElseThrow();
        assertThat(oauthLinks.findByProviderAccount(OAuthProvider.GOOGLE, subject))
                .get()
                .satisfies(link -> assertThat(link.getUser().getId()).isEqualTo(created.getId()));
        assertThat(announcementsFor(created.getId())).isEqualTo(1);
    }

    /**
     * Signing in again is not signing up again: the link is what identifies the account,
     * so nothing is created and nothing is announced a second time. mugen-user would
     * otherwise be told to build a profile that already exists on every sign-in.
     */
    @Test
    @DisplayName("signing in again neither creates an account nor announces one")
    void repeatSignInIsNotARegistration() {
        String subject = "google-sub-" + UUID.randomUUID();
        String email = "sso-" + UUID.randomUUID() + "@mugen.dev";
        provider.signsInAs(subject, email, true);

        signIn();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        long accounts = users.count();

        signIn();

        assertThat(users.count()).isEqualTo(accounts);
        assertThat(announcementsFor(userId)).isEqualTo(1);
    }

    /**
     * The damage the missing transaction actually did, phrased as a test: a failure part
     * way through must leave nothing behind. An account with no link has no password and
     * no provider identity — nobody can ever sign into it, and its username is taken
     * forever.
     * <p>
     * The link insert is failed by a provider account id wider than its column, which is
     * a real failure at the same point in the sequence rather than a mock's.
     */
    @Test
    @DisplayName("a failure after the account is written leaves no account behind")
    void aFailedSignInLeavesNothingBehind() {
        String email = "sso-" + UUID.randomUUID() + "@mugen.dev";
        // oauth_links.provider_user_id is NVARCHAR(200); this is 300.
        provider.signsInAs("s".repeat(300), email, true);

        assertThatThrownBy(this::signIn).isInstanceOf(RuntimeException.class);

        assertThat(users.findByEmail(email)).isEmpty();
    }

    private long announcementsFor(UUID userId) {
        return outboxEvents.findAll().stream()
                .filter(event -> event.getMessageKey().equals(userId.toString()))
                .count();
    }
}
