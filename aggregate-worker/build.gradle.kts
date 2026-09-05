plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.tracedown.worker.ApplicationKt")
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
    // JUnit Platform 6. The BOM is what actually moves the engine: both
    // kotlin-test-junit5 and testcontainers-junit-jupiter still ask for
    // Jupiter 5.x transitively, and the BOM lifts every junit artifact onto
    // one version. Gradle no longer injects the platform launcher into the
    // test runtime, so it is declared here rather than inherited.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
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
