package com.mugen.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The full application context against real infrastructure — the only tier that can
 * see a proxy, a transaction boundary, or anything a database does that an in-memory
 * substitute does not.
 * <p>
 * <b>Not used directly.</b> Containers differ per service, so each service composes one
 * annotation over this naming its own, and every integration test in that service then
 * uses that annotation and nothing else:
 *
 * <pre>{@code
 * @Target(ElementType.TYPE)
 * @Retention(RetentionPolicy.RUNTIME)
 * @IntegrationTest
 * @Import(AuthContainers.class)
 * @TestPropertySource(properties = "mugen.outbox.enabled=false")
 * public @interface AuthIntegrationTest {}
 * }</pre>
 *
 * <p>
 * <b>Adding {@code properties} to an individual test class is the one thing not to do
 * here.</b> Spring caches an application context per distinct configuration, and a
 * container is started per context — so one test class with one extra property is a
 * second context and a second database. Four such classes are why {@code verify} used
 * to boot four SQL Servers. If a test genuinely needs different configuration, that is
 * a deliberate second annotation with a comment saying why, not a one-off override.
 * <p>
 * One context means one database, so {@link DatabaseCleaner} empties it before each
 * test class — see {@link CleanDatabaseExtension}. Without that, a class that
 * deliberately commits leaves its rows to the next one, and any assertion phrased as a
 * total quietly starts counting them.
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
@Import({MockMvcConfiguration.class, DatabaseCleaner.class})
@ExtendWith(CleanDatabaseExtension.class)
public @interface IntegrationTest {
}
