package com.mugen.auth.slice;

import com.mugen.auth.controller.SsoController;
import com.mugen.auth.controller.SsoStateCookies;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.oauth.PendingAuthorization;
import com.mugen.auth.service.OAuthService;
import com.mugen.auth.support.CookieComponents;
import com.mugen.test.SliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the browser actually receives. The interesting assertions are about the two
 * things that must never appear in a redirect URL — the access token and the PKCE
 * verifier — and about the cookie attributes, which are the whole security model of
 * this flow and are invisible from a service-level test.
 */
@SliceTest(SsoController.class)
@Import(CookieComponents.class)
class SsoControllerTest {

    private static final String FRONTEND = "http://localhost:4200/auth/callback";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OAuthService oauthService;


    private static PendingAuthorization pending() {
        return new PendingAuthorization(OAuthProvider.GOOGLE, "verifier", FRONTEND, "nonce");
    }

    @Test
    @DisplayName("GET /sso/google redirects to the provider and plants the browser nonce")
    void startRedirectsToProvider() throws Exception {
        when(oauthService.begin(eq(OAuthProvider.GOOGLE), any())).thenReturn(new OAuthService.SsoRedirect(
                URI.create("https://accounts.google.com/o/oauth2/v2/auth?client_id=x&state=s"), "nonce-value"));

        MvcResult result = mvc.perform(get("/api/v1/auth/sso/google"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&state=s"))
                .andReturn();

        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie)
                .contains("mugen_sso=nonce-value")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("Path=/api/v1/auth/sso")
                // Lax, not Strict: the callback is a cross-site top-level navigation
                // from the provider, and a Strict cookie would never be sent on it.
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("an unknown provider is a 404 problem document, not a redirect")
    void unknownProviderIsProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/auth/sso/myspace"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("SSO_PROVIDER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("a completed callback sets the refresh cookie and sends the browser home")
    void callbackSetsRefreshCookieAndRedirects() throws Exception {
        when(oauthService.consumeState("state-value", "nonce")).thenReturn(pending());
        when(oauthService.complete(any(), any(), any(), any(), any()))
                .thenReturn(new TokenPair("access-token", "refresh-token", Duration.ofMinutes(15)));

        MvcResult result = mvc.perform(get("/api/v1/auth/sso/google/callback")
                        .param("code", "auth-code")
                        .param("state", "state-value")
                        .cookie(new jakarta.servlet.http.Cookie(SsoStateCookies.NAME, "nonce")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", FRONTEND))
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                .contains("mugen_refresh=refresh-token")
                .contains("HttpOnly")
                .contains("SameSite=Strict"));
        // The handshake cookie is spent and cleared in the same response.
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                .contains("mugen_sso=")
                .contains("Max-Age=0"));

        // The access token goes in the body of /refresh, never in a URL where it
        // would land in browser history, Referer headers and proxy logs.
        assertThat(result.getResponse().getHeader("Location")).doesNotContain("access-token");
    }

    @Test
    @DisplayName("a refused sign-in goes back to the app with a code, not a raw error page")
    void refusalRedirectsBackWithErrorCode() throws Exception {
        when(oauthService.consumeState("state-value", "nonce")).thenReturn(pending());
        when(oauthService.complete(any(), any(), any(), any(), any()))
                .thenThrow(new AuthExceptions.SsoEmailNotVerified("GOOGLE"));

        mvc.perform(get("/api/v1/auth/sso/google/callback")
                        .param("code", "auth-code")
                        .param("state", "state-value")
                        .cookie(new jakarta.servlet.http.Cookie(SsoStateCookies.NAME, "nonce")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", FRONTEND + "?error=SSO_EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("a user who declines consent is sent back, not shown an error")
    void deniedConsentRedirectsBack() throws Exception {
        when(oauthService.consumeState("state-value", "nonce")).thenReturn(pending());

        mvc.perform(get("/api/v1/auth/sso/google/callback")
                        .param("error", "access_denied")
                        .param("state", "state-value")
                        .cookie(new jakarta.servlet.http.Cookie(SsoStateCookies.NAME, "nonce")))
                .andExpect(status().isFound())
                // An ErrorCode name like every other value of this parameter, so a
                // client switches on one vocabulary rather than two.
                .andExpect(header().string("Location", FRONTEND + "?error=SSO_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("a callback whose state does not check out never reaches the token exchange")
    void invalidStateIsProblemDetail() throws Exception {
        when(oauthService.consumeState(any(), any())).thenThrow(new AuthExceptions.SsoStateInvalid());

        mvc.perform(get("/api/v1/auth/sso/google/callback")
                        .param("code", "auth-code")
                        .param("state", "forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SSO_STATE_INVALID"));
    }
}
