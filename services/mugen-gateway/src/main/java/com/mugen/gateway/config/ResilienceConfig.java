package com.mugen.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One breaker policy for every route; each route still gets its own breaker instance, named in application.yml. */
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    /**
     * Sets the time limiter explicitly because Spring Cloud's default is one second —
     * enough to fail a login (BCrypt alone is a quarter of it) on any busy afternoon,
     * counting each as a breaker failure until the circuit opens for everyone.
     */
    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerDefaults(GatewayProperties gatewayProperties) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(gatewayProperties.circuitBreaker().timeout())
                        .build())
                .build());
    }
}
