# Mugen — Build Tasks

## How to use
Tell Claude Code: "do the next unchecked task" and it will always
know exactly what to do. Never skip a task — each one is a dependency
for the next.

---

## PHASE 0 — Foundation

### 0.1 Root Maven multi-module setup
- [x] Create root pom.xml with all modules declared
- [x] Create .gitignore (Java, IntelliJ, keys, .env)
- [x] Create .env file with all environment variables
- [x] Create folder structure: services/, shared/, docs/, config/, http/

### 0.2 mugen-shared module
- [x] Maven module setup
- [x] Kafka event DTOs:
  UserRegisteredEvent, UserFollowedEvent,
  PostCreatedEvent, PostLikedEvent,
  VideoTranscodeJobEvent, VideoProgressEvent,
  PaymentCompletedEvent
- [x] ApiResponse<T> wrapper { data, error, traceId }
- [x] TraceIdHolder (MDC utility — get/set/clear traceId)
- [x] ErrorCode enum (USER_NOT_FOUND, TOKEN_EXPIRED, etc.)
- [x] JwtClaims record { userId, sessionId, roles }

---

## PHASE 1 — Infrastructure (Docker)

### 1.1 compose.yml
- [x] Kafka in KRaft mode, single node as combined broker+controller
      (official `apache/kafka` image; ZooKeeper dropped — deprecated by
      KIP-500 and removed in Kafka 4.0)
- [x] Redis (redis:7-alpine)
- [x] MongoDB (mongo:7)
- [x] SQL Server (mcr.microsoft.com/mssql/server:2022-latest)
- [x] Postgres (postgres:16-alpine)
- [x] MinIO (minio/minio)
- [x] Elasticsearch (elasticsearch:8.x)
- [x] Eureka server (standalone Spring Boot in Docker — new standalone `eureka-server/` Maven module + Dockerfile, not part of the mugen-parent reactor)
- [x] Jaeger (jaegertracing/all-in-one)
- [x] Prometheus (prom/prometheus)
- [x] Loki (grafana/loki)
- [x] Grafana (grafana/grafana) with datasources auto-configured
- [x] mugen-network bridge network for all containers
- [x] Health checks on every container
- [x] MinIO bucket init (create all 4 buckets on startup)
- [x] Kafka topic init (create all topics on startup)
- [x] (extra, needed for correctness) Postgres multi-db init script — POSTGRES_DB only creates one DB, mugen-payment needs its own
- [x] (extra, needed for correctness) SQL Server init one-shot — SQL Server has no POSTGRES_DB-style auto-create; Flyway (2.2) only manages schema inside an existing DB

### 1.2 config/ folder
- [x] config/prometheus/prometheus.yml (scrape all services)
- [x] config/grafana/datasources/datasources.yml (Jaeger+Prometheus+Loki)
- [x] config/loki/loki-config.yml
- [x] config/kafka/init-topics.sh (create all mugen.* topics)
- [x] config/postgres/init-multi-db.sh
- [x] config/sqlserver/init-db.sh

---

## PHASE 2 — Auth Service

### 2.1 mugen-auth setup
- [x] Maven module + dependencies (Spring Security, JJWT, Flyway, SQL Server driver)
- [x] application.yml (default = host-run dev) + application-docker.yml
      (only for when the service itself is containerised — see CLAUDE.md
      "Dev vs deploy")
- [x] Generate RS256 keypair → src/main/resources/keys/private.pem + public.pem
      (2048-bit PKCS#8; private.pem gitignored, public.pem committed for the
      gateway — see keys/README.md for regeneration)

### 2.2 Database layer
- [x] V1__create_users.sql (+ user_roles child table)
- [x] V2__create_sessions.sql (with token_version column)
- [x] V3__create_oauth_links.sql
- [x] User entity + UserRepository
- [x] Session entity + SessionRepository
- [x] OAuthLink entity + OAuthLinkRepository
- [x] (extra) SchemaTest — 9 tests proving the migrations and the JPA
      mappings agree against a real SQL Server via Testcontainers. This is what
      caught the two Boot 4 issues below.

### 2.3 Security config
- [x] JwtKeyConfig (RSA keypair + Nimbus JwtEncoder/JwtDecoder beans)
- [x] JwtProperties (validated @ConfigurationProperties for mugen.jwt.*)
- [x] PasswordConfig (delegating encoder, not bare Bcrypt — stores `{bcrypt}`
      prefix so the algorithm can be changed without a mass password reset)
- [x] SecurityConfig (stateless filter chain, public routes, roles-claim
      authority converter)
- **Decision:** JWT handling uses Spring Security's JOSE support (Nimbus), NOT
  JJWT as originally written above. One implementation signs in mugen-auth and
  verifies in mugen-gateway; /me and /validate become a plain resource server
  instead of a hand-written filter. Phase 3.3's JwtVerificationFilter is
  simplified accordingly.

### 2.4 Core services
- [x] JwtService (generateAccessToken, generateRefreshToken, parseRefreshToken)
- [x] SessionService (open, rotate, revokeOne, revokeById, revokeAllExcept)
- [x] AuthService (register, login, refresh, logout)
- [x] OAuthService (Google)
- **Decision:** the authorization code flow is driven explicitly, NOT via
  `oauth2Login()`. Spring's login chain ends in an authenticated servlet session;
  mugen has none, so a sign-in must end in the same token pair a password login
  produces — both routes now converge on `AuthService.issueTokens`. The protocol
  steps are still Spring Security's (token exchange, PKCE, user-info call).
- [x] (extra) OAuthProfileMapper per provider — providers agree on the handshake
      and almost nothing after it: where the account id lives, what the email
      field is called, whether verification is reported at all.
