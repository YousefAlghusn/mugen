package com.mugen.auth.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.outbox.*}. The poll and purge intervals are deliberately absent:
 * {@code @Scheduled} reads those from the environment, and binding them here too would
 * make two sources for one value.
 *
 * @param batchSize          rows claimed per tick; raise before shortening the tick
 * @param sendTimeout        bounded because the wait happens inside a transaction
 *                           holding row locks
 * @param initialBackoff     delay before the first retry, doubling from there
 * @param maxBackoff         ceiling, so an outage does not push a live event days out
 * @param alertAfterAttempts where failures log at ERROR rather than WARN. Not a
 *                           give-up threshold — nothing is ever discarded
 * @param retention          how long a published row is kept, so "was this delivered?"
 *                           stays answerable
 */
@Validated
@ConfigurationProperties(prefix = "mugen.outbox")
public record OutboxProperties(

        @Positive int batchSize,
        @NotNull Duration sendTimeout,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @Positive int alertAfterAttempts,
        @NotNull Duration retention
) {

    public OutboxProperties {
        if (initialBackoff != null && maxBackoff != null && maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "mugen.outbox.max-backoff (%s) must be >= initial-backoff (%s)"
                            .formatted(maxBackoff, initialBackoff));
        }
    }
}
