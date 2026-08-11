package com.mugen.auth.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugen.auth.support.AuthFixtures;
import com.mugen.test.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.mugen.auth.support.AuthFixtures.PASSWORD;
import static com.mugen.auth.support.AuthFixtures.uniqueEmail;
import static com.mugen.auth.support.AuthFixtures.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole credential lifecycle against real SQL Server and Redis: register,
 * login, refresh, replay, logout.
 * <p>
 * Deliberately not {@code @Transactional} — session rotation takes a pessimistic lock
 * and the replay path commits a revocation, so rolling back would erase the behaviour
 * under test. Isolation comes from {@link AuthFixtures} minting a fresh identity per
 * call instead.
 */
@IntegrationTest
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthFixtures auth;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("register returns 201 with an access token and sets a hardened refresh cookie")
    void registerIssuesTokens() throws Exception {
        AuthFixtures.Account account = auth.register();

        assertThat(account.response().getResponse().getStatus()).isEqualTo(201);
        assertThat(account.accessToken()).isNotBlank();

        Cookie cookie = account.refresh();
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");

        // SameSite is not exposed on jakarta Cookie; assert on the raw header.
        assertThat(account.response().getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("SameSite=Strict");
    }

    @Test
    @DisplayName("the refresh token never appears in the response body")
    void refreshTokenIsCookieOnly() throws Exception {
        AuthFixtures.Account account = auth.register();

        String body = account.response().getResponse().getContentAsString();

        assertThat(body).doesNotContain(account.refresh().getValue());
        assertThat(body).doesNotContain("refreshToken");
    }

    @Test
    @DisplayName("a duplicate email is a 409, not a 500")
    void duplicateEmailConflicts() throws Exception {
        String email = uniqueEmail();
        auth.register(uniqueUsername(), email, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(uniqueUsername(), email, PASSWORD)))
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
        AuthFixtures.Account account = auth.register();

        MvcResult wrongPassword = auth.login(account.email(), "wrong-password").response();
        MvcResult unknownEmail = auth.login(uniqueEmail(), PASSWORD).response();

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);

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
        AuthFixtures.Account account = auth.register();

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("a refresh token is refused as a Bearer credential")
    void refreshTokenCannotAuthenticate() throws Exception {
        AuthFixtures.Account account = auth.register();

        // Same key, same issuer, unexpired — only the type claim stops this.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.refresh().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh rotates the cookie and issues a fresh access token")
    void refreshRotates() throws Exception {
        AuthFixtures.Account account = auth.register();
        Cookie original = account.refresh();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(auth.refreshCookieOf(refreshed).getValue()).isNotEqualTo(original.getValue());
        assertThat(auth.accessTokenOf(refreshed)).isNotBlank();
    }

    @Test
    @DisplayName("replaying a spent refresh token kills the whole session")
    void replayRevokesSession() throws Exception {
        AuthFixtures.Account account = auth.register();
        Cookie original = account.refresh();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andReturn();
        Cookie rotated = auth.refreshCookieOf(refreshed);

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
        AuthFixtures.Account account = auth.register();
        Cookie refreshCookie = account.refresh();

        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout").cookie(refreshCookie))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(auth.refreshCookieOf(loggedOut).getMaxAge()).isZero();

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
        AuthFixtures.Account account = auth.register();

        // A second login from another device.
        auth.login(account.email(), PASSWORD);

        mockMvc.perform(get("/api/v1/auth/sessions").header(HttpHeaders.AUTHORIZATION, account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.current == true)].length()").exists());
    }
}
