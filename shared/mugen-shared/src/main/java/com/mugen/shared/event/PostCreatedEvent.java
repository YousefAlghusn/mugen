package com.mugen.shared.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Topic: mugen.post.created
 * Producer: mugen-post
 * Consumers: mugen-feed, mugen-search, mugen-notification
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostCreatedEvent(
        UUID eventId,
        UUID postId,
        UUID authorId,
        String contentPreview,
        List<String> mediaKeys,
        Instant occurredAt
) implements DomainEvent {
}
