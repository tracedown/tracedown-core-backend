# syntax=docker/dockerfile:1

# Multi-service Dockerfile for the Tracedown backend.
# Build context: repository root (tracedown/)
#
# Uses a single Gradle build stage with BuildKit cache mounts,
# then extracts each service into its own slim runtime image
# via build targets.

# ── Build stage ──
FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /app

# ── 1. Gradle wrapper, config, version catalog (changes very rarely) ──
COPY core/tracedown-core-backend/gradle ./core/tracedown-core-backend/gradle
COPY core/tracedown-core-backend/gradlew core/tracedown-core-backend/build.gradle.kts core/tracedown-core-backend/settings.gradle.kts core/tracedown-core-backend/gradle.properties ./core/tracedown-core-backend/

# ── 2. Per-module build scripts only (change when dependencies change) ──
COPY core/tracedown-core-backend/tracedown-core-common/build.gradle.kts ./core/tracedown-core-backend/tracedown-core-common/build.gradle.kts
COPY core/tracedown-core-backend/schema-migrator/build.gradle.kts ./core/tracedown-core-backend/schema-migrator/build.gradle.kts
COPY core/tracedown-core-backend/api-gateway/build.gradle.kts ./core/tracedown-core-backend/api-gateway/build.gradle.kts
COPY core/tracedown-core-backend/probe-scheduler/build.gradle.kts ./core/tracedown-core-backend/probe-scheduler/build.gradle.kts
COPY core/tracedown-core-backend/result-ingestor/build.gradle.kts ./core/tracedown-core-backend/result-ingestor/build.gradle.kts
COPY core/tracedown-core-backend/notification-dispatcher/build.gradle.kts ./core/tracedown-core-backend/notification-dispatcher/build.gradle.kts
COPY core/tracedown-core-backend/email-service/build.gradle.kts ./core/tracedown-core-backend/email-service/build.gradle.kts
COPY core/tracedown-core-backend/metrics-service/build.gradle.kts ./core/tracedown-core-backend/metrics-service/build.gradle.kts
COPY core/tracedown-core-backend/aggregate-worker/build.gradle.kts ./core/tracedown-core-backend/aggregate-worker/build.gradle.kts
COPY core/tracedown-core-backend/realtime-service/build.gradle.kts ./core/tracedown-core-backend/realtime-service/build.gradle.kts

# ── 3. Warm dependency cache (layer cached until build scripts change) ──
WORKDIR /app/core/tracedown-core-backend
# sharing=locked: compose builds every service target in parallel from this
# same Dockerfile; concurrent Gradle runs over one shared cache mount corrupt
# its transform metadata. Locked serializes the mount — after the first target
# compiles, the rest hit a warm cache and fly through.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# ── 4. Copy all source code (changes frequently) ──
WORKDIR /app
COPY core/tracedown-core-backend/tracedown-core-common ./core/tracedown-core-backend/tracedown-core-common
COPY core/tracedown-core-backend/schema-migrator ./core/tracedown-core-backend/schema-migrator
COPY core/tracedown-core-backend/api-gateway ./core/tracedown-core-backend/api-gateway
COPY core/tracedown-core-backend/probe-scheduler ./core/tracedown-core-backend/probe-scheduler
COPY core/tracedown-core-backend/result-ingestor ./core/tracedown-core-backend/result-ingestor
COPY core/tracedown-core-backend/notification-dispatcher ./core/tracedown-core-backend/notification-dispatcher
COPY core/tracedown-core-backend/email-service ./core/tracedown-core-backend/email-service
COPY core/tracedown-core-backend/metrics-service ./core/tracedown-core-backend/metrics-service
COPY core/tracedown-core-backend/aggregate-worker ./core/tracedown-core-backend/aggregate-worker
COPY core/tracedown-core-backend/realtime-service ./core/tracedown-core-backend/realtime-service

# ── 5. Build (dependencies already cached via mount) ──
WORKDIR /app/core/tracedown-core-backend
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew installDist --no-daemon -x test

# ── Runtime images (one per service) ──

FROM eclipse-temurin:17-jre-alpine AS migrator
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/schema-migrator/build/install/schema-migrator ./
CMD ["./bin/schema-migrator"]

FROM eclipse-temurin:17-jre-alpine AS gateway
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/api-gateway/build/install/api-gateway ./
ENV PORT=20714
EXPOSE 20714
CMD ["./bin/api-gateway"]

FROM eclipse-temurin:17-jre-alpine AS scheduler
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/probe-scheduler/build/install/probe-scheduler ./
ENV PORT=20810
EXPOSE 20810
CMD ["./bin/probe-scheduler"]

FROM eclipse-temurin:17-jre-alpine AS ingestor
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/result-ingestor/build/install/result-ingestor ./
ENV PORT=20820
EXPOSE 20820
CMD ["./bin/result-ingestor"]

FROM eclipse-temurin:17-jre-alpine AS dispatcher
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/notification-dispatcher/build/install/notification-dispatcher ./
ENV PORT=20830
EXPOSE 20830
CMD ["./bin/notification-dispatcher"]

FROM eclipse-temurin:17-jre-alpine AS email
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/email-service/build/install/email-service ./
ENV PORT=20840
EXPOSE 20840
CMD ["./bin/email-service"]

FROM eclipse-temurin:17-jre-alpine AS metrics
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/metrics-service/build/install/metrics-service ./
ENV PORT=20850
EXPOSE 20850
CMD ["./bin/metrics-service"]

FROM eclipse-temurin:17-jre-alpine AS worker
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/aggregate-worker/build/install/aggregate-worker ./
ENV PORT=20860
EXPOSE 20860
CMD ["./bin/aggregate-worker"]

FROM eclipse-temurin:17-jre-alpine AS realtime
WORKDIR /app
COPY --from=build /app/core/tracedown-core-backend/realtime-service/build/install/realtime-service ./
ENV PORT=20870
EXPOSE 20870
CMD ["./bin/realtime-service"]
