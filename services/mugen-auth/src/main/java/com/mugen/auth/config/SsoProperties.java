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
 * @param stateTtl            minutes, not hours — it need only cover a consent
 *                            screen, and every extra minute widens the window for a
 *                            forged callback
 * @param defaultRedirectUri  used when the caller asked for nowhere in particular
 * @param allowedRedirectUris an allowlist, not a pattern: the one check between
 *                            {@code ?redirect_uri=} and an open redirect
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

        // A default that is not itself allowed breaks every sign-in omitting
        // redirect_uri — the common case, so it is caught at startup.
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