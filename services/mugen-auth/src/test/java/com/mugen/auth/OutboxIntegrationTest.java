package com.mugen.auth;

import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.entity.OutboxEvent;
import com.mugen.auth.entity.User;
import com.mugen.auth.repository.OutboxEventRepository;
import com.mugen.auth.repository.UserRepository;
import com.mugen.auth.service.AuthService;
import com.mugen.auth.service.UserEventPublisher;
import com.mugen.shared.event.UserRegisteredEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the outbox that only exists against a real SQL Server: the V4 schema,
 * the {@code ISJSON} constraint, and the native claim query whose whole meaning is
 * its {@code UPDLOCK, READPAST, ROWLOCK} hints. {@link
 * com.mugen.auth.service.OutboxPollerTest} covers the decisions above that line.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "management.tracing.enabled=false",
        // The poller is exercised by unit tests; leaving it running here would race
        // this test for the very rows it is asserting on.
        "mugen.outbox.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Testcontainers
@Transactional
class OutboxIntegrationTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle.
    static final MSSQLServerContainer<?> SQL_SERVER =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @TestConfiguration
    static class DataSourceOverride {
        @Bean
        DynamicPropertyRegistrar sqlServerProperties() {
            return registry -> {
                registry.add("spring.datasource.url", SQL_SERVER::getJdbcUrl);
                registry.add("spring.datasource.username", SQL_SERVER::getUsername);
                registry.add("spring.datasource.password", SQL_SERVER::getPassword);
            };
        }
    }

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserEventPublisher publisher;

    @Autowired
    private JsonMapper json;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private static OutboxEvent event(String payload) {
        return OutboxEvent.pending(
                UUID.randomUUID(), "mugen.user.registered", "UserRegisteredEvent",
                UUID.randomUUID().toString(), payload);
    }

    /**
     * Saves a row and backdates it so it is unambiguously due.
     * <p>
     * {@code claimBatch} compares against the database clock while
     * {@code OutboxEvent} stamps itself from the JVM's. Those are different clocks
     * here — the server is in a container — and a row stamped a few milliseconds into
     * the server's future would not be claimed. A minute is far more skew than that
     * gap can plausibly hold.
     */
    private OutboxEvent saveDue(OutboxEvent event) {
        OutboxEvent saved = outbox.saveAndFlush(event);
        entityManager.createNativeQuery(
                        "UPDATE outbox_events SET next_attempt_at = DATEADD(minute, -1, next_attempt_at) WHERE id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.refresh(saved);
        return saved;
    }

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V4 applies and Hibernate validates outbox_events against the entity")
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
     * Proves {@code Persistable.isNew()} is doing its job. Under the default id-is-null
     * rule Spring Data would take the {@code merge} branch, find the row and quietly
     * UPDATE it; taking the {@code persist} branch it hits the primary key instead.
     */
    @Test
    @DisplayName("saving a row whose id already exists inserts and fails, rather than merging")
    void savesByInsertNotMerge() {
        OutboxEvent first = outbox.saveAndFlush(event("{\"n\":1}"));
        entityManager.detach(first);

        assertThatThrownBy(() -> outbox.saveAndFlush(
                OutboxEvent.pending(first.getId(), "mugen.user.registered", "UserRegisteredEvent", "k", "{\"n\":2}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // The claim query
    // ------------------------------------------------------------------

    @Test
    @DisplayName("claimBatch returns rows that are due and unpublished")
    void claimsDueRows() {
        OutboxEvent due = saveDue(event("{\"n\":1}"));

        List<OutboxEvent> claimed = outbox.claimBatch(10);

        assertThat(claimed).extracting(OutboxEvent::getId).contains(due.getId());
    }

    @Test
    @DisplayName("claimBatch skips rows already published")
    void skipsPublishedRows() {
        OutboxEvent published = saveDue(event("{\"n\":1}"));
        published.markPublished();
        outbox.saveAndFlush(published);

        assertThat(outbox.claimBatch(10)).extracting(OutboxEvent::getId)
                .doesNotContain(published.getId());
    }

    /**
     * The backoff has to actually hold rows back at the database, not merely be
     * recorded on them — otherwise a failing event is re-sent on the very next tick.
     */
    @Test
    @DisplayName("claimBatch skips a row whose backoff has not elapsed")
    void respectsBackoff() {
        OutboxEvent failed = saveDue(event("{\"n\":1}"));
        failed.recordFailure("broker down", Duration.ofMinutes(30), Duration.ofHours(1));
        outbox.saveAndFlush(failed);

        assertThat(outbox.claimBatch(10)).extracting(OutboxEvent::getId)
                .doesNotContain(failed.getId());
    }

    @Test
    @DisplayName("claimBatch honours the batch size and takes the oldest first")
    void claimsOldestFirstWithinBatchSize() {
        OutboxEvent first = saveDue(event("{\"n\":1}"));
        OutboxEvent second = saveDue(event("{\"n\":2}"));
        saveDue(event("{\"n\":3}"));

        List<OutboxEvent> claimed = outbox.claimBatch(2);

        assertThat(claimed).hasSize(2);
        assertThat(claimed).extracting(OutboxEvent::getId)
                .containsExactly(first.getId(), second.getId());
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the purge deletes old published rows and spares everything pending")
    void purgeSparesPendingRows() {
        OutboxEvent pending = saveDue(event("{\"n\":1}"));
        OutboxEvent published = outbox.saveAndFlush(event("{\"n\":2}"));
        published.markPublished();
        outbox.saveAndFlush(published);

        int deleted = outbox.deletePublishedBefore(Instant.now().plusSeconds(60));

        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.findById(pending.getId())).isPresent();
        assertThat(outbox.findById(published.getId())).isEmpty();
    }

    @Test
    @DisplayName("the backlog count sees only unpublished rows")
    void countsOnlyPending() {
        saveDue(event("{\"n\":1}"));
        OutboxEvent published = outbox.saveAndFlush(event("{\"n\":2}"));
        published.markPublished();
        outbox.saveAndFlush(published);

        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Wiring into registration
    // ------------------------------------------------------------------

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
    }

    /**
     * The guard that keeps the atomicity claim honest. Outside a transaction this
     * must fail loudly rather than open one of its own — a self-committing publisher
     * would put the event and the users row in separate transactions and reinstate
     * exactly the dual write the outbox exists to remove.
     * <p>
     * {@code NOT_SUPPORTED} suspends this test's own transaction, which is the only
     * way to reach the publisher from outside one here.
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
