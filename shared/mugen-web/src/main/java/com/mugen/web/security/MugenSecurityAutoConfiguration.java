package com.mugen.web.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers {@link PublicEndpointMatcher} and the two handlers that keep a refused
 * request on the same problem shape as a failed one, in any service depending on this
 * module. A service still has to attach the handlers to its own filter chain.
 * <p>
 * Auto-configuration rather than component scanning, for the same reason
 * {@code MugenErrorHandlingAutoConfiguration} is: {@code com.mugen.web} sits outside
 * every service's {@code @SpringBootApplication} base package.
 */
@AutoConfiguration
@ConditionalOnClass({RequestMatcher.class, RequestMappingHandlerMapping.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MugenSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PublicEndpointMatcher publicEndpointMatcher(ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        return new PublicEndpointMatcher(handlerMappings);
    }

    /**
     * {@code @Lazy} because the filter chain is built before MVC finishes configuring,
     * and asking for the resolver eagerly closes a cycle between the two.
     */
    @Bean
    @ConditionalOnMissingBean
    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(
            @Lazy @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {

        return new ProblemAuthenticationEntryPoint(handlerExceptionResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemAccessDeniedHandler problemAccessDeniedHandler(
            @Lazy @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {

        return new ProblemAccessDeniedHandler(handlerExceptionResolver);
    }
}
