package com.mugen.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * One line per request, written as the response commits, so it has the final status
 * whether the request was proxied, refused by security, or rate limited.
 * <p>
 * The traceId is passed explicitly rather than left to the correlation pattern: the
 * commit callback can run on a thread whose MDC belongs to another request.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startedAt = System.nanoTime();
        exchange.getResponse().beforeCommit(() -> {
            logCompleted(exchange, Duration.ofNanos(System.nanoTime() - startedAt));
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private static void logCompleted(ServerWebExchange exchange, Duration elapsed) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        // Path only, never the query string: it is where a redirect_uri or a token would appear.
        log.info("Request completed traceId={} method={} path={} status={} durationMs={} routeId={} userId={}",
                TraceIdFilter.traceId(exchange),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                status != null ? status.value() : null,
                elapsed.toMillis(),
                route != null ? route.getId() : null,
                exchange.getAttributes().get(IdentityHeadersFilter.USER_ID_ATTRIBUTE));
    }
}
