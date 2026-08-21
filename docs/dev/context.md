# Current state

Companion to tasks.md, which is the checklist. This file holds only what the code
and git history do NOT already say: live status, decisions and their reasoning,
and traps worth not rediscovering.

## Status (2026-08-11)
**The test suite has a structure now** — three tiers, `shared/mugen-test`, one context
and one container for the whole run. See "Test tiers" below and the `Testing` section
in CLAUDE.md. Nothing was deleted: 110 unit + slice and 44 integration, the same tests
as before, `./mvnw verify` green in ~45s.

What that leaves for 2.13 is the half it is actually named for: **the bar a test has to
meet**, and the pruning that follows from it. The named offenders are unchanged in
tasks.md, and `OpenApiTest` is still 319 lines that boot a database to assert a document
title and three sentences of javadoc prose.

## Status (paused 2026-08-04)
**Real Google SSO works end to end.** Credentials from a Google Cloud OAuth client,
`sso` profile activated for the first time, a real sign-in completed, and the account
it created reached Kafka. Getting there cost one genuine bug — see "The transaction
that was never there" below, which is the most important thing in this file right now.

Three items remain on the 2.11 gate: the regression-suite holes (now including a test
that can see a proxy boundary and the cross-provider `state` test), the revocation
decision plus the rest of the quality review, and the Dockerfile.

**GitHub SSO was removed on 2026-08-08**, not deferred — see "Removing GitHub SSO"
below. The replacement is 2.12, the device grant, which is new work and deliberately
**not** on the gate.

### Resuming the SSO flow specifically
1. `.env` holds real `GOOGLE_OAUTH_*` values and is gitignored — never committed, and
   verified absent from history.
2. Spring does not read `.env` (its own header says so). Export it, or use the
   IntelliJ EnvFile plugin. A loader script lives in the session scratchpad.
3. `../../mvnw spring-boot:run -Dspring-boot.run.profiles=sso`, then open
   `http://localhost:8081/api/v1/auth/sso/google` **in a browser** — the nonce cookie
   must be set in the browser that finishes the flow, so curl cannot drive it.
4. A successful sign-in ends at `http://localhost:4200/auth/callback`, which does not
   exist yet. "This site can't be reached" **is** the success case; the refresh cookie
   is set and the rows are written. Check the database, not the browser.
5. `MUGEN_SSO_CALLBACK_BASE_URL=http://localhost:8081` overrides the `:8080` gateway
   default in `application-sso.yml`. It must match the console registration exactly.
   Move it to `:8080` when the gateway exists, and re-register there.

## Status (paused 2026-08-03)
Phases 0, 1 and 2 done except four items on the **2.11 exit gate**. Earlier this day:
2.10 (Swagger / OpenAPI, springdoc 3.1.0) landed and **mugen-auth ran for the first
time** — see "First real run" below.

Since then, a quality pass over the three things mugen-auth would otherwise copy into
ten more services: a written comment standard and its application, `@PublicEndpoint` as
the single declaration of endpoint visibility, and API docs derived from javadoc. See
"Authn vs authz" and "Endpoint visibility" below.

**111 unit + 44 integration green (`./mvnw verify`), and the service was run against the
compose stack afterwards** — the derived security rules and the generated document were
both checked live, plus register/login/me/sessions/refresh/replay end to end.

**mugen-auth is proven working end to end against the compose stack**: registration
through to a `mugen.user.registered` message on a real Kafka broker, refresh
rotation, replay detection, Flyway against real SQL Server, Eureka registration.
That claim could not be made before this session and is the point of the gate.

### Resuming
Nothing is half-finished — the tree is clean and `./mvnw verify` is green. To pick up:

1. Start Docker Desktop first; the daemon is not running on boot, and Testcontainers
   and compose both need it. `docker info` answering is the check.
2. `docker compose up -d` (all 14 containers verified working; ~1 min to healthy)
3. `cd services/mugen-auth && ../../mvnw spring-boot:run`
4. http://localhost:8081/swagger-ui.html, or `http/auth.http`

`spring-boot:run` leaves a process holding port 8081 after the Maven run is killed — if a
restart fails with "Port 8081 was already in use", find it with
`Get-NetTCPConnection -LocalPort 8081 -State Listen` and stop that PID.

If the shared modules changed since the last run, `./mvnw install -DskipTests -pl
shared/mugen-shared,shared/mugen-web` first — running a service alone resolves them
from the local repo, and a stale jar shadows source changes silently.

### What is left on the 2.11 gate
Three items, in the order they are worth doing (updated 2026-08-08):

1. **Regression suite holes** — no controller tests for AuthController /
   SessionController, none for RevocationCacheService or AuthorizationRequestStore, none
   for GoogleProfileMapper. TokenIntrospectController and both SSO endpoints are now
   covered at the filter-chain level by `OpenApiTest`, but only for
   reachability, not behaviour. Two named holes join this: a test that can see a proxy
   boundary, and the cross-provider `state` test removed with GitHub.
