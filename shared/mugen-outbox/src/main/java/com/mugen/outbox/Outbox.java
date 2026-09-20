package com.mugen.outbox;

import com.mugen.shared.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the events a service owes the rest of the system.
 * <p>
 * Nothing here talks to Kafka. It writes rows into {@code outbox_events}, inside the
 * caller's transaction; {@link OutboxPoller} does the publishing later. That split is
 * what makes the event and the database write atomic.
 */
@Slf4j
@RequiredArgsConstructor
public class Outbox {

    private final OutboxEventRepository outboxEvents;
    private final JsonMapper json;

    /**
     * Queues one event for a topic. The row id is the event's own {@code eventId}, so
     * a duplicate seen by a consumer traces back to exactly one row here.
     * <p>
     * {@code MANDATORY} is the safety rail: called without a transaction this throws
     * rather than quietly committing on its own, which would put the event and the
     * write it describes in separate transactions and reintroduce the dual write this
     * whole mechanism exists to remove.
     *
     * @param messageKey Kafka partition key — the aggregate the event is about, so
     *                   everything about one aggregate stays in order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String topic, String messageKey, DomainEvent event) {
        outboxEvents.save(OutboxEvent.pending(
                event.eventId(),
                topic,
                event.getClass().getSimpleName(),
                messageKey,
                json.writeValueAsString(event)));

        log.debug("Queued outbox event topic={} messageKey={} eventId={}", topic, messageKey, event.eventId());
    }
}
