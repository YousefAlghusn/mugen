package com.mugen.auth.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugen.test.IntegrationTest;
import com.mugen.shared.error.ErrorCode;
import com.mugen.web.security.PublicEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@IntegrationTest
class OpenApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

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

    /**
     * The invariant, rather than a list of paths: an operation advertises
     * {@code bearerAuth} exactly when its handler is not {@code @PublicEndpoint}.
     * <p>
     * Stated this way it also covers endpoints that do not exist yet, which a hand
     * written list cannot. Obtaining a token must never itself require one, and that
     * failure is silent — the endpoints keep working and only the docs lie.
     */
    @Test
    @DisplayName("an operation needs a token in the document exactly when it needs one in the filter chain")
    void documentAgreesWithTheFilterChain() throws Exception {
        JsonNode paths = document().get("paths");

        Set<String> publicOperations = publicOperationIds();
        assertThat(publicOperations).isNotEmpty();

        paths.properties().forEach(pathEntry ->
                pathEntry.getValue().properties().forEach(methodEntry -> {
                    // A path item may also carry "parameters" or "summary" alongside
                    // its operations; only the verbs are operations.
                    if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                        return;
                    }
                    JsonNode operation = methodEntry.getValue();
                    String id = pathEntry.getKey() + " " + methodEntry.getKey();
                    boolean declaresBearer = operation.path("security").toString().contains("bearerAuth");

                    if (publicOperations.contains(id)) {
                        assertThat(declaresBearer)
                                .as("%s is @PublicEndpoint, so it must not advertise a token", id)
                                .isFalse();
                    } else {
                        assertThat(declaresBearer)
                                .as("%s is not @PublicEndpoint, so it must advertise a token", id)
                                .isTrue();
                    }
                }));
    }

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /**
     * {@code "/api/v1/auth/login post"} for every handler carrying the annotation.
     * <p>
     * Across <em>all</em> handler mappings, not one: actuator contributes a second
     * {@code RequestMappingHandlerMapping}, so asking for the bean by type throws.
     * {@link com.mugen.web.security.PublicEndpointMatcher} iterates them for the same
     * reason, and this mirrors it deliberately.
     */
    private Set<String> publicOperationIds() {
        Set<String> ids = new HashSet<>();

        context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(handlerMapping ->
                handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
                    boolean isPublic =
                            AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PublicEndpoint.class)
                                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PublicEndpoint.class);
                    if (!isPublic || mapping.getPathPatternsCondition() == null) {
                        return;
                    }
                    mapping.getPathPatternsCondition().getPatterns().forEach(pattern ->
                            mapping.getMethodsCondition().getMethods().forEach(method ->
                                    ids.add(pattern.getPatternString() + " "
                                            + method.name().toLowerCase(Locale.ROOT))));
                }));

        return ids;
    }

    /**
     * Descriptions come from javadoc via therapi, and springdoc falls back silently
     * when that is not wired — the document still generates, just without a word of
     * prose in it. Asserted on a sentence that exists only in {@code AuthController}'s
     * javadoc, never in an annotation.
     */
    @Test
    @DisplayName("javadoc reaches the document, so a broken therapi setup cannot fail quietly")
    void javadocBecomesTheDescription() throws Exception {
        JsonNode register = document().at("/paths/~1api~1v1~1auth~1register/post");

        // Asserted on the whole operation rather than on `summary` or `description`
        // specifically: which of the two springdoc puts the first sentence in is its
        // business, and the claim here is only that the javadoc arrived at all.
        // Phrases chosen to sit on a single javadoc line, so the assertion does not
        // depend on how line breaks in the comment are normalised.
        assertThat(register.toString())
                .contains("Register a new account and sign in")
                // Exists in no annotation anywhere — only in the javadoc.
                .contains("Creates the account and returns a token pair immediately")
                .contains("deduplicate");
    }

    /**
     * The half the document cannot prove: that the derived matchers actually permit and
     * refuse the right requests. Without this the scan could return nothing at all and
     * every assertion above would still pass.
     */
    @Test
    @DisplayName("the derived matchers permit exactly the @PublicEndpoint handlers")
    void publicEndpointsAreReachableAndTheRestAreNot() throws Exception {
        // Reaching the handler is the claim, so these assert on what the handler does
        // with an empty request — 400 for a missing body, 204 for a logout with no
        // cookie. A 401 here would mean the filter chain refused before the handler ran.
        mockMvc.perform(post("/api/v1/auth/login")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/register")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());

        // /refresh answers 401 either way, so status alone proves nothing. The problem
        // document does: only the handler produces this code.
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/validate")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/auth/sessions")).andExpect(status().isUnauthorized());
    }

    /**
     * The shared problem shape is registered once and referenced, rather than repeated
     * per operation. {@code code} is enumerated from {@link ErrorCode}, so a new code
     * documents itself.
     */
    @Test
    @DisplayName("failures reference one reusable RFC 9457 response")
    void declaresTheSharedProblemResponse() throws Exception {
        JsonNode document = document();

        assertThat(document.at("/components/responses/Problem/content").has("application/problem+json")).isTrue();

        JsonNode problem = document.at("/components/schemas/Problem/properties");
        assertThat(problem.has("traceId")).isTrue();
        assertThat(problem.at("/code/enum").toString()).contains(ErrorCode.EMAIL_ALREADY_REGISTERED.name());

        assertThat(document.at("/paths/~1api~1v1~1auth~1register/post/responses/409/$ref").asText())
                .isEqualTo("#/components/responses/Problem");
    }
}
