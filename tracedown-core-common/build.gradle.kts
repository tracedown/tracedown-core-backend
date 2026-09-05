plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)

    implementation(libs.hikari)
    implementation(libs.postgresql)

    implementation(libs.lettuce)

    implementation(libs.koin.core)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.logback)
    // Shared logback-base.xml (this module's resources) uses the logstash JSON
    // encoder, which must be on every consuming service's runtime classpath,
    // so it lives here.
    implementation(libs.logstash.logback.encoder)

    implementation(libs.simple.java.mail)
    implementation(libs.simple.java.mail.batch)
    implementation(libs.okhttp)
    implementation(libs.minio)

    implementation(libs.ktor.server.core)
    implementation(libs.java.jwt)
    implementation(libs.java.otp)
    implementation(libs.bcrypt)

    // JUnit Platform 6. The BOM is what actually moves the engine: both
    // kotlin-test-junit5 and testcontainers-junit-jupiter still ask for
    // Jupiter 5.x transitively, and the BOM lifts every junit artifact onto
    // one version. Gradle no longer injects the platform launcher into the
    // test runtime, so it is declared here rather than inherited.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotlin.test)
}
