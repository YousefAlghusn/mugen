package com.mugen.auth.controller;

import com.mugen.auth.service.SessionService;
import com.mugen.auth.dto.SessionResponse;
import com.mugen.auth.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Sessions", description = """
        Seeing and ending your own sessions — the practical answer to "I think someone else \
        is logged into my account".

        Every operation is scoped to the caller's own user id, taken from the access token. \
        There is no user id in any path or parameter here, deliberately: one would be an \
        invitation to pass somebody else's.""")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class SessionController {

    private final SessionService sessions;

    @Operation(summary = "List your active sessions",
            description = """
                    One entry per device, each carrying the user agent and IP the session was \
                    opened from so an unfamiliar one is recognisable. The entry for the calling \
                    session is flagged `current`.""")
    @ApiResponse(responseCode = "200", description = "Active sessions, newest first")
    @GetMapping
    public List<SessionResponse> listMine(@AuthenticationPrincipal Jwt token) {
        UUID currentSessionId = CurrentUser.sessionId(token);

        return sessions.listActive(CurrentUser.userId(token)).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    @Operation(summary = "Revoke one session",
            description = """
                    Ends a single session — "sign this device out". Revoking your own current \
                    session is allowed and is equivalent to logging out.

                    The session is also written to the revocation cache in Redis, which the \
                    gateway checks on every request, so an access token already in flight stops \
                    working rather than surviving to its expiry.""")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "401", description = """
            Also the answer when the session does not exist or belongs to someone else — \
            `SESSION_NOT_FOUND`, deliberately indistinguishable from an unusable token, so \
            this endpoint cannot be used to discover which session ids are real""",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revokeOne(@AuthenticationPrincipal Jwt token,
                                          @Parameter(description = "From the list above; must be one of your own")
                                          @PathVariable UUID sessionId) {
        sessions.revokeOne(sessionId, CurrentUser.userId(token));
        return ResponseEntity.noContent().build();
    }

    /**
     * "Log out everywhere else". Spares the calling session on purpose — a user
     * responding to a compromise should not be signed out of the device they are
     * fixing it from.
     */
    @Operation(summary = "Sign out everywhere else",
            description = """
                    Revokes every session except the one making the call. Sparing the caller is \
                    the point, not an omission: someone responding to a compromise should not \
                    be signed out of the device they are fixing it from.""")
    @ApiResponse(responseCode = "204", description = "Every other session revoked; this one still works")
    @DeleteMapping
    public ResponseEntity<Void> revokeAllOthers(@AuthenticationPrincipal Jwt token) {
        sessions.revokeAllExcept(CurrentUser.userId(token), CurrentUser.sessionId(token));
        return ResponseEntity.noContent().build();
    }
}
