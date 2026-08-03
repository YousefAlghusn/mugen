package com.mugen.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.jwt.*}. Validated, so a missing key file or malformed TTL fails
 * at startup rather than on the first login in production.
 *
 * @param privateKey         PEM (PKCS#8) used to sign; never leaves this service
 * @param publicKey          PEM (X.509), also copied to mugen-gateway to verify
 * @param issuer             {@code iss} claim; verifiers must check it
 * @param accessTokenTtl     short by design — it bounds how long a revoked session
 *                           can still be honoured
 * @param refreshTokenTtl    long-lived, HttpOnly cookie, rotated on every use
 * @param revocationCacheTtl must be at least {@code accessTokenTtl}, or a token
 *                           outlives the record saying it was revoked
 */
@Validated
@ConfigurationProperties(prefix = "mugen.jwt")
public record JwtProperties(

        @NotNull Resource privateKey,
        @NotNull Resource publicKey,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration revocationCacheTtl
) {

    public JwtProperties {
        if (revocationCacheTtl != null && accessTokenTtl != null
                && revocationCacheTtl.compareTo(accessTokenTtl) < 0) {
            throw new IllegalArgumentException(
                    "mugen.jwt.revocation-cache-ttl (%s) must be >= access-token-ttl (%s), otherwise a revoked "
                            .formatted(revocationCacheTtl, accessTokenTtl)
                            + "session's access token stays valid after the revocation record has expired");
        }
    }
}
