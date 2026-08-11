package com.mugen.auth.support;

import com.mugen.test.SliceTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One mugen-auth controller behind the real filter chain.
 * <p>
 * The {@link JwtDecoder} mock is here because {@code SecurityConfig}'s resource server
 * requires the bean to exist before any request is dispatched, whether or not the test
 * presents a token — so every slice in this service needed the same four lines.
 *
 * <pre>{@code
 * @AuthSliceTest(SsoController.class)
 * class SsoControllerTest {
 *
 *     @Autowired  private MockMvc mvc;
 *     @MockitoBean private OAuthService oauthService;
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SliceTest
@MockitoBean(types = JwtDecoder.class)
public @interface AuthSliceTest {

    /** The controllers under test. */
    @AliasFor(annotation = SliceTest.class, attribute = "value")
    Class<?>[] value() default {};
}
