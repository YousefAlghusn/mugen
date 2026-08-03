# Current state

Companion to tasks.md, which is the checklist. This file holds only what the code
and git history do NOT already say: live status, decisions and their reasoning,
and traps worth not rediscovering.

## Status (2026-08-03)
Phases 0, 1 and 2 done except the **2.11 exit gate**. 2.10 (Swagger / OpenAPI)
landed this session: springdoc 3.1.0, seven new integration tests.
79 unit + 41 integration tests green.

**Next task is the 2.11 exit gate**, which Phase 3 must not start before. Its first
item is the one everything else waits on: the service has never actually been run.

Testcontainers starts its own SQL Server and Redis, so none of this exercises the
compose stack, and no test has ever spoken to a real broker — the outbox tests all
mock `KafkaTemplate`. Both are on the 2.11 gate.

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
Everything untested about mugen-auth is now tracked as the **2.11 exit gate** in
tasks.md, which Phase 3 must not start before. In short: the service has never been
run, `docker compose up` has never been executed, SSO has never touched a real
provider, and the regression suite has named holes.

Cheapest first SSO check, short of registering real apps: run with
`-Dspring-boot.run.profiles=sso` and dummy credentials, then confirm
`GET /api/v1/auth/sso/google` 302s to Google carrying the right `client_id`,
`state`, `code_challenge` and `redirect_uri`. That proves everything up to the
point Google's own credential check takes over.

Not on the gate, deferred by choice:
- Nothing ships logs to Loki. Grafana has the datasource but no writer; services
  need a Loki appender (loki-logback-appender) when logging is set up.
- **Orphan user row in `OAuthService.linkOrCreate`** (pre-existing, spotted while
  wiring 2.8). Two first-ever sign-ins for the same provider account can both find
  no link and both create a user; the loser of the `oauth_links` race re-reads the
  winner and signs in as them, leaving its own freshly-created user row unreachable
  — no link, no password, and now deliberately no `mugen.user.registered` either,
  since a profile for an unreachable account is worse than none. Needs a
  narrow window and a matching provider account, so it is not urgent; the fix is
  to delete the losing row in the catch block.

## Up next
1. **2.11 exit gate — Phase 3 does not start until all of it is ticked.** Full list
   in tasks.md. What is not yet true at all: the service has never been run
   (`docker compose up` has never been executed), SSO has never touched a real
   provider, no event has ever reached a real broker, and the regression suite has
   known holes — no controller tests for AuthController / SessionController /
   TokenIntrospectController, none for RevocationCacheService or
   AuthorizationRequestStore, none for the profile mappers. Plus the quality review
   and the service's own Dockerfile.
   Swagger UI now makes the "exercise every endpoint" item cheaper than
   hand-writing requests — `/swagger-ui.html` on port 8081, everything except the
   two SSO redirects is driveable from there.
2. Phase 3 — gateway.

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
