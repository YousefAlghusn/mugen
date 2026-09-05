package com.mugen.auth.integration;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.service.RevocationCacheService;
import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one piece of state mugen-auth shares with another service. A revocation is written
 * here and read by mugen-gateway on every request, which is what closes the gap a
 * self-validating token leaves: revoking a session in SQL Server means nothing to a token
 * already in circulation, because the gateway never reads that database.
 * <p>
 * Against a real Redis because both claims are Redis', not Java's: the key another
 * service will look for, and an expiry that actually gets set.
 */
@IntegrationTest
class RevocationCacheTest {

    /**
     * Duplicated from the service on purpose, the same way a URL path is: this string is
     * the contract with mugen-gateway, which has no shared constant to import yet.
     * Changing it there without changing it here breaks revocation silently — the writes
     * still succeed and nobody reads them.
     */
    private static final String KEY_PREFIX = "mugen:auth:revoked-session:";

    @Autowired
    private RevocationCacheService revocationCacheService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("a revoked session is announced under the key the gateway looks for")
    void writesTheSharedKey() {
        UUID session = UUID.randomUUID();

        revocationCacheService.revoke(session);

        assertThat(redis.hasKey(KEY_PREFIX + session)).isTrue();
        assertThat(revocationCacheService.isRevoked(session)).isTrue();
    }

    @Test
    @DisplayName("a session nobody revoked is not revoked")
    void unknownSessionIsNotRevoked() {
        assertThat(revocationCacheService.isRevoked(UUID.randomUUID())).isFalse();
    }

    /**
     * The entry may expire only once the access token it is about has expired too —
     * otherwise a revoked session becomes live again for the remainder of that token's
     * life. {@link JwtProperties} refuses to start with a shorter TTL than the access
     * token's; this is the other half, that the TTL is applied at all rather than the
     * key being written to live forever.
     */
    @Test
    @DisplayName("the announcement outlives the access tokens it is about, and no longer")
    void expiresWithTheAccessToken() {
        UUID session = UUID.randomUUID();

        revocationCacheService.revoke(session);
        Long ttl = redis.getExpire(KEY_PREFIX + session, TimeUnit.SECONDS);

        assertThat(ttl).isNotNull().isPositive();
        assertThat(Duration.ofSeconds(ttl))
                .isLessThanOrEqualTo(jwtProperties.revocationCacheTtl())
                .isGreaterThanOrEqualTo(jwtProperties.accessTokenTtl().minusSeconds(5));
    }

    /** "Sign out everywhere else" revokes a list, and every one of them has to land. */
    @Test
    @DisplayName("revoking many announces every one of them")
    void revokesEverySessionInABatch() {
        List<UUID> sessions = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        revocationCacheService.revokeAll(sessions);

        assertThat(sessions).allSatisfy(session ->
                assertThat(revocationCacheService.isRevoked(session)).isTrue());
    }
}
