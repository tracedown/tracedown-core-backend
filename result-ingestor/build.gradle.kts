plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.tracedown.ingestor.ApplicationKt")
}

dependencies {
    implementation(project(":tracedown-core-common"))

    // Ktor server (lifecycle container)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    // Database
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.hikari)
    implementation(libs.postgresql)

    // Redis
    implementation(libs.lettuce)

    // Serialization & coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)

    // Test
    testImplementation(libs.kotlin.test)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

// Include all service module migrations for test DB setup
tasks.processTestResources {
    from(project(":api-gateway").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":result-ingestor").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":notification-dispatcher").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":metrics-service").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":aggregate-worker").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
