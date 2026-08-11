package com.mugen.web.unit;

import com.mugen.web.security.PublicEndpoint;
import com.mugen.web.security.PublicEndpointMatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the scan finds exactly the annotated handlers, and that an unannotated one is
 * not matched — which is what makes forgetting the annotation deny rather than grant.
 */
class PublicEndpointMatcherTest {

    @RestController
    @RequestMapping("/api/v1/things")
    static class Controller {

        @PublicEndpoint
        @PostMapping("/open")
        void open() {
        }

        @GetMapping("/closed")
        void closed() {
        }

        @PublicEndpoint
        @RequestMapping("/any-method")
        void anyMethod() {
        }
    }

    @PublicEndpoint
    @RestController
    @RequestMapping("/api/v1/everything")
    static class WhollyPublicController {

        @GetMapping("/one")
        void one() {
        }
    }

    private static PublicEndpointMatcher matcherFor(Class<?>... controllers) {
        RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
        var context = new org.springframework.web.context.support.StaticWebApplicationContext();
        Stream.of(controllers).forEach(type -> context.registerSingleton(type.getName(), type));
        context.refresh();

        handlerMapping.setApplicationContext(context);
        handlerMapping.afterPropertiesSet();

        return new PublicEndpointMatcher(providerOf(handlerMapping));
    }

    private static ObjectProvider<RequestMappingHandlerMapping> providerOf(RequestMappingHandlerMapping mapping) {
        return new ObjectProvider<>() {
            @Override
            public RequestMappingHandlerMapping getObject() {
                return mapping;
            }

            @Override
            public RequestMappingHandlerMapping getObject(Object... args) {
                return mapping;
            }

            @Override
            public RequestMappingHandlerMapping getIfAvailable() {
                return mapping;
            }

            @Override
            public RequestMappingHandlerMapping getIfUnique() {
                return mapping;
            }

            @Override
            public java.util.Iterator<RequestMappingHandlerMapping> iterator() {
                return List.of(mapping).iterator();
            }
        };
    }

    private static HttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("a handler annotated on the method matches, on its declared verb only")
    void matchesAnnotatedMethod() {
        PublicEndpointMatcher matcher = matcherFor(Controller.class);

        assertThat(matcher.matches(request("POST", "/api/v1/things/open"))).isTrue();
        // Same path, different verb: not the mapping that was annotated.
        assertThat(matcher.matches(request("GET", "/api/v1/things/open"))).isFalse();
    }

    @Test
    @DisplayName("an unannotated handler does not match")
    void doesNotMatchUnannotatedMethod() {
        PublicEndpointMatcher matcher = matcherFor(Controller.class);

        assertThat(matcher.matches(request("GET", "/api/v1/things/closed"))).isFalse();
    }

    @Test
    @DisplayName("a mapping with no verb restriction matches every verb")
    void matchesEveryMethodWhenMappingNamesNone() {
        PublicEndpointMatcher matcher = matcherFor(Controller.class);

        assertThat(matcher.matches(request("GET", "/api/v1/things/any-method"))).isTrue();
        assertThat(matcher.matches(request("DELETE", "/api/v1/things/any-method"))).isTrue();
    }

    @Test
    @DisplayName("the annotation is honoured on the controller class")
    void matchesWhenAnnotatedOnClass() {
        PublicEndpointMatcher matcher = matcherFor(WhollyPublicController.class);

        assertThat(matcher.matches(request("GET", "/api/v1/everything/one"))).isTrue();
    }

    /**
     * A service may legitimately have no public endpoints at all. OrRequestMatcher
     * rejects an empty list, so this would be a startup failure rather than a
     * "matches nothing" if it were not handled.
     */
    @Test
    @DisplayName("a service with no public endpoints matches nothing, and still starts")
    void matchesNothingWhenNoneAreAnnotated() {
        PublicEndpointMatcher matcher = matcherFor(SecuredOnlyController.class);

        assertThat(matcher.matchers()).isEmpty();
        assertThat(matcher.matches(request("GET", "/api/v1/secured/one"))).isFalse();
    }

    @RestController
    @RequestMapping("/api/v1/secured")
    static class SecuredOnlyController {

        @GetMapping("/one")
        void one() {
        }
    }
}
