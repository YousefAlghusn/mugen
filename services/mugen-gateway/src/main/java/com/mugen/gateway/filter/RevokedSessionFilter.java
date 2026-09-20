package com.mugen.gateway.filter;

import com.mugen.gateway.config.GatewayProperties;
import com.mugen.web.error.TokenInvalidException;
import com.mugen.web.error.TokenRevokedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses a request whose session mugen-auth has revoked, however valid its signature.
 * <p>
 * Closes the gap in stateless auth: an access token is self-validating, so a sign-out
 * or a replay detection in mugen-auth changes nothing about tokens already in the wild
 * until they expire. mugen-auth writes the revoked session id to Redis for the access
 * token's lifetime; this is the one lookup per authenticated request that honours it.
 * The same key is checked again by mugen-auth on its own endpoints — deliberately, see
 * context.md, "Revocation: mugen-auth checks for itself".
 * <p>
 * Runs after the bearer token has been verified, so it only sees tokens mugen-auth
 * issued. A request with no token passes untouched — it has no session to be revoked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RevokedSessionFilter implements WebFilter {

    /** Claim mugen-auth puts the session id under. */
    private static final String SESSION_ID_CLAIM = "sessionId";

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties gatewayProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Resolved to a boolean before touching the chain: chain.filter completes empty,
        // so a switchIfEmpty placed after it would run the request a second time.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .flatMap(this::isRevoked)
                .defaultIfEmpty(false)
                .flatMap(revoked -> revoked
                        ? Mono.error(new TokenRevokedException())
                        : chain.filter(exchange));
    }

    private Mono<Boolean> isRevoked(JwtAuthenticationToken token) {
        String sessionId = token.getToken().getClaimAsString(SESSION_ID_CLAIM);
        if (sessionId == null) {
            // Every access token mugen-auth mints carries one. A verified token without
            // it is not one of ours to interpret, so it is not one to trust.
            return Mono.error(new TokenInvalidException("Access token carries no session."));
        }
        // No onErrorReturn: a Redis outage fails closed. A 500 is recoverable; a request
        // honoured on a token nobody could check is not.
        return redis.hasKey(gatewayProperties.revocationKeyPrefix() + sessionId)
                .doOnNext(revoked -> {
                    if (revoked) {
                        log.warn("Refused a revoked session sessionId={}", sessionId);
                    }
                });
    }
}
