/**
 * The three test tiers, as three annotations.
 * <p>
 * A test belongs to the <em>lowest</em> tier that can see the failure it is written
 * for. That is the whole rule; the tiers exist so that "lowest" is a question with an
 * answer rather than a matter of taste.
 *
 * <table border="1">
 *   <caption>Choosing a tier</caption>
 *   <tr><th>Tier</th><th>Annotation</th><th>Runner</th><th>What only it can see</th></tr>
 *   <tr>
 *     <td>unit</td><td>{@link com.mugen.test.UnitTest}</td><td>surefire</td>
 *     <td>Decisions: a rule, a mapping, a calculation, a state machine. No Spring.</td>
 *   </tr>
 *   <tr>
 *     <td>slice</td><td>{@link com.mugen.test.SliceTest}</td><td>surefire</td>
 *     <td>The HTTP contract: status codes, headers, cookie attributes, problem bodies.</td>
 *   </tr>
 *   <tr>
 *     <td>integration</td><td>{@code @<Service>IntegrationTest}</td><td>failsafe</td>
 *     <td>Proxies, transactions, and real database semantics.</td>
 *   </tr>
 * </table>
 *
 * <p>
 * That bottom row is the one this project has paid for twice. A {@code @Transactional},
 * {@code @Async}, {@code @Cacheable} or {@code @PreAuthorize} that never applies is
 * <em>invisible</em> above it — the annotation is implemented by a proxy, and a test
 * that builds its subject with {@code new} has no proxy. Twenty-three green unit tests
 * once coexisted with a transaction that had never once opened. So: if the behaviour
 * under test depends on Spring having wrapped something, it is an integration test, and
 * there is no shortcut.
 *
 * <p>
 * Integration tests are the tier a service must finish itself, because the containers
 * differ per service — mugen-auth is SQL Server, mugen-user is Postgres. Each service
 * composes one annotation over {@link com.mugen.test.IntegrationTest} naming its own
 * containers, and every integration test in that service then uses it <em>unmodified</em>.
 * That uniformity is not tidiness: Spring caches an application context per distinct
 * configuration, so four test classes with four slightly different property sets mean
 * four contexts and four database containers. Identical annotations mean one of each.
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
