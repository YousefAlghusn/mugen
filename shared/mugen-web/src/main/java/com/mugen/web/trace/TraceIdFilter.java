package com.mugen.web.trace;

import com.mugen.shared.trace.TraceIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts a trace id in the MDC before anything in the request can log or fail.
 * <p>
 * Micrometer Tracing normally does this, and when it has, this filter leaves its id
 * alone. It covers the requests where nothing did — no tracer configured, or no span
 * in scope — because a {@code ProblemDetail} whose traceId reaches the caller but no
 * log line is decoration, and the caller cannot tell the two cases apart.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Only what this filter minted is cleared: an id belonging to a tracer is
        // removed when that tracer closes its own scope, further out than this.
        boolean minted = TraceIdHolder.get() == null;
        TraceIdHolder.getOrCreate();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (minted) {
                TraceIdHolder.clear();
            }
        }
    }
}
