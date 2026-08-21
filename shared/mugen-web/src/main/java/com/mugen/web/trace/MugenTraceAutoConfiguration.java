package com.mugen.web.trace;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers {@link TraceIdFilter} in any service that depends on this module.
 * <p>
 * Auto-configuration rather than component scanning, for the same reason
 * {@code MugenErrorHandlingAutoConfiguration} is: {@code com.mugen.web} sits outside
 * every service's {@code @SpringBootApplication} base package.
 */
@AutoConfiguration
@ConditionalOnClass(Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MugenTraceAutoConfiguration {

    /**
     * Inside Boot's observation filter, which registers at
     * {@code HIGHEST_PRECEDENCE + 1} and has already published a real trace id by the
     * time this runs — so the tracer wins whenever there is one. Still outside the
     * security chain at {@code -100}, so a request refused there has an id to log.
     */
    @Bean
    @ConditionalOnMissingBean
    FilterRegistrationBean<TraceIdFilter> mugenTraceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
