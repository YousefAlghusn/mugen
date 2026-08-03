package com.mugen.shared.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Read access to the current request's trace id, for RFC 9457 bodies and logs.
 * <p>
 * <strong>Micrometer Tracing owns the {@value #TRACE_ID_KEY} MDC key</strong> and
 * populates it from the inbound {@code traceparent}. This class only reads it — minting
 * ids in competition would make a log line disagree with Jaeger. {@link #getOrCreate()}
 * covers the one edge where no span is active, and its fallback matches the W3C shape
 * so nothing downstream sees two formats.
 */
public final class TraceIdHolder {

    /** The MDC key Micrometer Tracing writes the current trace id to. */
    public static final String TRACE_ID_KEY = "traceId";

    private TraceIdHolder() {
    }

    /**
     * @return the active trace id, or {@code null} when no span is in scope
     */
    public static String get() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * @return the active trace id, generating and storing a W3C-shaped fallback
     *         only if the tracer has not already set one
     */
    public static String getOrCreate() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = newW3CTraceId();
            MDC.put(TRACE_ID_KEY, traceId);
        }
        return traceId;
    }

    public static void set(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * Must be called when the request completes: MDC is thread-local and the threads
     * are pooled, so a missed clear leaks this trace id onto the next request.
     */
    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

    /** 128 random bits as 32 lower-case hex chars, matching W3C Trace Context. */
    private static String newW3CTraceId() {
        UUID uuid = UUID.randomUUID();
        return "%016x%016x".formatted(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }
}