- **GitHub removed 2026-08-08.** It was a second authorization-code provider, so it
  exercised the profile-mapper seam and nothing else — the same flow, a different
  JSON shape. Replaced by 2.12, a genuinely different grant. See context.md,
  "Removing GitHub SSO".
- [x] (extra) AuthorizationRequestStore — single-use `state` in Redis. No HTTP
      session exists to hold it, and any instance behind the gateway may receive
      the callback for a flow another instance started.
- [x] (extra) TokenType + TokenTypeValidator — access and refresh tokens are
      otherwise indistinguishable to a verifier (same key, same issuer), so a
      refresh token would authenticate as a bearer credential

### 2.5 Redis revocation
- [x] RevocationCacheService (write on revoke, check, TTL = access-token TTL)

### 2.6 Exception handling
- [x] Exception hierarchy (AppException + ResourceNotFound / Conflict /
      BusinessRule / Forbidden / Unauthorized)
- [x] GlobalExceptionHandler (RFC 9457 ProblemDetail + traceId + errors[])
- [x] AuthExceptions — mugen-auth's typed failures
- [x] (fix, 2026-08-03) **`handleAppException` no longer logs `ex.getMessage()` on
      4xx.** The first live run wrote a real email address into the log, because
      `EmailAlreadyRegistered` formats the address into its message and the handler
      logged that message verbatim. Right in the response, permanent in Loki. Now
      logged as `errorCode` + `path` — the same rule the class already applied to
      framework 4xx one method below — and pinned by a `ListAppender` test.
- **Deviation:** these live in a new `shared/mugen-web` module and are
  auto-configured, NOT copied per service. Eleven copies of the same classes is
  eleven places for the error contract to drift. Services still get "one
  GlobalExceptionHandler per service" semantics — they can add their own advice
  or override the bean.

### 2.7 Controllers
- [x] AuthController (/register, /login, /refresh, /logout)
- [x] SsoController (/sso/{provider}, /sso/{provider}/callback)
- [x] (extra) SsoStateCookies — a `SameSite=Lax` nonce cookie binding the flow to
      the browser that started it. `state` alone does not stop login CSRF: an
      attacker can start their own sign-in, get a genuine code+state, and lure a
      victim through the callback into the attacker's account. Lax rather than
      Strict because the callback is a cross-site top-level navigation.
- [x] SessionController (/sessions GET, /sessions/{id} DELETE, /sessions DELETE)
- [x] TokenIntrospectController (/me, /validate)
- [x] (extra) RefreshTokenCookies + RefreshCookieProperties — HttpOnly, Secure,
      SameSite=Strict, Path=/api/v1/auth, defined in one place

### 2.8 Kafka
- [x] UserEventPublisher (publish mugen.user.registered after register)
- **Decision taken: the full outbox, here, not a direct publish.** CLAUDE.md
  requires it for any Kafka publish that must be atomic with a DB write, and this
  is one — a lost event leaves a user in mugen-auth with no profile in mugen-user,
  permanently. Nothing else gives atomicity: Kafka and Redis are both a second
  system, and a second system reintroduces the dual write. Phase 5 now reuses a
  pattern proven against a real database instead of inventing one.
- [x] (extra) V4__create_outbox_events.sql — `ISJSON` CHECK on the payload, and
      filtered indexes so polling costs the size of the backlog, not the table
- [x] (extra) OutboxEvent entity — assigned id, which is also the payload's
      `eventId`; implements `Persistable` so an assigned id still INSERTs
- [x] (extra) OutboxPoller (@Scheduled) — claims rows with
      `WITH (UPDLOCK, READPAST, ROWLOCK)` so two instances never publish the same
      event, exponential backoff, retention sweep
- [x] Both registration routes publish: `AuthService.register` and
      `OAuthService.linkOrCreate` when it creates an account
- **Delivery is at-least-once.** A send that succeeds and then fails to commit is
  re-sent, so every consumer must deduplicate on `eventId` — this is the
  requirement Phase 4.7's `UserRegisteredConsumerTest (idempotency)` is testing.

### 2.9 Tests
- [x] JwtServiceTest (sign, verify, tampered token, expired, wrong key, type confusion)
- [x] SessionServiceTest (rotation, replay attack detection)
- [x] AuthFlowTest (Testcontainers SQL Server + Redis) — full
      register/login/refresh/replay/logout flow
- [x] SchemaTest (Testcontainers SQL Server)
- [x] OAuthServiceTest (23 tests — PKCE, single-use and provider-bound state,
      browser-nonce binding, and the linking rules that decide whether a provider
      identity may be attached to an existing account)
- [x] (extra) SsoControllerTest (@WebMvcTest — redirect targets and the cookie
      attributes, which are the whole security model of the flow and are
      invisible from a service-level test)
- [x] (extra) OutboxEventTest, UserEventPublisherTest, OutboxPollerTest — backoff
      overflow, payload contract, and what one poison event does to its batch
- [x] (extra) OutboxTest (Testcontainers SQL Server) — the half that
      only exists against a real server: the ISJSON constraint and the native
      claim query, whose whole meaning is its table hints

### 2.10 Swagger / OpenAPI
Interactive API docs, so the service can be exercised from a browser instead of
by hand-writing requests. Set up here, in the first service, because whatever
shape it takes gets copied into the other ten.

- [x] **Check compatibility before anything else.** springdoc-openapi 2.x targets
      Boot 3 / Spring Framework 6; this project is on Boot 4.1 / Framework 7 and
      needs a release built for it. Confirm one exists and resolves from Maven
      Central before designing around it — the same verification the Boot 4.1.0
      and Spring Cloud 2025.1.2 pins got. If none exists yet, fall back to a
      hand-maintained `openapi.yaml` served by Swagger UI, and record the choice.
