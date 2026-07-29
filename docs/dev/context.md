# Current state

## Status
Phase 1 complete. compose.yml syntax-validated via `docker compose config`, but the Docker daemon was not running in this environment so the stack itself hasn't been brought up yet — do that before starting Phase 2 (Flyway will need a live SQL Server).

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