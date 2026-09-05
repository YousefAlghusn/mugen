# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# mugen-auth, for deployment only. In development this service runs on the host
# — see CLAUDE.md, "Dev vs deploy" — and the root compose.yml is infrastructure
# only, so nothing here is wired into it.
#
# BUILD FROM THE REPOSITORY ROOT, not from this directory:
#
#     docker build -f services/mugen-auth/Dockerfile -t mugen-auth .
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
COPY services/mugen-auth services/mugen-auth

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
    mvn -B -q -pl services/mugen-auth -am package -DskipTests \
    && cp services/mugen-auth/target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------
# Runtime stage
#
# Layers are copied least- to most-frequently-changed so that a code-only change
# re-uploads just the small application layer, not the ~60MB of dependencies.
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
EXPOSE 8081

# The image is only meaningful with this profile: it is what swaps localhost for
# the container hostnames and turns the API docs off (application-docker.yml).
ENV SPRING_PROFILES_ACTIVE=docker

# ---------------------------------------------------------------------------
# The signing key is NOT in this image, deliberately.
#
# public.pem is committed and travels in the jar — every service needs it to
# verify. private.pem is gitignored and never built in: an image is pushed to a
# registry and pulled by anyone with read access, and a key baked into one is a
# key that cannot be rotated without a rebuild. Mount it at runtime and point
# the service at it:
#
#     -v ./private.pem:/run/secrets/mugen-auth/private.pem:ro
#     -e JWT_PRIVATE_KEY_PATH=file:/run/secrets/mugen-auth/private.pem
#
# application-docker.yml already defaults to that path, so a correctly mounted
# secret needs no environment variable at all. Without it the service refuses to
# start, which is the right failure: a JWT issuer with no key can do nothing.
# ---------------------------------------------------------------------------

# MaxRAMPercentage so the JVM honours the container memory limit rather than
# sizing the heap off the host's total RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]
