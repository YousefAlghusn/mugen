package com.mugen.auth.config;

import com.mugen.auth.exception.AuthExceptions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Binds {@code mugen.auth.sso.*} — the parts of the SSO flow that belong to
 * <em>us</em> rather than to a provider. Client ids and secrets are Spring
 * Security's own {@code spring.security.oauth2.client.*} and live in the
 * {@code sso} profile.
 *
 * @param stateTtl            how long a pending authorization survives in Redis.
 *                            Minutes, not hours: it only has to cover the time a
 *                            human spends on a consent screen, and every extra
 *                            minute is extra window for a forged callback.
 * @param defaultRedirectUri  where the callback sends the browser when the caller
 *                            did not ask for anywhere in particular.
 * @param allowedRedirectUris the complete set of post-login targets. An allowlist
 *                            and not a pattern: this is the one check standing
 *                            between {@code /sso/google?redirect_uri=...} and an
 *                            open redirect that hands a look-alike site a freshly
 *                            signed-in browser.
 */
@Validated
@ConfigurationProperties(prefix = "mugen.auth.sso")
public record SsoProperties(

        @NotNull Duration stateTtl,
        @NotBlank String defaultRedirectUri,
        @NotEmpty List<@NotBlank String> allowedRedirectUris
) {

    public SsoProperties {
        allowedRedirectUris = allowedRedirectUris == null ? List.of() : List.copyOf(allowedRedirectUris);

        // Caught at startup rather than on the first sign-in: a default that is not
        // itself allowed makes every SSO attempt that omits redirect_uri fail, which
        // is the common case and would otherwise only show up in production.
        if (defaultRedirectUri != null && !allowedRedirectUris.isEmpty()
                && !allowedRedirectUris.contains(defaultRedirectUri)) {
            throw new IllegalArgumentException(
                    "mugen.auth.sso.default-redirect-uri (%s) must also appear in allowed-redirect-uris %s"
                            .formatted(defaultRedirectUri, allowedRedirectUris));
        }
    }

    /**
     * @return {@code requested} if it is allowed, or the default when nothing was
     *         requested
     * @throws AuthExceptions.SsoRedirectNotAllowed if a target was requested and is
     *         not on the allowlist
     */
    public String resolveRedirectUri(String requested) {
        if (!StringUtils.hasText(requested)) {
            return defaultRedirectUri;
        }
        if (!allowedRedirectUris.contains(requested)) {
            throw new AuthExceptions.SsoRedirectNotAllowed(requested);
        }
        return requested;
    }
}