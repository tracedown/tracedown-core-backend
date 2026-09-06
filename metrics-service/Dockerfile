FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY probe-platform-common ./probe-platform-common
COPY metrics-service ./metrics-service

RUN chmod +x gradlew && ./gradlew :metrics-service:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/metrics-service/build/install/metrics-service ./

ENV PORT=8080
EXPOSE 8080

CMD ["./bin/metrics-service"]
