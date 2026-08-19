package com.mugen.auth.config;

import com.mugen.web.openapi.MugenApiDocs;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document Swagger UI renders and {@code /v3/api-docs} serves.
 * <p>
 * Written as a bean rather than {@code @OpenAPIDefinition} on the application class
 * so the values that also exist as configuration — the refresh cookie's name, the
 * token TTLs, the build's version — are read from what the running service uses. An
 * annotation would have to restate them as literals, and a literal is a copy that
 * goes stale silently: the docs would keep promising {@code mugen_refresh} long
 * after someone renamed the cookie.
 * <p>
 * Two schemes are declared, because this service authenticates two different ways
 * and only one of them is a header a caller can type in. See {@link AuthApiDocs}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI authOpenApi(RefreshCookieProperties refreshCookieProperties,
                        JwtProperties jwtProperties,
                        ObjectProvider<BuildProperties> buildProperties) {

        return new OpenAPI()
                .info(new Info()
                        .title("Mugen Auth API")
                        .version(version(buildProperties))
                        .description(description(refreshCookieProperties, jwtProperties))
                        .license(new License().name("Apache-2.0")))
                .components(new Components()
                        // HTTP bearer with a JWT format is what makes the UI's "Authorize"
                        // send an Authorization header; the name is mugen-web's because
                        // SecurityRequirementCustomizer attaches it to every secured operation.
                        .addSecuritySchemes(MugenApiDocs.BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        The access token from `/register`, `/login` or `/refresh`. \
                                        RS256, signed by this service; every other service verifies \
                                        it with `public.pem` and never calls back here."""))
                        .addSecuritySchemes(AuthApiDocs.REFRESH_COOKIE_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(refreshCookieProperties.name())
                                .description("""
                                        Set by this service, sent back by the browser. `HttpOnly`, \
                                        so it cannot be supplied from Swagger UI.""")));
    }

    /**
     * The artifact's own version, written by the {@code build-info} goal. Absent only
     * when the application is started without Maven having run, which is a developer's
     * IDE and not somewhere the published version means anything.
     */
    private static String version(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "dev" : build.getVersion();
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