- **Result: springdoc 3.1.0, no fallback needed.** The 3.x line is the Boot 4 one
  (3.0.0 shipped for Boot 4.0.0); 3.1.0 declares
  `spring-boot-starter-parent:4.1.0` as its own parent — the exact version this
  project pins — so its Spring dependencies resolve to what the build already
  has. Verified by resolving it from Central, not by reading a compatibility
  table. Version is managed by importing `springdoc-openapi-bom` in the root pom,
  the same way `testcontainers-bom` is, so the other ten services never repeat it.
- [x] Dependency + `/swagger-ui.html` and `/v3/api-docs` reachable
- [x] Permit both in SecurityConfig — they sit behind the gateway, but they are
      unauthenticated by nature and must be listed deliberately, not by accident
- [x] Document the auth scheme so "Authorize" works: HTTP bearer, JWT format
- [x] Annotate the endpoints that are not self-explanatory — the SSO pair in
      particular (a browser redirect flow, not a JSON call) and the refresh
      cookie, which never appears in a request body
- [x] Decide whether it is exposed in deployed environments or dev-only
- **Decision: on in dev, off in the `docker` profile, as two separate switches.**
  `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` are distinct
  because wanting one is not wanting the other: the document is a machine-readable
  contract with an obvious later consumer (the gateway aggregating per-service
  docs, Phase 3), while the UI is an interactive form that submits real
  credentials and has no caller in production. Both are env-var overridable, so an
  environment turns them back on deliberately rather than by editing an image.
- [x] (extra) OpenApiConfig builds the document from `RefreshCookieProperties` and
      `JwtProperties` rather than from literals — the cookie name and the TTLs the
      docs promise are the ones the service actually uses, and cannot go stale
      behind a rename
- [x] (extra) OpenApiTest — the document is generated, is readable with
      no token, excludes `/actuator`, declares both security schemes, and marks
      exactly the token-protected operations. Guards the one pairing nothing else
      would catch: the springdoc paths permitted in SecurityConfig against the
      paths springdoc serves. Break either alone and the docs 401 — still working
      in every developer's browser, because they are already logged in.

### 2.11 Exit gate — Phase 3 does not start until every box here is ticked
Not a checklist of nice-to-haves. mugen-auth is the template the other ten
services get built from, so anything wrong here gets copied ten times.

- [x] **Actually run the service.** `docker compose up -d`, then
      `cd services/mugen-auth && ../../mvnw spring-boot:run`. Passing tests are
      not the same claim: Testcontainers starts its own SQL Server and Redis and
      tears them down, so as of the pause nothing has ever run against the
      compose stack, and `docker compose up` has never been executed at all.
      This is the first real test of the KRaft broker, the init scripts, Eureka
      registration and the host-vs-container address split in `.env`.
- **Done 2026-08-03, and it earned its place on this list.** Infra came up clean —
  14 containers healthy, three init one-shots exit 0, database/topics/buckets all
  created. The service did not: no `KafkaTemplate` bean, because Boot 4 ships
  `KafkaAutoConfiguration` in `spring-boot-kafka`, not `spring-kafka`. The suite
  could not have caught it — the poller test mocks the template and every
  integration test disables the outbox. Flyway applied 4 migrations against real
  SQL Server; Eureka registration returned 204. See context.md for the full list.
- [x] **Exercise every endpoint against the running service** — register, login,
      refresh, logout, sessions list/revoke, /me, /validate. `http/auth.http`
      (Phase 11.1) is the natural artifact; pull it forward to here.
- **`http/auth.http` written and the sweep run by hand.** All of the above plus the
  401/405/415/400 paths, validation `errors[]`, duplicate-email 409, rotation and
  replay detection. Replay behaved exactly as designed: 401
  `SESSION_REPLAY_DETECTED` and the session revoked. Caught a second bug on the way
  — a real email address in a log line, see 2.6 below.
- [x] **Watch one event go all the way to Kafka.** Register a user, then confirm
      the row in `outbox_events` gets `published_at` set and the message lands on
      `mugen.user.registered` (`kafka-console-consumer`). Every outbox test mocks
      the broker, so the poller has never spoken to a real one — this is also the
      first check that the payload is readable to a consumer that is not us.
- **Worked first attempt.** `published_at` set with `attempts=0`, and the payload's
  `eventId` is byte-for-byte the table's row id — the assigned-id design doing the
  job it was chosen for. Payload is plain JSON with no `__TypeId__` header, so a
  consumer binds mugen-shared's record by configuration as intended.
- [x] **Decide whether mugen-auth should enforce revocation itself.** Found while
      exercising replay: with the session revoked, `/validate` answers 401 but
      `/sessions` still answers 200, because the resource server only verifies the
      signature and only `/validate` consults Redis. That is the documented design
      — the gateway does the check and auth stays free of per-request lookups — and
      behind the gateway it is airtight. The question is whether "the gateway is the
      only entry point" is a strong enough guarantee to rest a revocation on.
- [x] **Run the SSO flow against a real Google app.** It had never touched a
      provider: token exchange and user-info are stubbed in every test and the `sso`
      profile had never been activated, so `application-sso.yml` had not been parsed
      once. Needs real client credentials and the callback URL registered in the
      console.
- **Done 2026-08-04, and it found the bug the whole gate exists for.** A real sign-in
  500ed on `IllegalTransactionStateException`: `OAuthService.complete` called
  `linkOrCreate` on its own bean, so the proxy was bypassed and its `@Transactional`
  had never once applied. See context.md, "The transaction that was never there".
  After the fix, verified end to end: user + `oauth_links` + `outbox_events` rows all
  committing together, then `mugen.user.registered` on the real broker with
  `published_at` set on the first attempt and `eventId` equal to the row id.
