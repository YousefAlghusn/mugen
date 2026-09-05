package com.mugen.auth.slice;

import com.mugen.auth.controller.SessionController;
import com.mugen.auth.entity.Session;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.SessionService;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.SliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.mugen.test.Callers.authenticatedAs;
import static com.mugen.test.Callers.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session management is scoped to the caller by construction, and that is what this
 * tier can see: <b>every user id these endpoints act on comes from the verified token,
 * never from the request.</b> There is no user id in any path or parameter to pass
 * somebody else's, so the assertions are about what reaches the service.
 */
@SliceTest(SessionController.class)
class SessionControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CURRENT_SESSION = UUID.randomUUID();
    private static final UUID OTHER_SESSION = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SessionService sessionService;

    /** A token as the resource server would hand it to a handler, past the decoder. */
    private static RequestPostProcessor caller() {
        return authenticatedAs(token()
                .subject(USER.toString())
                .claim("sessionId", CURRENT_SESSION.toString())
                .claim("roles", List.of("ROLE_USER"))
                .build());
    }

    /**
     * Ids are assigned by Hibernate on insert and there is deliberately no setter, so a
     * session that has never been persisted has none — and this response is keyed on it.
     */
    private static Session session(UUID id) {
        Session session = Session.open(
                User.withPassword("kaneki", "kaneki@mugen.dev", "{bcrypt}$2a$10$hash"),
                Instant.now().plus(30, ChronoUnit.DAYS),
                "Firefox/141.0",
                "203.0.113.5");
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    /**
     * The one field a client cannot work out for itself: which of these is the session
     * asking. A UI needs it to label "this device" rather than offer to revoke it by
     * accident.
     */
    @Test
    @DisplayName("the list is the caller's own, with the calling session flagged")
    void listsTheCallersOwnSessions() throws Exception {
        when(sessionService.listActive(USER))
                .thenReturn(List.of(session(CURRENT_SESSION), session(OTHER_SESSION)));

        mvc.perform(get("/api/v1/auth/sessions").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CURRENT_SESSION.toString()))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1].current").value(false))
                // The device details are the point of the list; without them one row
                // looks like another and nothing is recognisable.
                .andExpect(jsonPath("$[0].userAgent").value("Firefox/141.0"))
                .andExpect(jsonPath("$[0].ipAddress").value("203.0.113.5"));
    }

    /**
     * Ownership is the service's rule, but it can only apply it if the controller hands it
     * the token's user id. Passing the path's session id alone would let anyone revoke any
     * session whose id they had seen.
     */
    @Test
    @DisplayName("revoking one session is checked against the token's user, not the request")
    void revokeOnePassesTheTokensUser() throws Exception {
        mvc.perform(delete("/api/v1/auth/sessions/{sessionId}", OTHER_SESSION).with(caller()))
                .andExpect(status().isNoContent());

        verify(sessionService).revokeOne(OTHER_SESSION, USER);
    }

    /**
     * Deliberately indistinguishable from "no such session": answering differently would
     * turn this endpoint into a way to discover which session ids are real.
     */
    @Test
    @DisplayName("revoking somebody else's session is refused as if it did not exist")
    void revokingAnotherUsersSessionIsRefused() throws Exception {
        doThrow(new AuthExceptions.SessionNotFound()).when(sessionService).revokeOne(any(), any());

        mvc.perform(delete("/api/v1/auth/sessions/{sessionId}", OTHER_SESSION).with(caller()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.SESSION_NOT_FOUND.name()));
    }

    /**
     * "Sign out everywhere else" spares the caller on purpose: someone responding to a
     * compromise must not be signed out of the device they are fixing it from. Which
     * session to spare is the token's, so it cannot be aimed at another.
     */
    @Test
    @DisplayName("sign out everywhere else spares the calling session")
    void revokeAllOthersSparesTheCaller() throws Exception {
        mvc.perform(delete("/api/v1/auth/sessions").with(caller()))
                .andExpect(status().isNoContent());

        verify(sessionService).revokeAllExcept(USER, CURRENT_SESSION);
    }
}
