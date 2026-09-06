FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY probe-platform-common ./probe-platform-common
COPY aggregate-worker ./aggregate-worker

RUN chmod +x gradlew && ./gradlew :aggregate-worker:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/aggregate-worker/build/install/aggregate-worker ./

ENV PORT=8080
EXPOSE 8080

CMD ["./bin/aggregate-worker"]
