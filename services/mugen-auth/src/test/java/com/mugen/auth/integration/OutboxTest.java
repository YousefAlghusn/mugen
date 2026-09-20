package com.mugen.auth.integration;

import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.entity.User;
import com.mugen.auth.repository.UserRepository;
import com.mugen.auth.service.AuthService;
import com.mugen.auth.service.UserEventPublisher;
import com.mugen.outbox.OutboxEvent;
import com.mugen.outbox.OutboxEventRepository;
import com.mugen.shared.event.UserRegisteredEvent;
import com.mugen.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is mugen-auth's own about the shared outbox: the V4 schema on SQL Server with
 * its {@code ISJSON} constraint, that the shared claim query's lock renders on this
 * database at all, and the wiring from registration into it. The mechanics are tested
 * in mugen-outbox, against Postgres.
 */
@IntegrationTest
@Transactional
class OutboxTest {

    @Autowired private OutboxEventRepository outbox;
    @Autowired private UserRepository users;
    @Autowired private AuthService authService;
    @Autowired private UserEventPublisher publisher;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static OutboxEvent event(String payload) {
        return OutboxEvent.pending(
                UUID.randomUUID(), "mugen.user.registered", "UserRegisteredEvent",
                UUID.randomUUID().toString(), payload);
    }

    @Test
    @DisplayName("V4 applies and Hibernate validates outbox_events against the shared entity")
    void schemaMatchesEntity() {
        // Reaching here means Flyway ran V4 and ddl-auto=validate agreed. Both are
        // startup-time guarantees, which is exactly why this assertion is trivial.
        assertThat(outbox.count()).isZero();
    }

    @Test
    @DisplayName("the ISJSON constraint refuses a payload that is not JSON")
    void rejectsNonJsonPayload() {
        assertThatThrownBy(() -> outbox.saveAndFlush(event("this is not json")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an empty payload is refused too — the case a silent truncation produces")
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> outbox.saveAndFlush(event("")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The one thing the shared module cannot prove for this service: that Hibernate
     * renders {@code PESSIMISTIC_WRITE} plus skip-locked as SQL Server hints and the
     * query runs. A dialect that could not would fail here, not in production.
     */
    @Test
    @DisplayName("the shared claim query runs against SQL Server")
    void claimQueryRendersOnSqlServer() {
        OutboxEvent saved = outbox.saveAndFlush(event("{\"n\":1}"));
        entityManager.createNativeQuery(
                        "UPDATE outbox_events SET next_attempt_at = DATEADD(minute, -1, next_attempt_at) WHERE id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.refresh(saved);

        List<OutboxEvent> claimed = outbox.claimBatch(Instant.now(), Limit.of(10));

        assertThat(claimed).extracting(OutboxEvent::getId).contains(saved.getId());
    }

    @Test
    @DisplayName("registering a user queues exactly one event, carrying that user's id")
    void registrationQueuesItsEvent() {
        authService.register("kaneki", "kaneki@mugen.dev", "correct-horse-battery",
                new RequestContext("JUnit", "127.0.0.1"));

        User created = users.findByEmail("kaneki@mugen.dev").orElseThrow();
        List<OutboxEvent> queued = outbox.findAll();

        assertThat(queued).hasSize(1);
        UserRegisteredEvent published =
                json.readValue(queued.getFirst().getPayloadJson(), UserRegisteredEvent.class);
        assertThat(published.userId()).isEqualTo(created.getId());
        assertThat(published.username()).isEqualTo("kaneki");
        assertThat(queued.getFirst().getMessageKey()).isEqualTo(created.getId().toString());
        assertThat(queued.getFirst().getId()).isEqualTo(published.eventId());
    }

    /**
     * The guard that keeps the atomicity claim honest, reached through this service's
     * own publisher. {@code NOT_SUPPORTED} suspends the test's transaction, which is the
     * only way to call it from outside one here.
     */
    @Test
    @DisplayName("publishing outside a transaction is refused, not quietly committed")
    void refusesToPublishWithoutATransaction() {
        User user = users.saveAndFlush(User.withPassword("touka", "touka@mugen.dev", "{bcrypt}hash"));

        TransactionTemplate suspended = new TransactionTemplate(transactionManager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        assertThatThrownBy(() -> suspended.executeWithoutResult(status -> publisher.userRegistered(user)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
