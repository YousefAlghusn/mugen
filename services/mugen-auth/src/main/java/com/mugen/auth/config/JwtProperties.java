package com.mugen.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.jwt.*}.
 * <p>
 * A validated record rather than {@code @Value} injection: the constraints run at
 * startup, so a missing key file or a malformed TTL fails immediately with a clear
 * message instead of throwing on the first login attempt in production.
 *
 * @param privateKey         PEM (PKCS#8) used to sign. Never leaves this service.
 * @param publicKey          PEM (X.509) — also copied to mugen-gateway to verify.
 * @param issuer             {@code iss} claim; verifiers must check it.
 * @param accessTokenTtl     short by design: the gateway checks revocation against
 *                           Redis with this same TTL, so a revoked session cannot
 *                           outlive the cache entry that records it.
 * @param refreshTokenTtl    long-lived, HttpOnly cookie, rotated on every use.
 * @param revocationCacheTtl how long a revoked sessionId stays in Redis. Must be
 *                           at least {@code accessTokenTtl}, or an access token
 *                           could outlive the record saying it was revoked.
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
