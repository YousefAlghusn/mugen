package com.mugen.auth.service;

import com.mugen.auth.config.OutboxProperties;
import com.mugen.auth.entity.OutboxEvent;
import com.mugen.auth.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The poller's decisions, with the broker and the database mocked out: what gets
 * marked published, what gets rescheduled, and what a single bad event does to the
 * rest of its batch. The SQL that claims the rows is proven separately, in
 * {@link com.mugen.auth.OutboxIntegrationTest}, because table hints only mean
 * something against a real SQL Server.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    private static final String TOPIC = "mugen.user.registered";

    private static final OutboxProperties PROPERTIES = new OutboxProperties(
            100,
            Duration.ofSeconds(10),
            Duration.ofSeconds(2),
            Duration.ofMinutes(5),
            3,
            Duration.ofDays(7));

    @Mock
    private OutboxEventRepository outbox;

    @Mock
    private KafkaTemplate<String, String> kafka;

    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new OutboxPoller(outbox, kafka, PROPERTIES);
    }

    private static OutboxEvent event(String payload) {
        return OutboxEvent.pending(
                UUID.randomUUID(), TOPIC, "UserRegisteredEvent", UUID.randomUUID().toString(), payload);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture((SendResult<String, String>) org.mockito.Mockito.mock(SendResult.class));
    }

    private static CompletableFuture<SendResult<String, String>> rejected(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    @Test
    @DisplayName("an empty outbox does not touch the broker")
    void emptyBatchDoesNothing() {
        when(outbox.claimBatch(100)).thenReturn(List.of());

        poller.publishPending();

        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("an acknowledged event is marked published and never re-sent")
    void marksAcknowledgedEventsPublished() {
        OutboxEvent event = event("{\"a\":1}");
        when(outbox.claimBatch(100)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledged());

        poller.publishPending();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    @DisplayName("the payload is published byte-for-byte under the row's own key")
    void publishesStoredPayloadVerbatim() {
        OutboxEvent event = event("{\"eventId\":\"abc\",\"username\":\"kaneki\"}");
        when(outbox.claimBatch(100)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledged());

        poller.publishPending();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(TOPIC), eq(event.getMessageKey()), payload.capture());
        assertThat(payload.getValue()).isEqualTo("{\"eventId\":\"abc\",\"username\":\"kaneki\"}");
    }

    @Test
    @DisplayName("a rejected send leaves the event pending, with the reason recorded")
    void reschedulesRejectedSends() {
        OutboxEvent event = event("{\"a\":1}");
        when(outbox.claimBatch(100)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(rejected("broker unreachable"));

        poller.publishPending();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unreachable");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
    }

    /**
     * The important one. A batch is one transaction, so letting a send failure
     * propagate would roll back the rows that succeeded and re-send them next tick —
     * one undeliverable event would wedge the outbox behind it indefinitely.
     */
    @Test
    @DisplayName("one poison event does not hold up the rest of its batch")
    void oneFailureDoesNotBlockTheBatch() {
        OutboxEvent first = event("{\"n\":1}");
        OutboxEvent poison = event("{\"n\":2}");
        OutboxEvent third = event("{\"n\":3}");
        when(outbox.claimBatch(100)).thenReturn(List.of(first, poison, third));
        when(kafka.send(anyString(), eq(first.getMessageKey()), anyString())).thenReturn(acknowledged());
        when(kafka.send(anyString(), eq(poison.getMessageKey()), anyString())).thenReturn(rejected("nope"));
        when(kafka.send(anyString(), eq(third.getMessageKey()), anyString())).thenReturn(acknowledged());

        poller.publishPending();

        assertThat(first.isPublished()).isTrue();
        assertThat(third.isPublished()).isTrue();
        assertThat(poison.isPublished()).isFalse();
        assertThat(poison.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("the whole batch is handed to the producer before any acknowledgement is awaited")
    void sendsBatchBeforeWaiting() {
        List<OutboxEvent> batch = List.of(event("{\"n\":1}"), event("{\"n\":2}"), event("{\"n\":3}"));
        when(outbox.claimBatch(100)).thenReturn(batch);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledged());

        poller.publishPending();

        // Three sends, one wait each — not three round trips serialised end to end.
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
        assertThat(batch).allMatch(OutboxEvent::isPublished);
    }

    @Test
    @DisplayName("the purge sweep deletes published rows past the retention window")
    void purgesOldPublishedRows() {
        when(outbox.deletePublishedBefore(any())).thenReturn(4);

        poller.purgePublished();

        ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).deletePublishedBefore(before.capture());
        assertThat(before.getValue()).isBefore(Instant.now().minus(Duration.ofDays(6)));
    }

    @Test
    @DisplayName("publishing never deletes — the purge sweep is the only thing that does")
    void publishingDoesNotDelete() {
        OutboxEvent event = event("{\"a\":1}");
        when(outbox.claimBatch(100)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledged());

        poller.publishPending();

        verify(outbox, never()).deletePublishedBefore(any());
        verify(outbox, never()).delete(any());
    }
}
