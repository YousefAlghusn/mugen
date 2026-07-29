# Current state

## Status
Phase 0.2 complete. Maven build unverified in this environment — no Java/Maven on PATH in the dev shell (open in IntelliJ to build, or install a JDK 21 + Maven and re-check).

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

## Up next
Phase 1.1 — compose.yml (all infra containers: Kafka+ZK, Redis, MongoDB, SQL Server, Postgres, MinIO, Elasticsearch, Eureka, Jaeger, Prometheus, Loki, Grafana)

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