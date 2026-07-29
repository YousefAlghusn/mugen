package com.mugen.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.user.followed
 * Producer: mugen-user
 * Consumers: mugen-notification
 */
public record UserFollowedEvent(
        UUID eventId,
        UUID followerId,
        UUID followeeId,
        Instant occurredAt
) {
}
