package com.mugen.auth.service;

import com.mugen.auth.entity.OutboxEvent;
import com.mugen.auth.entity.User;
import com.mugen.auth.repository.OutboxEventRepository;
import com.mugen.shared.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the events mugen-auth owes the rest of the system.
 * <p>
 * Nothing here talks to Kafka. It writes rows into the outbox, inside the caller's
 * transaction; {@link OutboxPoller} does the publishing later. That split is what
 * makes the event and the database write atomic — see
 * {@code V4__create_outbox_events.sql}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    /** Consumers: mugen-user, mugen-search. */
    public static final String USER_REGISTERED_TOPIC = "mugen.user.registered";

    private final OutboxEventRepository outboxEvents;
    private final JsonMapper json;

    /**
     * Announces a new account, from either registration route — password
     * ({@link AuthService#register}) or a first SSO sign-in
     * ({@link OAuthService#linkOrCreate}). mugen-user creates the profile from it,
     * so an account that never emits this event has no profile, forever.
     * <p>
     * {@code MANDATORY} is the safety rail: called without a transaction this throws
     * rather than quietly committing on its own, which would put the event and the
     * users row in separate transactions and reintroduce the dual write this whole
     * mechanism exists to remove.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void userRegistered(User user) {
        UUID eventId = UUID.randomUUID();

        // The row id and the eventId are the same value — see OutboxEvent.
        UserRegisteredEvent event = new UserRegisteredEvent(
                eventId, user.getId(), user.getUsername(), user.getEmail(), Instant.now());

        outboxEvents.save(OutboxEvent.pending(
                eventId,
                USER_REGISTERED_TOPIC,
                UserRegisteredEvent.class.getSimpleName(),
                // Keyed by user, so everything about one account lands on one
                // partition and stays in order relative to itself.
                user.getId().toString(),
                json.writeValueAsString(event)));

        log.debug("Queued outbox event topic={} userId={} eventId={}",
                USER_REGISTERED_TOPIC, user.getId(), eventId);
    }
}
