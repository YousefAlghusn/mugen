# Mugen

Infinite content. Infinite community. Reddit + YouTube platform.
Built as an enterprise learning project using Java 21 + Spring Boot 4.1.x.

## Reference repos (study before implementing)
- Exception handling pattern: https://github.com/Dancan254/exception-handling
  Use its exception hierarchy: AppException → ResourceNotFoundException,
  ConflictException, BusinessRuleException, ForbiddenException etc.
  Use its GlobalExceptionHandler RFC 9457 ProblemDetail pattern exactly.

## Package naming
com.mugen.<servicename>
e.g. com.mugen.auth, com.mugen.gateway, com.mugen.user

## Naming — injected dependencies
Fields and `@Bean` method parameters. One rule with two carve-outs, so a name
never means two things in two files.

- **Default: camelCase of the type.** `sessionService`, `authService`,
  `passwordEncoder`, `jwtProperties`, `outboxProperties`, `refreshTokenCookies`.
  Never bare `properties` — four different `@ConfigurationProperties` types were
  all called that, and the field stopped saying which one.
- **Repositories: the plural entity.** `users`, `sessions`, `oauthLinks`,
  `outboxEvents` — `users.findByEmail(...)` reads as what it is, a collection.
  This is why services must NOT drop their suffix: `sessions` is the
  `SessionRepository`, so `SessionService` has to be `sessionService`.
- **Infrastructure clients: the technology.** `redis`, `kafka`, `json`,
  `restClient`.
- Two beans of one type each take a qualifier: `refreshTokenDecoder`.

## Logging
Logs are read during an incident by someone who is not you.

- **Levels.** ERROR — the service cannot do its job, a human is needed. WARN — one
  request failed in a way worth noticing (security refusal, exhausted retry) but
  the service is fine. INFO — a state change worth one line in production at
  steady state. DEBUG — per-request detail for diagnosis, off in production.
- **Every error response has a matching log line.** A ProblemDetail carries a
  traceId, and that traceId is what a user quotes to support — if no log line
  mentions it, the field is decoration. This includes the 4xx paths Spring MVC
  handles for you (405, 415, malformed body, validation), which log nothing until
  you make them.
- **Log a framework exception's type, not its message, on 4xx.** Spring builds
  those messages out of the offending input, so
  `HttpMessageNotReadableException` quotes the request body — a registration
  payload, password included.
- **`key={}` for every identifier**: `userId={}`, `sessionId={}`, `eventId={}`,
  `provider={}`. Bare values are unsearchable and cannot become structured fields
  later. Non-identifiers stay prose.
- One sentence, capitalised, no trailing full stop, always `{}` placeholders —
  never string concatenation.
- **No prefix naming the component.** The logger is already
  `com.mugen.auth.service.OutboxPoller`; `"Outbox: ..."` says it twice.
- **Never log PII or secrets**: no email addresses, password material, tokens,
  OAuth `state`, PKCE verifiers, cookies or Authorization headers. Log the id
  instead — an email in a log is an email in Loki, forever.
- **Exceptions**: pass the throwable as the trailing argument (no `{}`) when the
  stack matters. `ex.getMessage()` alone is only for expected failures whose stack
  is noise, and the code should say why.

## Comments
This is a learning project, so the reasoning stays. Its *length* is the thing to
control — a five-line comment for a one-line claim makes code harder to read, not
easier, and reads as noise once there are eleven services of it.

- **A comment answers why.** If it restates what the line does, delete it.
  `// Save the user` above `users.save(user)` earns nothing.
- **One comment, one claim, three lines at most.** Needing more means it is a
  design decision, and those belong in `docs/dev/context.md`, which exists for
  exactly that. Leave a pointer, not the argument.
- **Type javadoc is one sentence** — what this type is for. Not its history, not
  the alternatives rejected, not how it fits the architecture.
- **No fact is explained twice.** Two comments saying the same thing in different
  files will disagree within a month. In particular: never both javadoc *and*
  `@Operation` on an endpoint — javadoc is the source, springdoc reads it.
