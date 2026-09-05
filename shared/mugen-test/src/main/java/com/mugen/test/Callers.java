package com.mugen.test;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * An authenticated caller, for a controller slice.
 * <p>
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} cannot be used at this tier: it
 * hands the authentication to the filter chain to install, and a {@code @WebMvcTest}
 * has no filter chain. This puts it straight into the {@link SecurityContextHolder}
 * instead, which is where {@code @AuthenticationPrincipal} reads it from. Cleanup is
 * spring-security-test's {@code WithSecurityContextTestExecutionListener}, registered
 * for every Spring test, which clears the holder after each method.
 * <p>
 * No authorities are granted. Anything deciding on a role is enforced by
 * {@code @PreAuthorize}, which is a proxy and therefore invisible below the integration
 * tier — a slice asserting one would be asserting nothing.
 */
public final class Callers {

    private Callers() {
    }

    /**
     * A verified access token, as the resource server would have handed it to the
     * handler. Claims are the caller's: {@code .subject(...)}, {@code .claim(...)}.
     */
    public static Jwt.Builder token() {
        return Jwt.withTokenValue("verified-by-the-resource-server").header("alg", "RS256");
    }

    /** {@code mvc.perform(get("/api/v1/auth/me").with(authenticatedAs(token)))}. */
    public static RequestPostProcessor authenticatedAs(Jwt token) {
        return request -> {
            TestSecurityContextHolder.setAuthentication(new JwtAuthenticationToken(token));
            return request;
        };
    }
}
