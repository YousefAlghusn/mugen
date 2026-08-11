package com.mugen.auth.unit;

import com.mugen.auth.entity.OutboxEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    private static final Duration INITIAL = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofMinutes(5);

    private static OutboxEvent pending() {
        return OutboxEvent.pending(
                UUID.randomUUID(), "mugen.user.registered", "UserRegisteredEvent",
                UUID.randomUUID().toString(), "{\"eventId\":\"x\"}");
    }

    @Test
    @DisplayName("a new event is unpublished and due immediately")
    void startsDue() {
        OutboxEvent event = pending();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
        assertThat(event.isNew()).isTrue();
    }

    @Test
    @DisplayName("publishing clears the last error so a recovered row reads clean")
    void publishingClearsError() {
        OutboxEvent event = pending();
        event.recordFailure("broker down", INITIAL, MAX);

        event.markPublished();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    @DisplayName("a failure counts an attempt and pushes the retry into the future")
    void failureSchedulesRetry() {
        OutboxEvent event = pending();

        event.recordFailure("TimeoutException: broker down", INITIAL, MAX);

        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("TimeoutException: broker down");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(event.isPublished()).isFalse();
    }

    @Test
    @DisplayName("an oversized error is truncated to fit the column")
    void truncatesLongErrors() {
        OutboxEvent event = pending();

        event.recordFailure("x".repeat(5_000), INITIAL, MAX);

        assertThat(event.getLastError()).hasSize(1_000);
    }

    @ParameterizedTest(name = "attempt {0} backs off {1}s")
    @CsvSource({"1, 2", "2, 4", "3, 8", "4, 16", "8, 256"})
    @DisplayName("backoff doubles per attempt")
    void backoffDoubles(int attempts, long expectedSeconds) {
        assertThat(OutboxEvent.backoffFor(attempts, INITIAL, MAX))
                .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    @DisplayName("backoff stops at the cap rather than growing forever")
    void backoffIsCapped() {
        assertThat(OutboxEvent.backoffFor(20, INITIAL, MAX)).isEqualTo(MAX);
    }

    /**
     * The regression that matters: doubling overflows a long around attempt 63, and
     * a negative backoff schedules the retry in the past — turning the cap into a
     * hot retry loop against a broker that is already struggling.
     */
    @ParameterizedTest
    @CsvSource({"62", "63", "64", "100", "2147483647"})
    @DisplayName("an attempt count large enough to overflow still yields the cap")
    void backoffNeverOverflows(int attempts) {
        assertThat(OutboxEvent.backoffFor(attempts, INITIAL, MAX)).isEqualTo(MAX);
    }
}
