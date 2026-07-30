package com.mugen.shared.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.user.followed
 * Producer: mugen-user
 * Consumers: mugen-notification
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserFollowedEvent(
        UUID eventId,
        UUID followerId,
        UUID followeeId,
        Instant occurredAt
) implements DomainEvent {
}
