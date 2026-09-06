FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY probe-platform-common ./probe-platform-common
COPY notification-dispatcher ./notification-dispatcher

RUN chmod +x gradlew && ./gradlew :notification-dispatcher:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/notification-dispatcher/build/install/notification-dispatcher ./

ENV PORT=8080
EXPOSE 8080

CMD ["./bin/notification-dispatcher"]
