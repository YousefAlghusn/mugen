package com.mugen.web.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers {@link PublicEndpointMatcher} in any service that depends on this module.
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
}
