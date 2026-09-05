package com.mugen.test.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Teaches a controller slice to resolve {@code @AuthenticationPrincipal}.
 * <p>
 * A {@code @WebMvcTest} applies web auto-configuration and no security
 * auto-configuration at all, so {@code @EnableWebSecurity} — which is what normally
 * contributes this resolver, via {@code WebMvcSecurityConfiguration} — is never
 * evaluated. Without it Spring MVC falls through to treating the parameter as a model
 * attribute and tries to instantiate a {@code Jwt}, so every handler that reads its
 * caller answers 500 for a reason that has nothing to do with the test.
 * <p>
 * The resolver alone, rather than the whole of {@code @EnableWebSecurity}: a slice has
 * no filter chain and does not want one — {@code SecurityMockMvcRequestPostProcessors}
 * puts the authentication straight into the context, and whether the chain would have
 * let the request through is an integration-tier question.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityMethodArguments implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticationPrincipalArgumentResolver());
    }
}
