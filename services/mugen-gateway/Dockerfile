# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# mugen-gateway, for deployment only. In development this service runs on the host
# — see CLAUDE.md, "Dev vs deploy" — and the root compose.yml is infrastructure
# only, so nothing here is wired into it.
#
# BUILD FROM THE REPOSITORY ROOT, not from this directory:
#
#     docker build -f services/mugen-gateway/Dockerfile -t mugen-gateway .
#
# This module is part of the mugen-parent reactor and compiles against
# mugen-shared and mugen-web, so the parent pom and both shared modules have to
# be in the build context. That is the one way it differs from eureka-server,
# which is standalone precisely so its image can build from its own directory.
#
# The ignore file is Dockerfile.dockerignore beside this file rather than one at
# the context root: BuildKit reads <dockerfile>.dockerignore first, which lets a
# service own its exclusions even though the context is the whole repository.
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY shared shared
# Every service's pom, sources of none: the parent pom names them all as modules
# and Maven will not read a reactor with one missing, -pl or not. The ignore file
# beside this Dockerfile keeps everything else of the siblings out of the context.
COPY services services

# One step, with no pom-only pre-resolve pass in front of it — which is what
# eureka-server does and what does not work here. A reactor module's siblings
# are resolved as artifacts, so `dependency:resolve` on mugen-web with only the
# poms present fails: mugen-shared has not been built and there is no jar of it
# to find. Dependency caching comes from the BuildKit mount below instead, which
# survives across builds and is the part that actually saves the time.

# -am builds mugen-shared and mugen-web on the way. Tests are not run here: the
# integration tier needs a Docker daemon of its own, and a release is built from
# a commit `./mvnw verify` has already passed.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/mugen-gateway -am package -DskipTests \
    && cp services/mugen-gateway/target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------
# Runtime stage
#
# Layers are copied least- to most-frequently-changed so that a code-only change
# re-uploads just the small application layer, not the ~70MB of dependencies.
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
EXPOSE 8080
# The actuator, for the orchestrator's probes and scrape only — never published.
EXPOSE 9080

# The image is only meaningful with this profile: it is what swaps localhost for
# the container hostnames and drops the route listing from the actuator
# (application-docker.yml).
ENV SPRING_PROFILES_ACTIVE=docker

# Only public.pem travels in the jar. The gateway verifies and never signs, so
# there is no secret to mount: a leaked image can check tokens, not mint them.

# MaxRAMPercentage so the JVM honours the container memory limit rather than
# sizing the heap off the host's total RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]
