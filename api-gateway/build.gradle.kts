plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

application {
    mainClass.set("dev.tracedown.gateway.ApplicationKt")
}

dependencies {
    implementation(project(":tracedown-core-common"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.cors)

    // RRule validation (service maintenance windows)
    implementation(libs.lib.recur)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)

    implementation(libs.hikari)
    implementation(libs.postgresql)

    implementation(libs.lettuce)

    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)

    implementation(libs.bcrypt)
    implementation(libs.java.jwt)
    implementation(libs.java.otp)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    implementation("dev.lacelang:kotlin-validator:0.1.5")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)

    testImplementation("dev.lacelang:kotlin-lacetest:0.1.2")
    // Pinned forward past what the harness asks for. kotlin-lacetest 0.1.2 —
    // the latest published — still declares executor 0.1.3, which predates the
    // spec's assert-only helpers `count(x)` and `includes(search, x)` (S8.1).
    // The validator above is new enough to PARSE them, so without this pin a
    // script using either one compiles, runs, and quietly evaluates the
    // condition to null: a passing-looking assertion that tests nothing.
    // Remove once a kotlin-lacetest release carries the bump (it is fixed in
    // that repo's source, unpublished as of this pin).
    testImplementation("dev.lacelang:lacelang-kotlin-executor:0.1.6")

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)

    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.processTestResources {
    from(project(":result-ingestor").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":notification-dispatcher").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":metrics-service").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    from(project(":aggregate-worker").file("src/main/resources/db")) { into("db"); exclude("**/.gitkeep") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.test {
    environment("PLATFORM_AES_KEY", "0000000000000000000000000000000000000000000000000000000000000000")
}

// The fatJar task is registered for every service module by the root build.

