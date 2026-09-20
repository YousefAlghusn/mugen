package com.mugen.gateway.config;

import com.mugen.gateway.filter.RevokedSessionFilter;
import com.mugen.web.error.TokenInvalidException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

/**
 * The gateway authenticates and never authorizes.
 * <p>
 * Every exchange is permitted: a request carrying a token has it verified — signature,
 * expiry, issuer, type, revocation — and a bad one is refused here; a request carrying
 * none is forwarded with no identity, for the service's own default-deny to refuse if
 * the endpoint needs one. Whether an endpoint is public is an authorization question,
 * and only the service that owns the endpoint can answer it — see context.md,
 * "Authn vs authz". A public-vs-protected route list here would be a second copy of
 * every service's {@code @PublicEndpoint} annotations, wrong within a month.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                       ReactiveJwtDecoder accessTokenDecoder,
                                       RevokedSessionFilter revokedSessionFilter) {
        return http
                // Stateless: the token is the whole state, so nothing is saved between requests.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // Safe for the same reason as in mugen-auth: the access token is a header
                // browsers never attach by themselves, and the refresh cookie is
                // SameSite=Strict, scoped to /api/v1/auth, and only ever read there.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS is answered by the gateway's route handler (globalcors in
                // application.yml); enabling it here too would answer preflights twice.
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(accessTokenDecoder))
                        .authenticationEntryPoint(problemEntryPoint()))
                // After authentication, because it only has a question to ask about a
                // token that turned out to be ours.
                .addFilterAfter(revokedSessionFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .build();
    }

    /**
     * Spring Security's default answers a bad token with a bare 401 and no body. Raising
     * the shared exception instead sends it through {@code GatewayExceptionHandler}, so a
     * refusal is the same RFC 9457 document as every other failure, traceId included.
     */
    private static ServerAuthenticationEntryPoint problemEntryPoint() {
        return (exchange, failure) ->
                Mono.error(new TokenInvalidException("Access token is missing, expired or not valid."));
    }
}
