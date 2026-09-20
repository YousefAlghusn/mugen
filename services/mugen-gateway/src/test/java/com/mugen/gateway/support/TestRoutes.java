package com.mugen.gateway.support;

import com.mugen.test.Fixture;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Routes that exist only under test, added beside the real ones from application.yml
 * rather than replacing them — the yaml list cannot be extended from a profile file,
 * only overwritten, and the real routes should stay loaded so a bad definition fails
 * the suite.
 */
@Fixture
public class TestRoutes {

    /** Proxied to {@link UpstreamStub}; what the gateway forwards is what it echoes. */
    public static final String UPSTREAM = "/upstream";

    /** Same stub, its own route id, so a tight bucket in application-test.yml applies only here. */
    public static final String LIMITED = "/limited";

    /** Nothing listens on port 1; the circuit breaker's fallback is the only possible answer. */
    public static final String UNREACHABLE = "/unreachable";

    @Bean
    RouteLocator testRouteLocator(RouteLocatorBuilder builder, UpstreamStub upstreamStub) {
        return builder.routes()
                .route("upstream", route -> route.path(UPSTREAM + "/**").uri(upstreamStub.baseUrl()))
                .route("limited", route -> route.path(LIMITED + "/**").uri(upstreamStub.baseUrl()))
                .route("unreachable", route -> route.path(UNREACHABLE + "/**")
                        .filters(filters -> filters.circuitBreaker(breaker -> breaker
                                .setName("unreachable")
                                .setFallbackUri("forward:/fallback/unreachable")))
                        .uri("http://localhost:1"))
                .build();
    }
}
