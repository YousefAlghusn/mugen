# Current state

## Status
Phase 1 complete, then a full review pass over Phases 0–1 (see "Review pass" below).
compose.yml re-validated via `docker compose config`. The stack still has not been
brought up against a live Docker daemon — do that before starting Phase 2 (Flyway
needs a live SQL Server), and it is also the only way to confirm the KRaft broker
and the new eureka-server Dockerfile actually build and start.

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

## Known gaps (deliberate, not yet done)
- Nothing ships logs to Loki. Grafana has the datasource but no writer; services
  will need a Loki appender (loki-logback-appender) when logging is set up.
- The whole stack has never been started against a live Docker daemon.

## Up next
Phase 2 — mugen-auth service (Maven module, RS256 keypair, SQL Server + Flyway, JWT/session services, exception handling, controllers)

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