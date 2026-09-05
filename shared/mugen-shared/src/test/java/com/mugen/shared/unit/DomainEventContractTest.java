package com.mugen.shared.unit;

import com.mugen.shared.event.DomainEvent;
import com.mugen.shared.event.PaymentCompletedEvent;
import com.mugen.shared.event.PostCreatedEvent;
import com.mugen.shared.event.PostLikedEvent;
import com.mugen.shared.event.UserFollowedEvent;
import com.mugen.shared.event.UserRegisteredEvent;
import com.mugen.shared.event.VideoProgressEvent;
import com.mugen.shared.event.VideoTranscodeJobEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These contracts cross a service boundary over Kafka, so the guarantees that
 * matter are the wire-level ones: every payload round-trips, and a consumer
 * running older code survives a producer that has added a field.
 */
class DomainEventContractTest {

    private static final Instant WHEN = Instant.parse("2026-07-31T10:15:30Z");

    /**
     * Jackson 3, unconfigured, because that is what a mugen service has: Boot 4's
     * default mapper. The previous Jackson 2 mapper here had to be told to write
     * ISO-8601 dates, which is a setting that no longer exists — so the test was
     * asserting a wire format arrived at differently from the one production
     * produces.
     */
    private final JsonMapper mapper = JsonMapper.builder().build();

    static Stream<DomainEvent> allEventTypes() {
        return Stream.of(
                new UserRegisteredEvent(UUID.randomUUID(), UUID.randomUUID(), "kaneki", "k@mugen.dev", WHEN),
                new UserFollowedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), WHEN),
                new PostCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "preview", List.of("mugen-media/a.png"), WHEN),
                new PostLikedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), WHEN),
                new VideoTranscodeJobEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "mugen-raw/v.mp4", WHEN),
                new VideoProgressEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "PROCESSING", 50, null, WHEN),
                new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("19.99"), "USD", WHEN)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEventTypes")
    @DisplayName("every event round-trips through JSON unchanged")
    void roundTripsThroughJson(DomainEvent event) throws Exception {
        String json = mapper.writeValueAsString(event);
        DomainEvent parsed = (DomainEvent) mapper.readValue(json, event.getClass());

        assertThat(parsed).isEqualTo(event);
        assertThat(parsed.eventId()).isEqualTo(event.eventId());
        assertThat(parsed.occurredAt()).isEqualTo(WHEN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEventTypes")
    @DisplayName("occurredAt serialises as an ISO-8601 string, not an epoch number")
    void occurredAtIsIso8601(DomainEvent event) throws Exception {
        assertThat(mapper.writeValueAsString(event)).contains("\"2026-07-31T10:15:30Z\"");
    }

    @Test
    @DisplayName("a consumer on older code tolerates a field the producer added")
    void unknownFieldsAreIgnored() throws Exception {
        // Kafka consumers are redeployed independently of producers, so a new
        // producer field must not deserialize into an exception on a consumer
        // that has not caught up yet.
        String jsonWithNewField = """
                {
                  "eventId": "0b7a9f3e-3b0e-4a1a-9d2c-6c1f9c3a5b21",
                  "userId": "1c8b0a4f-4c1f-4b2b-8e3d-7d2f0d4b6c32",
                  "username": "kaneki",
                  "email": "k@mugen.dev",
                  "occurredAt": "2026-07-31T10:15:30Z",
                  "displayName": "added by a newer producer"
                }
                """;

        UserRegisteredEvent parsed = mapper.readValue(jsonWithNewField, UserRegisteredEvent.class);

        assertThat(parsed.username()).isEqualTo("kaneki");
        assertThat(parsed.occurredAt()).isEqualTo(WHEN);
    }
}
