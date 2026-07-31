package com.mugen.auth.controller;

import com.mugen.auth.dto.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

/**
 * Reads the device details recorded on a new session. Shared by every endpoint that
 * opens one, so a session created by SSO is described the same way as one created
 * by a password login — otherwise the "your devices" list would show two different
 * kinds of entry depending on how the user signed in.
 */
final class RequestContexts {

    private RequestContexts() {
    }

    static RequestContext of(HttpServletRequest request) {
        return new RequestContext(request.getHeader(HttpHeaders.USER_AGENT), clientIpOf(request));
    }

    /**
     * Trusts {@code X-Forwarded-For} because the gateway is the only way in
     * (CLAUDE.md architecture rules) and it sets the header. If this service were
     * ever exposed directly the value would be caller-controlled and this would
     * need to become a trusted-proxy check.
     */
    private static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // Leftmost entry is the original client; the rest are proxies.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
