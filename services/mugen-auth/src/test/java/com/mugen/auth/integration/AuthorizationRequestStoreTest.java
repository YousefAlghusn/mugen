package com.mugen.auth.integration;

import com.mugen.auth.config.SsoProperties;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.oauth.AuthorizationRequestStore;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store behind {@code state}, against a real Redis — which is the only place its two
 * claims are true or false. Both are about Redis semantics rather than about Java:
 * whether the entry expires at all, and whether exactly one of two callers can consume it.
 */
@IntegrationTest
class AuthorizationRequestStoreTest {

    private static final String KEY_PREFIX = "mugen:auth:sso-state:";

    @Autowired
    private AuthorizationRequestStore pendingAuthorizations;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SsoProperties ssoProperties;

    private static PendingAuthorization pending() {
        return new PendingAuthorization(OAuthProvider.GOOGLE, "code-verifier", "http://localhost:4200", "nonce");
    }

    private static String state() {
        return "state-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("what was saved is what comes back")
    void roundTripsEveryField() {
        String state = state();
        pendingAuthorizations.save(state, pending());

        assertThat(pendingAuthorizations.consume(state)).contains(pending());
    }

    /**
     * The property that makes {@code state} worth having: a captured callback URL cannot
     * be replayed, because the first legitimate use spent it. Redis answers true to
     * exactly one {@code DEL}, which is why the decision is made on the delete and not on
     * the read before it.
     */
    @Test
    @DisplayName("a state is good for exactly one callback")
    void isSingleUse() {
        String state = state();
        pendingAuthorizations.save(state, pending());

        assertThat(pendingAuthorizations.consume(state)).isPresent();
        assertThat(pendingAuthorizations.consume(state)).isEmpty();
    }

    @Test
    @DisplayName("a state that was never issued is simply absent")
    void unknownStateIsEmpty() {
        assertThat(pendingAuthorizations.consume(state())).isEmpty();
    }

    /**
     * The hash and its expiry are written in one MULTI/EXEC. A crash between a bare HSET
     * and its EXPIRE would leave a state entry that never expires — and a state that
     * outlives its window is a callback that can be forged against for longer.
     */
    @Test
    @DisplayName("an entry always carries its expiry, never merely usually")
    void expiryIsWrittenWithTheEntry() {
        String state = state();
        pendingAuthorizations.save(state, pending());

        Long ttl = redis.getExpire(KEY_PREFIX + state, TimeUnit.SECONDS);

        assertThat(ttl).isNotNull().isPositive();
        assertThat(Duration.ofSeconds(ttl)).isLessThanOrEqualTo(ssoProperties.stateTtl());
    }

    /**
     * Entries outlive a deployment by up to their TTL, so a provider removed from the
     * enum — GitHub was, in August — leaves in-flight sign-ins naming it. Refused as
     * though expired, which is what the caller already handles; {@code valueOf} would
     * have made it a 500.
     */
    @Test
    @DisplayName("a state naming a provider this deployment does not have is refused, not a 500")
    void unknownProviderIsRefusedLikeAnExpiredState() {
        String state = state();
        redis.opsForHash().putAll(KEY_PREFIX + state, Map.of(
                "provider", "GITHUB",
                "codeVerifier", "code-verifier",
                "redirectUri", "http://localhost:4200",
                "browserNonce", "nonce"));

        Optional<PendingAuthorization> consumed = pendingAuthorizations.consume(state);

        assertThat(consumed).isEmpty();
        // Spent regardless: an entry that cannot be read is still an entry that has now
        // been used, and leaving it would let the same forged callback be retried.
        assertThat(redis.hasKey(KEY_PREFIX + state)).isFalse();
    }
}
