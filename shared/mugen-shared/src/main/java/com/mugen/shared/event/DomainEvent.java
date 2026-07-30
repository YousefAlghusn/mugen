package com.mugen.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract every {@code mugen.*} Kafka payload satisfies.
 * <p>
 * The two accessors are the ones consumers need generically, regardless of topic:
 * <ul>
 *   <li>{@link #eventId()} — stable per event and never reused across retries, so a
 *       consumer can deduplicate on it. Kafka gives at-least-once delivery, so every
 *       consumer in this system must be idempotent, and this is what it keys on.</li>
 *   <li>{@link #occurredAt()} — when the fact happened in the producer, not when it
 *       was published or consumed. Lets late-arriving events be ordered correctly
 *       after a consumer catches up from lag.</li>
 * </ul>
 * Implementations are records annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)}:
 * a producer must be able to add a field without breaking consumers that have not been
 * redeployed yet. Removing or renaming a field is a breaking change and needs a new topic.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();
}
