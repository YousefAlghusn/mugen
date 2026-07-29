package com.mugen.shared.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * MDC-backed traceId access. The gateway's TraceIdFilter sets this first, before
 * any other filter, so it is present in every downstream log line and ProblemDetail.
 */
public final class TraceIdHolder {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceIdHolder() {
    }

    public static String get() {
        return MDC.get(TRACE_ID_KEY);
    }

    public static String getOrCreate() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
            MDC.put(TRACE_ID_KEY, traceId);
        }
        return traceId;
    }

    public static void set(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }
}
