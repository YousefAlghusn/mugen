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
- [x] (extra) SchemaIntegrationTest — 9 tests proving the migrations and the JPA
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
- [x] OAuthService (Google + GitHub provider)
- **Decision:** the authorization code flow is driven explicitly, NOT via
  `oauth2Login()`. Spring's login chain ends in an authenticated servlet session;
  mugen has none, so a sign-in must end in the same token pair a password login
  produces — both routes now converge on `AuthService.issueTokens`. The protocol
  steps are still Spring Security's (token exchange, PKCE, user-info call).
- [x] (extra) OAuthProfileMapper per provider — providers agree on the handshake
      and almost nothing after it. GitHub needs a second call to `/user/emails`:
      it omits private addresses from `/user` and reports verification nowhere
      else.
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
- [ ] UserEventPublisher (publish mugen.user.registered after register)

### 2.9 Tests
- [x] JwtServiceTest (sign, verify, tampered token, expired, wrong key, type confusion)
- [x] SessionServiceTest (rotation, replay attack detection)
- [x] AuthFlowIntegrationTest (Testcontainers SQL Server + Redis) — full
      register/login/refresh/replay/logout flow
- [x] SchemaIntegrationTest (Testcontainers SQL Server)
- [x] OAuthServiceTest (23 tests — PKCE, single-use and provider-bound state,
      browser-nonce binding, and the linking rules that decide whether a provider
      identity may be attached to an existing account)
- [x] (extra) SsoControllerTest (@WebMvcTest — redirect targets and the cookie
      attributes, which are the whole security model of the flow and are
      invisible from a service-level test)

---

## PHASE 3 — API Gateway

### 3.1 mugen-gateway setup
- [ ] Maven module (Spring Cloud Gateway + WebFlux)
- [ ] Copy public.pem from auth-service
- [ ] application.yml + application-docker.yml

### 3.2 Config
- [ ] PublicKeyConfig (load RSAPublicKey bean from public.pem)
- [ ] RouteConfig (all service routes defined)
- [ ] SecurityConfig (public vs protected routes list)
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
- [ ] V2__create_outbox.sql
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