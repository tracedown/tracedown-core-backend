FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY probe-platform-common ./probe-platform-common
COPY probe-scheduler ./probe-scheduler

RUN chmod +x gradlew && ./gradlew :probe-scheduler:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/probe-scheduler/build/install/probe-scheduler ./

ENV PORT=8080
EXPOSE 8080

CMD ["./bin/probe-scheduler"]
