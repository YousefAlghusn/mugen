package com.mugen.web.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Matches requests whose handler is annotated {@link PublicEndpoint}.
 * <p>
 * Matching is still by path pattern, not by resolving the handler per request:
 * resolving would run the handler mapping twice and fire its interceptors before
 * the security filter chain had decided anything. The patterns are simply derived
 * from the annotated handlers instead of being typed out a second time.
 */
public final class PublicEndpointMatcher implements RequestMatcher {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    /**
     * Built on first use rather than in the constructor. This matcher is injected
     * into the {@code SecurityFilterChain} bean, and reading the handler mapping
     * there would force Spring MVC's infrastructure to initialise in the middle of
     * security configuration.
     */
    private volatile RequestMatcher delegate;

    public PublicEndpointMatcher(ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return delegate().matches(request);
    }

    /** The patterns this resolved to, for tests and for logging at startup. */
    public List<RequestMatcher> matchers() {
        return List.copyOf(scan());
    }

    private RequestMatcher delegate() {
        RequestMatcher resolved = this.delegate;
        if (resolved == null) {
            synchronized (this) {
                resolved = this.delegate;
                if (resolved == null) {
                    List<RequestMatcher> matchers = scan();
                    // An OrRequestMatcher rejects an empty list, and a service with no
                    // public endpoints at all is perfectly legitimate.
                    resolved = matchers.isEmpty()
                            ? anyRequest -> false
                            : new OrRequestMatcher(matchers);
                    this.delegate = resolved;
                }
            }
        }
        return resolved;
    }

    private List<RequestMatcher> scan() {
        List<RequestMatcher> matchers = new ArrayList<>();

        for (RequestMappingHandlerMapping handlerMapping : handlerMappings) {
            handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
                if (isPublic(handler)) {
                    matchers.addAll(matchersFor(mapping));
                }
            });
        }

        return matchers;
    }

    private static boolean isPublic(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PublicEndpoint.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PublicEndpoint.class);
    }

    private static List<RequestMatcher> matchersFor(RequestMappingInfo mapping) {
        PathPatternsRequestCondition paths = mapping.getPathPatternsCondition();
        if (paths == null) {
            return List.of();
        }

        Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();

        List<RequestMatcher> matchers = new ArrayList<>();
        paths.getPatterns().forEach(pattern -> {
            String path = pattern.getPatternString();
            if (methods.isEmpty()) {
                matchers.add(PathPatternRequestMatcher.pathPattern(path));
            } else {
                methods.forEach(method ->
                        matchers.add(PathPatternRequestMatcher.pathPattern(HttpMethod.valueOf(method.name()), path)));
            }
        });
        return matchers;
    }
}
