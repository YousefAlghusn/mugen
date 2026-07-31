package com.mugen.auth.dto;

import com.mugen.auth.entity.Session;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the "your active sessions" list.
 *
 * @param current whether this is the session making the request — so the UI can
 *                label it "this device" and avoid offering to revoke it by accident
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
