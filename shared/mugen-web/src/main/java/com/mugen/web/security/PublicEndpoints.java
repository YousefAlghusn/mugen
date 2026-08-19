package com.mugen.web.security;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Whether a handler is reachable without a token.
 * <p>
 * One implementation because three things ask: the filter chain's matcher, the
 * document's security requirement, and the 401 every secured operation publishes.
 * Three copies of the check can disagree, and the symptom is a document that lies
 * while every request keeps working.
 */
public final class PublicEndpoints {

    private PublicEndpoints() {
    }

    /** Method first, then the controller — a class-level annotation covers every handler in it. */
    public static boolean isPublic(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PublicEndpoint.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PublicEndpoint.class);
    }
}
