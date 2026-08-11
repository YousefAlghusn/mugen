package com.mugen.auth.support;

import com.mugen.test.IntegrationTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every mugen-auth integration test, with no per-class configuration whatsoever.
 * <p>
 * Autowire {@code MockMvc} for HTTP, {@link AuthFixtures} for a registered user, or any
 * bean in the service. Add {@code @Transactional} when the test wants its rows rolled
 * back — but not when the behaviour under test is a commit, as replay detection and
 * revocation both are.
 *
 * <pre>{@code
 * @AuthIntegrationTest
 * class RefreshRotationTest {
 *
 *     @Autowired private MockMvc mvc;
 *     @Autowired private AuthFixtures auth;
 * }
 * }</pre>
 *
 * <p>
 * The outbox poller is off for all of them. Registration still writes its row — that is
 * part of the flow under test — but there is no broker here, so a running poller would
 * retry against nothing, hold connections, and race the tests asserting on those very
 * rows. Turning it back on is a deliberate second annotation, not a property on one
 * class: see {@link IntegrationTest} for why one-off overrides cost a whole context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@Import({AuthContainers.class, AuthFixtures.class})
@TestPropertySource(properties = "mugen.outbox.enabled=false")
public @interface AuthIntegrationTest {
}
