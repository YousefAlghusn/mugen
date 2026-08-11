package com.mugen.test;

import com.mugen.web.error.GlobalExceptionHandler;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One controller and the real filter chain, with everything below it mocked: what the
 * client actually receives.
 * <p>
 * Worth its own tier because status codes, response headers and cookie attributes are
 * invisible from a service-level test — a service returns a {@code TokenPair}, and
 * whether that reaches the browser as {@code HttpOnly; Secure; SameSite=Strict} is
 * decided entirely above it. Collaborators are supplied with {@code @MockitoBean}.
 * <p>
 * {@link GlobalExceptionHandler} is imported here so no test has to remember to.
 * It reaches a running service through mugen-web's auto-configuration, but
 * {@code @WebMvcTest} applies only its own slice's list of auto-configurations — so
 * without this import a handler's exception escapes raw and every assertion about a
 * problem document silently tests the wrong thing.
 *
 * <pre>{@code
 * @SliceTest(SsoController.class)
 * class SsoControllerTest {
 *
 *     @Autowired  private MockMvc mvc;
 *     @MockitoBean private OAuthService oauthService;
 * }
 * }</pre>
 *
 * @see com.mugen.test
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@Import(GlobalExceptionHandler.class)
public @interface SliceTest {

    /** The controllers under test. */
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
