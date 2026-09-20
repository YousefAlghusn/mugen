package com.mugen.outbox.integration;

import com.mugen.outbox.Outbox;
import com.mugen.outbox.OutboxEvent;
import com.mugen.outbox.OutboxEventRepository;
import com.mugen.shared.event.UserFollowedEvent;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the outbox that only exists against a real database: the shipped schema,
 * the JSON check, and the claim query whose whole meaning is its lock. The unit tier
 * covers the decisions above that line.
 */
@IntegrationTest
@Transactional
class OutboxRepositoryTest {

    @Autowired private OutboxEventRepository outbox;
    @Autowired private Outbox writer;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private static OutboxEvent event(String payload) {
        return OutboxEvent.pending(
                UUID.randomUUID(), "mugen.user.followed", "UserFollowedEvent", UUID.randomUUID().toString(), payload);
    }

    /** Saved and backdated a minute, so it is due beyond any doubt about clocks. */
    private OutboxEvent saveDue(OutboxEvent event) {
        OutboxEvent saved = outbox.saveAndFlush(event);
        entityManager.createNativeQuery(
                        "UPDATE outbox_events SET next_attempt_at = next_attempt_at - interval '1 minute' WHERE id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.refresh(saved);
        return saved;
    }

    private List<OutboxEvent> claim(int batchSize) {
        return outbox.claimBatch(Instant.now(), Limit.of(batchSize));
    }

    @Test
    @DisplayName("the shipped migration applies and Hibernate validates the entity against it")
    void schemaMatchesEntity() {
        // Reaching here means Flyway ran and ddl-auto=validate agreed — both startup-time
        // guarantees, which is exactly why this assertion is trivial.
        assertThat(outbox.count()).isZero();
    }

    @Test
    @DisplayName("the JSON check refuses a payload that is not JSON")
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
     * rule Spring Data would take the merge branch, find the row and quietly UPDATE it;
     * taking the persist branch it hits the primary key instead.
     */
    @Test
    @DisplayName("saving a row whose id already exists inserts and fails, rather than merging")
    void savesByInsertNotMerge() {
        OutboxEvent first = outbox.saveAndFlush(event("{\"n\":1}"));
        entityManager.detach(first);

        assertThatThrownBy(() -> outbox.saveAndFlush(
                OutboxEvent.pending(first.getId(), "mugen.user.followed", "UserFollowedEvent", "k", "{\"n\":2}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("claimBatch returns rows that are due and unpublished")
    void claimsDueRows() {
        OutboxEvent due = saveDue(event("{\"n\":1}"));

        assertThat(claim(10)).extracting(OutboxEvent::getId).contains(due.getId());
    }

    @Test
    @DisplayName("claimBatch skips rows already published")
    void skipsPublishedRows() {
        OutboxEvent published = saveDue(event("{\"n\":1}"));
        published.markPublished();
        outbox.saveAndFlush(published);

        assertThat(claim(10)).extracting(OutboxEvent::getId).doesNotContain(published.getId());
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

        assertThat(claim(10)).extracting(OutboxEvent::getId).doesNotContain(failed.getId());
    }

    @Test
    @DisplayName("claimBatch honours the batch size and takes the oldest first")
    void claimsOldestFirstWithinBatchSize() {
        OutboxEvent first = saveDue(event("{\"n\":1}"));
        OutboxEvent second = saveDue(event("{\"n\":2}"));
        saveDue(event("{\"n\":3}"));

        List<OutboxEvent> claimed = claim(2);

        assertThat(claimed).extracting(OutboxEvent::getId).containsExactly(first.getId(), second.getId());
    }

    /**
     * The lock's whole point, observed from a second connection: a row one poller holds
     * is stepped over by another, not waited for. Without skip-locked the second claim
     * would block until this transaction ends — which it never does while the assertion
     * is pending — and the test would hang instead of fail.
     */
    @Test
    @DisplayName("a row claimed by one transaction is skipped by another, not waited for")
    // No test transaction: the row has to be committed for a second connection to see it
    // at all, and the two claims have to be real, separate transactions.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimedRowsAreSkippedByAnotherPoller() {
        TransactionTemplate fresh = new TransactionTemplate(transactionManager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID held = fresh.execute(status -> saveDue(event("{\"n\":1}")).getId());

        try {
            List<UUID> seenByOther = fresh.execute(first -> {
                assertThat(claim(10)).extracting(OutboxEvent::getId).contains(held);
                // Still inside the first claim, so its lock is held while the second looks.
                return fresh.execute(second -> claim(10).stream().map(OutboxEvent::getId).toList());
            });

            assertThat(seenByOther).doesNotContain(held);
        } finally {
            fresh.executeWithoutResult(status -> outbox.deleteById(held));
        }
    }

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

    @Test
    @DisplayName("the write API stores the event under its own id, keyed as asked")
    void recordsAnEventUnderItsOwnId() {
        UserFollowedEvent event = new UserFollowedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        writer.record("mugen.user.followed", event.followerId().toString(), event);
        entityManager.flush();

        OutboxEvent row = outbox.findById(event.eventId()).orElseThrow();
        assertThat(row.getMessageKey()).isEqualTo(event.followerId().toString());
        assertThat(row.getPayloadJson()).contains(event.eventId().toString());
    }

    /**
     * The guard that keeps the atomicity claim honest. Outside a transaction this must
     * fail loudly rather than open one of its own. {@code NOT_SUPPORTED} suspends this
     * test's own transaction, which is the only way to reach the writer from outside one.
     */
    @Test
    @DisplayName("recording outside a transaction is refused, not quietly committed")
    void refusesToRecordWithoutATransaction() {
        UserFollowedEvent event = new UserFollowedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        TransactionTemplate suspended = new TransactionTemplate(transactionManager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        assertThatThrownBy(() -> suspended.executeWithoutResult(status -> writer.record("t", "k", event)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
