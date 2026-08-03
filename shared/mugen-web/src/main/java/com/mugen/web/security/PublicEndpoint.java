package com.mugen.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler reachable without a token — the one place visibility is declared.
 * {@link PublicEndpointMatcher} turns it into the filter chain's permit rules and the
 * OpenAPI customizer reads it for security requirements, so the two cannot disagree.
 * <p>
 * Absence is what secures an endpoint: the chain ends in
 * {@code anyRequest().authenticated()}, so forgetting this denies rather than grants.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
