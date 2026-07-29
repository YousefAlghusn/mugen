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
- Kafka (async events), MinIO (file storage), Elasticsearch (search)
- Eureka (discovery)
- Observability: Jaeger + Prometheus + Grafana + Loki

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

## Pagination
- Cursor-based everywhere: { items: [], nextCursor: "..." }
- WHERE created_at < :cursor ORDER BY created_at DESC LIMIT n
- Never OFFSET

## Commands
- Start infra:   docker compose up -d
- Start service: cd services/mugen-auth && mvn spring-boot:run -Dspring-boot.run.profiles=docker
- Run tests:     mvn test
- Build:         mvn clean package -DskipTests