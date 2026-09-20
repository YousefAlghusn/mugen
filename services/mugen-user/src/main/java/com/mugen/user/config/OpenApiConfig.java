package com.mugen.user.config;

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

/** The document Swagger UI renders and {@code /v3/api-docs} serves. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI userOpenApi(AvatarProperties avatarProperties, ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("Mugen User API")
                        .version(build == null ? "dev" : build.getVersion())
                        .description("""
                                Profiles, follows and avatars.

                                **Reached through the gateway in every real deployment** — the port this \
                                document was served from is a development convenience. Paths are unchanged \
                                by the hop.

                                ### Identity
                                Every write is scoped to the caller: the user id comes from the access \
                                token mugen-auth issued, never from the request. Reads of a profile and \
                                its lists are public.

                                ### Avatars
                                Files never pass through this service. An upload URL is presigned for one \
                                object and one content type and lasts %d minutes; the URL a profile carries \
                                is presigned for reading and lasts %d minutes — fetch the profile again \
                                rather than storing it.

                                ### Errors
                                Every failure is an RFC 9457 `application/problem+json` document carrying a \
                                `code` and a `traceId`. Quote the `traceId` when reporting a problem.
                                """.formatted(avatarProperties.uploadUrlTtl().toMinutes(), avatarProperties.urlTtl().toMinutes()))
                        .license(new License().name("Apache-2.0")))
                .components(new Components()
                        .addSecuritySchemes(MugenApiDocs.BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("The access token from mugen-auth. Verified here with its public key; "
                                        + "this service never calls back.")));
    }
}
