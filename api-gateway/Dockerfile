FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY probe-platform-common ./probe-platform-common
COPY api-gateway ./api-gateway

RUN chmod +x gradlew && ./gradlew :api-gateway:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/api-gateway/build/install/api-gateway ./

ENV PORT=8080
EXPOSE 8080

CMD ["./bin/api-gateway"]
