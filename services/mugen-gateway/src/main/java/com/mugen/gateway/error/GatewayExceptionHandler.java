package com.mugen.gateway.error;

import com.mugen.gateway.filter.TraceIdFilter;
import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiErrors;
import com.mugen.web.error.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * Renders every failure that escapes the filter chain as an RFC 9457
 * {@code application/problem+json} document — the reactive counterpart of mugen-web's
 * {@code GlobalExceptionHandler}, which is servlet-only.
 * <p>
 * A gateway's failures are filter-level, not controller-level: a refused token, a rate
 * limit, no route for the path, an unreachable service. Nothing about them passes
 * through a {@code @ControllerAdvice}, so this is a {@code WebExceptionHandler} ordered
 * ahead of Boot's default one at {@code -1}.
 */
@Slf4j
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private final ServerResponse.Context writers;

    public GatewayExceptionHandler(ServerCodecConfigurer codecs) {
        this.writers = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecs.getWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            // Headers are gone and bytes may be too; there is no document left to write.
            return Mono.error(ex);
        }

        String traceId = TraceIdFilter.traceId(exchange);
        String path = exchange.getRequest().getPath().value();
        ProblemDetail body = switch (ex) {
            case AppException app -> expected(app, traceId, path);
            case ResponseStatusException framework -> framework(framework, traceId, path);
            default -> unexpected(ex, traceId, path);
        };

        if (body.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            // RFC 6750 wants a challenge on a 401 — bare, because the framework's own
            // description is built from the rejected token. Here rather than in the entry
            // point so a revocation refusal carries it too.
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return ServerResponse.status(body.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(body)
                .flatMap(response -> response.writeTo(exchange, writers));
    }

    /** The exception already knows its status and code; the message is written for the client. */
    private static ProblemDetail expected(AppException ex, String traceId, String path) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("Request failed traceId={} errorCode={} status={} path={}",
                    traceId, ex.getErrorCode(), ex.getStatus().value(), path, ex);
        } else {
            log.warn("Request refused traceId={} errorCode={} status={} path={}",
                    traceId, ex.getErrorCode(), ex.getStatus().value(), path);
        }
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), traceId, path);
    }

    /**
     * WebFlux's own: 404 for a path no route claims, 405, 415. Type only, never the
     * reason — it is built from the offending request.
     */
    private static ProblemDetail framework(ResponseStatusException ex, String traceId, String path) {
        HttpStatusCode status = ex.getStatusCode();
        ErrorCode code = ApiErrors.codeFor(status);
        if (status.is5xxServerError()) {
            log.error("Request failed traceId={} status={} reason={} path={}",
                    traceId, status.value(), ex.getClass().getSimpleName(), path, ex);
        } else {
            log.warn("Request rejected traceId={} status={} reason={} path={}",
                    traceId, status.value(), ex.getClass().getSimpleName(), path);
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String detail = resolved != null ? resolved.getReasonPhrase() + "." : "Request rejected.";
        return problem(status, code, detail, traceId, path);
    }

    /** Anything else is a bug. The message is logged and withheld — it names internals. */
    private static ProblemDetail unexpected(Throwable ex, String traceId, String path) {
        log.error("Unhandled exception traceId={}", traceId, ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote traceId %s when reporting it.".formatted(traceId), traceId, path);
    }

    /** Same shape, field for field, as mugen-web's servlet handler produces. */
    private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail, String traceId,
                                         String path) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(ApiErrors.typeUri(code));
        HttpStatus resolved = HttpStatus.resolve(status.value());
        body.setTitle(resolved != null ? resolved.getReasonPhrase() : null);
        body.setInstance(URI.create(path));
        body.setProperty("code", code.name());
        body.setProperty("traceId", traceId);
        return body;
    }
}
