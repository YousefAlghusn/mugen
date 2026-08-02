package com.mugen.auth.service;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.entity.Session;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns the session lifecycle and, with it, refresh-token replay detection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessions;
    private final RevocationCacheService revocationCacheService;
    private final JwtProperties jwtProperties;

    @Transactional
    public Session open(User user, String userAgent, String ipAddress) {
        Session session = Session.open(
                user,
                Instant.now().plus(jwtProperties.refreshTokenTtl()),
                truncate(userAgent, 400),
                truncate(ipAddress, 45));
        return sessions.save(session);
    }

    /**
     * Validates a presented refresh-token version and advances the session.
     * <p>
     * Three outcomes, and the middle one is the point of the whole design:
     * <ul>
     *   <li><b>version == current</b> — the expected case. Rotate and return.</li>
     *   <li><b>version &lt; current</b> — this token was already exchanged. Only the
     *       holder of a copy can present a spent token, so the session is treated as
     *       compromised and revoked entirely. Rejecting just this one request would
     *       not help: whoever stole it may hold the newer token too, and rejecting
     *       one call would leave them logged in.</li>
     *   <li><b>version &gt; current</b> — a version this service never issued.
     *       Forged, and handled the same way.</li>
     * </ul>
     * The row is locked for update, so two concurrent refreshes cannot both read the
     * same version and both succeed — which would fork the session into two valid
     * token chains with nothing to distinguish them.
     *
     * @return the session, with {@code tokenVersion} already advanced
     */
    // noRollbackFor is load-bearing, not a tidy-up. Throwing SessionReplayDetected
    // marks the transaction for rollback, which would undo the revocation this
    // method just performed — leaving the session live in the database while Redis
    // (non-transactional) already reported it revoked. The attacker's stolen token
    // would keep working until the Redis key expired 15 minutes later. The
    // revocation is a deliberate security side effect and must outlive the throw.
    @Transactional(noRollbackFor = AuthExceptions.SessionReplayDetected.class)
    public Session rotate(UUID sessionId, int presentedVersion) {
        Session session = sessions.findForRotation(sessionId)
                .orElseThrow(AuthExceptions.SessionNotFound::new);

        if (!session.isActive(Instant.now())) {
            throw new AuthExceptions.SessionNotFound();
        }

        if (presentedVersion != session.getTokenVersion()) {
            log.warn("Refresh token replay detected, revoking session sessionId={} userId={} "
                            + "presentedVersion={} currentVersion={}",
                    sessionId, session.getUser().getId(), presentedVersion, session.getTokenVersion());
            revoke(session);
            throw new AuthExceptions.SessionReplayDetected();
        }

        session.rotate();
        return session;
    }

    /** Sessions to show in a "your devices" list. */
    @Transactional(readOnly = true)
    public List<Session> listActive(UUID userId) {
        return sessions.findActiveByUserId(userId, Instant.now());
    }

    /**
     * Revokes one session on the owner's behalf.
     *
     * @throws AuthExceptions.SessionNotFound if the session belongs to someone else —
     *         deliberately indistinguishable from "does not exist", so this endpoint
     *         cannot be used to discover which session ids are real
     */
    @Transactional
    public void revokeOne(UUID sessionId, UUID requestingUserId) {
        Session session = sessions.findById(sessionId)
                .filter(candidate -> candidate.getUser().getId().equals(requestingUserId))
                .orElseThrow(AuthExceptions.SessionNotFound::new);

        revoke(session);
    }

    /**
     * "Log out everywhere else" — the standard response to a suspected compromise,
     * so the user is not signed out of the device they are fixing it from.
     *
     * @return how many sessions were revoked
     */
    @Transactional
    public int revokeAllExcept(UUID userId, UUID currentSessionId) {
        List<UUID> doomed = sessions.findActiveByUserId(userId, Instant.now()).stream()
                .map(Session::getId)
                .filter(id -> !id.equals(currentSessionId))
                .toList();

        int revoked = sessions.revokeAllForUserExcept(userId, currentSessionId, Instant.now());

        // Redis after the DB update: the gateway must never see a session cached as
        // revoked that the database still considers live.
        revocationCacheService.revokeAll(doomed);
        return revoked;
    }

    /**
     * Revokes a session the caller has already proved possession of, via a valid
     * refresh token. Idempotent — revoking an already-revoked session is a no-op,
     * which is what makes a repeated logout harmless.
     */
    @Transactional
    public void revokeById(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(this::revoke);
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        List<UUID> doomed = sessions.findActiveByUserId(userId, Instant.now()).stream()
                .map(Session::getId)
                .toList();

        sessions.revokeAllForUser(userId, Instant.now());
        revocationCacheService.revokeAll(doomed);
    }

    private void revoke(Session session) {
        session.revoke();
        sessions.save(session);
        revocationCacheService.revoke(session.getId());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
