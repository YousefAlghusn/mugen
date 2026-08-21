package com.mugen.shared.unit;

import com.mugen.shared.trace.TraceIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdHolderTest {

    @AfterEach
    void tearDown() {
        TraceIdHolder.clear();
    }

    @Test
    @DisplayName("get() returns null when no span is in scope")
    void getReturnsNullWhenUnset() {
        assertThat(TraceIdHolder.get()).isNull();
    }

    @Test
    @DisplayName("resolve() reports the id the tracer already set")
    void resolveDefersToExistingId() {
        // Simulates Micrometer Tracing having populated the MDC from a
        // traceparent header. Minting a second id here would decouple the
        // log's trace id from the one Jaeger recorded.
        String fromTracer = "4bf92f3577b34da6a3ce929d0e0e4736";
        MDC.put(TraceIdHolder.TRACE_ID_KEY, fromTracer);

        assertThat(TraceIdHolder.resolve()).isEqualTo(fromTracer);
    }

    @Test
    @DisplayName("resolve() falls back to the W3C trace-id shape when nothing is tracing")
    void resolveFallbackIsW3CShaped() {
        // 32 lower-case hex chars, per W3C Trace Context — not a dashed UUID,
        // so consumers never have to handle two formats.
        assertThat(TraceIdHolder.resolve()).matches("[0-9a-f]{32}");
    }

    /**
     * The reason this is a read and not a {@code getOrCreate}: request threads are
     * pooled, so an id written here outlives its response and the next request on
     * that thread reads someone else's trace as its own.
     */
    @Test
    @DisplayName("resolve() never writes to the MDC it did not own")
    void resolveLeavesNothingBehind() {
        TraceIdHolder.resolve();

        assertThat(MDC.get(TraceIdHolder.TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("clear() removes the id so a pooled thread cannot leak it")
    void clearRemovesId() {
        TraceIdHolder.set("4bf92f3577b34da6a3ce929d0e0e4736");
        TraceIdHolder.clear();

        assertThat(TraceIdHolder.get()).isNull();
    }
}
