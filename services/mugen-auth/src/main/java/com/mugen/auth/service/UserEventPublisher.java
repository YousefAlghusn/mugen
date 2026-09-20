package com.mugen.auth.service;

import com.mugen.auth.entity.User;
import com.mugen.outbox.Outbox;
import com.mugen.shared.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The events mugen-auth owes the rest of the system, as rows in the shared outbox.
 * <p>
 * Nothing here talks to Kafka: {@link Outbox} writes the row inside the caller's
 * transaction and refuses to run without one, and the poller publishes later. See
 * {@code V4__create_outbox_events.sql}.
 */
@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    /** Consumers: mugen-user, mugen-search. */
    public static final String USER_REGISTERED_TOPIC = "mugen.user.registered";

    private final Outbox outbox;

    /**
     * Announces a new account, from either registration route — password
     * ({@link AuthService#register}) or a first SSO sign-in
     * ({@link OAuthService#linkOrCreate}). mugen-user creates the profile from it,
     * so an account that never emits this event has no profile, forever.
     */
    public void userRegistered(User user) {
        UserRegisteredEvent event = new UserRegisteredEvent(
                UUID.randomUUID(), user.getId(), user.getUsername(), user.getEmail(), Instant.now());

        // Keyed by user, so everything about one account lands on one partition and
        // stays in order relative to itself.
        outbox.record(USER_REGISTERED_TOPIC, user.getId().toString(), event);
    }
}
