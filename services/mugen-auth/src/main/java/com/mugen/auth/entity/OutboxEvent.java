package com.mugen.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One Kafka message owed to the broker, so "the user was created" and "the world was
 * told" commit together. Publishing happens afterwards and may fail freely.
 * <p>
 * Not a {@link BaseEntity}: this is a queue row whose id is assigned by the caller and
 * is also the payload's {@code eventId} — one identifier through to the consumer that
 * deduplicates on it.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA.
public class OutboxEvent implements Persistable<UUID> {

    /** Longest error text kept; the column is NVARCHAR(1000). */
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 200, updatable = false)
    private String topic;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "message_key", nullable = false, length = 100, updatable = false)
    private String messageKey;

    @Column(name = "payload_json", nullable = false, updatable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the broker has acknowledged the send. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    /**
     * Tells Spring Data this row is new even though its id is not null — {@code save()}
     * otherwise takes the merge branch and pays a SELECT that always misses. The
     * callback below is not optional: a stale {@code true} would retry the INSERT.
     */
    @Transient
    private boolean isNew = true;

    private OutboxEvent(UUID eventId, String topic, String eventType, String messageKey, String payloadJson) {
        this.id = eventId;
        this.topic = topic;
        this.eventType = eventType;
        this.messageKey = messageKey;
        this.payloadJson = payloadJson;
        this.createdAt = Instant.now();
        // Due immediately. The poller picks it up on its next tick.
        this.nextAttemptAt = this.createdAt;
        this.attempts = 0;
    }

    /**
     * Records a message as owed. Must run inside the transaction performing the write
     * it describes — {@link com.mugen.auth.service.UserEventPublisher} enforces it.
     *
     * @param eventId    also the {@code eventId} inside {@code payloadJson}
     * @param messageKey Kafka partition key, so events about one aggregate stay ordered
     */
    public static OutboxEvent pending(UUID eventId,
                                      String topic,
                                      String eventType,
                                      String messageKey,
                                      String payloadJson) {
        return new OutboxEvent(eventId, topic, eventType, messageKey, payloadJson);
    }

    /** The broker acknowledged it. Terminal — a published row is never re-sent. */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records a failed send and schedules the retry.
     * <p>
     * Backoff is exponential and capped: a tight retry loop against a broker that is
     * down turns one outage into two, and the row is durable, so waiting costs little.
     */
    public void recordFailure(String error, Duration initialBackoff, Duration maxBackoff) {
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = Instant.now().plus(backoffFor(attempts, initialBackoff, maxBackoff));
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * {@code initialBackoff * 2^(attempts-1)}, capped at {@code maxBackoff}.
     * <p>
     * Compares by shifting the cap down rather than the base up: doubling eventually
     * overflows a long and wraps negative, scheduling the retry in the past and turning
     * a capped backoff into a hot loop against a broker that is already sick.
     */
    public static Duration backoffFor(int attempts, Duration initialBackoff, Duration maxBackoff) {
        int shift = Math.max(attempts - 1, 0);
        if (shift >= Long.SIZE - 1) {
            return maxBackoff;
        }

        long baseMillis = initialBackoff.toMillis();
        long capMillis = maxBackoff.toMillis();
        // Equivalent to (baseMillis << shift) > capMillis, without the overflow.
        if (baseMillis > (capMillis >> shift)) {
            return maxBackoff;
        }
        return Duration.ofMillis(baseMillis << shift);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}