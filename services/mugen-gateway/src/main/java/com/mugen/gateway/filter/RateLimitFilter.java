package com.mugen.gateway.filter;

import com.mugen.gateway.error.GatewayExceptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Token bucket per caller per route, backed by Spring Cloud's Redis limiter so the
 * count is shared by every gateway instance.
 * <p>
 * A global filter rather than the {@code RequestRateLimiter} route filter for one
 * reason: that one answers a bare 429 with no body, and every refusal from this gateway
 * is an RFC 9457 document with a traceId. The bucket sizes are Spring Cloud's own
 * configuration ({@code redis-rate-limiter.config.<routeId>} in application.yml), so a
 * route with no entry falls back to {@code defaultFilters} and none is ever unlimited.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RedisRateLimiter redisRateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String key = RateLimitKeys.keyFor(exchange);

        return redisRateLimiter.isAllowed(route.getId(), key)
                .flatMap(response -> {
                    exchange.getResponse().getHeaders().setAll(response.getHeaders());
                    if (!response.isAllowed()) {
                        return refuse(route, key);
                    }
                    return chain.filter(exchange);
                });
    }

    private static Mono<Void> refuse(Route route, String key) {
        // The key is logged: it is a user id or an address, and it is what an operator
        // needs to tell a stuck client from a scraper.
        log.warn("Rate limit exceeded routeId={} key={}", route.getId(), key);
        return Mono.error(new GatewayExceptions.RateLimitExceeded());
    }

    /** After the identity headers, whose attribute the key is derived from. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    /** The one decision here, kept pure so it can be tested without a context. */
    public static final class RateLimitKeys {

        private RateLimitKeys() {
        }

        /**
         * A signed-in caller is limited by user id, so a shared office address is not one
         * bucket for everyone behind it; an anonymous one by address, which on the login
         * route is the whole point. The address is the socket's, never {@code X-Forwarded-For}:
         * trusting that header lets a client choose its own bucket, and the login limit
         * exists precisely for clients that would.
         */
        public static String keyFor(ServerWebExchange exchange) {
            Object userId = exchange.getAttributes().get(IdentityHeadersFilter.USER_ID_ATTRIBUTE);
            if (userId != null) {
                return "user:" + userId;
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return "ip:" + (remote != null ? remote.getAddress().getHostAddress() : "unknown");
        }
    }
}