- **Always worth keeping**, compressed to a line: framework traps that cost real
  time, security reasoning ("the alternative lets anyone enumerate accounts"),
  non-obvious constraints, and why a tempting simpler approach fails.
- **Never a banner or a section divider.** `// ===== Services =====` in a pom or a
  class is structure the file already has.

## Services
- mugen-shared        (shared DTOs, Kafka contracts, utils)
- mugen-auth          (JWT, SSO, sessions — SQL Server)
- mugen-gateway       (Spring Cloud Gateway, routing, rate limiting)
- mugen-user          (profiles, follows, avatars — Postgres)
- mugen-post          (GraphQL, MongoDB, Outbox pattern)
- mugen-feed          (cache-aside, cursor pagination — Redis + MongoDB)
- mugen-notification  (WebSocket, fan-out — Redis + MongoDB)
- mugen-payment       (idempotency, Saga — Postgres)
- mugen-video         (chunked upload, HLS — MongoDB + MinIO)
- mugen-transcode     (FFmpeg worker, HLS packaging)
- mugen-search        (Elasticsearch, bulk indexing)

## Stack
- Java 21 + Spring Boot 4.1.x (Spring Framework 7, Jakarta EE 11) + Maven multi-module
- Spring Cloud 2025.1.x (Oakwood) release train — gateway uses `spring-cloud-gateway-server-webflux`
- SQL Server (auth), Postgres (user, payment)
- MongoDB (post, video, feed, notification)
- Redis (cache, sessions, rate limiting, revocation cache)
- Kafka (async events, KRaft mode — no ZooKeeper), MinIO (file storage), Elasticsearch (search)
- Eureka (discovery), Spring Cloud Config Server (centralised config — Phase 3.5,
  after the gateway; clients use `spring.config.import: "optional:configserver:..."`
  so a service still starts when it is down)
- Observability: Jaeger + Prometheus + Grafana + Loki
- Build: Maven Wrapper (`./mvnw`) — do not assume a `mvn` on PATH

## Dev vs deploy — two different containerisation stories
- **Root `compose.yml` is infrastructure ONLY.** Databases, Kafka, Redis, MinIO,
  Elasticsearch, Eureka, observability. It never contains an application service.
- **In development the 11 services run on the host**, from IntelliJ or `./mvnw
  spring-boot:run`. That is what gives you debugging, hot reload and breakpoints.
- **Each service owns a `Dockerfile` in its own module**, used for deployment only —
  never wired into the root compose. Use `eureka-server/Dockerfile` as the template:
  layered-jar extraction, non-root user, `-XX:MaxRAMPercentage`, plus a `.dockerignore`.
- Consequence for config: services on the host reach infra at **localhost:<published
  port>**, never at the Docker hostname. `kafka:29092` is container-only and
  `localhost:9092` is host-only — see the header of `.env.example`.
- `.env` is read by Docker Compose, NOT by Spring. Section A is compose-side,
  Section B is for the host-run services and must be exported into the run
  configuration (IntelliJ EnvFile plugin) to have any effect.

## Architecture rules
- Services NEVER share databases or call each other's DB directly
- Inter-service HTTP only for enrichment, wrapped in Resilience4j circuit breaker
- All async communication via Kafka topics only
- Gateway is the ONLY entry point — clients never call services directly
- Every service uses RFC 9457 ProblemDetail (see reference repo above)
- Outbox pattern for any Kafka publish that must be atomic with a DB write
- Cursor pagination everywhere — never OFFSET
- Presigned URLs for all MinIO file serving — never stream files through services
- All schemas managed by Flyway migrations

## Auth / Token design
- Access token: RS256 JWT, 15 min, claims: {userId, sessionId, roles}
  → lives in Angular memory only — never localStorage or sessionStorage
- Refresh token: RS256 JWT, 30 days, claims: {sessionId, version}
  → HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth cookie only
