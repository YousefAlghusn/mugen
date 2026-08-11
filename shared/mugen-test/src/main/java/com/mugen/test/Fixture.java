package com.mugen.test;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that builds test data or drives the service's own API, so an
 * integration test can {@code @Autowired} it.
 * <p>
 * A fixture lives in the service, in {@code support/}, under the application's base
 * package — which is why nothing has to import it: {@code @SpringBootTest} component
 * scans from that package, and test classes are on the classpath. Slices are unaffected,
 * because {@code @WebMvcTest} restricts scanning to controllers. Nothing reaches
 * production either, since test classes are never packaged into the jar.
 * <p>
 * A stereotype rather than a bare {@code @Component} so that "what is a fixture here"
 * is greppable, and so the answer to "how do I add one" stays a single word.
 *
 * <pre>{@code
 * @Fixture
 * public class AuthFixtures {
 *
 *     private final MockMvc mvc;
 *
 *     public AuthFixtures(MockMvc mvc) { this.mvc = mvc; }
 *
 *     public Account register() throws Exception { ... }
 * }
 * }</pre>
 *
 * @see com.mugen.test
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Fixture {
}
