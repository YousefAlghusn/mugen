package com.mugen.auth.controller;

import com.mugen.auth.service.SessionService;
import com.mugen.auth.dto.SessionResponse;
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

/**
 * Lets a user see and end their own sessions — the practical answer to "I think
 * someone else is logged into my account".
 * <p>
 * Every operation is scoped to the caller's own user id, taken from the token
 * rather than from a parameter. A userId in the path would be an invitation to
 * pass someone else's.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessions;

    @GetMapping
    public List<SessionResponse> listMine(@AuthenticationPrincipal Jwt token) {
        UUID currentSessionId = CurrentUser.sessionId(token);

        return sessions.listActive(CurrentUser.userId(token)).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revokeOne(@AuthenticationPrincipal Jwt token,
                                          @PathVariable UUID sessionId) {
        sessions.revokeOne(sessionId, CurrentUser.userId(token));
        return ResponseEntity.noContent().build();
    }

    /**
     * "Log out everywhere else". Spares the calling session on purpose — a user
     * responding to a compromise should not be signed out of the device they are
     * fixing it from.
     */
    @DeleteMapping
    public ResponseEntity<Void> revokeAllOthers(@AuthenticationPrincipal Jwt token) {
        sessions.revokeAllExcept(CurrentUser.userId(token), CurrentUser.sessionId(token));
        return ResponseEntity.noContent().build();
    }
}
