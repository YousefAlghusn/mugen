# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# Build stage
#
# The BuildKit cache mount on ~/.m2 survives across builds, which is both
# faster and more reliable than `dependency:go-offline` — that goal routinely
# misses plugin-time and BOM-resolved artifacts for Spring Boot projects, so
# the "offline" build then fails or silently re-downloads anyway.
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:resolve

COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------
# Runtime stage
#
# Layers are copied least- to most-frequently-changed so that a code-only
# change re-uploads just the small application layer, not the ~40MB of
# dependencies.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run the JVM as root.
RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring:spring
EXPOSE 8761

# MaxRAMPercentage so the JVM honours the container memory limit rather than
# sizing the heap off the host's total RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]
