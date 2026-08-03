package com.mugen.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document Swagger UI renders and {@code /v3/api-docs} serves.
 * <p>
 * Written as a bean rather than {@code @OpenAPIDefinition} on the application class
 * so the values that also exist as configuration — the refresh cookie's name, the
 * token TTLs — are read from the same properties the running service uses. An
 * annotation would have to restate them as literals, and a literal is a copy that
 * goes stale silently: the docs would keep promising {@code mugen_refresh} long
 * after someone renamed the cookie.
 * <p>
 * Two schemes are declared, because this service authenticates two different ways
 * and only one of them is a header a caller can type in. See the field javadocs.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /**
     * The scheme behind Swagger UI's "Authorize" button. HTTP bearer with the
     * {@code JWT} bearer format, which is what makes the UI send
     * {@code Authorization: Bearer <token>} rather than prompting for a raw header.
     */
    public static final String BEARER_SCHEME = "bearerAuth";

    /**
     * The refresh token. Documented as a cookie scheme rather than a parameter
     * because that is what it is — it never appears in a body or a header, and
     * {@code /refresh} and {@code /logout} take no visible input at all without it.
     * <p>
     * Swagger UI cannot drive this one: the cookie is {@code HttpOnly}, so script
     * cannot set it, and browsers refuse {@code Cookie} as a fetch header. It is
     * declared so the contract is complete and so the two endpoints do not read as
     * though they need nothing — actually exercising them means letting the browser
     * replay the cookie a prior {@code /login} set.
     */
    public static final String REFRESH_COOKIE_SCHEME = "refreshCookie";

    @Bean
    OpenAPI authOpenApi(RefreshCookieProperties refreshCookieProperties,
                        JwtProperties jwtProperties,
                        @Value("${spring.application.version:0.1.0-SNAPSHOT}") String applicationVersion) {

        return new OpenAPI()
                .info(new Info()
                        .title("Mugen Auth API")
                        .version(applicationVersion)
                        .description(description(refreshCookieProperties, jwtProperties))
                        .license(new License().name("Apache-2.0")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        The access token from `/register`, `/login` or `/refresh`. \
                                        RS256, signed by this service; every other service verifies \
                                        it with `public.pem` and never calls back here."""))
                        .addSecuritySchemes(REFRESH_COOKIE_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(refreshCookieProperties.name())
                                .description("""
                                        Set by this service, sent back by the browser. `HttpOnly`, \
                                        so it cannot be supplied from Swagger UI.""")));
    }

    /**
     * Deliberately explains the token model instead of restating the endpoint list —
     * that part the document already shows. What a reader cannot infer from the paths
     * is why the access token comes back in a body and the refresh token does not.
     */
    private static String description(RefreshCookieProperties refreshCookieProperties,
                                      JwtProperties jwtProperties) {
        return """
                Registration, login, RS256 token issuance, session management and SSO.

                **Reached through the gateway in every real deployment** — the port this \
                document was served from is a development convenience. Paths are unchanged \
                by the hop.

                ### Tokens
                - **Access token** — RS256 JWT, %d minutes, claims `{userId, sessionId, roles}`. \
                Returned in the response body, to be held in memory only. Never `localStorage` \
                or `sessionStorage`: both are readable by any XSS on the page.
                - **Refresh token** — RS256 JWT, %d days. Never in a body. It is set as a \
                `%s` cookie, `HttpOnly; Secure; SameSite=%s; Path=%s`, so script cannot read it \
                and the browser will not attach it to any other call.

                Refreshing rotates the token and increments the session's version. A token \
                presented at an already-spent version is treated as replay and revokes the \
                whole session.

                ### Errors
                Every failure is an RFC 9457 `application/problem+json` document carrying a \
                `code` and a `traceId`. Quote the `traceId` when reporting a problem — it is \
                what ties the response to the server-side log line.
                """
                .formatted(
                        jwtProperties.accessTokenTtl().toMinutes(),
                        jwtProperties.refreshTokenTtl().toDays(),
                        refreshCookieProperties.name(),
                        refreshCookieProperties.sameSite(),
                        refreshCookieProperties.path());
    }
}
