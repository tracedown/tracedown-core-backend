plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.tracedown.migrator.MainKt")
}

val serviceModules = listOf(
    "api-gateway",
    "probe-scheduler",
    "result-ingestor",
    "notification-dispatcher",
    "metrics-service",
    "aggregate-worker"
)

tasks.processResources {
    for (mod in serviceModules) {
        from(project(":$mod").file("src/main/resources/db")) {
            into("db")
            exclude("**/.gitkeep")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

dependencies {
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback)
}

// No fat JAR for the migrator: Flyway's classpath scanning does not find
// migrations inside a merged JAR (it would run and silently apply zero
// migrations). The migrator ships via installDist / the `migrator` target in
// docker/Dockerfile, which keeps the classpath as discrete entries.
