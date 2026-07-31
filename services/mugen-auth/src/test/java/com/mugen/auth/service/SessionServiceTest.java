package com.mugen.auth.service;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.domain.Session;
import com.mugen.auth.domain.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused on rotation and replay detection — the logic that decides whether a
 * stolen refresh token gets to stay useful.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository sessions;

    @Mock
    private RevocationCacheService revocationCache;

    private JwtProperties properties;
    private SessionService sessionService;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties(
                new ByteArrayResource(new byte[0]),
                new ByteArrayResource(new byte[0]),
                "https://mugen.dev/auth",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                Duration.ofMinutes(15));

        sessionService = new SessionService(sessions, revocationCache, properties);

        userId = UUID.randomUUID();
        user = User.withPassword("kaneki", "kaneki@mugen.dev", "{bcrypt}hash");
        ReflectionTestUtils.setField(user, "id", userId);
    }

    private Session sessionAtVersion(int version) {
        Session session = Session.open(user, Instant.now().plus(30, ChronoUnit.DAYS), "JUnit", "127.0.0.1");
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(session, "tokenVersion", version);
        return session;
    }

    @Test
    @DisplayName("presenting the current version rotates the session forward")
    void rotatesOnMatchingVersion() {
        Session session = sessionAtVersion(3);
        when(sessions.findForRotation(session.getId())).thenReturn(Optional.of(session));

        Session rotated = sessionService.rotate(session.getId(), 3);

        assertThat(rotated.getTokenVersion()).isEqualTo(4);
        verify(revocationCache, never()).revoke(any());
    }

    @Test
    @DisplayName("a spent version revokes the entire session, not just the request")
    void replayRevokesWholeSession() {
        Session session = sessionAtVersion(5);
        when(sessions.findForRotation(session.getId())).thenReturn(Optional.of(session));

        // v3 was rotated away two refreshes ago — only a captured copy could present it.
        assertThatThrownBy(() -> sessionService.rotate(session.getId(), 3))
                .isInstanceOf(AuthExceptions.SessionReplayDetected.class);

        assertThat(session.getRevokedAt()).isNotNull();
        verify(revocationCache).revoke(session.getId());
    }

    @Test
    @DisplayName("a version this service never issued is treated as forged")
    void futureVersionIsRejected() {
        Session session = sessionAtVersion(2);
        when(sessions.findForRotation(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.rotate(session.getId(), 99))
                .isInstanceOf(AuthExceptions.SessionReplayDetected.class);

        verify(revocationCache).revoke(session.getId());
    }

    @Test
    @DisplayName("an already-revoked session cannot be rotated")
    void revokedSessionCannotRotate() {
        Session session = sessionAtVersion(1);
        session.revoke();
        when(sessions.findForRotation(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.rotate(session.getId(), 1))
                .isInstanceOf(AuthExceptions.SessionNotFound.class);
    }

    @Test
    @DisplayName("an expired session cannot be rotated")
    void expiredSessionCannotRotate() {
        Session session = Session.open(user, Instant.now().minus(1, ChronoUnit.DAYS), "JUnit", "127.0.0.1");
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        when(sessions.findForRotation(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.rotate(session.getId(), 0))
                .isInstanceOf(AuthExceptions.SessionNotFound.class);
    }

    @Test
    @DisplayName("an unknown session id is rejected without revealing that it is unknown")
    void unknownSessionIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(sessions.findForRotation(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.rotate(unknown, 0))
                .isInstanceOf(AuthExceptions.SessionNotFound.class)
                .hasMessage("Session is no longer valid.");
    }

    @Test
    @DisplayName("revoking someone else's session is indistinguishable from it not existing")
    void cannotRevokeAnotherUsersSession() {
        Session session = sessionAtVersion(0);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.revokeOne(session.getId(), UUID.randomUUID()))
                .isInstanceOf(AuthExceptions.SessionNotFound.class);

        assertThat(session.getRevokedAt()).isNull();
        verify(revocationCache, never()).revoke(any());
    }

    @Test
    @DisplayName("log out everywhere spares the current session and caches the rest")
    void revokeAllExceptSparesCurrent() {
        Session current = sessionAtVersion(0);
        Session other = sessionAtVersion(0);
        when(sessions.findActiveByUserId(eq(userId), any())).thenReturn(List.of(current, other));
        when(sessions.revokeAllForUserExcept(eq(userId), eq(current.getId()), any())).thenReturn(1);

        int revoked = sessionService.revokeAllExcept(userId, current.getId());

        assertThat(revoked).isEqualTo(1);

        // Only the other session reaches Redis; the current one keeps working.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> cached = ArgumentCaptor.forClass(Iterable.class);
        verify(revocationCache).revokeAll(cached.capture());
        assertThat(cached.getValue()).containsExactly(other.getId());
    }

    @Test
    @DisplayName("logout is idempotent")
    void repeatedLogoutIsHarmless() {
        Session session = sessionAtVersion(0);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        sessionService.revokeById(session.getId());
        Instant firstRevokedAt = session.getRevokedAt();
        sessionService.revokeById(session.getId());

        // The original revocation timestamp is preserved — the audit record of when
        // the session actually ended must not be overwritten by a retry.
        assertThat(session.getRevokedAt()).isEqualTo(firstRevokedAt);
    }
}
