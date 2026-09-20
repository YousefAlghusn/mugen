package com.mugen.user.service;

import com.mugen.outbox.Outbox;
import com.mugen.shared.event.UserFollowedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** The events mugen-user owes the rest of the system, as rows in the shared outbox. */
@Service
@RequiredArgsConstructor
public class FollowEventPublisher {

    /** Consumers: mugen-notification. */
    public static final String USER_FOLLOWED_TOPIC = "mugen.user.followed";

    private final Outbox outbox;

    /** Inside the follow's transaction — {@link Outbox#record} refuses to run outside one. */
    public void userFollowed(UUID followerId, UUID followeeId) {
        // Keyed by the followee: their notifications arrive in the order the follows happened.
        outbox.record(USER_FOLLOWED_TOPIC, followeeId.toString(),
                new UserFollowedEvent(UUID.randomUUID(), followerId, followeeId, Instant.now()));
    }
}
