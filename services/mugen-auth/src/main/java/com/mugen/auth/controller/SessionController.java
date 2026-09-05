package com.mugen.auth.controller;

import com.mugen.auth.dto.SessionResponse;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.SessionService;
import com.mugen.auth.token.CurrentUser;
import com.mugen.web.openapi.Throws;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = """
        Seeing and ending your own sessions — the practical answer to "I think someone else \
        is logged into my account".

        Every operation is scoped to the caller's own user id, taken from the access token. \
        There is no user id in any path or parameter here, deliberately: one would be an \
        invitation to pass somebody else's.""")
// A revoked session may not manage sessions — least of all these, which are what somebody
// uses to eject an attacker. See RevokedSessionFilter.
@Throws(AuthExceptions.TokenRevoked.class)
public class SessionController {

    private final SessionService sessions;

    /**
     * List your active sessions.
     *
     * <p>One entry per device, each carrying the user agent and IP the session was opened
     * from so an unfamiliar one is recognisable. The calling session is flagged
     * {@code current}.
     *
     * @return active sessions, newest first
     */
    @GetMapping
    public List<SessionResponse> listMine(@AuthenticationPrincipal Jwt token) {
        UUID currentSessionId = CurrentUser.sessionId(token);

        return sessions.listActive(CurrentUser.userId(token)).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    /**
     * Revoke one session — "sign this device out".
     *
     * <p>Revoking your own current session is allowed and is equivalent to logging out.
     *
     * <p>The session is also written to the revocation cache in Redis, which the gateway
     * checks on every request, so an access token already in flight stops working rather
     * than surviving to its expiry.
     *
     * @param sessionId from the list above; must be one of your own
     */
    @ApiResponse(responseCode = "204", description = "Revoked")
    @Throws(AuthExceptions.SessionNotFound.class)
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revokeOne(@AuthenticationPrincipal Jwt token,
                                          @Parameter(description = "From the list above; must be one of your own")
                                          @PathVariable UUID sessionId) {
        sessions.revokeOne(sessionId, CurrentUser.userId(token));
        return ResponseEntity.noContent().build();
    }

    /**
     * Sign out everywhere else.
     *
     * <p>Revokes every session except the one making the call. Sparing the caller is the
     * point, not an omission: someone responding to a compromise should not be signed out
     * of the device they are fixing it from.
     */
    @ApiResponse(responseCode = "204", description = "Every other session revoked; this one still works")
    @DeleteMapping
    public ResponseEntity<Void> revokeAllOthers(@AuthenticationPrincipal Jwt token) {
        sessions.revokeAllExcept(CurrentUser.userId(token), CurrentUser.sessionId(token));
        return ResponseEntity.noContent().build();
    }
}
