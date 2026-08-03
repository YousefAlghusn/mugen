package com.mugen.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler reachable without a token.
 * <p>
 * The one place an endpoint's visibility is declared. {@link PublicEndpointMatcher}
 * turns it into the security config's permit rules, and the OpenAPI customizer reads
 * the same annotation to decide which operations advertise a security requirement —
 * so the filter chain and the published document cannot disagree.
 * <p>
 * Absence is what secures an endpoint: every filter chain built on this ends in
 * {@code anyRequest().authenticated()}, so forgetting the annotation denies access
 * rather than granting it.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
