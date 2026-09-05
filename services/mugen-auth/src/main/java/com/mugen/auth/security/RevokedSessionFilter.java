package com.mugen.auth.security;

import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.service.RevocationCacheService;
import com.mugen.auth.token.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.UUID;

/**
 * Refuses a request whose session has been revoked, however valid its signature still is.
 * <p>
 * mugen-auth enforces revocation for itself rather than relying on the gateway to have
 * done it. The architecture says the gateway is the only entry point, and behind it this
 * check is redundant — but "the only entry point" is a rule, not a boundary anything
 * enforces, and the endpoints this protects are precisely the ones somebody uses to eject
 * an attacker. Without it, a stolen access token whose session was revoked could still
 * list and revoke sessions for the remainder of its lifetime, including the sessions of
 * the person doing the revoking.
 * <p>
 * The cost is one Redis lookup per authenticated request, on a service whose traffic is
 * sign-ins. It is the same lookup the gateway makes, against the same key, and it is not
 * a database call — which is what "auth makes no per-request lookups" was protecting.
 * <p>
 * Runs after the bearer token has been verified, so it only ever sees a token this
 * service issued, and answers through {@code handlerExceptionResolver} so a refusal is
 * the same RFC 9457 document as every other failure.
 */
@Slf4j
@RequiredArgsConstructor
public class RevokedSessionFilter extends OncePerRequestFilter {

    private final RevocationCacheService revocationCache;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }

        UUID sessionId;
        try {
            sessionId = CurrentUser.sessionId(token.getToken());
        } catch (RuntimeException unusable) {
            // Every access token this service mints carries one. A verified token
            // without it is not one of ours to interpret, so it is not one to trust.
            refuse(request, response, new AuthExceptions.TokenInvalid("Access token carries no session."));
            return;
        }

        if (revocationCache.isRevoked(sessionId)) {
            log.debug("Refused a revoked session sessionId={}", sessionId);
            refuse(request, response, new AuthExceptions.TokenRevoked());
            return;
        }

        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, RuntimeException failure) {
        // Cleared first: nothing downstream may see an identity this request no longer has.
        SecurityContextHolder.clearContext();
        handlerExceptionResolver.resolveException(request, response, null, failure);
    }
}
