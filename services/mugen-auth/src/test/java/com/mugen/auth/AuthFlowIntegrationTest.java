package com.mugen.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.servlet.http.Cookie;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole credential lifecycle against real SQL Server and Redis: register,
 * login, refresh, replay, logout.
 * <p>
 * Not {@code @Transactional} — session rotation takes a pessimistic lock and the
 * replay path deliberately commits a revocation, so rolling back would hide the
 * behaviour under test. Tests isolate by using a fresh email each time instead.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "management.tracing.enabled=false"
})
@Testcontainers
class AuthFlowIntegrationTest {

    private static final String COOKIE = "mugen_refresh";

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> SQL_SERVER =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @TestConfiguration
    static class Containers {
        @Bean
        DynamicPropertyRegistrar containerProperties() {
            return registry -> {
                registry.add("spring.datasource.url", SQL_SERVER::getJdbcUrl);
                registry.add("spring.datasource.username", SQL_SERVER::getUsername);
                registry.add("spring.datasource.password", SQL_SERVER::getPassword);
                registry.add("spring.data.redis.host", REDIS::getHost);
                registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            };
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    /**
     * MockMvc is built explicitly rather than via {@code @AutoConfigureMockMvc}:
     * Boot 4 split test autoconfiguration per technology and no longer ships the
     * MockMvc variant in spring-boot-starter-test. {@code springSecurity()} is what
     * installs the filter chain — without it every endpoint would answer 200 and
     * the authentication assertions below would pass for the wrong reason.
     */
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@mugen.dev";
    }

    private static String uniqueUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private MvcResult register(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andReturn();
    }

    private String accessTokenOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private Cookie refreshCookieOf(MvcResult result) {
        return result.getResponse().getCookie(COOKIE);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("register returns 201 with an access token and sets a hardened refresh cookie")
    void registerIssuesTokens() throws Exception {
        MvcResult result = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(accessTokenOf(result)).isNotBlank();

        Cookie cookie = refreshCookieOf(result);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");

        // SameSite is not exposed on jakarta Cookie; assert on the raw header.
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("SameSite=Strict");
    }

    @Test
    @DisplayName("the refresh token never appears in the response body")
    void refreshTokenIsCookieOnly() throws Exception {
        MvcResult result = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");

        String body = result.getResponse().getContentAsString();
        String refreshToken = refreshCookieOf(result).getValue();

        assertThat(body).doesNotContain(refreshToken);
        assertThat(body).doesNotContain("refreshToken");
    }

    @Test
    @DisplayName("a duplicate email is a 409, not a 500")
    void duplicateEmailConflicts() throws Exception {
        String email = uniqueEmail();
        register(uniqueUsername(), email, "correct-horse-battery");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"correct-horse-battery"}
                                """.formatted(uniqueUsername(), email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("a short password is rejected with per-field validation errors")
    void weakPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"short"}
                                """.formatted(uniqueUsername(), uniqueEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    @DisplayName("a wrong password and an unknown email fail identically")
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(uniqueUsername(), email, "correct-horse-battery");

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-password"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery"}
                                """.formatted(uniqueEmail())))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Identical bodies apart from traceId — otherwise this endpoint tells an
        // attacker which addresses have accounts.
        JsonNode a = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(unknownEmail.getResponse().getContentAsString());
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(a.get("code")).isEqualTo(b.get("code"));
    }

    @Test
    @DisplayName("/me answers from the access token")
    void meReturnsIdentity() throws Exception {
        MvcResult registered = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf(registered)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("a refresh token is refused as a Bearer credential")
    void refreshTokenCannotAuthenticate() throws Exception {
        MvcResult registered = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");
        String refreshToken = refreshCookieOf(registered).getValue();

        // Same key, same issuer, unexpired — only the type claim stops this.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh rotates the cookie and issues a fresh access token")
    void refreshRotates() throws Exception {
        MvcResult registered = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");
        Cookie original = refreshCookieOf(registered);

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andReturn();

        Cookie rotated = refreshCookieOf(refreshed);
        assertThat(rotated.getValue()).isNotEqualTo(original.getValue());
        assertThat(accessTokenOf(refreshed)).isNotBlank();
    }

    @Test
    @DisplayName("replaying a spent refresh token kills the whole session")
    void replayRevokesSession() throws Exception {
        MvcResult registered = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");
        Cookie original = refreshCookieOf(registered);

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andReturn();
        Cookie rotated = refreshCookieOf(refreshed);

        // The stolen copy: valid signature, but a version already rotated away.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isUnauthorized());

        // The legitimate holder is logged out too. That is intentional — once the
        // token has leaked the two are indistinguishable, so the session dies.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(rotated))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout clears the cookie and ends the session")
    void logoutClearsCookie() throws Exception {
        MvcResult registered = register(uniqueUsername(), uniqueEmail(), "correct-horse-battery");
        Cookie refreshCookie = refreshCookieOf(registered);

        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout").cookie(refreshCookie))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(refreshCookieOf(loggedOut).getMaxAge()).isZero();

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout without a cookie still succeeds")
    void logoutIsAlwaysSafe() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("protected endpoints reject an unauthenticated caller")
    void protectedEndpointsRequireToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the session list marks the calling session as current")
    void sessionListFlagsCurrentSession() throws Exception {
        String email = uniqueEmail();
        MvcResult registered = register(uniqueUsername(), email, "correct-horse-battery");

        // A second login from another device.
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"correct-horse-battery"}
                        """.formatted(email)));

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf(registered)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.current == true)].length()").exists());
    }
}
