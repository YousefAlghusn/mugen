package com.mugen.auth.integration;

import com.mugen.auth.support.AuthFixtures;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

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
    private JsonMapper json;

    /**
     * The cookie's attributes are asserted in {@code slice/AuthControllerTest}, which is
     * the lowest tier that can see them. What only this tier can say is that a real
     * registration, against a real database, ends in a real token pair.
     */
    @Test
    @DisplayName("register returns 201 with an access token and a refresh cookie")
    void registerIssuesTokens() throws Exception {
        AuthFixtures.Account account = auth.register();

        assertThat(account.response().getResponse().getStatus()).isEqualTo(201);
        assertThat(account.accessToken()).isNotBlank();
        assertThat(account.refresh()).isNotNull();
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
        JsonNode a = json.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = json.readTree(unknownEmail.getResponse().getContentAsString());
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

    /**
     * The finding this service was left with after its first real run: with the session
     * revoked, {@code /validate} answered 401 while {@code /sessions} still answered 200,
     * because only {@code /validate} consulted Redis. The access token is signature-valid
     * for its whole TTL either way — so the session-management endpoints stayed open to a
     * token belonging to a session someone had just ended, which is the opposite of what
     * ending it is for.
     * <p>
     * Settled at the 2.11 review: mugen-auth checks for itself rather than trusting that
     * every request came through the gateway. See {@code RevokedSessionFilter}.
     */
    @Test
    @DisplayName("a revoked session's access token stops working on every endpoint, not only /validate")
    void revocationIsEnforcedByTheServiceItself() throws Exception {
        AuthFixtures.Account account = auth.register();

        mockMvc.perform(post("/api/v1/auth/logout").cookie(account.refresh()))
                .andExpect(status().isNoContent());

        for (String endpoint : List.of("/api/v1/auth/validate", "/api/v1/auth/me", "/api/v1/auth/sessions")) {
            mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, account.bearer()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_REVOKED.name()));
        }
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