- [x] **Regression suite complete and green** — `./mvnw verify` with the gaps
      closed: AuthController and SessionController have no controller-level tests,
      TokenIntrospectController is untested, RevocationCacheService and the
      Redis-backed AuthorizationRequestStore have no tests of their own, and
      GoogleProfileMapper is untested.
- **Before closing this, read 2.13.** Some of what is already green is restatement or
  hand-listed paths, and filling these holes the same way multiplies the problem.
  Deciding the bar first is cheaper than writing tests twice.
- [x] **Restore the cross-provider `state` test.** Deleted with GitHub 2026-08-08:
      `OAuthProvider` has one value, so no second provider exists to mint a state for.
      The check in `OAuthService.complete` is still there and still unguarded — this
      is a real hole, not bookkeeping.
- [x] **A test that can see a proxy boundary.** Added 2026-08-04: nothing in the suite
      can, which is why a `@Transactional` that never applied survived 23 passing tests.
      Needs a Spring context, not `new OAuthService(...)` — assert that a first SSO
      sign-in leaves the user, link and outbox rows all present, and that nothing is
      left behind when the outbox insert fails.
- [x] **Quality review pass over the whole service** — library choices, layering,
      error handling, DevEx, deploy story. Fix findings, do not defer them.
- **Partly done 2026-08-03**, on the three things that would have been copied ten
  times. Comments: a written standard in CLAUDE.md, then applied — blocks of 12+
  lines down from 38 to 18. Endpoint visibility: `@PublicEndpoint` on the handler is
  now the only declaration, replacing the `PUBLIC_ENDPOINTS` array, the per-class
  `@SecurityRequirement` and the test that hand-listed paths to pin them together.
  API docs: javadoc is the source, via springdoc's therapi integration, so an
  endpoint is documented once instead of twice. Also settled the authn/authz split
  Phase 3 needs — gateway authenticates, service authorizes, no public-endpoint list
  at the edge (context.md). `./mvnw verify` green (111 unit + 44 integration) and the
  service re-run against the compose stack to check the derived security rules and the
  generated document live. Two things only the real run found: javadoc's hanging indent
  reaching the document as would-be Markdown code blocks, and there being two
  `RequestMappingHandlerMapping` beans rather than one.
- **Correctness review 2026-09-01**, a multi-agent pass over the whole branch before the
  merge to main. Five findings, all fixed and each pinned by a test where one could see
  it: the register password bounded by UTF-8 bytes rather than characters (BCrypt's real
  72-byte limit, so an oversized multi-byte password is a 400 and not a 500 or a silent
  truncation); a 500 now logs the same traceId it returns, which with no active span it
  did not; one predicate — `Session.isReplayOf` — now decides refresh-token replay
  instead of a `!=` inlined beside it; the SSO `state` hash and its TTL written in one
  MULTI/EXEC so a crash between them cannot leave a state that never expires; and a stale
  `TraceIdHolder` javadoc link. `./mvnw verify` green.
- **Was still open, now done (2026-09-05):** library choices, layering and the deploy story —
  see the gate note below. The passes before it covered correctness and the error/security
  contract, and comments, endpoint visibility and API docs.
- [x] mugen-auth `Dockerfile` + `.dockerignore`, following `eureka-server/` as the
      template (layered jar, non-root, MaxRAMPercentage). Every service owes one
      and this is the first.

---

**Gate closed 2026-09-05.** Every box above is ticked; `./mvnw verify` green at 107
unit + slice and 59 integration, and the image was built and run against the compose
stack. What the last pass added, beyond the checkboxes:

- **Revocation is enforced by the service itself** — `RevokedSessionFilter`, one Redis
  lookup after the token is verified. Reasoning and the argument against it in
  context.md, "Revocation: mugen-auth checks for itself".
