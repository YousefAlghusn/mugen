package com.mugen.gateway.integration;

import com.mugen.gateway.support.TestRoutes;
import com.mugen.gateway.support.TokenSigner;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket is per caller per route. Sized by the {@code limited} entry in
 * application-test.yml, read back here rather than retyped, so the test follows the
 * configuration instead of pinning it.
 */
@IntegrationTest
class RateLimitTest {

    private static final String LIMITED = TestRoutes.LIMITED + "/resource";

    @Autowired private WebTestClient client;
    @Autowired private TokenSigner tokens;
    @Autowired private RedisRateLimiter redisRateLimiter;

    @Test
    void aCallerGetsExactlyTheBurstThenIsRefused() {
        String bearer = "Bearer " + tokens.accessToken().value();
        long burst = burstCapacityOf("limited");

        for (int request = 1; request <= burst; request++) {
            client.get().uri(LIMITED).header(HttpHeaders.AUTHORIZATION, bearer)
                    .exchange()
                    .expectStatus().isOk();
        }

        client.get().uri(LIMITED).header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.name())
                .jsonPath("$.traceId").isNotEmpty();
    }

    /** A signed-in caller is limited by who they are, not by where they call from. */
    @Test
    void oneCallersExhaustedBucketDoesNotRefuseAnother() {
        String exhausted = "Bearer " + tokens.accessToken().value();
        String other = "Bearer " + tokens.accessToken().value();
        long burst = burstCapacityOf("limited");

        for (int request = 0; request <= burst; request++) {
            client.get().uri(LIMITED).header(HttpHeaders.AUTHORIZATION, exhausted).exchange();
        }

        client.get().uri(LIMITED).header(HttpHeaders.AUTHORIZATION, other)
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * No route may be unlimited. Spring Cloud's limiter throws for a route id it has no
     * entry for, so deleting the {@code defaultFilters} entry would turn every route
     * without its own into a 500 — this is the test that notices.
     */
    @Test
    void aRouteWithoutItsOwnEntryIsStillLimited() {
        RateLimiter.Response decision = redisRateLimiter.isAllowed("upstream", "user:" + UUID.randomUUID()).block();

        assertThat(decision).isNotNull();
        assertThat(decision.isAllowed()).isTrue();
    }

    private long burstCapacityOf(String routeId) {
        return redisRateLimiter.getConfig().get(routeId).getBurstCapacity();
    }
}
