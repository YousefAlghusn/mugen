package com.mugen.shared.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.post.liked
 * Producer: mugen-post
 * Consumers: mugen-notification, mugen-feed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostLikedEvent(
        UUID eventId,
        UUID postId,
        UUID likedByUserId,
        UUID postAuthorId,
        Instant occurredAt
) implements DomainEvent {
}
