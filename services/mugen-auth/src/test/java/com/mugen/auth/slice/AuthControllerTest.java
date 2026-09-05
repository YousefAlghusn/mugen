package com.mugen.auth.slice;

import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.auth.controller.AuthController;
import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.AuthService;
import com.mugen.auth.support.CookieComponents;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.SliceTest;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the browser receives from the four credential endpoints.
 * <p>
 * The cookie attributes are the whole reason this tier exists for this controller: a
 * service returns a token pair, and whether that reaches the browser as
 * {@code HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth} — and whether logout's
 * cookie matches it closely enough to actually replace it — is decided entirely above
 * the service and is invisible from one.
 */
@SliceTest(AuthController.class)
@Import(CookieComponents.class)
class AuthControllerTest {

    private static final TokenPair TOKENS =
            new TokenPair("access-token", "refresh-token", Duration.ofMinutes(15));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RefreshCookieProperties refreshCookieProperties;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("register answers 201 and login 200, both with the access token in the body")
    void registerAndLoginDifferOnlyInStatus() throws Exception {
        when(authService.register(any(), any(), any(), any())).thenReturn(TOKENS);
        when(authService.login(any(), any(), any())).thenReturn(TOKENS);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"kaneki","email":"kaneki@mugen.dev","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                // Seconds, not the Duration's toString: a client schedules a refresh off it.
                .andExpect(jsonPath("$.expiresIn").value(900));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"kaneki@mugen.dev","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"));
    }

    /**
     * The refresh token leaves in a cookie and only in a cookie. Every attribute here is
     * load-bearing: {@code HttpOnly} is what an XSS cannot read, {@code SameSite=Strict}
     * is what makes disabling CSRF defensible, and {@code Path} is what keeps the cookie
     * off every other call in the system.
     */
    @Test
    @DisplayName("the refresh token is set as a hardened cookie and never appears in the body")
    void refreshTokenLeavesOnlyAsACookie() throws Exception {
        when(authService.login(any(), any(), any())).thenReturn(TOKENS);

        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"kaneki@mugen.dev","password":"correct-horse-battery"}
                                """))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(TOKENS.refreshToken())
                .doesNotContain("refreshToken");

        Cookie cookie = result.getResponse().getCookie(refreshCookieProperties.name());
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(TOKENS.refreshToken());
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isEqualTo(refreshCookieProperties.secure());
        assertThat(cookie.getPath()).isEqualTo(refreshCookieProperties.path());
        // SameSite is not exposed on jakarta Cookie; it only exists in the raw header.
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("SameSite=" + refreshCookieProperties.sameSite());
    }

    /**
     * The trap {@link com.mugen.auth.controller.RefreshTokenCookies#clear()} exists for: a
     * browser replaces a cookie only when name, path and attributes all match, so a
     * clearing cookie that differs in any of them leaves the original one in place and the
     * user signed in after logging out.
     */
    @Test
    @DisplayName("logout clears the cookie with the same attributes it was set with")
    void logoutClearsTheCookieItSet() throws Exception {
        when(authService.login(any(), any(), any())).thenReturn(TOKENS);

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"kaneki@mugen.dev","password":"correct-horse-battery"}
                                """))
                .andReturn();

        MvcResult logout = mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(refreshCookieProperties.name(), "refresh-token")))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie cleared = logout.getResponse().getCookie(refreshCookieProperties.name());
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.getPath()).isEqualTo(login.getResponse()
                .getCookie(refreshCookieProperties.name()).getPath());
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(cleared.getSecure()).isEqualTo(refreshCookieProperties.secure());
    }

    /**
     * Logout must not be able to fail. Reporting an expired or unknown token would leave a
     * client believing it is still signed in — with a cookie this response has just
     * cleared.
     */
    @Test
    @DisplayName("logout still answers 204 when the session behind the cookie is already gone")
    void logoutSwallowsAnUnusableToken() throws Exception {
        doThrow(new AuthExceptions.SessionNotFound()).when(authService).logout(any());

        MvcResult result = mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(refreshCookieProperties.name(), "spent-token")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getCookie(refreshCookieProperties.name()).getMaxAge()).isZero();
    }

    /**
     * {@code /refresh} takes no body: the token is read from the cookie and only from the
     * cookie, so a client is never invited to keep it anywhere script can reach. A cookie
     * under any other name is not that cookie.
     */
    @Test
    @DisplayName("refresh without the configured cookie never reaches the service")
    void refreshWithoutItsCookieIsRefusedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_INVALID.name()));

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("some_other_cookie", "refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_INVALID.name()));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("refresh exchanges the cookie for a new pair and rotates the cookie")
    void refreshRotatesTheCookie() throws Exception {
        when(authService.refresh("refresh-token"))
                .thenReturn(new TokenPair("next-access", "next-refresh", Duration.ofMinutes(15)));

        MvcResult result = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(refreshCookieProperties.name(), "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("next-access"))
                .andReturn();

        assertThat(result.getResponse().getCookie(refreshCookieProperties.name()).getValue())
                .isEqualTo("next-refresh");
    }

    /**
     * The device details a session is recorded with, which are what makes an unfamiliar
     * entry recognisable in the session list. The leftmost {@code X-Forwarded-For} entry is
     * the client; everything after it is a proxy that handled the request.
     */
    @Test
    @DisplayName("the session records the original client address, not the last proxy's")
    void recordsTheOriginatingClient() throws Exception {
        when(authService.login(any(), any(), any())).thenReturn(TOKENS);

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, "Firefox/141.0")
                        .header("X-Forwarded-For", "203.0.113.5, 10.0.0.7")
                        .content("""
                                {"email":"kaneki@mugen.dev","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<RequestContext> context = ArgumentCaptor.forClass(RequestContext.class);
        verify(authService).login(eq("kaneki@mugen.dev"), any(), context.capture());

        assertThat(context.getValue().ipAddress()).isEqualTo("203.0.113.5");
        assertThat(context.getValue().userAgent()).isEqualTo("Firefox/141.0");
    }

    /**
     * Both credential endpoints are {@code @PublicEndpoint}, so nothing above them
     * validates the payload — a bad body must be a 400 naming its fields, not a 500 and
     * not a token.
     */
    @Test
    @DisplayName("a body that fails validation is a 400 naming every offending field")
    void invalidBodyIsAValidationProblem() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"k","email":"not-an-email","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(Matchers.containsInAnyOrder(
                                List.of("username", "email", "password").toArray())));

        verifyNoInteractions(authService);
    }
}