2. **Decide the revocation question** below, then finish the quality review. The review
   is part-done: comments, endpoint visibility and API docs were covered (see "Endpoint
   visibility" below). **Untouched: library choices, layering, and the deploy story.**
3. **`Dockerfile` + `.dockerignore`**, following `eureka-server/` as the template.

Google SSO closed the fourth item on 2026-08-04. GitHub was removed rather than
finished — see "Removing GitHub SSO" below.

## The error contract, and what the document was promising (2026-08-16)
The `@ApiError`/`@Throws` derivation was already right — one declaration on the
exception class feeds the thrown response and the document, so neither can drift. What
it could not see was everything answered *outside* a handler, and that is where the
document was lying.

- **A refused request never reached `GlobalExceptionHandler`.** Spring Security's
  default entry point writes a bare 401: no `code`, no `traceId`, no log line — while
  the document promised all three and springdoc attached a `Problem` schema to it. The
  commonest failure in the system was the one with no problem document.
  `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` fix it by handing
  the exception to `handlerExceptionResolver` instead of serialising a second body, so
  the 401 comes back out of the same handler as everything else. **Both the
  `oauth2ResourceServer` and the `exceptionHandling` hooks have to be set**: a bad token
  is refused by the first, no token at all by the second, and wiring only one leaves the
  more common half empty. `@Lazy` on the resolver — the filter chain is built before MVC
  finishes, and asking eagerly closes a cycle.
- **Every framework 4xx claimed `VALIDATION_FAILED`.** 405, 415 and a malformed body all
  carried the one code whose published contract is an `errors[]` naming each bad field,
  which none of them have. A code exists to be branched on, so they now have their own.
- **Parameter validation was a 500.** In Boot 4 a constrained `@RequestParam` throws
  `HandlerMethodValidationException`, not `ConstraintViolationException` — the older
  advice everyone copies handles the wrong one.

Two things worth keeping in mind when extending this:

- **`@ApiError` cannot tell an omitted attribute from one written with its default
  value** — reflection has no way to. `code = INTERNAL_ERROR` on a subclass silently
  inherits the base's code. Pinned by a test rather than fixed, because it cannot be.
- **Failures on one endpoint merge by code**, so two exception classes sharing an
  `ErrorCode` publish only the first's explanation. `AuthExceptionsTest` fails on it;
  every later service wants the same test.

`/validate` gave up its deliberately-empty 401 in the same pass. It was the only
endpoint in the service answering 401 differently from the rest, which is the kind of
exception that has to be remembered — and a client wanting yes/no still branches on the
status without reading the body.

## The traceId that led nowhere (2026-08-21)
Found while exercising Swagger: a 401 answered with
`traceId=e7a7e6717e584b229a6bb213817e885f` and no log line in the run mentioned it.
The 2026-08-16 pass had given refusals a problem document; what it had not given them
was the half that makes the document useful.

Three causes, and only the first is the one anybody would guess:

- **Tracing was never auto-configured.** See "Boot 4 traps" — the pom had the
  libraries, not the module. Proved rather than assumed: a request carrying
  `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-…` came back with an unrelated id.
- **The id was minted after the line was written.** `handleAppException` logged, then
  returned `problem(...)`, and `problem` is what calls `getOrCreate()`. On the
  fallback path the value in the body did not exist yet at the moment of the log call,
  so no log pattern could have printed it. `handleExceptionInternal` had the same
  shape.
- **`TraceIdHolder.clear()` had no caller**, against its own javadoc. Nothing leaked
  only because `spring.threads.virtual.enabled` gives a thread per request; a service
  copying this without virtual threads would have served one request's id to the next.

The fix keeps the guarantee independent of the tracing dependency, because ten more
services will copy this and one of them will forget it: `TraceIdFilter` in mugen-web
puts an id in the MDC before anything can log, ordered at `HIGHEST_PRECEDENCE + 2` —
inside Boot's observation filter at `+ 1` so a real trace id always wins, outside the
security chain at `-100` so a refused request still has one. It clears only what it
minted; an id belonging to a tracer is that tracer's to end. The handler logs
`traceId={}` explicitly rather than relying on `logging.pattern.correlation`, which
exists only where tracing is configured.

`GlobalExceptionHandlerTest.theLoggedTraceIdIsTheOneTheCallerIsGiven` is the invariant
worth keeping: it reads the id out of the response body and requires a log line
containing it, on all five paths. It fails on the bug rather than on a rename.

## Removing GitHub SSO, and what replaces it (2026-08-08)
GitHub was a second **authorization-code** provider. Same redirect, same PKCE, same
`state`, same callback — only the user-info JSON differed. It exercised
`OAuthProfileMapper` and nothing else, so finishing it would have proven the seam
that is already proven and taught nothing new. Removed rather than left half-done.

Replaced by **2.12, the device authorization grant**, which varies the *grant* rather
than the response shape. That is the part `OAuthClientRegistry` cannot absorb.

**What the removal cost, and it is not free:**
- `OAuthProvider` now has one value. `OAuthServiceTest.rejectsCrossProviderState`
  could not survive that — there is no second provider to mint a state for. The check
  in `OAuthService.complete` is untouched and now **unguarded**; it is on the 2.11
  gate to restore, not written off.
- `spring-boot-restclient` has no injector left. Kept deliberately for 2.12; if that
  slips, it is dead weight and the pom comment says so.
- The migrations still say `GOOGLE | GITHUB` in comments and were **deliberately not
  edited**. Flyway checksums applied migrations, so changing even a comment fails
  validation against every database that already ran V1 and V3. A wrong comment is
  cheaper than a repair migration; correct it in a later migration if it ever matters.

## SSO flow shapes — why the device grant (2026-08-08)
The axis that matters is **which channel carries the handshake**, not which company
is on the other end.

- **Front-channel** (redirect): OIDC authorization code, SAML, CAS. The browser is
  the courier, so there is a `redirect_uri`, and login CSRF is the threat. This is
  what mugen has.
- **Back-channel** (no redirect): OIDC CIBA, the device grant, Kerberos, mTLS. The
  server talks to the provider; the user approves somewhere else entirely; the client
  polls or is called back.

**Nafath is back-channel + separate-device + poll** — you POST a national id, the
user's phone buzzes, they tap a number your screen is showing, you poll for the
result. That two-digit number is channel binding: it proves the person tapping is the
person watching. BankID and MitID are the same shape.

**None of them are obtainable.** Nafath needs a Saudi commercial registration and a
compliance review; BankID needs a Nordic bank relationship. There is no developer
tier for national identity, which is correct of them and fatal for us.

The device grant is the same architecture with credentials Google hands out for free:
no redirect, separate device, transaction state with a TTL, polling, expiry, and
`slow_down` backpressure. The one difference is that the user types a code rather
than receiving a push — a UX difference, not a backend one.

Rejected: a mock Nafath module. This project's whole 2.11 gate exists because
stubbed providers hid a `@Transactional` that had never applied. A mock agrees with
whatever we assumed, and being surprised is the point.

## The test library, second pass (2026-08-11)
The first pass gave mugen-auth its own `AuthContainers`, `@AuthIntegrationTest` and
`@AuthSliceTest`. That is duplication with an extra step: eleven services would each
re-derive the same three files, which is what a shared module was supposed to prevent.
Rewritten so **a service declares nothing** — the three annotations are used as they are.

Four things were established by trying them, and the failures are the useful part:

- **Container beans must be user configuration, not auto-configuration.** An
  `@AutoConfiguration` looked right — it is the one ordering where
  `@ConditionalOnMissingBean` is reliable — and it silently does not work.
  Auto-configurations register after ordinary configuration, so
  `DataSourceAutoConfiguration` had already resolved `spring.datasource.url` to the
  service's real `localhost:1433` and Flyway failed against a database nobody started.
  `MugenContainers` is an `@Import` on `@IntegrationTest`; the escape hatch is a
  property, not bean ordering.
- **A container is selected by the technology, not the Testcontainers module.** The
  condition requires the driver too. Without that second half, every service that merely
  has the jar in reach boots the database — mugen-test's own tests would have started
  SQL Server, Postgres and Mongo to test themselves.
- **A mock `JwtDecoder` has to be `@MockitoBean`, not a `@Bean`.** A bean *definition* is
  visible to `@ConditionalOnBean`, so declaring one activates
  `OAuth2ResourceServerWebSecurityAutoConfiguration`, which then wants an `HttpSecurity`
  a `@WebMvcTest` slice does not have, and the context fails to start. A bean *override*
  is applied after auto-configuration has been evaluated.
- **Fixtures need no import.** `@Fixture` is a `@Component` stereotype, and a service's
  test-source fixtures sit under its base package, so `@SpringBootTest`'s component scan
  finds them. Slices are unaffected (`@WebMvcTest` scans controllers only) and nothing
  reaches production (test classes are not packaged).

**mugen-test tests itself**, against Postgres because it is the fastest real database to
start. That is what lets the library be proven without a service: the self-test asserts
that `@IntegrationTest` alone yields a working datasource, and that no container starts
for a technology the application does not use.

Per-service configuration now lives in `src/test/resources/application-test.yml`
(`mugen.outbox.enabled: false` for auth). One file, so still one cached context.

## Test tiers (2026-08-11)
The rule is one sentence — **a test belongs to the lowest tier that can see the failure
it is written for** — and the tiers exist so that "lowest" has an answer. The tier is
the directory (`unit/`, `slice/`, `integration/`), and the directory is what selects
surefire or failsafe, so the two cannot drift apart by forgetting a rename.

What is worth knowing beyond CLAUDE.md's summary:

- **The container count was a context-cache problem, not a Testcontainers one.** Four
  test classes each had a `@SpringBootTest(properties = ...)` that differed slightly —
  one excluded Redis autoconfiguration, another did not — and Spring keys its context
  cache on exactly that. Four keys, four contexts, four SQL Servers. Making every
  integration test carry one identical annotation is the whole fix; the containers
  being `@Bean`s in the cached context follows. This is why `@IntegrationTest` says not
  to add `properties` to an individual class: it silently costs a database.
- **One shared database has a price, and it is real.** `AuthFlowTest` cannot be
  `@Transactional` — it asserts on a committed revocation — so its rows outlive it.
  Per-class containers had been hiding that; six assertions phrased as totals
  (`users.count()).isZero()`) failed the moment the container was shared. `DatabaseCleaner`
  empties the schema before each test class, which restores exactly the isolation the
  old layout gave by accident. Per class, not per test: per test would land inside a
  transaction `@Transactional` had already opened.
- **`@ServiceConnection`'s factories are split per technology in Boot 4** — JDBC in
  `spring-boot-jdbc`, Redis in `spring-boot-data-redis`, none of them in
  `spring-boot-testcontainers`. Fourth instance of the same Boot 4 trap; a service gets
  the factory only because it already depends on the technology.
- **A composed annotation carries `@MockitoBean` and stacked `@Import`s correctly.**
  `@AuthSliceTest` supplies the `JwtDecoder` mock every slice in this service needed,
  and `@AuthIntegrationTest`'s `@Import` merges with the one on `@IntegrationTest`
  rather than replacing it. Both verified by running, not assumed.
- **Package-private members are unreachable from a tier folder.** `OutboxEvent.backoffFor`
  had to become public to keep its overflow test. One case is a fair price; a second
  would mean the tier layout is wrong for this project, so count them.

## The transaction that was never there (2026-08-04)
The first real Google sign-in answered 500 with
`IllegalTransactionStateException: No existing transaction found for transaction
marked with propagation 'mandatory'`. The cause is Spring's oldest trap, and it had
been sitting in the code since the SSO flow was written.

- `@Transactional` is not compiled into the method; the **proxy** around the bean
  opens the transaction. So it only applies to calls arriving from outside the bean.
- `SsoController` → `complete` goes through the proxy, but `complete` is deliberately
  not transactional: it makes two provider network calls, and holding a connection
  across them exhausts the pool.
- `complete` → `linkOrCreate` is a plain `this` call on the same bean. The proxy is
  bypassed, so `linkOrCreate`'s `@Transactional` **had never once taken effect.**
- `linkOrCreate` → `userEventPublisher.userRegistered` crosses into a *different*
  bean, so that proxy does apply, and its `MANDATORY` correctly refused to write an
  outbox row with no transaction around it.

**The 500 was the good outcome. The silent damage was the point.** With no
transaction, `users.saveAndFlush` and `oauthLinks.saveAndFlush` each committed
separately, so the failed attempt left a real linked account with no
`mugen.user.registered` event — no profile in mugen-user, forever. Exactly the dual
write the outbox exists to remove, reintroduced by an annotation that looked right.
This also widens the orphan-row gap recorded under "Open gaps": it was filed as a
race needing two simultaneous sign-ins, but any failure between the two saves does it.

**Fix:** `complete` opens the transaction with a `TransactionTemplate` at the call
site, which needs no proxy and keeps the network calls outside it. `linkOrCreate`
moved to `MANDATORY`, the same rail the publisher already had. Note what that
annotation does and does not do — on the self-call path it is still not evaluated, so
it guards external callers; the template is what actually supplies the transaction.

**Why 23 passing tests missed it:** `OAuthServiceTest` builds the service with `new
OAuthService(...)`. No context, no proxy, so the annotation was never in play, and a
mocked publisher does not care about transactions. The lesson generalises past this
bug: **a unit test cannot see a proxy boundary, so it cannot see `@Transactional`,
`@Async`, `@Cacheable` or `@PreAuthorize` failing to apply.** Every one of those is a
silent no-op under self-invocation. Ten services will use them.

## First real run (2026-08-03) — what it caught
`docker compose up -d` and `spring-boot:run`, both for the first time. Infra came up
clean: 14 containers healthy, all three init one-shots exit 0, `mugen_auth` created,
seven topics, four buckets. The service found two things no test could.

- **`KafkaTemplate` bean missing — the service would not start at all.** The Boot 4
  autoconfiguration split again, third instance: `spring-kafka` gives you the class,
  `spring-boot-kafka` gives you `KafkaAutoConfiguration`. Fixed by adding the module.
  Worth understanding why the suite was blind to it: `OutboxPollerTest` mocks the
  template, and every integration test sets `mugen.outbox.enabled=false` because it
  has no broker — so nothing ever asked the context for the real bean. **Any service
  that publishes to Kafka needs `spring-boot-kafka`.**
- **A real email address in a log line.** `GlobalExceptionHandler` logged
  `ex.getMessage()` on 4xx, and `EmailAlreadyRegistered` formats the address into its
  message, so a duplicate registration wrote `mugenuser@mugen.dev` into the log. The
  message is right for the response — the caller typed it — and permanent in Loki.
  Now logged as `errorCode` + `path`, matching the rule the same class already
  applied to framework 4xx, and guarded by a test. The 2026-08-02 naming pass fixed
  direct log statements and missed this indirect path entirely.

Verified working end to end: register/login/refresh/logout, `/me`, `/validate`,
sessions list and revoke, rotation, replay detection, validation `errors[]`, the
405/415/400 framework paths each leaving a log line, Flyway's 4 migrations against
real SQL Server, Eureka registration (204), and **the outbox all the way to Kafka** —
`published_at` set on the first attempt, and the message's `eventId` equal to the
table's row id, which is what a consumer deduplicates on.

**Open finding, not yet decided: revocation is not enforced by mugen-auth itself.**
After a replay revoked the session, `/validate` correctly answered 401 but
`/sessions` still answered 200 — the resource server trusts the signature, and only
`/validate` consults Redis. That is the documented design (the gateway does the
check, auth stays zero-DB-calls), and behind the gateway it is airtight. But it means
a revoked session can still list and revoke sessions for up to the access token's TTL
on any path that reaches mugen-auth directly. Decide at the 2.11 quality review
whether the gateway alone is enough.

Two Windows notes: `docker exec` with container paths must be run from PowerShell —
MSYS rewrites `/opt/...` into `C:/Program Files/Git/opt/...` and the exec fails
(`MSYS_NO_PATHCONV=1` also works). And the refresh cookie is `Secure`, which browsers
accept on localhost but curl does not, so curl must replay the cookie by hand rather
than use a cookie jar.

## Toolchain on this machine
- JDK 21: `C:\Users\youse\.jdks\ms-21.0.12` (Microsoft OpenJDK, via IntelliJ)
- No `java` or `mvn` on PATH — use `.\mvnw.cmd` with `JAVA_HOME` set to the above.
- Build shared modules before testing a service alone: `-pl services/mugen-auth`
  resolves `mugen-shared` from the local repo, so a stale installed jar silently
  shadows source changes. Use `-am`, or `install` the shared modules first.

## Open gaps
Everything untested about mugen-auth is tracked as the **2.11 exit gate** in
tasks.md, which Phase 3 must not start before. The first three items are now closed
— the service runs, the compose stack works, and an event has reached a real broker.
What is left is SSO against a real provider, the named regression-suite holes, the
quality review (including the revocation question above) and the Dockerfile.

Cheapest first SSO check, short of registering real apps: run with
`-Dspring-boot.run.profiles=sso` and dummy credentials, then confirm
`GET /api/v1/auth/sso/google` 302s to Google carrying the right `client_id`,
`state`, `code_challenge` and `redirect_uri`. That proves everything up to the
point Google's own credential check takes over.

Not on the gate, deferred by choice:
- Nothing ships logs to Loki. Grafana has the datasource but no writer; services
  need a Loki appender (loki-logback-appender) when logging is set up.
- **Orphan user row in `OAuthService.linkOrCreate`** (pre-existing, spotted while
  wiring 2.8). Narrower now that a transaction actually wraps the two writes — before
  2026-08-04 there was none, so any failure between them orphaned a row, not just a
  race. Two first-ever sign-ins for the same provider account can both find
  no link and both create a user; the loser of the `oauth_links` race re-reads the
  winner and signs in as them, leaving its own freshly-created user row unreachable
  — no link, no password, and now deliberately no `mugen.user.registered` either,
  since a profile for an unreachable account is worse than none. Needs a
  narrow window and a matching provider account, so it is not urgent; the fix is
  to delete the losing row in the catch block.

## Up next
1. **2.11 exit gate — Phase 3 does not start until all of it is ticked.** Full list in
   tasks.md; four items remain, in the order given under "What is left on the 2.11 gate"
   above. Swagger UI makes the endpoint sweep cheap — `/swagger-ui.html` on port 8081
   drives everything except the two SSO redirects.
2. **2.13 — the test suite revision**, ideally before the gate's regression-suite item
   rather than after. Filling holes at the current standard multiplies restatement and
   hand-listed paths across ten more services; the reasoning and the named offenders
   are in tasks.md. 2.12 (device grant) is feature work and can follow the gateway.
3. Phase 3 — gateway. **Read "Authn vs authz" below before writing 3.2's SecurityConfig**:
   it must NOT hold a public-vs-protected route list, and the reasoning matters more than
   the rule. `@PublicEndpoint`, `PublicEndpointMatcher` and the OpenAPI customizers
   already live in `mugen-web`, so the gateway and every later service inherit them by
   adding the dependency.

## Phase 2 — OpenAPI design (2026-08-03)
- **springdoc 3.1.0, and the version line matters more than the number.** 3.x is the
  Boot 4 line; 2.x is Boot 3 / Framework 6 and does not work here. 3.1.0's own parent
  is `spring-boot-starter-parent:4.1.0` — this project's exact pin — so its Spring
  dependencies resolve to what the build already has, and the tree comes out with no
  conflicts. Confirmed by resolving it, not by reading a compatibility matrix.
- The BOM is imported in the root pom next to `testcontainers-bom`. The ui starter
  pulls the api starter, swagger-core and the swagger-ui webjar, and those four have
  to agree; a per-service version property would be four chances to drift, ten times
  over.
- **The document is built from the same properties the service runs on.** The cookie
  name and both token TTLs come from `RefreshCookieProperties` and `JwtProperties`,
  not from literals in an `@OpenAPIDefinition`. An annotation can only restate them,
  and a restatement goes stale silently — the docs would keep promising
  `mugen_refresh` long after a rename, with nothing to catch it.
- Two security schemes, because the service authenticates two ways. The bearer one
  is what makes Swagger UI's "Authorize" send an `Authorization` header. The refresh
  cookie is declared as an apiKey-in-cookie scheme even though **the UI cannot drive
  it** — it is `HttpOnly`, so no script can set it and browsers reject `Cookie` as a
  fetch header. Declared anyway so `/refresh` and `/logout` do not read as endpoints
  that need no input at all.
- `@CookieValue` parameters are `@Parameter(hidden = true)`. springdoc renders the
  annotation's value, and the refresh cookie's name there is the literal
  `${mugen.auth.refresh-cookie.name}` — the placeholder Spring resolves at handler
  registration, not the resolved name. It would have been published as the cookie
  name.
- **No global security requirement.** It would mark `/login` and `/register` as
  needing a token — the two calls that exist to obtain one. Requirements are per
  class instead, and a test asserts both directions, because this failure is silent:
  the endpoints keep working and only the documentation lies.
- `paths-to-match: /api/v1/auth/**` keeps `/actuator` out. Operational surface is not
  API surface and nothing codes against it.
- **Exposure: on in dev, off in the `docker` profile, as two independent switches.**
  The document has a plausible consumer in a deployed environment (Phase 3's gateway
  aggregating per-service docs); the interactive UI has none, and is a login form
  that submits real credentials. Both are env-var overridable so turning them on is
  a deliberate per-environment act.

## Authn vs authz — who decides what (2026-08-03)
Settled before the gateway exists, because building it the other way would create the
duplication this decision avoids. **The gateway authenticates; the service authorizes.**

- **Authentication** — is this credential genuine, and whose is it? Signature, expiry,
  issuer, token type, and the Redis revocation check. Done at the gateway, and again in
  each service, which stays a resource server: a service must not depend on the gateway
  having been honest.
- **Authorization** — may this identity do *this*? Whether the endpoint needs a login at
  all, role checks, ownership checks. Service only.

The load-bearing observation: **"is this endpoint public?" is an authorization
question.** That is why the gateway never needs a public-endpoint list — it was never
its question, and answering it would require a map of all eleven services' endpoints.
A request with no token is forwarded with no identity and the service's default-deny
refuses it; a request with a *bad* token is rejected at the gateway, which needs no list.

Considered and rejected: a `/public-api` URL namespace, so both tiers could derive the
answer from the path. Three reasons. The refresh cookie is scoped `Path=/api/v1/auth`
deliberately, and `/refresh` and `/logout` are public endpoints that need it — a
separate namespace would force widening that scope. Visibility would become part of the
URL contract, so making something public later is a breaking change. And it fits badly
where one resource has mixed visibility, which is most of mugen-post, feed, search and
user. Prefix matching on raw paths is also where Spring Security bypasses live
(CVE-2024-38819, CVE-2025-41242); matching a resolved handler has no such bug class.

**Direction is not negotiable: default-deny.** Forget to mark an endpoint and it is
secured, which breaks loudly in development. The inverse fails open and leaks silently.

Still open, unchanged by this: whether mugen-auth should enforce revocation itself
(2.11). Separable, and deliberately not folded into a cleanup pass.

## Endpoint visibility, and docs from javadoc (2026-08-03)
Three copies of one fact became one. "This endpoint needs no token" was written in
`SecurityConfig`'s `PUBLIC_ENDPOINTS` array, in per-class `@SecurityRequirement`, and in
a test hand-listing paths to pin the two together. Now: `@PublicEndpoint` on the handler,
read by `PublicEndpointMatcher` to build the permit rules and by
`SecurityRequirementCustomizer` to mark the document.

- **Method-level, not class-level**, even on controllers whose whole surface is public
  today. Class-level would silently expose the next endpoint added to `AuthController`.
- **The matcher resolves on first request**, not in its constructor. It is injected into
  the `SecurityFilterChain` bean, and reading `RequestMappingHandlerMapping` there forces
  Spring MVC to initialise mid-security-configuration.
- The springdoc and actuator paths stay literal patterns — not our handlers, nothing to
  annotate.

**Javadoc is now the API documentation**, via springdoc's therapi integration. The prose
previously existed twice, verbatim: a javadoc block and an `@Operation(description=...)`
saying the same thing. A swagger annotation still wins where both exist, so the migration
was incremental.

- **Lombok had to be added to `annotationProcessorPaths`.** Declaring that element turns
  off processor discovery from the compile classpath, which is how Lombok ran until now —
  Boot's parent only sets `<parameters>true</parameters>`. Omitting it stops every
  `@RequiredArgsConstructor` generating, everywhere, at once.
- The therapi version is ours to pin (0.15.0): springdoc's BOM manages only springdoc's
  own artifacts, and an `annotationProcessorPath` needs a literal version anyway.
- **springdoc falls back silently when therapi is missing** — the document still
  generates, just with no prose. Hence the test asserting a javadoc sentence reaches it.
- The shared `Problem` response is registered once in `components` and referenced by
  `$ref`, replacing five-line `@Content`/`@Schema` blocks per operation. Described by
  hand rather than from `ProblemDetail.class`, because `code`, `traceId` and `errors` are
  set as dynamic properties and do not exist on the class — generating from the type
  documented a response nobody sends. Its `code` enum comes from `ErrorCode`.
- **Javadoc's hanging indent leaks into the document.** Swagger UI renders descriptions
  as Markdown, and the conventional alignment under an `@param` tag arrives verbatim —
  runs of 6-20 leading spaces, which Markdown may read as a code block. Continuation
  lines in any javadoc that reaches the document sit at one space. Only visible by
  querying `/v3/api-docs`; in source the indentation looks right.
- **Two `RequestMappingHandlerMapping` beans exist**, MVC's and actuator's, so
  `getBean(RequestMappingHandlerMapping.class)` throws. `PublicEndpointMatcher` iterates
  the `ObjectProvider` for exactly this reason.

**Verified live, after `./mvnw verify`:** public endpoints answer 400/204/404 without a
token and secured ones answer 401, purely from the annotation scan — no path list
anywhere. The document carries `bearerAuth` on exactly the secured operations,
`refreshCookie` on `/refresh` and `/logout`, and nothing on the four public ones. Summary,
description, `@param` and record-component javadoc all reach it. Register → me → sessions
→ refresh → replay behaves as before, replay still answering `SESSION_REPLAY_DETECTED`.

**On the comment pass:** density went 31% → 27%, which is a worse headline than the
result. Removing a comment line removes a total line too, so the ratio barely moves.
Blocks of 12 lines or more — the actual complaint — went 38 → 18, and most of the
remaining 18 are no longer commentary: they are Swagger operation descriptions and
`@param` lists that are now schema field documentation.

## Conventions — naming and logging (2026-08-02)
Both are written in CLAUDE.md and were applied across mugen-auth and mugen-web.
They live there rather than here because every service must follow them; what
belongs here is only why they exist.

- The trigger was `sessions` meaning `SessionService` in `AuthService` and
  `SessionRepository` in `SessionService` — one name, two things. Hence: repositories
  take the plural entity, so services must keep their suffix.
- Four unrelated `@ConfigurationProperties` types were all injected as `properties`.
- Two log lines carried PII or secrets: a registration email, and the SSO `state` in
  three places. Both removed — a log line is a Loki document, permanently.
- **Only two of the four exception-handler paths logged anything.** Validation
  failures and everything `ResponseEntityExceptionHandler` handles natively (405,
  415, malformed body, no handler) answered with a traceId that appeared in no log,
  so the id a user quotes to support led nowhere. Fixed by overriding
  `handleExceptionInternal`, and guarded by a test using a Logback `ListAppender`.
- 4xx framework exceptions are logged by type, never by message: Spring builds
  `HttpMessageNotReadableException`'s message from the body it could not parse,
  which here is a registration payload with a password in it.

## Phase 2 — Outbox design (2026-08-02)
- **Full outbox, not a direct publish.** The alternative was
  `@TransactionalEventListener(AFTER_COMMIT)`, which loses the event on a crash
  between commit and send. Considered and rejected: staging the event in Redis
  instead — Redis is a second system exactly as Kafka is, so it reinstates the dual
  write, and this Redis is stock `redis:7-alpine` (RDB only, no AOF), which can
  drop up to 60s of writes anyway.
- The row id **is** the payload's `eventId`. Costs a `Persistable` implementation,
  because Spring Data would otherwise see a non-null id and `merge` — a SELECT that
  always misses. Buys one identifier from the table through to the consumer that
  deduplicates on it.
- Rows are claimed with `WITH (UPDLOCK, READPAST, ROWLOCK)`. `READPAST` is the
  load-bearing one: without it two pollers serialise instead of sharing the work.
  Postgres spells all three `FOR UPDATE SKIP LOCKED`.
- The poller's transaction spans the Kafka round trip, which is normally wrong.
  It is defensible here because the locks it holds are contended only by other
  pollers — which `READPAST` skips — and never by registration, which only inserts.
  `send-timeout` and `max.block.ms` bound it regardless.
- Producer `value-serializer` is **StringSerializer**, not `JsonSerializer`: the
  payload is already JSON text, and JsonSerializer would encode it twice. Side
  benefit — no `__TypeId__` header, so consumers bind mugen-shared's record by
  configuration instead of inheriting mugen-auth's package names over the wire.
- A failing event retries forever at capped backoff and is **never discarded**;
  past `alert-after-attempts` it just logs at ERROR. The purge sweep only ever
  deletes rows the broker has acknowledged.
- Known limitation, harmless today: a batch is sent in parallel, so if event A
  fails and B succeeds for the *same* key, B is published first and A retries
  later. `mugen.user.registered` is one-per-user, so no key ever has two.

## Phase 2 — SSO design (2026-07-31)
- The authorization code flow is driven explicitly, not through `oauth2Login()`.
  Spring's login chain ends in an authenticated servlet session and mugen has none:
  a sign-in must end in the same token pair a password login produces, so both
  routes converge on `AuthService.issueTokens`. The protocol steps are still Spring
  Security's — no hand-written OAuth.
- **An unverified provider email is refused, never matched onto an account.** It is
  only a claim; honouring one would let anyone put a stranger's address on a
  throwaway provider account and take over — or pre-emptively squat — the matching
  mugen account. A *verified* address does link to an existing account, on the same
  reasoning a password reset relies on.
- `state` is single-use, held in Redis, and bound to the provider that issued it.
- The flow is also bound to the browser that started it, via a `SameSite=Lax` nonce
  cookie. `state` alone does not stop login CSRF: an attacker can start their own
  sign-in, obtain a genuine code+state, and lure a victim through the callback into
  the attacker's account. Lax not Strict, because the callback arrives as a
  cross-site top-level navigation, on which Strict is never sent.
- The callback sets only the refresh cookie and redirects. The access token never
  enters a URL, where it would reach browser history, `Referer` and every proxy log.
- Credentials live in `application-sso.yml` behind the `sso` profile. Boot rejects a
  blank client-id, so putting them in the default profile would stop mugen-auth
  booting on any machine without provider credentials. Profile off → no
  `ClientRegistrationRepository` → the /sso endpoints answer 404.

## Boot 4 traps (each bites once, then applies to all 9 remaining services)
Boot 4 split autoconfiguration into one module per technology. The library class
being on the classpath no longer means its autoconfiguration is:
- `spring-boot-flyway` — without it migrations silently never run.
- `spring-boot-kafka` — without it there is no `KafkaTemplate` bean, and any service
  with a component that injects one fails to start. `spring-kafka` alone is not
  enough; it carries the class, not the autoconfiguration.
- `spring-boot-restclient` — without it there is no `RestClient.Builder` bean at
  all, though `RestClient` sits in spring-web. Inject the bean rather than
  `RestClient.create()`: it carries the service's Jackson config, including
  `fail-on-unknown-properties: false`.
- `spring-boot-micrometer-tracing-opentelemetry` — the worst of the set, because
  nothing fails. `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`
  carry the libraries only, so with those alone there is no `Tracer`: nothing writes
  the `traceId` MDC key, an inbound `traceparent` is ignored, and Jaeger receives
  nothing while the service looks healthy. Found 2026-08-21 by noticing a
  ProblemDetail's `traceId` in no log line — every one ever issued was
  `TraceIdHolder`'s local fallback. This module also carries
  `LogCorrelationEnvironmentPostProcessor`, which is what sets
  `logging.pattern.correlation`, so its absence is also why no log line had a
  correlation field to begin with.
- `spring-boot-webmvc-test` — `@WebMvcTest` moved to
  `org.springframework.boot.webmvc.test.autoconfigure`, no longer in
  `spring-boot-starter-test`.
- `@WebMvcTest` applies only its own slice's autoconfigurations, so mugen-web's
  auto-configured `GlobalExceptionHandler` is absent and must be `@Import`ed.

Unrelated to Boot 4 but caught the same way: a bean with two constructors and no
`@Autowired` fails the *entire* context load, not just that bean.

Also worth remembering: Boot 4 ships Jackson 3, which moved
`write-dates-as-timestamps` off `SerializationFeature` — setting it fails startup.

## Key decisions
- Name: Mugen (無限). Package: `com.mugen.*`
- Spring Boot 4.1.x / Framework 7 / Jakarta EE 11 (Boot 3.x hit EOL 2026-06-30 —
  upgraded from CLAUDE.md's original 3.x pin). Spring Cloud 2025.1.x (Oakwood); the
  gateway uses `spring-cloud-gateway-server-webflux`, not the pre-4.x artifact.
- Error handling: RFC 9457, following the Dancan254/exception-handling reference.
  Deviation: the hierarchy and handler live in a shared `mugen-web` module and are
  auto-configured, not copied into all 11 services.
- Token: RS256 asymmetric, access in memory, refresh in HttpOnly cookie.
- JWT via Spring Security's JOSE support (Nimbus), not JJWT — one implementation
  signs in auth and verifies in the gateway.
- Sessions: `token_version` integer (not hash); replay = revoke the entire session.
- Files: presigned URLs always. Pagination: cursor always, never OFFSET.
- Video: multipart chunked upload → FFmpeg → HLS adaptive bitrate.
- Dev vs deploy: root compose.yml is **infra only**; the 11 services run on the host
  from IntelliJ. Each service owns a Dockerfile for deployment, never wired into the
  root compose. `eureka-server/` is a standalone module outside the reactor so its
  image builds without its siblings.
- Kafka runs KRaft, not ZooKeeper (removed in Kafka 4.0).
- Config Server at Phase 3.5, after the gateway: it touches every service's config
  bootstrapping, so it is cheaper once the multi-service shape exists. Secrets stay
  as env passthrough — the config repo holds non-secret config only, never
  `private.pem`.
