package com.mugen.gateway.support;

import com.mugen.test.Fixture;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A downstream service for the gateway to proxy to: it answers every request with the
 * headers it received, as JSON, so a test can see exactly what the gateway forwarded.
 * <p>
 * Reactor Netty directly, because the gateway already ships it, and the alternatives
 * (a second Spring context, a mock-server library) would be a heavier way to answer one
 * question — what arrived on the other side.
 */
@Fixture
public class UpstreamStub {

    private final DisposableServer server;

    public UpstreamStub() {
        this.server = HttpServer.create()
                .port(0)
                .handle((request, response) -> response
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(headersAsJson(request.requestHeaders()))))
                .bindNow();
    }

    /** Where a test route should point. */
    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    @PreDestroy
    void stop() {
        server.disposeNow();
    }

    /** Lower-cased names: header names are case-insensitive, and the reader should not have to care. */
    private static String headersAsJson(HttpHeaders headers) {
        Map<String, String> received = new TreeMap<>();
        headers.forEach(entry -> received.put(entry.getKey().toLowerCase(), entry.getValue()));
        return received.entrySet().stream()
                .map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"")
                .collect(Collectors.joining(",", "{\"headers\":{", "}}"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
