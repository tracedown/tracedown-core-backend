import org.gradle.kotlin.dsl.support.serviceOf
import java.net.HttpURLConnection
import java.net.URI

// The monolith: every service in one JVM, one jar. For minimal installs that
// don't want eight processes — probes run on an embedded in-process Lace
// executor instead of external agents, and the frontend bundle is served from
// the classpath. Deliberate trade: deployment simplicity for the microservice
// properties (independent scaling, isolation, per-service rollouts, remote
// probe vantage points).

plugins {
    application
}

application {
    mainClass.set("dev.tracedown.monolith.MonolithKt")
}

dependencies {
    implementation(project(":tracedown-core-common"))
    implementation(project(":schema-migrator"))
    implementation(project(":api-gateway"))
    implementation(project(":probe-scheduler"))
    implementation(project(":result-ingestor"))
    implementation(project(":notification-dispatcher"))
    implementation(project(":email-service"))
    implementation(project(":metrics-service"))
    implementation(project(":aggregate-worker"))
    implementation(project(":realtime-service"))

    // Embedded probe execution — the canonical Kotlin Lace implementations.
    // (The two repos publish under different artifact-id conventions.)
    implementation("dev.lacelang:kotlin-validator:0.1.6")
    implementation("dev.lacelang:lacelang-kotlin-executor:0.1.8")

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
}

val serviceModules = listOf(
    "api-gateway",
    "probe-scheduler",
    "result-ingestor",
    "notification-dispatcher",
    "email-service",
    "metrics-service",
    "aggregate-worker",
    "realtime-service",
)

val generatedResources = layout.buildDirectory.dir("generated-resources/monolith")

// Every service ships an `application.conf`, and nine same-named resources
// cannot coexist in one jar — so each module's conf files are copied under a
// unique prefix (conf/<module>/) and the monolith builds each service's
// configuration from its copy. HOCON `include` resolves relative to the
// including file, so a module's sibling confs come along.
val copyServiceConfs = tasks.register<Copy>("copyServiceConfs") {
    serviceModules.forEach { module ->
        from(project(":$module").file("src/main/resources")) {
            include("*.conf")
            into("conf/$module")
        }
    }
    into(generatedResources)
}

// Flyway cannot enumerate migrations inside a merged jar (its classpath
// scanner comes up empty and reports success) — but per-file resource lookups
// work fine. This manifest lists every migration resource at build time so the
// monolith can extract them to a temp directory and run Flyway against the
// filesystem.
val writeFlywayManifest = tasks.register("writeFlywayManifest") {
    val migrationOwners = listOf(
        "api-gateway", "probe-scheduler", "result-ingestor",
        "notification-dispatcher", "metrics-service", "aggregate-worker",
    )
    val roots = migrationOwners.map { project(":$it").file("src/main/resources/db") }
    inputs.files(roots.filter { it.exists() })
    val outDir = generatedResources
    outputs.dir(outDir)
    doLast {
        val entries = sortedSetOf<String>()
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                entries.add("db/" + f.relativeTo(root).invariantSeparatorsPath)
            }
        }
        val manifest = outDir.get().file("flyway-manifest.txt").asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(entries.joinToString("\n"))
    }
}

// Bundles the frontend into the jar. Opt-in because it needs the network:
//   ./gradlew :tracedown-monolith:fatJar -PmonolithFrontend=latest
//   ./gradlew :tracedown-monolith:fatJar -PmonolithFrontend=v0.1.0
//   ./gradlew :tracedown-monolith:fatJar -PmonolithFrontend=/path/to/dist.tar.gz
// Without the property the jar builds fine and serves a note instead of the UI.
val fetchFrontend = tasks.register("fetchFrontend") {
    val requested = providers.gradleProperty("monolithFrontend")
    val outDir = generatedResources
    // Captured at configuration time: Task.project (and therefore project.copy /
    // project.tarTree / project.resources) is unavailable at execution time under
    // the configuration cache, which Gradle 10 makes mandatory.
    val fsOps = serviceOf<FileSystemOperations>()
    val archiveOps = serviceOf<ArchiveOperations>()
    outputs.upToDateWhen { false }
    doLast {
        val value = requested.orNull ?: return@doLast
        val archive = layout.buildDirectory.file("frontend-dist.tar.gz").get().asFile
        if (File(value).isFile) {
            File(value).copyTo(archive, overwrite = true)
        } else {
            val repo = "tracedown/tracedown-core-frontend"
            val url = if (value == "latest") {
                val meta = URI("https://api.github.com/repos/$repo/releases/latest").toURL()
                    .readText()
                val tag = Regex("\"tag_name\": *\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
                    ?: error("could not resolve latest frontend release")
                "https://github.com/$repo/releases/download/$tag/tracedown-core-frontend-${tag.removePrefix("v")}-dist.tar.gz"
            } else {
                "https://github.com/$repo/releases/download/$value/tracedown-core-frontend-${value.removePrefix("v")}-dist.tar.gz"
            }
            logger.lifecycle("Fetching frontend bundle: $url")
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
            }.inputStream.use { input ->
                archive.parentFile.mkdirs()
                archive.outputStream().use { input.copyTo(it) }
            }
        }
        val target = outDir.get().dir("frontend").asFile
        target.deleteRecursively()
        fsOps.copy {
            from(archiveOps.tarTree(archiveOps.gzip(archive)))
            into(target)
        }
        logger.lifecycle("Frontend bundle unpacked into the monolith resources.")
    }
}

sourceSets.main {
    resources.srcDir(generatedResources)
}

tasks.processResources {
    dependsOn(copyServiceConfs, writeFlywayManifest, fetchFrontend)
}

// The root fatJar task snapshots the jar spec; make sure regenerated
// resources (confs, manifest, frontend) always invalidate it.
tasks.named("fatJar") {
    dependsOn(tasks.processResources)
    inputs.dir(generatedResources)
}
