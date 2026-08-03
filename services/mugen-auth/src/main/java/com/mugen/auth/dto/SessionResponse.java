package com.mugen.auth.dto;

import com.mugen.auth.entity.Session;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the "your active sessions" list.
 *
 * @param userAgent the browser the session was opened from, so an unfamiliar one is
 * recognisable
 * @param ipAddress the address the session was opened from
 * @param current whether this is the session making the request, so a UI can label it
 * "this device" rather than offer to revoke it by accident
 */
public record SessionResponse(
        UUID id,
        String userAgent,
        String ipAddress,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean current
) {

    public static SessionResponse from(Session session, UUID currentSessionId) {
        return new SessionResponse(
                session.getId(),
                session.getUserAgent(),
                session.getIpAddress(),
                session.getCreatedAt(),
                session.getLastUsedAt(),
                session.getExpiresAt(),
                session.getId().equals(currentSessionId));
    }
}
