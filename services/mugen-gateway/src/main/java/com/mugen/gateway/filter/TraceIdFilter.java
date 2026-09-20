package com.mugen.gateway.filter;

import com.mugen.shared.trace.TraceIdHolder;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Settles the request's trace id before anything can refuse it, and hands it back on
 * every response as {@value #TRACE_ID_HEADER}.
 * <p>
 * First in the chain so that a 401 from the security filters, a 429, a 404 for an
 * unknown route and a 200 all carry the id a user quotes to support. The id is the
 * tracer's own — WebFlux starts the server observation around the whole filter chain,
 * so it already exists here — and only minted when nothing is tracing. Reading it from
 * the observation rather than MDC matters on an event loop: the thread may still carry
 * the previous request's value.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** Exchange attribute the problem handler and the request log read the id from. */
    public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Deferred, because the observation is started on subscription and this method
        // runs before that. At assembly time there is no span to read yet.
        return Mono.defer(() -> {
            String traceId = currentTraceId(exchange).orElseGet(TraceIdHolder::mint);
            exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
                return Mono.empty();
            });
            return chain.filter(exchange);
        });
    }

    /** What a caller should be told; falls back to a fresh id if the filter never ran. */
    public static String traceId(ServerWebExchange exchange) {
        String traceId = exchange.getAttribute(TRACE_ID_ATTRIBUTE);
        return traceId != null ? traceId : TraceIdHolder.mint();
    }

    private static Optional<String> currentTraceId(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.<TracingContext>get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(span -> span.context().traceId());
    }
}