- Sessions table stores token_version integer — rotation = version++
- Version mismatch on refresh = replay attack → revoke entire session
- Gateway validates token with public.pem (RS256) — zero DB calls
- Revoked sessions → Redis key with 15 min TTL → Gateway checks on every request

## Kafka topics
- mugen.user.registered       producer: auth        consumers: user, search
- mugen.user.followed         producer: user        consumers: notification
- mugen.post.created          producer: post        consumers: feed, search, notification
- mugen.post.liked            producer: post        consumers: notification, feed
- mugen.video.transcode.jobs  producer: video       consumer: transcode
- mugen.video.progress.events producer: transcode   consumers: video, notification
- mugen.payment.completed     producer: payment     consumers: notification

## MinIO buckets
- mugen-avatars   user profile pictures
- mugen-raw       uploaded raw video files
- mugen-hls       transcoded HLS segments + m3u8 playlists
- mugen-media     post images and attachments
- Never public — always serve via presigned GET URLs
- Cache presigned GET URLs in Redis 50 min (URL valid 60 min)

## Error handling standard
- Inherit from reference repo: https://github.com/Dancan254/exception-handling
- AppException base → typed subclasses per error
- Single GlobalExceptionHandler per service
- RFC 9457 ProblemDetail with custom fields: traceId (always), errors[] (validation)
- Content-Type: application/problem+json
- spring.mvc.problemdetails.enabled: true in every service
- Never expose raw exception messages to clients

## API docs (OpenAPI / Swagger)
springdoc **3.x** — the Boot 4 line. 2.x targets Boot 3 / Framework 6 and does not
work here. Version comes from `springdoc-openapi-bom`, imported once in the root
pom; a service declares `springdoc-openapi-starter-webmvc-ui` with no version.
`mugen-auth` is the reference implementation.

- **Build the document from configuration, never from literals.** Cookie names,
  TTLs and limits come from the `@ConfigurationProperties` the service actually
  runs on, via a `@Bean OpenAPI`. An `@OpenAPIDefinition` annotation can only
  restate them, and a restatement goes stale silently on the next rename.
- **No global security requirement.** It marks the endpoints that exist to obtain
  a token as requiring one. Put `@SecurityRequirement` on the classes that need
  it, and test both directions — this failure never breaks a request, only the
  documentation.
- `@CookieValue` parameters are `@Parameter(hidden = true)`. springdoc publishes
  the annotation's raw value, so a `${...}` placeholder is documented verbatim
  instead of resolved. Describe the cookie as a security scheme instead.
- `paths-to-match` limits the document to the service's own `/api/**`. `/actuator`
  is operational surface, not API surface.
- Permit the springdoc paths **explicitly** in SecurityConfig, and pin that
  pairing with a test. Docs behind a 401 still look fine to a logged-in developer.
- Deployed environments: `springdoc.api-docs.enabled` and
  `springdoc.swagger-ui.enabled` are separate switches, both off in the `docker`
  profile, both env-var overridable.

## Pagination
- Cursor-based everywhere: { items: [], nextCursor: "..." }
- WHERE created_at < :cursor ORDER BY created_at DESC LIMIT n
- Never OFFSET

## Commands
Always `./mvnw` (`.\mvnw.cmd` on Windows), never a bare `mvn` — the wrapper
pins Maven 3.9.9 and there may be no Maven installed at all.

- Start infra:      docker compose up -d
- Stop infra:       docker compose down          (add -v to wipe volumes)
- Start service:    cd services/mugen-auth && ../../mvnw spring-boot:run
                    (runs on the host against the dockerized infra; the default
                     profile points at localhost — see "Dev vs deploy" above)
- Unit tests:       ./mvnw test                  (fast, no Docker needed)
- All tests:        ./mvnw verify                (adds *IntegrationTest via
                     failsafe — needs a running Docker daemon for Testcontainers)
- Build:            ./mvnw clean package -DskipTests

Toolchain note: JDK 21 is required and enforced by maven-enforcer-plugin.