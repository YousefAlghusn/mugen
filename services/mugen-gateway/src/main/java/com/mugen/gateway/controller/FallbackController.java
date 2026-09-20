package com.mugen.gateway.controller;

import com.mugen.gateway.error.GatewayExceptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Where a route's {@code CircuitBreaker} filter forwards when the service behind it
 * cannot answer: an open circuit, a timeout, no registered instance.
 * <p>
 * Every method, because {@code forward:} keeps the original one. It throws rather than
 * builds a response so the 503 is rendered by the same handler as every other failure.
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/{service}")
    public void unavailable(@PathVariable String service, ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        // Type only: the message names hosts and ports, and for a caller that is noise.
        log.warn("Service unavailable service={} cause={}",
                service, cause != null ? cause.getClass().getSimpleName() : "direct call");
        throw new GatewayExceptions.ServiceUnavailable(service);
    }
}
