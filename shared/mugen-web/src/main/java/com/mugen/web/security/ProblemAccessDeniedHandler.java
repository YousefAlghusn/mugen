package com.mugen.web.security;

import com.mugen.web.error.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The 403 half of {@link ProblemAuthenticationEntryPoint} — a valid token that is not
 * allowed to do this, rendered as the same problem document by the same handler.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ProblemAccessDeniedHandler(HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) {

        // Never the framework's message: it names the authority that was missing,
        // which tells a caller what to go looking for.
        handlerExceptionResolver.resolveException(request, response, null,
                new ForbiddenException("You are not allowed to do this."));
    }
}
