package com.mugen.web.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * The half of {@code mugen.jwt.*} every resource server needs: enough to verify a
 * token mugen-auth minted, and nothing that could mint one.
 *
 * @param publicKey PEM (X.509), a copy of mugen-auth's
 * @param issuer    must equal what mugen-auth signs with, or every token is refused
 */
@Validated
@ConfigurationProperties(prefix = "mugen.jwt")
public record VerificationKeyProperties(@NotNull Resource publicKey, @NotBlank String issuer) {
}
