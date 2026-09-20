package com.mugen.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.gateway.*} — the knobs that are ours rather than Spring Cloud's.
 *
 * @param revocationKeyPrefix the Redis key mugen-auth writes a revoked session under;
 *                            the two services must agree or revocation silently stops
 * @param circuitBreaker      one policy for every route
 */
@Validated
@ConfigurationProperties(prefix = "mugen.gateway")
public record GatewayProperties(@NotBlank String revocationKeyPrefix, @NotNull CircuitBreaker circuitBreaker) {

    /**
     * @param timeout how long a downstream call may take before the breaker counts it
     *                as a failure and the caller gets the fallback
     */
    public record CircuitBreaker(@NotNull Duration timeout) {
    }
}
