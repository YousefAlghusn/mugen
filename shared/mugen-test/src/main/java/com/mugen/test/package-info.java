/**
 * The three test tiers, as three annotations, for all eleven services.
 * <p>
 * A test belongs to the <em>lowest</em> tier that can see the failure it is written for.
 * That is the whole rule; the tiers exist so that "lowest" is a question with an answer
 * rather than a matter of taste.
 *
 * <table border="1">
 *   <caption>Choosing a tier</caption>
 *   <tr><th>Tier</th><th>Directory</th><th>Annotation</th><th>Runner</th><th>What only it can see</th></tr>
 *   <tr>
 *     <td>unit</td><td>{@code unit/}</td><td>{@link com.mugen.test.UnitTest}</td><td>surefire</td>
 *     <td>Decisions: a rule, a mapping, a calculation, a state machine. No Spring.</td>
 *   </tr>
 *   <tr>
 *     <td>slice</td><td>{@code slice/}</td><td>{@link com.mugen.test.SliceTest}</td><td>surefire</td>
 *     <td>The HTTP contract: status codes, headers, cookie attributes, problem bodies.</td>
 *   </tr>
 *   <tr>
 *     <td>integration</td><td>{@code integration/}</td><td>{@link com.mugen.test.IntegrationTest}</td><td>failsafe</td>
 *     <td>Proxies, transactions, and real database semantics.</td>
 *   </tr>
 * </table>
 *
 * <p>
 * The directory is the tier, and the directory is what selects the runner (see the
 * surefire and failsafe includes in the root pom), so the two cannot drift apart by
 * forgetting to rename a class.
 *
 * <h2>A service declares nothing</h2>
 * These three annotations are used as they are. There is no per-service annotation to
 * compose, no container to declare and no property preamble to copy — that was the
 * duplication this module exists to delete, and re-introducing it one service at a time
 * is how it comes back.
 * <ul>
 *   <li><b>Containers follow the classpath.</b>
 *       {@link com.mugen.test.support.MugenContainers} starts SQL Server
 *       for a service that depends on {@code org.testcontainers:mssqlserver}, Redis for
 *       one that uses Spring Data Redis, and so on. The service declares the technology
 *       it already needs; the container follows.</li>
 *   <li><b>Configuration goes in {@code src/test/resources/application-test.yml}.</b> The
 *       integration tier activates the {@code test} profile for exactly this. A
 *       profile-specific file layers over the service's real {@code application.yml};
 *       a same-named {@code application.yml} in test resources would shadow it entirely.</li>
 *   <li><b>Something genuinely unusual is still yours to write.</b> Switch the standard
 *       one off with {@code mugen.test.containers.<technology>.enabled: false} and declare
 *       your own — for a second database, or a container needing a custom command.</li>
 * </ul>
 *
 * <h2>The proxy rule</h2>
 * {@code @Transactional}, {@code @Async}, {@code @Cacheable} and {@code @PreAuthorize}
 * are implemented by a proxy around the bean. A test that builds its subject with
 * {@code new} has no proxy, so the annotation is <em>inert</em> and the test cannot tell
 * you. Twenty-three green unit tests once coexisted with a transaction that had never
 * once opened. If the behaviour under test depends on Spring having wrapped something,
 * it is an integration test, and there is no shortcut.
 *
 * <h2>Mocking</h2>
 * One tool per tier, and the reason is context caching.
 * <ul>
 *   <li><b>unit</b> — Mockito {@code @Mock} and {@code @InjectMocks}, wired by
 *       {@link com.mugen.test.UnitTest}. Strict stubs: an unused {@code when(...)} fails
 *       the test, because a stub nobody calls is usually a test that has quietly stopped
 *       exercising the path it names.</li>
 *   <li><b>slice</b> — {@code @MockitoBean} for the service layer, freely. A slice
 *       context is small, so the extra cache entries it creates are cheap. (Boot 4's
 *       {@code @MockBean} is gone; {@code @MockitoBean} from spring-test replaces it.)</li>
 *   <li><b>integration</b> — <b>as little as possible.</b> Each distinct set of
 *       {@code @MockitoBean} overrides is a distinct context cache key, so a mock here
 *       costs a whole application context and a database. It is also usually the wrong
 *       tool: the point of the tier is that the wiring is real. To keep an external
 *       system out of a test, switch it off through configuration in
 *       {@code application-test.yml} — the same value for every test, so still one
 *       context.</li>
 * </ul>
 *
 * <h2>Fixtures</h2>
 * {@link com.mugen.test.Responses} reads {@code MockMvc} responses. Everything domain
 * shaped stays in the service, in {@code support/}, as an <b>object mother returning a
 * fluent builder</b> — {@code UserMother.registered().withRoles(ADMIN).build()}. A mother
 * alone grows a method per variation; a builder alone puts five lines of setup in every
 * test; together each test states only what it actually depends on.
 * <p>
 * That those fixtures are per service is not the duplication this module removes. Test
 * <em>infrastructure</em> is shared because it is identical everywhere and has no domain
 * in it; a fixture is a service's own bounded context, and sharing those across services
 * is the coupling that makes a shared library an anti-pattern rather than a saving.
 *
 * <h2>What a test has to earn</h2>
 * A test earns its place by failing when behaviour breaks. Three ways to fail that bar,
 * all of which look like coverage:
 * <ul>
 *   <li><b>Restatement</b> — asserting a literal copied from the source. It has no
 *       independent idea of what is right, so it cannot fail for a reason that matters.</li>
 *   <li><b>Brittle</b> — asserting an incidental detail, so a safe change goes red.
 *       Asserting a sentence of prose from a javadoc comment is the worst case: it makes
 *       rewording a comment a build failure.</li>
 *   <li><b>Hand-listed</b> — enumerating what exists today. It cannot fail when something
 *       is <em>added</em>, which is exactly when the rule needed enforcing.</li>
 * </ul>
 * The opposite is an invariant: state the rule, and it covers the cases nobody has
 * written yet. "An operation advertises {@code bearerAuth} exactly when its handler is
 * not {@code @PublicEndpoint}" holds for every endpoint mugen will ever have.
 */
package com.mugen.test;
