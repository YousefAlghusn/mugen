package com.mugen.user.integration;

import com.mugen.shared.event.UserRegisteredEvent;
import com.mugen.test.IntegrationTest;
import com.mugen.user.kafka.UserRegisteredConsumer;
import com.mugen.user.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * At-least-once delivery means the same event arrives twice on a bad day. The
 * primary key is what makes that harmless, so the test needs the real one.
 */
@IntegrationTest
class UserRegisteredConsumerTest {

    @Autowired private UserRegisteredConsumer consumer;
    @Autowired private UserProfileRepository profiles;
    @Autowired private JsonMapper json;

    private static UserRegisteredEvent registration(String username) {
        return new UserRegisteredEvent(UUID.randomUUID(), UUID.randomUUID(), username, username + "@mugen.dev", Instant.now());
    }

    @Test
    void createsTheProfileWithTheAccountIdAndUsername() {
        UserRegisteredEvent event = registration("kaneki");

        consumer.handle(event);

        assertThat(profiles.findById(event.userId()))
                .hasValueSatisfying(profile -> {
                    assertThat(profile.getUsername()).isEqualTo("kaneki");
                    assertThat(profile.getDisplayName()).isEqualTo("kaneki");
                });
    }

    @Test
    void aReplayedEventChangesNothing() {
        UserRegisteredEvent event = registration("touka");
        consumer.handle(event);
        long before = profiles.count();

        assertThatCode(() -> consumer.handle(event)).doesNotThrowAnyException();

        assertThat(profiles.count()).isEqualTo(before);
    }

    /** The listener binds the producer's string by record name, with no type header to trust. */
    @Test
    void bindsTheWirePayloadWithoutATypeHeader() {
        UserRegisteredEvent event = registration("rize");
        String wire = json.writeValueAsString(event);

        consumer.onUserRegistered(wire);

        assertThat(profiles.findById(event.userId())).isPresent();
    }

    /** The email is in the event because mugen-search wants it; it is not this service's to keep. */
    @Test
    void keepsNoEmail() {
        UserRegisteredEvent event = registration("yomo");

        consumer.handle(event);

        assertThat(json.writeValueAsString(profiles.findById(event.userId()).orElseThrow()))
                .doesNotContain("@mugen.dev");
    }
}
