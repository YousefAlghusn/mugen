package com.mugen.shared.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Read access to the current request's trace id, for stamping onto RFC 9457
 * ProblemDetail bodies and structured logs.
 * <p>
 * <strong>Micrometer Tracing owns the {@value #TRACE_ID_KEY} MDC key.</strong> Once a
 * service has a tracer on the classpath (this stack exports OTLP to Jaeger),
 * Micrometer populates it automatically from the inbound {@code traceparent}
 * header, or mints a new W3C id when there isn't one. This class therefore only
 * reads that value — it must not compete with the tracer to produce ids, or the
 * id in a log line would disagree with the id Jaeger recorded for the same request.
 * <p>
 * {@link #getOrCreate()} exists solely for the edge where no span is active: the
 * gateway rejecting a request in a filter that runs before tracing is established.
 * Its fallback deliberately matches the W3C trace-id shape (32 lower-case hex
 * characters) so nothing downstream has to handle two different formats.
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
     * Must be called when the request completes. MDC is thread-local and these
     * threads are pooled, so a missed clear leaks one request's trace id onto
     * the next request that happens to reuse the thread.
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
