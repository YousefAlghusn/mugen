# Current state

Companion to tasks.md, which is the checklist. This file holds only what the code
and git history do NOT already say: live status, decisions and their reasoning,
and traps worth not rediscovering.

## Status (paused 2026-07-31)
Phases 0, 1 and 2 done except **2.8** — the Kafka `mugen.user.registered` publisher,
which is the next task. 46 unit + 22 integration tests green.

The integration suite ran against a live Docker daemon for the first time this
session and immediately caught two context-load failures no unit test could see
(both under "Boot 4 traps"). Testcontainers starts its own SQL Server and Redis, so
this still does not exercise the compose stack.

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

## Up next
1. **2.8 UserEventPublisher** — publish `mugen.user.registered` after registration.
   Open question, deliberately not decided: CLAUDE.md requires the outbox pattern
   for any Kafka publish that must be atomic with a DB write, and this is one (a
   lost event means a user with no profile in mugen-user, forever) — but tasks.md
   schedules outbox work in Phase 5. Full outbox in auth vs. direct publish after
   commit is the fork to settle first.
   Note SSO creates accounts too: `OAuthService.linkOrCreate` returns
   `SsoUser(user, created)` precisely so that path publishes the same event.
2. **2.10 Swagger / OpenAPI** — set up here because the shape gets copied into the
   other ten services. Verify a Boot 4 / Framework 7 compatible springdoc release
   exists before designing around it; 2.x targets Boot 3.
3. **2.11 exit gate — Phase 3 does not start until all of it is ticked.** Full list
   in tasks.md. The three that are not yet true at all: the service has never been
   run (`docker compose up` has never been executed), SSO has never touched a real
   provider, and the regression suite has known holes — no controller tests for
   AuthController / SessionController / TokenIntrospectController, none for
   RevocationCacheService or AuthorizationRequestStore, none for the profile
   mappers. Plus the quality review and the service's own Dockerfile.
4. Phase 3 — gateway.

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
