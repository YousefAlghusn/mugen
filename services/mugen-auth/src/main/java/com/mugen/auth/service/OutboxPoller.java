package com.mugen.auth.service;

import com.mugen.auth.config.OutboxProperties;
import com.mugen.auth.entity.OutboxEvent;
import com.mugen.auth.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed outbox rows to Kafka.
 * <p>
 * Everything here is a retry away from correct: a row is only marked published once
 * the broker has acknowledged it, and one that is not marked is picked up again.
 * The consequence is at-least-once delivery — a send that succeeds and then fails to
 * commit is re-sent — which is why consumers deduplicate on {@code eventId}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mugen.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties outboxProperties;

    /**
     * Claims one batch, publishes it, and marks the results.
     * <p>
     * {@code fixedDelay}, not {@code fixedRate}: the gap is measured from the end of
     * the previous run, so a slow batch cannot have the next one start on top of it.
     * <p>
     * The transaction spans the Kafka round trip, which is normally a thing to avoid
     * — but the row locks it holds are contended only by other pollers, which
     * {@code READPAST} sends straight past, and never by the registration path, which
     * only inserts. The wait is bounded by {@code sendTimeout} for the same reason
     * {@link OAuthService#complete} is not transactional: an unbounded wait on someone
     * else's availability drains the connection pool.
     */
    @Scheduled(
            initialDelayString = "${mugen.outbox.poll-interval}",
            fixedDelayString = "${mugen.outbox.poll-interval}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEvents.claimBatch(outboxProperties.batchSize());
        if (batch.isEmpty()) {
            return;
        }

        // Sent first, waited on second. Sending the batch up front lets the producer
        // pipeline it into one round trip; a send-then-wait loop would pay the broker
        // latency once per event and hold the locks that much longer.
        List<CompletableFuture<?>> sends = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            sends.add(kafka.send(event.getTopic(), event.getMessageKey(), event.getPayloadJson()));
        }

        int published = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (awaitAcknowledgement(batch.get(i), sends.get(i))) {
                published++;
            }
        }

        // Marks are flushed by the commit; the locks are released with it.
        log.debug("Published {} of {} claimed outbox events", published, batch.size());
    }

    /**
     * Deletes rows the broker acknowledged long enough ago to be of no further
     * interest. Pending rows are never touched, whatever their age.
     */
    @Scheduled(
            initialDelayString = "${mugen.outbox.purge-interval}",
            fixedDelayString = "${mugen.outbox.purge-interval}")
    @Transactional
    public void purgePublished() {
        int deleted = outboxEvents.deletePublishedBefore(Instant.now().minus(outboxProperties.retention()));
        if (deleted > 0) {
            log.info("Purged {} published outbox events older than {}", deleted, outboxProperties.retention());
        }
    }

    /**
     * @return whether the broker acknowledged, the row having been marked either way
     */
    private boolean awaitAcknowledgement(OutboxEvent event, CompletableFuture<?> send) {
        try {
            send.get(outboxProperties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished();
            return true;

        } catch (InterruptedException ex) {
            // Shutdown. Restore the flag and leave the row pending — the next run,
            // in this instance or another, will claim it again.
            Thread.currentThread().interrupt();
            event.recordFailure("interrupted", outboxProperties.initialBackoff(), outboxProperties.maxBackoff());
            return false;

        } catch (Exception ex) {
            // Never rethrown: one unpublishable event must not abort the batch and
            // roll back the rows that did go out, or a single poison message would
            // wedge the whole outbox behind it.
            event.recordFailure(describe(ex), outboxProperties.initialBackoff(), outboxProperties.maxBackoff());

            if (event.getAttempts() >= outboxProperties.alertAfterAttempts()) {
                log.error("Outbox event is still failing after {} attempts eventId={} topic={} nextAttemptAt={}: {}",
                        event.getAttempts(), event.getId(), event.getTopic(), event.getNextAttemptAt(), describe(ex));
            } else {
                log.warn("Outbox send failed eventId={} topic={} attempt={}: {}",
                        event.getId(), event.getTopic(), event.getAttempts(), describe(ex));
            }
            return false;
        }
    }

    /** Cause first — a send failure's own message is usually just "failed to send". */
    private static String describe(Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
