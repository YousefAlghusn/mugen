package com.mugen.gateway.integration;

import com.mugen.gateway.filter.TraceIdFilter;
import com.mugen.gateway.support.TestRoutes;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every failure the gateway answers for itself is the same RFC 9457 document mugen-web
 * produces in the services, and every response — failed or not — hands back the
 * traceId a caller can quote.
 */
@IntegrationTest
class ProblemContractTest {

    @Autowired private WebTestClient client;
    @Autowired private RouteLocator routes;
    @Autowired private RedisRateLimiter redisRateLimiter;

    @Test
    void aPathNoRouteClaimsIsAProblemDocument() {
        client.get().uri("/api/v1/nothing-here")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name())
                .jsonPath("$.traceId").isNotEmpty()
                .jsonPath("$.type").isNotEmpty();
    }

    /** The fallback is the answer for a service that is down, not a stack trace. */
    @Test
    void anUnreachableServiceIsAProblemDocumentNotAFailure() {
        client.get().uri(TestRoutes.UNREACHABLE + "/anything")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.name())
                .jsonPath("$.traceId").isNotEmpty();
    }

    /**
     * {@code traceId} and {@code code} at the top level, not nested under
     * {@code properties}: the mixin that flattens them is registered by the framework,
     * and losing it would change the contract without failing anything else.
     */
    @Test
    void problemPropertiesAreTopLevelFields() {
        client.get().uri("/api/v1/nothing-here")
                .exchange()
                .expectBody()
                .jsonPath("$.properties").doesNotExist()
                .jsonPath("$.traceId").exists()
                .jsonPath("$.code").exists();
    }

    @Test
    void theTraceIdInTheBodyIsTheOneInTheHeader() {
        EntityExchangeResult<byte[]> refused = client.get().uri("/api/v1/nothing-here")
                .exchange()
                .expectBody().returnResult();

        String header = refused.getResponseHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(header).isNotBlank();
        assertThat(new String(refused.getResponseBody())).contains("\"traceId\":\"" + header + "\"");
    }

    @Test
    void aSuccessfulResponseCarriesTheTraceIdToo() {
        client.get().uri(TestRoutes.UPSTREAM + "/echo")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(TraceIdFilter.TRACE_ID_HEADER);
    }

    /**
     * A rate-limit entry for a route id that does not exist is a typo that silently
     * applies the default instead. Derived from both sides so a route added later is
     * covered on the day it is added.
     */
    @Test
    void everyRateLimitEntryNamesARouteThatExists() {
        Set<String> routeIds = routes.getRoutes().map(Route::getId).collect(Collectors.toSet()).block();
        Set<String> limited = redisRateLimiter.getConfig().keySet().stream()
                .filter(id -> !id.equals("defaultFilters"))
                .collect(Collectors.toSet());

        assertThat(routeIds).containsAll(limited);
    }
}