- **Two real bugs, both found by writing the missing tests**: the lost-link-race
  recovery in `OAuthService.linkOrCreate` could never have run (context.md, "The
  recovery that could not run"), and `AuthorizationRequestStore.consume` answered 500
  for a state naming a provider the deployment no longer has.
- **Library and layering findings**: mugen-shared put Jackson 2 databind on all eleven
  classpaths for one annotation — now `jackson-annotations` only, with the contract
  test moved onto the Jackson 3 mapper services actually serialize with. `CurrentUser`
  moved out of `controller` into `token`, since the security filter reads it too.
- **Still deliberately open**: `spring-boot-restclient` has no injector until 2.12 (the
  pom says so), and the DevEx half of the review — `http/auth.http` has not been
  extended with the endpoints added since.

---

### 2.12 Device authorization grant (RFC 8628)
A second sign-in route with **no front-channel redirect at all**: the backend asks
Google for a code, the user approves on a different device, and this service polls
for the result. Chosen over a second authorization-code provider because that only
varies the profile mapper, while this varies the *grant* — which is what forces the
provider abstraction to be real.

Modelled on Nafath / BankID, which are back-channel + separate-device + poll. Those
need a national commercial registration and cannot be obtained for a personal
project; the device grant is the same architecture with credentials anyone can get.
See context.md, "SSO flow shapes".

**Not on the 2.11 gate.** The gate makes mugen-auth a safe template; this is a
feature. Decide deliberately whether Phase 3 waits for it.

- [ ] Google Cloud console: a **second** OAuth client of type *TVs and Limited Input
      devices*. The web client cannot be reused — Google serves `/device/code` only
      to that client type. It has no client secret, which is the point: a device
      client cannot keep one.
- [ ] `DeviceGrantProperties` — client id, poll interval floor, expiry. Behind the
      same `sso` profile, for the same booting reason as `application-sso.yml`.
- [ ] **`SsoProvider` abstraction above both flows.** `OAuthClientRegistry` and
      `ClientRegistration` are authorization-code machinery — `redirect-uri`, PKCE,
      an authorization endpoint — and none of it applies here. Do not bend one into
      the other; find the seam they actually share, which is `OAuthUserProfile` in
      and `AuthService.issueTokens` out.
- [ ] `DeviceAuthorizationService` — start (POST `/device/code`), poll (POST `/token`
      with `grant_type=urn:ietf:params:oauth:grant-type:device_code`), then the same
      `linkOrCreate` as the redirect flow
- [ ] Redis-backed `DeviceAuthorizationStore` — `device_code`, status and expiry,
      TTL matching Google's `expires_in`. Sibling of `AuthorizationRequestStore`,
      but multi-read rather than single-use, which is the whole difference
- [ ] Handle all four provider states: `authorization_pending`, `slow_down`,
      `access_denied`, `expired_token`. **`slow_down` is not optional** — ignoring it
      gets the client throttled, and it is the one branch a stub never teaches you
- [ ] `DeviceAuthController` — `POST /sso/device/start`, `GET /sso/device/poll`.
      Both `@PublicEndpoint`. **The poll endpoint needs its own rate limit**: it is
      unauthenticated, it is called in a loop by design, and the client controls how
      fast
- [ ] Links to the **same** `OAuthProvider.GOOGLE`. Same account, same `sub`, so a
      person who signs in both ways must land on one mugen user — a `GOOGLE_DEVICE`
      enum value would give them two links to one Google account
- [ ] Tests: expiry, denial, `slow_down` backoff, a poll for an unknown device code,
      and that a completed code cannot be polled twice for a second token pair
- [ ] **Run it against the real Google device endpoint.** Same rule that caught the
      `@Transactional` bug — a stubbed provider agrees with whatever you assumed

---

### 2.13 Test suite revision — decide what is worth testing
Noticed 2026-08-08, before the suite gets copied into ten more services. The problem
is not coverage, it is that **some tests assert things that were never claims**, and
those break on harmless edits while catching no defect. A suite that cries wolf on a
rename gets ignored on the one day it is right.

#### Structure — done 2026-08-11
The tiers and the shared wiring, with no test deleted and the counts unchanged
(110 unit + slice, 44 integration, `./mvnw verify` green in ~45s).

- [x] **`shared/mugen-test`** — the three tiers as three annotations, plus what each
      needs. A service declares it once at test scope instead of six test dependencies;
      mugen-auth's pom lost five. Tier definitions live in its `package-info.java`.
- [x] **Tier is the directory**, and the directory picks the runner: `unit/` and
      `slice/` to surefire, `integration/` to failsafe, by path rather than class-name
      suffix so the two cannot disagree. `*IntegrationTest` suffixes dropped
      accordingly — `AuthFlowIntegrationTest` is now `integration/AuthFlowTest`.
- [x] **One context and one container for the whole run, not four.** Every integration
      test uses `@AuthIntegrationTest` unmodified, so Spring caches a single context and
      the containers are beans in it. `verify` started 4 SQL Servers before this and
      starts 1 now — the 40-container problem below, closed before it arrived.
- [x] **`@ServiceConnection` replaces the `DynamicPropertyRegistrar` blocks**, four
      copies of them. Note the Boot 4 split: the factories are in `spring-boot-jdbc` and
      `spring-boot-data-redis`, not in `spring-boot-testcontainers`.
- [x] **`AuthFixtures`** — a registered, signed-in user in one line, replacing five
      private helpers that lived inside `AuthFlowTest` where nothing else could use them.
      It reads the refresh cookie by the configured name rather than a literal.
- [x] **`DatabaseCleaner`** — the cost of one shared database, paid once. A class that
      deliberately commits (`AuthFlowTest` must) used to be isolated by having its own
      container; now the database is emptied before each test class instead. Six
      assertions phrased as totals failed exactly once and are unchanged.
- [x] A `Testing` section in CLAUDE.md covering the tier rule and the container
      reasoning. **The bar for what a test must earn is not written yet** — that is the
      pruning pass below, and it is still the deliverable this item is named for.
- One production change: `OutboxEvent.backoffFor` went package-private → public,
  because the unit tier cannot reach a package-private member from `unit/`. It is a
  pure, documented function and its overflow test is worth keeping. **Revisit if a
  second case appears** — two would mean the tier layout is wrong, not the modifier.

**The distinction to settle first.** A test earns its place by failing when behaviour
breaks. Three failure modes to name and then hunt:

- **Restatement** — asserting a literal that was copied from the source. Change the
  source, change the test, learn nothing. The test cannot fail for a reason that
  matters, because it has no independent idea of what is right.
- **Brittle** — asserting an incidental detail (a path string, a sentence of prose, a
  title) so a safe change goes red.
- **Hand-listed** — enumerating what exists today. It cannot fail when something is
  *added*, which is exactly when the rule needs enforcing.

Against: an **invariant** test, which states the rule and covers cases not written
yet. `OpenApiTest.documentAgreesWithTheFilterChain` is the model — "an
operation advertises `bearerAuth` exactly when its handler is not `@PublicEndpoint`"
holds for every endpoint mugen will ever have.

**Concrete offenders found while looking, all in `OpenApiTest`:**
- [x] `documentsThePublicApi` hand-lists 8 paths. Delete or invert it — if springdoc
      found no controllers, four other tests in the file already fail.
- [x] `apiDocsArePublic` asserts `info.title == "Mugen Auth API"`. Pure restatement of
      `OpenApiConfig`. The reachability half of it is the part worth keeping.
- [x] `javadocBecomesTheDescription` asserts three English phrases from
      `AuthController`'s javadoc. **Rewording a comment fails the build.** The real
      claim — therapi is wired at all — is structural: some operation has a non-empty
      description.
- [x] `declaresBearerScheme` and `declaresRefreshCookieScheme` restate `OpenApiConfig`
      literals. The cookie one has a genuine claim underneath (the name is *derived*
      from `RefreshCookieProperties`, not hardcoded) — so assert it equals the
      injected property, which fails on the bug it was written for and not on a rename.
- [x] `declaresTheSharedProblemResponse` mixes a good invariant (every `ErrorCode`
      reaches the document) with a hardcoded `/register` + 409. Keep the first half.
- [x] `publicEndpointsAreReachableAndTheRestAreNot` hand-lists 8 paths **in the same
      file** whose other javadoc explains why a hand-written list cannot cover
      endpoints that do not exist yet. Derive the list from the same annotation scan.

**Wider, and the reason this is its own item:**
- [x] **43 hardcoded `/api/v1/auth/...` literals** across `AuthFlowTest`,
      `OpenApiTest` and `SsoControllerTest`. A path change is a 43-line
      edit today. Decide whether that is fine (paths are a public contract, and
      pinning them is arguably the point) or whether it wants constants — but decide
      it, rather than inheriting it ten more times.
- [x] **Four `@SpringBootTest` + Testcontainers classes, each starting its own SQL
      Server.** Closed 2026-08-11 by the structure pass above: one shared annotation,
      one cached context, one container. `OpenApiTest` still needs a database only
      because the context wants a datasource — but it no longer costs a container.
- [x] **Decide the coverage bar.** Not "everything". Candidates: security decisions,
      state machines, anything with a silent failure mode, anything a proxy boundary
      could disable. Explicitly *not*: configuration literals, framework behaviour,
      getters, prose.

**Deliverable is a written standard in CLAUDE.md**, next to the comment and logging
rules and for the same reason — the alternative is eleven services of per-file
judgement. Then apply it here, and let the number of tests fall if it falls.

---

## PHASE 3 — API Gateway

### 3.1 mugen-gateway setup
- [ ] Maven module (Spring Cloud Gateway + WebFlux)
- [ ] Copy public.pem from auth-service
- [ ] application.yml + application-docker.yml

### 3.2 Config
- [ ] PublicKeyConfig (load RSAPublicKey bean from public.pem)
- [ ] RouteConfig (all service routes defined)
- [ ] SecurityConfig — **no public-vs-protected route list.** Decided 2026-08-03: the
      gateway authenticates (verify RS256, check Redis revocation, reject a bad token)
      and the service authorizes. "Is this endpoint public?" is an authorization
      question, answered only by `@PublicEndpoint` in the service. A request with no
      token is forwarded with no identity and the service's default-deny refuses it.
      See context.md, "Authn vs authz" — including why a `/public-api` URL convention
      was considered and rejected.
- [ ] Strip client-supplied identity headers (`X-User-*`) on the way in, so a caller
      cannot forge one
- [ ] RateLimitConfig (token bucket config per route)

### 3.3 Filters (order matters)
- [ ] TraceIdFilter (runs first — always, even on rejected requests)
- [ ] JwtVerificationFilter (verify RS256 + Redis revocation check)
- [ ] RateLimitFilter (per userId + per IP, strict on /auth/login)
- [ ] RequestLoggingFilter (structured JSON log with traceId + userId)

### 3.4 Fallback
- [ ] FallbackController (circuit breaker fallback — RFC 9457 response)
- [ ] GlobalExceptionHandler (filter-level exceptions)

### 3.5 Tests
- [ ] JwtVerificationFilterTest (valid, expired, tampered, revoked)
- [ ] RateLimitFilterTest (under limit, at limit, exceeded)

---

## PHASE 3.5 — Config Server

Deliberately scheduled here, not earlier. It touches every service's config
bootstrapping, so it is done once the gateway has proven the multi-service
config shape — 2 services to migrate instead of 11, and 3 real config shapes
(auth, gateway, shared defaults) to factor from rather than guessing off one.

Spring Cloud's third foundational piece alongside Eureka (done) and Gateway
(3.x). Without it, changing a Kafka address or log level means editing 11
application.yml files and restarting each.

### 3.5.1 config-server module
- [ ] Standalone Maven module, NOT in the mugen-parent reactor — same reasoning
      as `eureka-server/`: its Dockerfile must build without sibling modules
- [ ] `@EnableConfigServer`, port 8888
- [ ] Dockerfile + .dockerignore (use eureka-server as the template)
- [ ] Add to compose.yml as infra + health check

### 3.5.2 config-repo
- [ ] `config-repo/` git-backed store: `application.yml` (shared defaults),
      `mugen-auth.yml`, `mugen-gateway.yml`
- [ ] Native/filesystem backend for local dev, git backend for deploy

### 3.5.3 Client wiring (per service)
- [ ] `spring.config.import: "optional:configserver:http://localhost:8888"`
      — the `optional:` prefix is required, not cosmetic: without it a service
      refuses to start when the config server is down, which breaks the
      run-from-IntelliJ dev story (see CLAUDE.md "Dev vs deploy").
      NOTE: bootstrap.yml is gone in modern Spring Cloud — do not reintroduce it.
- [ ] `@RefreshScope` + `/actuator/refresh` on values worth changing at runtime

### 3.5.4 Secrets — decided: env passthrough
- [ ] Config Server holds NON-SECRET config only: hosts, ports, TTLs, log
      levels, feature flags
- [ ] Passwords, OAuth client secrets and keys stay as environment variables
      resolved per service. Nothing sensitive enters the config repo, so no
      `{cipher}` key to protect and rotate.
- [ ] **`private.pem` never goes in Config Server.** Even encrypted, anyone with
      config-repo read access could mint tokens for any user — exactly what
      RS256 asymmetric signing exists to prevent.

---

## PHASE 4 — User Service

### 4.1 mugen-user setup
- [ ] Maven module + dependencies (Postgres, Flyway, MinIO SDK, Resilience4j)
- [ ] application.yml + application-docker.yml

### 4.2 Database layer
- [ ] V1__create_user_profiles.sql
- [ ] V2__create_follows.sql (composite unique index on followerId+followeeId)
- [ ] V3__add_follower_counts.sql
- [ ] UserProfile entity + UserProfileRepository
- [ ] Follow entity + FollowRepository (cursor pagination query)

### 4.3 Services
- [ ] UserService (getUser, updateProfile)
- [ ] FollowService (follow, unfollow, getFollowers, getFollowing — cursor pagination)
- [ ] AvatarService (presigned PUT URL, confirm upload, presigned GET URL + Redis cache)

### 4.4 MinIO + exception handling
- [ ] MinioClientWrapper (generatePresignedPut, generatePresignedGet, objectExists, delete)
- [ ] Exception hierarchy (copy + extend from reference repo)
- [ ] GlobalExceptionHandler (RFC 9457)

### 4.5 Controllers
- [ ] UserControllerV1 (/api/v1/users/**)
- [ ] UserControllerV2 (/api/v2/users/**) — same service, extended DTO
- [ ] AvatarController (/avatar request URL, /avatar/confirm)

### 4.6 Kafka
- [ ] UserRegisteredConsumer (mugen.user.registered → create UserProfile)
- [ ] UserEventPublisher (publish mugen.user.followed)

### 4.7 Tests
- [ ] UserServiceTest
- [ ] FollowServiceTest (follow, unfollow, duplicate follow, cursor pagination)
- [ ] UserRegisteredConsumerTest (idempotency)
- [ ] UserControllerV1IntegrationTest (Testcontainers Postgres)

---

## PHASE 5 — Post Service

### 5.1 mugen-post setup
- [ ] Maven module + dependencies (MongoDB, GraphQL, Kafka)
- [ ] application.yml + application-docker.yml
- [ ] GraphQL schema: src/main/resources/graphql/schema.graphqls

### 5.2 Domain + repository
- [ ] Post document (@Document: id, authorId, content, mediaKeys[], likeCount, createdAt)
- [ ] Comment document (embedded in Post)
- [ ] OutboxEvent document (eventType, payload, published, createdAt)
- [ ] PostRepository (cursor pagination)
- [ ] OutboxEventRepository

### 5.3 Services + Outbox
- [ ] PostService (create, delete, like, getById, getCursorPage)
- [ ] CommentService (add, delete)
- [ ] OutboxPoller (@Scheduled — find unpublished → publish to Kafka → mark published)

### 5.4 GraphQL resolvers
- [ ] PostQueryResolver (@QueryMapping — posts, postById, comments)
- [ ] PostMutationResolver (@MutationMapping — createPost, deletePost, likePost, addComment)

### 5.5 Kafka
- [ ] PostEventPublisher (publish mugen.post.created, mugen.post.liked)

### 5.6 Exception handling
- [ ] Exception hierarchy + GlobalExceptionHandler (RFC 9457)

### 5.7 Tests
- [ ] PostServiceTest
- [ ] OutboxPollerTest (publishes unpublished, skips already published)
- [ ] PostGraphQLIntegrationTest (Testcontainers MongoDB)

---

## PHASE 6 — Feed Service

### 6.1 mugen-feed setup
- [ ] Maven module + dependencies (MongoDB, Redis, Kafka)
- [ ] application.yml + application-docker.yml

### 6.2 Domain
- [ ] FeedItem document (userId, postId, authorId, type, score, createdAt)
- [ ] FeedRepository (cursor pagination by score/createdAt)

### 6.3 Services
- [ ] FeedService (getFeed — cache-aside: Redis → MongoDB fallback)
- [ ] FeedAggregatorService (combine posts + videos for a user's feed)
- [ ] CacheInvalidationService (clear user feed cache on new post)

### 6.4 Kafka
- [ ] PostCreatedConsumer (mugen.post.created → add to followers' feeds)
- [ ] PostLikedConsumer (mugen.post.liked → boost post score in feed)

### 6.5 Controller
- [ ] FeedController (GET /api/v1/feed?cursor=&limit=)

### 6.6 Tests
- [ ] FeedServiceTest (cache hit, cache miss + fallback, invalidation)

---

## PHASE 7 — Notification Service

### 7.1 mugen-notification setup
- [ ] Maven module + dependencies (WebSocket/STOMP, MongoDB, Redis, Kafka)
- [ ] application.yml + application-docker.yml

### 7.2 WebSocket
- [ ] WebSocketConfig (STOMP endpoint: /ws, broker: /topic /queue)
- [ ] SessionRegistry (userId → WebSocket session, Redis-backed for multi-instance)
- [ ] NotificationHandler (send to specific user by userId)

### 7.3 Domain
- [ ] Notification document (userId, type, payload, read, createdAt)
- [ ] NotificationRepository

### 7.4 Service
- [ ] NotificationService (fan-out: one event → N users → WebSocket push + DB persist)

### 7.5 Kafka consumers
- [ ] VideoProgressConsumer (mugen.video.progress.events → push progress to uploader)
- [ ] PostCreatedConsumer (mugen.post.created → notify followers)
- [ ] PostLikedConsumer (mugen.post.liked → notify post author)
- [ ] UserFollowedConsumer (mugen.user.followed → notify followee)
- [ ] PaymentConsumer (mugen.payment.completed → push receipt)

### 7.6 Controller
- [ ] NotificationController (GET /api/v1/notifications — inbox, mark read)

### 7.7 Tests
- [ ] NotificationServiceTest (fan-out logic)
- [ ] SessionRegistryTest (register, lookup, remove)

---

## PHASE 8 — Payment Service

### 8.1 mugen-payment setup
- [ ] Maven module + dependencies (Postgres, Flyway, Kafka, Redis)
- [ ] application.yml + application-docker.yml

### 8.2 Database layer
- [ ] V1__create_payments.sql
- [ ] V2__create_outbox.sql — second implementation of the pattern; mugen-auth's
      `V4__create_outbox_events.sql` is the reference. Postgres changes more than
      the types: `JSONB` replaces `NVARCHAR(MAX)` + the `ISJSON` check, there is
      no CLUSTERED/NONCLUSTERED split, and claiming rows is
      `FOR UPDATE SKIP LOCKED` rather than `WITH (UPDLOCK, READPAST, ROWLOCK)`.
      **With two real implementations, decide here whether the JPA entity, poller
      and properties move to a shared `mugen-outbox` module** — the same call
      `mugen-web` settled for the error hierarchy. Deliberately not decided off
      one implementation.
- [ ] Payment entity + PaymentRepository
- [ ] OutboxEvent entity + OutboxEventRepository

### 8.3 Services
- [ ] IdempotencyService (check Redis cache → return stored response if duplicate)
- [ ] PaymentService (process, with idempotency check)
- [ ] SagaOrchestrator (compensation on failure)
- [ ] OutboxPoller (publish mugen.payment.completed)

### 8.4 Controller
- [ ] PaymentController (POST /api/v1/payments — reads Idempotency-Key header)

### 8.5 Kafka
- [ ] PaymentEventPublisher (via outbox poller)

### 8.6 Tests
- [ ] IdempotencyServiceTest (duplicate request returns same response)
- [ ] PaymentServiceTest (success, failure + compensation)
- [ ] PaymentControllerIntegrationTest (Testcontainers Postgres)

---

## PHASE 9 — Video Service + Transcode Worker

### 9.1 mugen-video setup
- [ ] Maven module + dependencies (MongoDB, MinIO SDK, Kafka)
- [ ] application.yml + application-docker.yml

### 9.2 Domain
- [ ] Video document (id, uploaderId, title, status, uploadId, hlsKey, duration, createdAt)
- [ ] VideoStatus enum (UPLOADING → QUEUED → PROCESSING → READY / FAILED)
- [ ] VideoRepository

### 9.3 Services
- [ ] VideoUploadService (initiate multipart, get part URL, complete multipart)
- [ ] VideoQueryService (getById, getByUploader — cursor pagination)
- [ ] MinioService (CreateMultipart, PresignedPartUrl, CompleteMultipart, PresignedGet)

### 9.4 Controllers
- [ ] VideoUploadController (/upload/initiate, /upload/{id}/part/{n}/url, /upload/{id}/complete)
- [ ] VideoStreamController (/videos/{id}/stream — returns presigned HLS master URL)
- [ ] VideoMetadataController (/videos/{id}, /videos/{id}/status)

### 9.5 Kafka
- [ ] TranscodeJobProducer (publish mugen.video.transcode.jobs after complete)
- [ ] ProgressEventConsumer (mugen.video.progress.events → update Video status in DB)

### 9.6 mugen-transcode worker setup
- [ ] Maven module + dependencies (MinIO SDK, Kafka, FFmpeg via ProcessBuilder)
- [ ] FfmpegConfig (binary path, quality presets: 360p/720p/1080p)
- [ ] FfmpegService (run FFmpeg per quality, produce .ts segments + .m3u8)
- [ ] HlsPackagingService (build master playlist referencing all quality levels)
- [ ] MinioService (download raw, upload HLS segments + playlists)
- [ ] TranscodeJobConsumer (@KafkaListener mugen.video.transcode.jobs)
- [ ] ProgressEventProducer (publish progress 0→25→50→75→100%)

### 9.7 Tests
- [ ] VideoUploadServiceTest (initiate, part URL, complete)
- [ ] FfmpegServiceTest (mock ProcessBuilder)
- [ ] TranscodeJobConsumerTest (Testcontainers Kafka + MinIO)

---

## PHASE 10 — Search Service

### 10.1 mugen-search setup
- [ ] Maven module + dependencies (Elasticsearch client, Kafka)
- [ ] application.yml + application-docker.yml
- [ ] Index mappings for users, posts, videos

### 10.2 Indexing
- [ ] SearchDocument (unified document for users/posts/videos)
- [ ] ElasticsearchIndexer (bulk index with retry on failure)
- [ ] PostIndexConsumer (mugen.post.created → index)
- [ ] UserIndexConsumer (mugen.user.registered → index)
- [ ] VideoIndexConsumer (mugen.video.progress.events status=READY → index)

### 10.3 Search
- [ ] SearchService (full-text query builder, multi-field, type filter)
- [ ] SearchController (GET /api/v1/search?q=&type=&cursor=)

### 10.4 Tests
- [ ] SearchServiceTest (Testcontainers Elasticsearch)
- [ ] ElasticsearchIndexerTest (bulk retry logic)

---

## PHASE 11 — HTTP test files

- [ ] http/auth.http (register, login, refresh, logout, sessions)
- [ ] http/user.http (profile v1+v2, follow, avatar upload flow)
- [ ] http/post.http (GraphQL queries + mutations)
- [ ] http/feed.http (cursor pagination examples)
- [ ] http/payment.http (idempotency key examples)
- [ ] http/video.http (full multipart upload flow)
- [ ] http/search.http (search by type examples)
