package com.mugen.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Attributes of the refresh-token cookie. Configurable because exactly one of them
 * has to differ between local development and everywhere else.
 *
 * @param name     cookie name
 * @param path     scoped to the auth endpoints, so the cookie is not attached to
 *                 any other request. A cookie sent on every call to every service
 *                 is a cookie with far more chances to leak.
 * @param secure   HTTPS only. The single setting that may be relaxed locally, and
 *                 must never be false in a deployed environment.
 * @param sameSite {@code Strict} — the browser will not attach this cookie to any
 *                 cross-site request, which is what makes disabling CSRF
 *                 protection defensible for this endpoint.
 */
@Validated
@ConfigurationProperties(prefix = "mugen.auth.refresh-cookie")
public record RefreshCookieProperties(
        @NotBlank String name,
        @NotBlank String path,
        boolean secure,
        @NotBlank String sameSite
) {
}
