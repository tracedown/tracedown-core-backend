plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.tracedown.realtime.ApplicationKt")
}

dependencies {
    implementation(project(":tracedown-core-common"))

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Database (for session validation)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari)
    implementation(libs.postgresql)

    // Redis
    implementation(libs.lettuce)

    // Serialization & coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

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
}
