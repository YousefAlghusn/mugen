package com.mugen.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoints reachable without a token — the ones used to *obtain* one. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/sso/**"
    };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {

        return http
                // No server-side session: the token is the whole state. Spring must
                // not create a JSESSIONID, or we would have two competing notions of
                // "logged in" and horizontal scaling would need sticky sessions.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Safe to disable only because of how the tokens are carried. The
                // access token travels in an Authorization header, which browsers do
                // not attach automatically, so it is not forgeable cross-site. The
                // refresh token is a cookie and would be vulnerable — it is pinned to
                // SameSite=Strict and Path=/api/v1/auth, so a cross-site request
                // cannot cause it to be sent at all.
                .csrf(csrf -> csrf.disable())

                // CORS is the gateway's job. This service is not reachable from a
                // browser directly (CLAUDE.md: the gateway is the only entry point).
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Liveness/readiness must answer before the app is warm, and
                        // Prometheus scrapes without credentials on the private network.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())

                // /me, /validate and session management verify the access token this
                // service itself issued. Standard resource server — no custom filter.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))

                // Stateless API: answer 401/403 as JSON, never redirect to a login page.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .build();
    }

    /**
     * Maps the token's {@code roles} claim onto authorities.
     * <p>
     * Needed because Spring's default converter reads {@code scope}/{@code scp} and
     * prefixes each value with {@code SCOPE_}. This system issues full role names
     * ({@code ROLE_USER}) in a {@code roles} claim, so without this converter every
     * {@code hasRole} check would silently fail against an otherwise valid token.
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
