package com.mugen.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract every {@code mugen.*} Kafka payload satisfies. {@link #eventId()} is stable
 * across retries, which is what consumers deduplicate on under at-least-once delivery;
 * {@link #occurredAt()} is when the fact happened in the producer, so events arriving
 * late after lag can still be ordered.
 * <p>
 * Implementations ignore unknown properties, so a producer can add a field without
 * breaking consumers that have not been redeployed. Removing or renaming one is a
 * breaking change and needs a new topic.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();
}
