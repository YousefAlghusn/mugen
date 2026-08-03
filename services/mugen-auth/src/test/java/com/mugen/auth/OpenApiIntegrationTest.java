package com.mugen.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the OpenAPI document is generated at all, and that it is readable without a
 * token.
 * <p>
 * A container is the price of proving anything here: springdoc builds the document by
 * walking the live handler mappings, so there is no lighter slice that would exercise
 * the same thing — {@code @WebMvcTest} does not apply springdoc's auto-configuration,
 * and a hand-built {@code OpenAPI} bean asserted in isolation would only prove the
 * bean, not the document.
 * <p>
 * What it is really guarding is the pairing of two files that have no other reason to
 * stay in step: the springdoc paths permitted in {@code SecurityConfig} and the paths
 * springdoc actually serves. Change either alone and the docs answer 401 — reachable
 * in every developer's browser only because they happen to be logged in.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "management.tracing.enabled=false",
        "mugen.outbox.enabled=false",
        // No Redis container here: nothing in this test touches the revocation cache,
        // and Lettuce connects lazily, so the context starts without a server.
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Testcontainers
class OpenApiIntegrationTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle.
    static final MSSQLServerContainer<?> SQL_SERVER =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @TestConfiguration
    static class Containers {
        @Bean
        DynamicPropertyRegistrar containerProperties() {
            return registry -> {
                registry.add("spring.datasource.url", SQL_SERVER::getJdbcUrl);
                registry.add("spring.datasource.username", SQL_SERVER::getUsername);
                registry.add("spring.datasource.password", SQL_SERVER::getPassword);
            };
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    /**
     * {@code springSecurity()} is the whole point of the reachability assertions — it
     * installs the real filter chain. Without it every path would answer 200 and the
     * "no token needed" claims below would pass for the wrong reason.
     */
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("the API document is served without authentication")
    void apiDocsArePublic() throws Exception {
        JsonNode document = document();

        assertThat(document.at("/info/title").asText()).isEqualTo("Mugen Auth API");
        assertThat(document.at("/openapi").asText()).startsWith("3.");
    }

    @Test
    @DisplayName("Swagger UI is served without authentication")
    void swaggerUiIsPublic() throws Exception {
        // /swagger-ui.html is a redirect to the webjar's index.html, not the page
        // itself. A 401 or 403 here is the failure this test exists to catch; the
        // redirect target is springdoc's business, not ours.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("every endpoint a client codes against is in the document")
    void documentsThePublicApi() throws Exception {
        JsonNode paths = document().get("paths");

        assertThat(paths.fieldNames()).toIterable().contains(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/sessions",
                "/api/v1/auth/sessions/{sessionId}",
                "/api/v1/auth/me",
                "/api/v1/auth/validate");
    }

    @Test
    @DisplayName("actuator is not part of the API contract")
    void excludesOperationalEndpoints() throws Exception {
        assertThat(document().get("paths").fieldNames()).toIterable()
                .noneMatch(path -> path.startsWith("/actuator"));
    }

    /**
     * The "Authorize" button is the one piece of Swagger UI that is not decoration —
     * without a correctly declared scheme nothing behind a token can be tried at all.
     */
    @Test
    @DisplayName("the bearer scheme is declared so Authorize sends an Authorization header")
    void declaresBearerScheme() throws Exception {
        JsonNode scheme = document().at("/components/securitySchemes/bearerAuth");

        assertThat(scheme.get("type").asText()).isEqualTo("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat").asText()).isEqualTo("JWT");
    }

    /**
     * The cookie's name reaches the document from configuration, not from a literal.
     * Asserted because a hardcoded copy would keep promising the old name after a
     * rename, and nothing else would notice.
     */
    @Test
    @DisplayName("the refresh cookie is documented under the name the service actually sets")
    void declaresRefreshCookieScheme() throws Exception {
        JsonNode scheme = document().at("/components/securitySchemes/refreshCookie");

        assertThat(scheme.get("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.get("in").asText()).isEqualTo("cookie");
        assertThat(scheme.get("name").asText()).isEqualTo("mugen_refresh");
    }

    @Test
    @DisplayName("token-protected endpoints carry a security requirement, public ones do not")
    void marksWhichEndpointsNeedAToken() throws Exception {
        JsonNode paths = document().get("paths");

        assertThat(paths.at("/~1api~1v1~1auth~1me/get/security").toString()).contains("bearerAuth");
        assertThat(paths.at("/~1api~1v1~1auth~1sessions/get/security").toString()).contains("bearerAuth");

        // Obtaining a token cannot itself require one. A stray global security
        // requirement is the usual way this breaks, and it breaks silently — the
        // endpoints keep working, the docs just tell every reader to authenticate
        // first for the one call that exists to make that possible.
        assertThat(paths.at("/~1api~1v1~1auth~1login/post").has("security")).isFalse();
        assertThat(paths.at("/~1api~1v1~1auth~1register/post").has("security")).isFalse();
    }
}
