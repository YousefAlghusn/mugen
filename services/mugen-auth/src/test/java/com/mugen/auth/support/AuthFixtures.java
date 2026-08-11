package com.mugen.auth.support;

import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.test.Fixture;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A signed-in user in one line, so a test that needs one can spend its length on what
 * it is actually asserting.
 * <p>
 * Credentials are random per call. That is what lets the integration tier share one
 * database across the whole run without {@code @Transactional} — which several of these
 * tests must do without, because a committed revocation is the behaviour they exist to
 * check and a rollback would erase it.
 */
@Fixture
public class AuthFixtures {

    /** Long enough to satisfy the registration constraint, and constant so failures read the same way. */
    public static final String PASSWORD = "correct-horse-battery";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final RefreshCookieProperties refreshTokenCookie;

    public AuthFixtures(MockMvc mvc, JsonMapper json, RefreshCookieProperties refreshTokenCookie) {
        this.mvc = mvc;
        this.json = json;
        this.refreshTokenCookie = refreshTokenCookie;
    }

    /**
     * A registered, signed-in user with a fresh identity.
     *
     * @param username    as registered, for a test that needs to log in again
     * @param email       as registered
     * @param accessToken the bearer credential
     * @param refresh     the refresh cookie, ready to hand to {@code .cookie(...)}
     * @param response    the raw result, for assertions about the registration itself
     */
    public record Account(String username, String email, String accessToken, Cookie refresh, MvcResult response) {

        /** For {@code .header(HttpHeaders.AUTHORIZATION, account.bearer())}. */
        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@mugen.dev";
    }

    /** Usernames are length-limited, so this is a UUID with the dashes taken out and truncated. */
    public static String uniqueUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** Registers a new account and returns its credentials. */
    public Account register() throws Exception {
        return register(uniqueUsername(), uniqueEmail(), PASSWORD);
    }

    public Account register(String username, String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andReturn();

        return new Account(username, email, accessTokenOf(result), refreshCookieOf(result), result);
    }

    /** A second session for an account that already exists — a sign-in from another device. */
    public Account login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andReturn();

        return new Account(null, email, accessTokenOf(result), refreshCookieOf(result), result);
    }

    /** Null when the response carried no token, so a failure asserts on the body rather than an NPE here. */
    public String accessTokenOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body.isBlank()) {
            return null;
        }
        return json.readTree(body).path("accessToken").asString(null);
    }

    /** Read by the name the service is configured with, never a literal — that is the bug this would otherwise hide. */
    public Cookie refreshCookieOf(MvcResult result) {
        return result.getResponse().getCookie(refreshTokenCookie.name());
    }

    public HttpHeaders bearer(Account account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, account.bearer());
        return headers;
    }
}
