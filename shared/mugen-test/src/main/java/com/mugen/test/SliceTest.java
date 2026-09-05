package com.mugen.test;

import com.mugen.test.support.SecurityMethodArguments;
import com.mugen.web.error.GlobalExceptionHandler;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
 * invisible from a service-level test — a service returns a token pair, and whether that
 * reaches the browser as {@code HttpOnly; Secure; SameSite=Strict} is decided entirely
 * above it. Collaborators are supplied with {@code @MockitoBean}, which is cheap here:
 * a slice context is small, so the cache misses it causes cost little.
 * <p>
 * Two things are wired in that every slice in every service would otherwise repeat:
 * <ul>
 *   <li>{@link GlobalExceptionHandler}, which reaches a running service through
 *       mugen-web's auto-configuration. {@code @WebMvcTest} applies only its own slice's
 *       auto-configurations, so without this a handler's exception escapes raw and every
 *       assertion about a problem document quietly tests the wrong thing.</li>
 *   <li>A mock {@link JwtDecoder}. Every mugen service is a resource server — a service
 *       must not trust the gateway to have been honest — so its security configuration
 *       needs the bean before any request is dispatched, whether or not the test presents
 *       a token. A real one would need the service's keys, which is not what a controller
 *       slice is testing.</li>
 *   <li>{@link SecurityMethodArguments}, so a handler taking {@code @AuthenticationPrincipal}
 *       is given one. A slice applies no security auto-configuration, so nothing else
 *       contributes that resolver and the parameter is treated as a model attribute
 *       instead — a 500 with nothing to do with what the test is asserting.</li>
 * </ul>
 * <p>
 * It has to be {@code @MockitoBean} rather than a {@code @Bean} in an imported
 * configuration, and the difference is not stylistic. A bean definition is visible to
 * {@code @ConditionalOnBean}, so declaring one activates
 * {@code OAuth2ResourceServerWebSecurityAutoConfiguration}, which then tries to build a
 * filter chain from an {@code HttpSecurity} that a {@code @WebMvcTest} slice does not
 * have — and the whole context fails to start. A bean <em>override</em> is applied after
 * auto-configuration has been evaluated, so it fills the hole without opening that one.
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
@ActiveProfiles("test")
@Import({GlobalExceptionHandler.class, SecurityMethodArguments.class})
@MockitoBean(types = JwtDecoder.class)
public @interface SliceTest {

    /** The controllers under test. */
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
