package com.mugen.gateway.unit;

import com.mugen.gateway.filter.IdentityHeadersFilter;
import com.mugen.gateway.filter.RateLimitFilter.RateLimitKeys;
import com.mugen.test.UnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which bucket a request draws from is the whole rate limit: the wrong key either lumps
 * an office behind one address into one bucket, or hands a brute-forcer a fresh bucket
 * per request.
 */
@UnitTest
class RateLimitKeysTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("203.0.113.9", 51234);

    @Test
    void aSignedInCallerIsKeyedByWhoTheyAre() {
        UUID userId = UUID.randomUUID();
        ServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x").remoteAddress(CLIENT));
        exchange.getAttributes().put(IdentityHeadersFilter.USER_ID_ATTRIBUTE, userId.toString());

        assertThat(RateLimitKeys.keyFor(exchange)).isEqualTo("user:" + userId);
    }

    @Test
    void anAnonymousCallerIsKeyedByAddress() {
        ServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x").remoteAddress(CLIENT));

        assertThat(RateLimitKeys.keyFor(exchange)).isEqualTo("ip:203.0.113.9");
    }

    /** A client that can name its own address can name a new one per attempt. */
    @Test
    void aForwardedForHeaderDoesNotChooseTheBucket() {
        ServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x")
                .remoteAddress(CLIENT)
                .header("X-Forwarded-For", "10.0.0." + UUID.randomUUID().hashCode()));

        assertThat(RateLimitKeys.keyFor(exchange)).isEqualTo("ip:203.0.113.9");
    }

    private static ServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }
}
