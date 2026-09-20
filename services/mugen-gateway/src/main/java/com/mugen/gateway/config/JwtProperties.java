package com.mugen.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code mugen.jwt.*}: the half of mugen-auth's key material a verifier holds.
 *
 * @param publicKey PEM (X.509), a copy of mugen-auth's. The private half never leaves
 *                  that service, which is the whole point of RS256 here
 * @param issuer    must equal what mugen-auth signs with, or every token is refused
 */
@Validated
@ConfigurationProperties(prefix = "mugen.jwt")
public record JwtProperties(@NotNull Resource publicKey, @NotBlank String issuer) {
}
