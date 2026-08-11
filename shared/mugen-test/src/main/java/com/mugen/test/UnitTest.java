package com.mugen.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A decision, tested with no Spring at all: a rule, a mapping, a calculation, a state
 * machine. Milliseconds, no Docker.
 * <p>
 * The subject is built with {@code new} and its collaborators are {@code @Mock}. That
 * is the tier's strength and its one hard limit — <b>no proxy exists, so no Spring
 * annotation is in play.</b> A {@code @Transactional} or {@code @Cacheable} on the
 * subject is inert here and a test at this tier cannot tell you otherwise. When the
 * behaviour depends on one, the test belongs in the integration tier instead.
 * <p>
 * Strict stubbing is deliberate: an unused {@code when(...)} fails the test, because a
 * stub nobody calls is usually a test that has quietly stopped exercising the path it
 * names.
 *
 * <pre>{@code
 * @UnitTest
 * class GoogleProfileMapperTest {
 *
 *     private final GoogleProfileMapper mapper = new GoogleProfileMapper();
 *
 *     @Test
 *     void refusesAnUnverifiedEmail() { ... }
 * }
 * }</pre>
 *
 * @see com.mugen.test
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(MockitoExtension.class)
public @interface UnitTest {
}
