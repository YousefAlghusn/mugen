package com.mugen.web.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * The filter chain every mugen service starts from: stateless, default-deny, a
 * resource server over mugen-auth's tokens, and refusals rendered as problem documents.
 * <p>
 * A service's {@code SecurityConfig} calls {@link #configure(HttpSecurity)} and builds
 * — or adds to the returned {@code HttpSecurity} first, as mugen-auth does with its
 * revocation filter. One definition of the standard chain, so the eleventh service
 * cannot drift from the first in the part that decides who gets in.
 */
public class ResourceServerSecurity {

    /**
     * springdoc's handlers, not ours — there is nothing to put {@link PublicEndpoint} on.
     * When the document is disabled these patterns match nothing.
     */
    private static final String[] API_DOCS_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final PublicEndpointMatcher publicEndpointMatcher;
    private final ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint;
    private final ProblemAccessDeniedHandler problemAccessDeniedHandler;

    public ResourceServerSecurity(JwtAuthenticationConverter jwtAuthenticationConverter,
                                  PublicEndpointMatcher publicEndpointMatcher,
                                  ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint,
                                  ProblemAccessDeniedHandler problemAccessDeniedHandler) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.publicEndpointMatcher = publicEndpointMatcher;
        this.problemAuthenticationEntryPoint = problemAuthenticationEntryPoint;
        this.problemAccessDeniedHandler = problemAccessDeniedHandler;
    }

    public HttpSecurity configure(HttpSecurity http) throws Exception {
        return http
                // The token is the whole state; a JSESSIONID would be a second, competing
                // notion of "logged in" that also needs sticky sessions.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Safe only because of how the tokens are carried: the access token is an
                // Authorization header browsers never attach by themselves, and the refresh
                // cookie is SameSite=Strict and scoped to /api/v1/auth.
                .csrf(csrf -> csrf.disable())

                // CORS is the gateway's job — no service is browser-reachable.
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth
                        // Derived from the @PublicEndpoint handlers themselves, so an
                        // endpoint's visibility is stated where the endpoint is.
                        .requestMatchers(publicEndpointMatcher).permitAll()
                        .requestMatchers(HttpMethod.GET, API_DOCS_ENDPOINTS).permitAll()
                        // The whole actuator: probes, the scrape, config-server's refresh.
                        // Safe only because it is served on the management port, which a
                        // deployment never publishes — and mugen-web refuses to start a
                        // service where that port is ever the public one.
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())

                // Standard resource server over the tokens mugen-auth issues.
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

                // Stateless API: answer 401/403 as JSON, never redirect to a login page.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
    }
}
