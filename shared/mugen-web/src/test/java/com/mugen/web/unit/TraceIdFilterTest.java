package com.mugen.web.unit;

import com.mugen.shared.trace.TraceIdHolder;
import com.mugen.web.trace.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter exists so a traceId in a response body is never one that could not have
 * been logged, which is a claim about when the id exists rather than about its value.
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        TraceIdHolder.clear();
    }

    @Test
    @DisplayName("a request with no trace id gets one before anything downstream runs")
    void mintsATraceIdForTheRequest() throws Exception {
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                chainRecording(seenDownstream));

        assertThat(seenDownstream.get())
                .describedAs("anything that logs or fails downstream must already have an id")
                .isNotNull()
                .hasSize(32);
    }

    /**
     * Micrometer Tracing's id is the one Jaeger knows. Minting a competing one here
     * would make a log line disagree with the trace it belongs to.
     */
    @Test
    @DisplayName("a trace id already in scope is left exactly as it is")
    void doesNotOverrideAnActiveTraceId() throws Exception {
        String active = "4bf92f3577b34da6a3ce929d0e0e4736";
        TraceIdHolder.set(active);
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                chainRecording(seenDownstream));

        assertThat(seenDownstream.get()).isEqualTo(active);
    }

    /**
     * MDC is thread-local and request threads are pooled, so an id left behind is read
     * by whatever request lands on that thread next — it would report someone else's
     * trace under this one's id.
     */
    @Test
    @DisplayName("a minted id does not outlive its request")
    void clearsWhatItMinted() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(TraceIdHolder.get()).isNull();
    }

    @Test
    @DisplayName("an id it did not mint is not cleared either — its owner ends its own scope")
    void leavesAnInheritedIdBehind() throws Exception {
        String active = "4bf92f3577b34da6a3ce929d0e0e4736";
        TraceIdHolder.set(active);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(TraceIdHolder.get()).isEqualTo(active);
    }

    @Test
    @DisplayName("the id is cleared even when the request blows up")
    void clearsAfterAFailedRequest() {
        MockFilterChain exploding = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                throw new IllegalStateException("boom");
            }
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), exploding);
        } catch (Exception expected) {
            // The failure is the point; what matters is the state it leaves behind.
        }

        assertThat(TraceIdHolder.get()).isNull();
    }

    private static MockFilterChain chainRecording(AtomicReference<String> seen) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                seen.set(TraceIdHolder.get());
            }
        };
    }
}
