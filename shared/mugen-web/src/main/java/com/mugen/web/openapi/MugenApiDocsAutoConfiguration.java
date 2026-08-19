package com.mugen.web.openapi;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the customizers that fill in what every service's document has in common.
 * <p>
 * Conditional on springdoc being present: a service that publishes no document, or a
 * headless worker, gets nothing from this module beyond the classes it does use.
 */
@AutoConfiguration
@ConditionalOnClass({GlobalOpenApiCustomizer.class, GlobalOperationCustomizer.class})
public class MugenApiDocsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemResponseCustomizer problemResponseCustomizer() {
        return new ProblemResponseCustomizer();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityRequirementCustomizer securityRequirementCustomizer() {
        return new SecurityRequirementCustomizer();
    }

    @Bean
    @ConditionalOnMissingBean
    ApiErrorResponsesCustomizer apiErrorResponsesCustomizer() {
        return new ApiErrorResponsesCustomizer();
    }
}
