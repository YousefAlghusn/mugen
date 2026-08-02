package com.mugen.auth.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.outbox.*}.
 * <p>
 * The poll and purge intervals are absent here on purpose: {@code @Scheduled} reads
 * them from the environment directly, so binding them again would create two
 * sources for one value.
 *
 * @param batchSize          rows claimed per tick. Throughput ceiling is this over
 *                           the poll interval; raise it before shortening the tick.
 * @param sendTimeout        how long to wait for the broker's acknowledgement. Bounded
 *                           because the wait happens inside a transaction holding row
 *                           locks — an unbounded one would pin a connection until the
 *                           pool ran dry.
 * @param initialBackoff     delay before the first retry, doubling from there.
 * @param maxBackoff         backoff ceiling, so a long outage does not push the retry
 *                           of a live event days out.
 * @param alertAfterAttempts attempts after which failures log at ERROR instead of WARN.
 *                           Not a give-up threshold — nothing is ever discarded — just
 *                           where "transient" stops being a fair description.
 * @param retention          how long a published row is kept before the purge sweep
 *                           removes it. Long enough to answer "was this delivered?".
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
