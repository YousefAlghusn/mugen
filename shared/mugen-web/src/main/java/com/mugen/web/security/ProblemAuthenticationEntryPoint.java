package com.mugen.web.security;

import com.mugen.web.error.TokenInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers a refused request with the same RFC 9457 document as every other failure.
 * <p>
 * Spring Security's default entry point writes a bare 401 with no body, which is the
 * most common failure any service produces and the only one that arrived with no
 * {@code code}, no {@code traceId} and no log line — while the document promised all
 * three. Rather than serialise a second problem body here, the exception is handed to
 * the MVC exception resolver, so it comes back out of {@code GlobalExceptionHandler}:
 * one renderer, one shape, and the logging rule holds without being restated.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ProblemAuthenticationEntryPoint(HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authenticationException) {

        // RFC 6750 wants a challenge on a 401. Bare, with no error description: the
        // framework's own is built from the rejected token.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        handlerExceptionResolver.resolveException(request, response, null,
                new TokenInvalidException("Access token is missing, expired or not valid."));
    }
}
