package com.mugen.auth.unit;

import com.mugen.auth.entity.User;
import com.mugen.auth.service.UserEventPublisher;
import com.mugen.outbox.Outbox;
import com.mugen.shared.event.DomainEvent;
import com.mugen.shared.event.UserRegisteredEvent;
import com.mugen.test.UnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** What mugen-auth puts on the wire; the outbox mechanics are the shared module's to test. */
@UnitTest
class UserEventPublisherTest {

    @Mock
    private Outbox outbox;

    /** Hibernate assigns the id at insert; these tests never reach a database. */
    private static User persistedUser(String username, String email) {
        User user = User.withPassword(username, email, "{bcrypt}hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID(), UUID.class);
        return user;
    }

    private UserRegisteredEvent recorded(User user) {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outbox).record(eq(UserEventPublisher.USER_REGISTERED_TOPIC), eq(user.getId().toString()), captor.capture());
        return (UserRegisteredEvent) captor.getValue();
    }

    @Test
    @DisplayName("registration is recorded on mugen.user.registered, keyed by the user")
    void queuesRegistrationEventKeyedByUser() {
        User user = persistedUser("kaneki", "kaneki@mugen.dev");

        new UserEventPublisher(outbox).userRegistered(user);

        assertThat(recorded(user).userId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("the payload carries the fields mugen-user needs to build a profile")
    void payloadCarriesTheContract() {
        User user = persistedUser("rize", "rize@mugen.dev");

        new UserEventPublisher(outbox).userRegistered(user);

        UserRegisteredEvent event = recorded(user);
        assertThat(event.username()).isEqualTo("rize");
        assertThat(event.email()).isEqualTo("rize@mugen.dev");
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("two registrations get distinct event ids")
    void eventIdsAreUnique() {
        UserEventPublisher publisher = new UserEventPublisher(outbox);
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);

        publisher.userRegistered(persistedUser("a", "a@mugen.dev"));
        publisher.userRegistered(persistedUser("b", "b@mugen.dev"));

        verify(outbox, org.mockito.Mockito.times(2)).record(any(), any(), captor.capture());
        assertThat(captor.getAllValues().get(0).eventId()).isNotEqualTo(captor.getAllValues().get(1).eventId());
    }
}
