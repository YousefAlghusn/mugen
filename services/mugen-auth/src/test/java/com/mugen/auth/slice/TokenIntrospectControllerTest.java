package com.mugen.auth.slice;

import com.mugen.auth.controller.TokenIntrospectController;
import com.mugen.auth.entity.Role;
import com.mugen.test.SliceTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static com.mugen.test.Callers.authenticatedAs;
import static com.mugen.test.Callers.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two endpoints that answer questions about a token rather than doing anything with
 * it. Both answer from the token alone: whether the session behind it is still alive is
 * decided by {@code RevokedSessionFilter} for every endpoint of this service, which is a
 * filter-chain question and therefore an integration-tier one — {@code AuthFlowTest}
 * asserts it.
 */
@SliceTest(TokenIntrospectController.class)
class TokenIntrospectControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor caller() {
        return authenticatedAs(token()
                .subject(USER.toString())
                .claim("sessionId", SESSION.toString())
                .claim("roles", List.of(Role.USER, Role.MODERATOR))
                .build());
    }

    /**
     * No database read: {@code /me} is called on page load by every client there will
     * ever be, and the point of a self-validating token is that answering costs nothing.
     * It reflects the token, not current state — a role granted after it was issued shows
     * up at the next refresh.
     */
    @Test
    @DisplayName("/me answers from the token's own claims")
    void meReadsTheToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.roles").value(Matchers.contains(Role.USER, Role.MODERATOR)));
    }

    /** Reaching the handler is the answer; everything that could refuse it already has. */
    @Test
    @DisplayName("/validate is the status, with no body to read")
    void validateAnswersWithItsStatus() throws Exception {
        mvc.perform(get("/api/v1/auth/validate").with(caller()))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}
