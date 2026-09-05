package com.mugen.auth.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import com.mugen.auth.config.AuthApiDocs;
import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import com.mugen.web.error.ApiErrorSpec;
import com.mugen.web.error.ApiErrors;
import com.mugen.web.error.AppException;
import com.mugen.web.openapi.MugenApiDocs;
import com.mugen.web.openapi.Throws;
import com.mugen.web.security.PublicEndpoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the generated document describes the service that generated it.
 * <p>
 * A container is the price of proving anything here: springdoc builds the document by
 * walking the live handler mappings, so there is no lighter slice that would exercise
 * the same thing — {@code @WebMvcTest} does not apply springdoc's auto-configuration,
 * and a hand-built {@code OpenAPI} bean asserted in isolation would only prove the bean.
 * <p>
 * Every assertion below compares the document against the <em>same</em> source
 * production reads — the handler mappings, {@code @PublicEndpoint}, {@code @Throws},
 * {@link ErrorCode}, the cookie properties — rather than a literal copied out of
 * {@code OpenApiConfig}. A restatement can only fail when someone renames something,
 * which is the one occasion on which nothing is broken.
 */
@IntegrationTest
class OpenApiTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RefreshCookieProperties refreshCookieProperties;

    /** The filter deciding what the document covers, read here so both halves use one value. */
    @Value("${springdoc.paths-to-match}")
    private List<String> pathsToMatch;

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
        // Reachability is the whole claim: the springdoc paths permitted in
        // SecurityConfig and the paths springdoc serves are two files with no other
        // reason to stay in step, and behind a 401 the docs still look fine to a
        // developer who is already logged in.
        assertThat(document().at("/paths").isEmpty()).isFalse();
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

    /**
     * Both directions, from one source: every endpoint the path filter selects is in the
     * document, and the document contains nothing else.
     * <p>
     * The second half is what keeps {@code /actuator} out — operational surface is not
     * API surface — without naming it, and the first covers endpoints nobody has written
     * yet, which a list of eight paths cannot.
     */
    @Test
    @DisplayName("the document describes exactly the endpoints its path filter selects")
    void documentsExactlyTheMatchedEndpoints() throws Exception {
        assertThat(documentedOperations().keySet())
                .containsExactlyInAnyOrderElementsOf(handlerOperations().keySet());
    }

    /**
     * The "Authorize" button is the one part of Swagger UI that is not decoration, and a
     * requirement naming a scheme nobody declared disables it silently.
     */
    @Test
    @DisplayName("every scheme an operation requires is declared in components")
    void securitySchemesAreDeclaredWhereverTheyAreRequired() throws Exception {
        JsonNode declared = document().at("/components/securitySchemes");

        List<String> required = new ArrayList<>();
        documentedOperations().values().forEach(operation ->
                operation.path("security").forEach(requirement ->
                        requirement.fieldNames().forEachRemaining(required::add)));

        assertThat(required).isNotEmpty();
        assertThat(names(declared)).containsAll(Set.copyOf(required));
    }

    /**
     * The cookie's name reaches the document from the configuration the service runs on.
     * Compared against the injected property rather than the string {@code mugen_refresh},
     * so it fails on the bug it was written for — a hardcoded copy still promising the old
     * name after a rename — and not on the rename itself.
     */
    @Test
    @DisplayName("the refresh cookie is documented under the name the service actually sets")
    void declaresRefreshCookieScheme() throws Exception {
        JsonNode scheme = document().at("/components/securitySchemes/" + AuthApiDocs.REFRESH_COOKIE_SCHEME);

        assertThat(scheme.get("name").asText()).isEqualTo(refreshCookieProperties.name());
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
    @DisplayName("an operation needs a token in the document exactly when its handler does")
    void documentAgreesWithTheFilterChain() throws Exception {
        Map<String, JsonNode> documented = documentedOperations();

        handlerOperations().forEach((id, handler) -> {
            boolean declaresBearer = documented.get(id).path("security").toString()
                    .contains(MugenApiDocs.BEARER_SCHEME);

            assertThat(declaresBearer)
                    .as("%s is%s @PublicEndpoint", id, PublicEndpoints.isPublic(handler) ? "" : " not")
                    .isEqualTo(!PublicEndpoints.isPublic(handler));
        });
    }

    /**
     * The half the document cannot check itself: that the filter chain permits and
     * refuses the same endpoints. Derived from the annotation scan, so it covers whatever
     * is added next — a hand-written list only ever pins what already works.
     * <p>
     * A public endpoint may perfectly well answer 401 itself ({@code /refresh} does, with
     * no cookie), so the status cannot tell "refused by the chain" from "answered by the
     * handler". The {@code WWW-Authenticate} challenge can: only the entry point sets it.
     */
    @Test
    @DisplayName("the derived matchers permit exactly the @PublicEndpoint handlers")
    void publicEndpointsAreReachableAndTheRestAreNot() throws Exception {
        for (Map.Entry<String, HandlerMethod> entry : handlerOperations().entrySet()) {
            String[] id = entry.getKey().split(" ");
            MvcResult result = mockMvc.perform(
                            request(HttpMethod.valueOf(id[1].toUpperCase(Locale.ROOT)), concreteUri(id[0])))
                    .andReturn();
            String challenge = result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);

            if (PublicEndpoints.isPublic(entry.getValue())) {
                assertThat(challenge)
                        .as("%s is @PublicEndpoint and must reach its handler", entry.getKey())
                        .isNull();
            } else {
                assertThat(result.getResponse().getStatus())
                        .as("%s is not @PublicEndpoint and must be refused", entry.getKey())
                        .isEqualTo(401);
                assertThat(challenge).isNotNull();
            }
        }
    }

    /**
     * Descriptions come from javadoc via therapi, and springdoc falls back silently when
     * that is not wired — the document still generates, just without a word of prose.
     * <p>
     * The claim is structural, not a sentence: every handler that has javadoc at runtime
     * has prose in the document. Asserting the wording instead makes rewording a comment
     * a build failure.
     */
    @Test
    @DisplayName("javadoc reaches the document, so a broken therapi setup cannot fail quietly")
    void javadocBecomesTheDescription() throws Exception {
        Map<String, JsonNode> documented = documentedOperations();
        List<String> fromJavadoc = new ArrayList<>();

        handlerOperations().forEach((id, handler) -> {
            if (RuntimeJavadoc.getJavadoc(handler.getMethod()).isEmpty()) {
                return;
            }
            fromJavadoc.add(id);
            JsonNode operation = documented.get(id);

            assertThat(operation.path("summary").asText("") + operation.path("description").asText(""))
                    .as("%s has javadoc, so it must carry prose in the document", id)
                    .isNotBlank();
        });

        // Otherwise the loop above passes by finding no javadoc at all, which is exactly
        // the failure it exists to catch.
        assertThat(fromJavadoc).isNotEmpty();
    }

    /** A new {@link ErrorCode} documents itself, because the enum is what is enumerated. */
    @Test
    @DisplayName("every error code a client could receive is in the shared problem schema")
    void declaresTheSharedProblemSchema() throws Exception {
        JsonNode problem = document().at("/components/schemas/" + MugenApiDocs.PROBLEM_SCHEMA + "/properties");

        assertThat(problem.has("traceId")).isTrue();
        assertThat(problem.at("/code/enum").toString())
                .contains(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    /**
     * The point of {@code @Throws}: the explanation a caller reads is the one written on
     * the exception, so it cannot drift per endpoint. Every declaration on every handler,
     * rather than one endpoint's 409 — the wiring is per-declaration, so pinning one says
     * nothing about the next.
     */
    @Test
    @DisplayName("an endpoint's failures are explained by their exception's declaration")
    void throwsDeclarationsReachTheDocument() throws Exception {
        Map<String, JsonNode> documented = documentedOperations();
        int checked = 0;

        for (Map.Entry<String, HandlerMethod> entry : handlerOperations().entrySet()) {
            for (Class<? extends AppException> thrown : declaredOn(entry.getValue())) {
                ApiErrorSpec spec = ApiErrors.of(thrown);
                String description = documented.get(entry.getKey())
                        .at("/responses/" + spec.status().value() + "/description").asText();

                assertThat(description)
                        .as("%s declares %s", entry.getKey(), thrown.getSimpleName())
                        .contains(spec.code().name())
                        .contains(spec.description());
                checked++;
            }
        }

        assertThat(checked).isNotZero();
    }

    /**
     * The half a generated document cannot check itself: that the 401 it publishes on
     * every secured operation is the 401 the filter chain sends. Spring Security's
     * default entry point answers empty, which left the document promising a {@code code}
     * and a {@code traceId} that never arrived — and no log line behind either.
     */
    @Test
    @DisplayName("a request with no token is refused with the problem document the operation advertises")
    void refusalCarriesTheDocumentedProblemBody() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_INVALID.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        String documented = documentedOperations().get("/api/v1/auth/me get")
                .at("/responses/401/description").asText();

        assertThat(documented).contains(ErrorCode.TOKEN_INVALID.name());
    }

    /**
     * Swagger UI renders the schema's placeholders — {@code "code": "string"} — unless an
     * example says otherwise, which is the one thing a reader wants to copy. Each example
     * must describe the failure it is filed under, on every operation: one naming a
     * different code or status is worse than none, because it is the part that gets copied.
     */
    @Test
    @DisplayName("each documented code carries a worked example of the body it produces")
    void everyCodeShowsTheBodyItProduces() throws Exception {
        int checked = 0;

        for (Map.Entry<String, JsonNode> operation : documentedOperations().entrySet()) {
            for (Map.Entry<String, JsonNode> response : properties(operation.getValue().path("responses"))) {
                JsonNode examples = response.getValue()
                        .path("content").path(MugenApiDocs.PROBLEM_MEDIA_TYPE).path("examples");

                for (Map.Entry<String, JsonNode> example : properties(examples)) {
                    ErrorCode code = ErrorCode.valueOf(example.getKey());
                    JsonNode body = example.getValue().path("value");

                    assertThat(body.path("code").asText())
                            .as("%s %s example %s", operation.getKey(), response.getKey(), example.getKey())
                            .isEqualTo(code.name());
                    assertThat(body.path("status").asInt()).isEqualTo(Integer.parseInt(response.getKey()));
                    assertThat(body.path("type").asText()).isEqualTo(ApiErrors.typeUri(code).toString());
                    assertThat(body.path("traceId").asText()).isNotBlank();
                    checked++;
                }
            }
        }

        assertThat(checked).isNotZero();
    }

    /**
     * The failure this whole design replaced: a response declared as a bare {@code $ref}
     * silently loses its description, because OpenAPI drops a reference's siblings. Every
     * error the document names must say what it means, whatever endpoint added it.
     */
    @Test
    @DisplayName("no documented failure is left unexplained")
    void everyErrorResponseIsDescribed() throws Exception {
        documentedOperations().forEach((id, operation) ->
                properties(operation.path("responses")).forEach(response -> {
                    if (response.getKey().charAt(0) >= '4') {
                        assertThat(response.getValue().path("description").asText())
                                .describedAs("%s → %s", id, response.getKey())
                                .isNotBlank();
                    }
                }));
    }

    /** {@code "/api/v1/auth/login post"} to the operation the document publishes for it. */
    private Map<String, JsonNode> documentedOperations() throws Exception {
        Map<String, JsonNode> operations = new LinkedHashMap<>();

        properties(document().path("paths")).forEach(path ->
                properties(path.getValue()).forEach(method -> {
                    // A path item may also carry "parameters" or "summary" alongside its
                    // operations; only the verbs are operations.
                    if (HTTP_METHODS.contains(method.getKey())) {
                        operations.put(path.getKey() + " " + method.getKey(), method.getValue());
                    }
                }));

        return operations;
    }

    /**
     * The same key, for every handler the document is supposed to cover.
     * <p>
     * Across <em>all</em> handler mappings, not one: actuator contributes a second
     * {@code RequestMappingHandlerMapping}, so asking for the bean by type throws.
     * {@link com.mugen.web.security.PublicEndpointMatcher} iterates them for the same
     * reason.
     */
    private Map<String, HandlerMethod> handlerOperations() {
        Map<String, HandlerMethod> operations = new LinkedHashMap<>();
        AntPathMatcher matcher = new AntPathMatcher();

        context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(handlerMapping ->
                handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
                    if (mapping.getPathPatternsCondition() == null) {
                        return;
                    }
                    mapping.getPathPatternsCondition().getPatterns().forEach(pattern -> {
                        String path = pattern.getPatternString();
                        if (pathsToMatch.stream().noneMatch(filter -> matcher.match(filter, path))) {
                            return;
                        }
                        mapping.getMethodsCondition().getMethods().forEach(method ->
                                operations.put(path + " " + method.name().toLowerCase(Locale.ROOT), handler));
                    });
                }));

        return operations;
    }

    /** Method and controller both contribute, exactly as the customizer reads them. */
    private static List<Class<? extends AppException>> declaredOn(HandlerMethod handler) {
        List<Class<? extends AppException>> thrown = new ArrayList<>();

        Throws onClass = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Throws.class);
        Throws onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Throws.class);

        if (onClass != null) {
            thrown.addAll(Arrays.asList(onClass.value()));
        }
        if (onMethod != null) {
            thrown.addAll(Arrays.asList(onMethod.value()));
        }
        return thrown;
    }

    /** A path template made into something a request can be sent to; the value is never read. */
    private static String concreteUri(String pathPattern) {
        return pathPattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }

    private static List<Map.Entry<String, JsonNode>> properties(JsonNode node) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        node.properties().forEach(entries::add);
        return entries;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
