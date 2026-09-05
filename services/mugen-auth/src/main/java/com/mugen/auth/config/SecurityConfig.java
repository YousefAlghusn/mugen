package com.mugen.auth.config;

import com.mugen.auth.security.RevokedSessionFilter;
import com.mugen.auth.service.RevocationCacheService;
import com.mugen.web.security.ProblemAccessDeniedHandler;
import com.mugen.web.security.ProblemAuthenticationEntryPoint;
import com.mugen.web.security.PublicEndpoint;
import com.mugen.web.security.PublicEndpointMatcher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Collection;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The OpenAPI document and Swagger UI. Listed here rather than annotated because
     * they are springdoc's handlers, not ours — there is nothing to put
     * {@link PublicEndpoint} on. Whether they exist at all is a property
     * ({@code springdoc.api-docs.enabled} / {@code springdoc.swagger-ui.enabled}, off
     * in the deploy profile); when disabled these patterns match nothing.
     */
    private static final String[] API_DOCS_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtAuthenticationConverter jwtAuthenticationConverter,
                                    PublicEndpointMatcher publicEndpointMatcher,
                                    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint,
                                    ProblemAccessDeniedHandler problemAccessDeniedHandler,
                                    RevocationCacheService revocationCacheService,
                                    @Lazy @Qualifier("handlerExceptionResolver")
                                    HandlerExceptionResolver handlerExceptionResolver) throws Exception {

        return http
                // The token is the whole state; a JSESSIONID would be a second,
                // competing notion of "logged in" that also needs sticky sessions.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Safe only because of how the tokens are carried: the access token is
                // an Authorization header browsers never attach by themselves, and the
                // refresh cookie is SameSite=Strict and Path=/api/v1/auth.
                .csrf(csrf -> csrf.disable())

                // CORS is the gateway's job — this service is not browser-reachable.
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth
                        // Derived from the @PublicEndpoint handlers themselves, so an
                        // endpoint's visibility is stated where the endpoint is.
                        .requestMatchers(publicEndpointMatcher).permitAll()
                        .requestMatchers(HttpMethod.GET, API_DOCS_ENDPOINTS).permitAll()
                        // Liveness/readiness must answer before the app is warm, and
                        // Prometheus scrapes without credentials on the private network.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())

                // Standard resource server over the tokens this service itself issued.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        // A bad token is refused here...
                        .authenticationEntryPoint(problemAuthenticationEntryPoint)
                        .accessDeniedHandler(problemAccessDeniedHandler))

                // ...and no token at all never reaches the resource server's filter, so
                // both have to be set or the commonest 401 of the two answers empty.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemAuthenticationEntryPoint)
                        .accessDeniedHandler(problemAccessDeniedHandler))

                // After the token is verified, because it only has a question to ask
                // about a token that turned out to be ours. Constructed here rather
                // than declared a bean: a Filter bean is also registered with the
                // servlet container, where it would run a second time outside this
                // chain, before anything has been authenticated.
                .addFilterAfter(
                        new RevokedSessionFilter(revocationCacheService, handlerExceptionResolver),
                        BearerTokenAuthenticationFilter.class)

                // Stateless API: answer 401/403 as JSON, never redirect to a login page.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .build();
    }

    /**
     * Maps the token's {@code roles} claim onto authorities. Spring's default reads
     * {@code scope} and prefixes {@code SCOPE_}, so without this every {@code hasRole}
     * check would silently fail against an otherwise valid token.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authoritiesFrom);
        return converter;
    }

    private static Collection<GrantedAuthority> authoritiesFrom(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role)).toList();
    }
}
