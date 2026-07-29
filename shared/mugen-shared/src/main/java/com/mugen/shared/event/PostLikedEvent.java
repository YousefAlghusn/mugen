package com.mugen.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.post.liked
 * Producer: mugen-post
 * Consumers: mugen-notification, mugen-feed
 */
public record PostLikedEvent(
        UUID eventId,
        UUID postId,
        UUID likedByUserId,
        UUID postAuthorId,
        Instant occurredAt
) {
}
