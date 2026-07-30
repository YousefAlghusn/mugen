package com.mugen.shared.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.user.registered
 * Producer: mugen-auth
 * Consumers: mugen-user, mugen-search
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRegisteredEvent(
        UUID eventId,
        UUID userId,
        String username,
        String email,
        Instant occurredAt
) implements DomainEvent {
}
