FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY probe-platform-common ./probe-platform-common
COPY result-ingestor ./result-ingestor

RUN chmod +x gradlew && ./gradlew :result-ingestor:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/result-ingestor/build/install/result-ingestor ./

ENV PORT=8080
EXPOSE 8080

CMD ["./bin/result-ingestor"]
