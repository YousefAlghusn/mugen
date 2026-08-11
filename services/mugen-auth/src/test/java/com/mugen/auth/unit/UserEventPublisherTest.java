package com.mugen.auth.unit;

import com.mugen.auth.entity.OutboxEvent;
import com.mugen.auth.entity.User;
import com.mugen.auth.repository.OutboxEventRepository;
import com.mugen.auth.service.UserEventPublisher;
import com.mugen.shared.event.UserRegisteredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventPublisherTest {

    @Mock
    private OutboxEventRepository outbox;

    private final JsonMapper json = JsonMapper.builder().build();

    private UserEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new UserEventPublisher(outbox, json);
    }

    /** Hibernate assigns the id at insert; these tests never reach a database. */
    private static User persistedUser(String username, String email) {
        User user = User.withPassword(username, email, "{bcrypt}hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID(), UUID.class);
        return user;
    }

    private OutboxEvent capturedRow() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("registration queues one row on the mugen.user.registered topic")
    void queuesRegistrationEvent() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));

        publisher.userRegistered(persistedUser("kaneki", "kaneki@mugen.dev"));

        OutboxEvent row = capturedRow();
        assertThat(row.getTopic()).isEqualTo("mugen.user.registered");
        assertThat(row.getEventType()).isEqualTo("UserRegisteredEvent");
        assertThat(row.isPublished()).isFalse();
    }

    @Test
    @DisplayName("the row is keyed by userId, so one account's events share a partition")
    void keysByUserId() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));
        User user = persistedUser("touka", "touka@mugen.dev");

        publisher.userRegistered(user);

        assertThat(capturedRow().getMessageKey()).isEqualTo(user.getId().toString());
    }

    @Test
    @DisplayName("the payload carries the fields mugen-user needs to build a profile")
    void payloadCarriesTheContract() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));
        User user = persistedUser("rize", "rize@mugen.dev");

        publisher.userRegistered(user);

        UserRegisteredEvent published =
                json.readValue(capturedRow().getPayloadJson(), UserRegisteredEvent.class);

        assertThat(published.userId()).isEqualTo(user.getId());
        assertThat(published.username()).isEqualTo("rize");
        assertThat(published.email()).isEqualTo("rize@mugen.dev");
        assertThat(published.occurredAt()).isNotNull();
    }

    /**
     * Consumers deduplicate on eventId, and operators trace a duplicate back to a
     * row by id. Both break if the two are not the same value.
     */
    @Test
    @DisplayName("the row id and the eventId inside the payload are the same value")
    void rowIdMatchesEventId() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));

        publisher.userRegistered(persistedUser("nishiki", "nishiki@mugen.dev"));

        OutboxEvent row = capturedRow();
        UserRegisteredEvent published = json.readValue(row.getPayloadJson(), UserRegisteredEvent.class);
        assertThat(published.eventId()).isEqualTo(row.getId());
    }

    @Test
    @DisplayName("no password material reaches the payload")
    void payloadCarriesNoCredentials() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));

        publisher.userRegistered(persistedUser("yomo", "yomo@mugen.dev"));

        assertThat(capturedRow().getPayloadJson())
                .doesNotContain("bcrypt")
                .doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("two registrations get distinct event ids")
    void eventIdsAreUnique() {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        publisher.userRegistered(persistedUser("a", "a@mugen.dev"));
        publisher.userRegistered(persistedUser("b", "b@mugen.dev"));

        verify(outbox, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getId())
                .isNotEqualTo(captor.getAllValues().get(1).getId());
    }
}
