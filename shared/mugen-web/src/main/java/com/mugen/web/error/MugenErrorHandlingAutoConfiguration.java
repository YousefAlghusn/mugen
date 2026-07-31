package com.mugen.web.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers {@link GlobalExceptionHandler} in any service that depends on this
 * module.
 * <p>
 * Auto-configuration rather than component scanning, because {@code com.mugen.web}
 * is outside every service's {@code @SpringBootApplication} base package and would
 * otherwise be invisible. This keeps the wiring to "add the dependency".
 * <p>
 * {@code @ConditionalOnMissingBean} lets a service replace the handler wholesale by
 * declaring its own bean of the same type.
 */
@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MugenErrorHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler mugenGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
