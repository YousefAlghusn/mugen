package com.mugen.test.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The reactive counterpart of {@link MockMvcConfiguration}: a {@link WebTestClient} bound
 * to the application context, for a service on WebFlux (the gateway).
 * <p>
 * Binding to the context rather than to a port runs the real {@code WebFilter} chain —
 * Spring Security's included, since its chain is a {@code WebFilter} bean — without a
 * server. Nothing has to be applied for security the way {@code springSecurity()} must
 * be for MockMvc; a filter chain that is a bean is already in the chain.
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WebTestClientConfiguration {

    @Bean
    WebTestClient webTestClient(ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context).build();
    }
}
