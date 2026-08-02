# Current state

Companion to tasks.md, which is the checklist. This file holds only what the code
and git history do NOT already say: live status, decisions and their reasoning,
and traps worth not rediscovering.

## Status (paused 2026-08-02)
Phases 0, 1 and 2 done except **2.10** (Swagger) and the **2.11 exit gate**. 2.8 —
the outbox and the `mugen.user.registered` publisher — landed this session, followed
by a naming and logging consistency pass over the whole service.
79 unit + 34 integration tests green (was 46 + 22).

**Next task is 2.10 Swagger / OpenAPI**, and its first step is a compatibility
check, not code: confirm a springdoc release built for Boot 4.1 / Framework 7
resolves from Maven Central before designing around it. 2.x targets Boot 3.

Testcontainers starts its own SQL Server and Redis, so none of this exercises the
compose stack, and no test has ever spoken to a real broker — the outbox tests all
mock `KafkaTemplate`. Both are on the 2.11 gate.

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
1. **2.10 Swagger / OpenAPI** — set up here because the shape gets copied into the
   other ten services. Verify a Boot 4 / Framework 7 compatible springdoc release
   exists before designing around it; 2.x targets Boot 3.
2. **2.11 exit gate — Phase 3 does not start until all of it is ticked.** Full list
   in tasks.md. What is not yet true at all: the service has never been run
   (`docker compose up` has never been executed), SSO has never touched a real
   provider, no event has ever reached a real broker, and the regression suite has
   known holes — no controller tests for AuthController / SessionController /
   TokenIntrospectController, none for RevocationCacheService or
   AuthorizationRequestStore, none for the profile mappers. Plus the quality review
   and the service's own Dockerfile.
3. Phase 3 — gateway.

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
