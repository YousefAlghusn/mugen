package com.mugen.auth.slice;

import com.mugen.auth.controller.TokenIntrospectController;
import com.mugen.auth.entity.Role;
import com.mugen.auth.service.RevocationCacheService;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.SliceTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.mugen.test.Callers.authenticatedAs;
import static com.mugen.test.Callers.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two endpoints that answer questions about a token rather than doing anything with
 * it, and the difference between them: {@code /me} trusts the signature and reads the
 * claims, {@code /validate} additionally asks Redis whether the session behind them is
 * still alive. That second lookup is the entire reason the endpoint exists — an access
 * token stays signature-valid for its whole TTL after a sign-out.
 */
@SliceTest(TokenIntrospectController.class)
class TokenIntrospectControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RevocationCacheService revocationCache;

    private static RequestPostProcessor caller() {
        return authenticatedAs(token()
                .subject(USER.toString())
                .claim("sessionId", SESSION.toString())
                .claim("roles", List.of(Role.USER, Role.MODERATOR))
                .build());
    }

    /**
     * No database read, and no revocation lookup either: {@code /me} is called on page
     * load by every client there will ever be, and the point of a self-validating token
     * is that this costs nothing. It reflects the token, not current state.
     */
    @Test
    @DisplayName("/me answers from the token's own claims, consulting nothing")
    void meReadsTheTokenAndNothingElse() throws Exception {
        mvc.perform(get("/api/v1/auth/me").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.roles").value(Matchers.contains(Role.USER, Role.MODERATOR)));

        verifyNoInteractions(revocationCache);
    }

    @Test
    @DisplayName("/validate accepts a token whose session is still live")
    void validateAcceptsALiveSession() throws Exception {
        when(revocationCache.isRevoked(SESSION)).thenReturn(false);

        mvc.perform(get("/api/v1/auth/validate").with(caller()))
                .andExpect(status().isOk());
    }

    /**
     * The gap this endpoint closes. The signature is still good — nothing about a revoked
     * session invalidates it — so without the Redis check this would answer 200 for the
     * remainder of the access token's lifetime.
     */
    @Test
    @DisplayName("/validate refuses a token whose session has been revoked")
    void validateRefusesARevokedSession() throws Exception {
        when(revocationCache.isRevoked(SESSION)).thenReturn(true);

        mvc.perform(get("/api/v1/auth/validate").with(caller()))
                .andExpect(status().isUnauthorized())
                // A code of its own, not TOKEN_INVALID: the client's correct response is
                // to sign in again, not to retry with a refresh it also cannot use.
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_REVOKED.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
