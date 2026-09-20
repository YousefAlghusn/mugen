package com.mugen.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Strips every client-supplied {@code X-User-*} header, then sets the ones this gateway
 * vouches for from the verified token.
 * <p>
 * The strip is the security half and is unconditional: whatever a downstream service
 * might one day read from these headers must have been written here, never by the
 * caller. The set is a convenience — log correlation, a cheap "who" without re-parsing
 * the token. <strong>No service authorizes on them.</strong> Each one re-verifies the
 * bearer token it also receives, because a service must not depend on the gateway
 * having been honest (context.md, "Authn vs authz").
 */
@Component
public class IdentityHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    /** Under the same prefix as the user id, so the one strip rule covers it. */
    public static final String SESSION_ID_HEADER = "X-User-Session-Id";

    /** Exchange attribute for the request log, which runs outside the security context. */
    public static final String USER_ID_ATTRIBUTE = IdentityHeadersFilter.class.getName() + ".userId";

    private static final String IDENTITY_HEADER_PREFIX = "x-user-";
    private static final String SESSION_ID_CLAIM = "sessionId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> identified(exchange, token))
                .defaultIfEmpty(anonymous(exchange))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange identified(ServerWebExchange exchange, JwtAuthenticationToken token) {
        String userId = token.getToken().getSubject();
        exchange.getAttributes().put(USER_ID_ATTRIBUTE, userId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    stripIdentityHeaders(headers);
                    headers.set(USER_ID_HEADER, userId);
                    headers.set(SESSION_ID_HEADER, token.getToken().getClaimAsString(SESSION_ID_CLAIM));
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private static ServerWebExchange anonymous(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(IdentityHeadersFilter::stripIdentityHeaders)
                .build();
        return exchange.mutate().request(request).build();
    }

    /** By prefix, not by name: a header added next year is covered on the day it is added. */
    private static void stripIdentityHeaders(HttpHeaders headers) {
        // Copied first: headerNames() is a live view, and removing while iterating it is undefined.
        List.copyOf(headers.headerNames()).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(IDENTITY_HEADER_PREFIX))
                .forEach(headers::remove);
    }

    /** Before the routing filters, which is where the mutated request is read. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
