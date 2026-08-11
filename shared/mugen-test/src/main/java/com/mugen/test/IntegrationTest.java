package com.mugen.test;

import com.mugen.test.support.CleanDatabaseExtension;
import com.mugen.test.support.DatabaseCleaner;
import com.mugen.test.support.MockMvcConfiguration;
import com.mugen.test.support.MugenContainers;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The full application context against real infrastructure — the only tier that can see
 * a proxy, a transaction boundary, or anything a database does that an in-memory
 * substitute does not.
 * <p>
 * <b>Used directly, by every service.</b> There is nothing to compose over it and
 * nothing to declare: the containers come from {@link MugenContainers}, which reads the
 * service's own classpath, and {@code MockMvc} and {@link DatabaseCleaner} are wired in
 * here.
 *
 * <pre>{@code
 * @IntegrationTest
 * class RefreshRotationTest {
 *
 *     @Autowired private MockMvc mvc;
 * }
 * }</pre>
 *
 * <h2>Configuration belongs in application-test.yml, never on the class</h2>
 * Spring caches an application context per distinct configuration and the containers are
 * beans in that context, so <b>one test class with one extra {@code properties} entry is
 * a second context and a second database</b>. Four classes with four nearly-identical
 * property sets is how this suite came to start four SQL Servers.
 * <p>
 * So this annotation activates the {@code test} profile and a service puts its test
 * configuration in {@code src/test/resources/application-test.yml}, which layers over the
 * service's real {@code application.yml} instead of shadowing it the way a same-named
 * file would. Every test in the service reads the same file, so there is still one
 * context. Genuinely needing different configuration is a real second context: give it
 * its own annotation and a comment saying why.
 * <p>
 * {@code @MockitoBean} is subject to the same rule for the same reason — each distinct
 * set of overrides is a distinct cache key. At this tier it is usually the wrong tool
 * anyway: the point of the tier is that the wiring is real.
 * <p>
 * Two properties are set for every service. Eureka registration would have the suite
 * announcing itself to a discovery server that is not running, and tracing would export
 * spans to a collector that is not either; both cost startup time and produce nothing
 * but connection errors in the log.
 *
 * @see com.mugen.test
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "management.tracing.enabled=false"
})
@ActiveProfiles("test")
@Import({MugenContainers.class, MockMvcConfiguration.class, DatabaseCleaner.class})
@ExtendWith(CleanDatabaseExtension.class)
public @interface IntegrationTest {
}
