# Current state

## Status
Phase 2 (mugen-auth) is complete except 2.8 — the Kafka `mugen.user.registered`
publisher — which is the next task. 46 unit + 22 integration tests green.

The integration suite has now run against a live Docker daemon (2026-07-31), which
had never happened before. It immediately earned its keep: two context-load failures
that no unit test could see are recorded under "Boot 4 module splits" below. The
full compose stack still has not been brought up — Testcontainers starts its own
SQL Server and Redis, so that remains unproven.

## Toolchain on this machine
- JDK 21: `C:\Users\youse\.jdks\ms-21.0.12` (Microsoft OpenJDK, installed via IntelliJ)
- No `java` or `mvn` on PATH — use `.\mvnw.cmd` with `JAVA_HOME` set to the above.
  IntelliJ's bundled Maven 3.9.9 lives at
  `E:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven\lib\maven3`.

## Done
- [x] Full architecture designed
- [x] All services, Kafka topics, DBs decided
- [x] Auth flow, token design, session versioning designed
- [x] Video upload flow (multipart + HLS) designed
- [x] Error handling standard chosen (RFC 9457)
- [x] CLAUDE.md written
- [x] tasks.md written and ordered
- [x] Phase 0.1 — root pom.xml (Spring Boot 4.1.0 parent, Spring Cloud 2025.1.2 BOM), .gitignore, .env/.env.example, folder structure (services/, shared/, config/, http/)
- [x] Phase 0.2 — mugen-shared module: 7 Kafka event records (com.mugen.shared.event), ApiResponse<T>/ApiError (com.mugen.shared.response), ErrorCode enum (com.mugen.shared.error), TraceIdHolder MDC utility (com.mugen.shared.trace), JwtClaims record (com.mugen.shared.auth)
- [x] Phase 1 — compose.yml (16 services) + config/{prometheus,grafana,loki,kafka,postgres,sqlserver}. Added a standalone `eureka-server/` Maven module (own parent = spring-boot-starter-parent, NOT part of the mugen-parent reactor — keeps its Docker build independent of the other 10 services' pom.xml not existing yet) since there's no official Eureka image. Service port scheme fixed in .env: gateway 8080, auth 8081, user 8082, post 8083, feed 8084, notification 8085, payment 8086, video 8087, transcode 8088, search 8089, eureka 8761 — app services run on the host (`mvn spring-boot:run`), so Prometheus scrapes them via host.docker.internal.

## Review pass over Phases 0–1 (2026-07-31)
Verified first: Spring Boot 4.1.0 and Spring Cloud 2025.1.2 both resolve from Maven
Central and compile on JDK 21 — the version pins are real, not guesses.

Fixed:
- Root pom declared all 11 service modules while only mugen-shared existed, so every
  Maven command at the root failed. Modules are now added in the same commit that
  creates them. Added enforcer (JDK 21 / Maven 3.9+), managed mugen-shared's version,
  split surefire (unit) from failsafe (*IntegrationTest, needs Docker).
- Added Maven Wrapper 3.3.4 / Maven 3.9.9. The build no longer depends on a Maven
  that happens to be installed.
- Kafka migrated ZooKeeper → KRaft on the official `apache/kafka:4.0.0` image.
  ZooKeeper is deprecated by KIP-500 and removed in Kafka 4.0.
- Jaeger published only OTLP gRPC 4317, but Spring Boot's `management.otlp.tracing`
  exporter speaks OTLP over **HTTP**. Traces would have silently gone nowhere. 4318
  is now published.
- `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` was correct for nobody. `.env` is now split
  into Section A (compose/container-side) and Section B (host-run Spring services).
- eureka-server had actuator but no `micrometer-registry-prometheus`, so
  `/actuator/prometheus` 404'd and its Prometheus scrape job could never have worked.
- eureka-server Dockerfile: layered-jar extraction (layout verified against Boot
  4.1), non-root user, BuildKit ~/.m2 cache mount, `.dockerignore`, MaxRAMPercentage.
  This is now the template every service's own Dockerfile should follow.
- `TraceIdHolder` minted a dashed UUID into MDC key `traceId` — the key Micrometer
  Tracing owns. It now defers to the tracer, falling back only when no span is in
  scope, in W3C 32-hex shape.
- All 7 Kafka events implement `DomainEvent` and are
  `@JsonIgnoreProperties(ignoreUnknown = true)` so a producer can add a field
  without breaking a not-yet-redeployed consumer.
- First 20 tests in mugen-shared, all passing. Note: with only `slf4j-api` on the
  classpath MDC is a silent no-op, so `logback-classic` is a required *test*
  dependency — the library itself still ships no binding.

## Phase 2 — SSO (2026-07-31)
- The authorization code flow is driven explicitly, not through `oauth2Login()`.
  Spring's login chain ends in an authenticated servlet session and mugen has none:
  a sign-in has to end in the same access token and rotating refresh cookie a
  password login produces. Both routes converge on `AuthService.issueTokens`. The
  protocol steps are still Spring Security's, so there is no hand-written OAuth.
- **An unverified provider email is refused, never matched onto an account.** It is
  only a claim; honouring one would let anyone put a stranger's address on a
  throwaway provider account and take over — or pre-emptively squat — the matching
  mugen account. A *verified* address does link to an existing account, on the same
  reasoning a password reset relies on.
- `state` is single-use, held in Redis, and bound to the provider that issued it.
- The flow is also bound to the browser that started it, via a `SameSite=Lax` nonce
  cookie. `state` alone does not stop login CSRF — an attacker can start their own
  sign-in, obtain a genuine code+state, and lure a victim through the callback into
  the attacker's account. Lax and not Strict because the callback arrives as a
  cross-site top-level navigation from the provider, on which Strict is never sent.
- The callback sets only the refresh cookie and redirects; the access token is never
  in a URL, where it would reach browser history, `Referer` and every proxy log.
- Credentials live in `application-sso.yml` behind the `sso` profile. Boot rejects a
  blank client-id, so putting them in the default profile would stop mugen-auth
  booting on any machine without Google and GitHub credentials. With the profile off
  there is no `ClientRegistrationRepository`, and the /sso endpoints answer 404.

## Boot 4 module splits (bites once per technology)
Boot 4 split autoconfiguration into one module per technology — the same trap as the
`spring-boot-flyway` note already in mugen-auth's pom. Three hit in one sitting:
- `spring-boot-restclient` — without it there is no `RestClient.Builder` bean at all,
  though `RestClient` itself is right there in spring-web. Inject the bean rather
  than `RestClient.create()`: it carries the service's Jackson config, including
  `fail-on-unknown-properties: false`.
- `spring-boot-webmvc-test` — `@WebMvcTest` moved to
  `org.springframework.boot.webmvc.test.autoconfigure` and is no longer pulled in by
  `spring-boot-starter-test`.
- `@WebMvcTest` applies only its own slice's autoconfigurations, so mugen-web's
  auto-configured `GlobalExceptionHandler` is absent and must be `@Import`ed.

Also caught by the same run: a bean with two constructors and no `@Autowired` fails
the entire context load, not just that bean.

## Known gaps (deliberate, not yet done)
- Nothing ships logs to Loki. Grafana has the datasource but no writer; services
  will need a Loki appender (loki-logback-appender) when logging is set up.
- The whole stack has never been started against a live Docker daemon.

## Up next
Phase 2.8 — UserEventPublisher, the last open task in mugen-auth: publish
`mugen.user.registered` after a registration. Note SSO creates accounts too —
`OAuthService.linkOrCreate` returns `SsoUser(user, created)` precisely so that path
publishes the same event. Then the quality-gate review over the whole service, then
Phase 3 (gateway).

## Key decisions
- Name: Mugen (無限)
- Package: com.mugen.*
- Spring Boot 4.1.x / Spring Framework 7 / Jakarta EE 11 (Boot 3.x hit EOL 2026-06-30; upgraded from the original CLAUDE.md 3.x pin). Spring Cloud 2025.1.x (Oakwood) train — gateway module uses spring-cloud-gateway-server-webflux, not the deprecated pre-4.x gateway artifact.
- Error handling: follow https://github.com/Dancan254/exception-handling exactly
- Token: RS256 asymmetric, access in memory, refresh in HttpOnly cookie
- Sessions: token_version integer (not hash), replay = revoke entire session
- Files: presigned URLs always, never stream through services
- Pagination: cursor always, never OFFSET
- Video: multipart chunked upload → FFmpeg → HLS adaptive bitrate
- Dev vs deploy: root compose.yml is **infra only**. In development the 11 services
  run on the host from IntelliJ (debugging, hot reload). Each service gets its own
  Dockerfile in its own module for deployment, never wired into the root compose.
- Kafka runs KRaft, not ZooKeeper (removed in Kafka 4.0)
- JWT handling uses Spring Security's JOSE support (Nimbus), not JJWT — one
  implementation signs in auth and verifies in the gateway
- Config Server is scheduled for Phase 3.5 (after the gateway), not earlier: it
  touches every service's config bootstrapping, so it is cheaper once the
  multi-service config shape exists. Secrets stay as env passthrough — the
  config repo holds non-secret config only, and never `private.pem`.