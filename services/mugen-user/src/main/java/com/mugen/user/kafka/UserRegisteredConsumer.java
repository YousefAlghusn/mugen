package com.mugen.user.kafka;

import com.mugen.shared.event.UserRegisteredEvent;
import com.mugen.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates a profile for every account mugen-auth announces.
 * <p>
 * The payload arrives as the string the producer wrote and is bound here to the shared
 * record — by name, never by a type header, so mugen-auth's package layout is not part
 * of the wire contract. Delivery is at-least-once, so the handler is idempotent: a
 * replay finds the profile and does nothing, and the one race that slips past the
 * check loses on the primary key and is treated the same way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegisteredConsumer {

    public static final String TOPIC = "mugen.user.registered";

    private final UserService userService;
    private final JsonMapper json;

    @KafkaListener(topics = TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void onUserRegistered(String payload) {
        UserRegisteredEvent event = json.readValue(payload, UserRegisteredEvent.class);
        handle(event);
    }

    /** Separated from the listener so the decision can be exercised without a broker. */
    public void handle(UserRegisteredEvent event) {
        try {
            boolean created = userService.createFromRegistration(event.userId(), event.username());
            if (!created) {
                log.debug("Registration event already applied userId={} eventId={}", event.userId(), event.eventId());
            }
        } catch (DataIntegrityViolationException raced) {
            // A concurrent consumer inserted first. The row exists, which is all this event asks for.
            log.debug("Profile created concurrently userId={} eventId={}", event.userId(), event.eventId());
        }
    }
}
