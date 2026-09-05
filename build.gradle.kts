plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "dev.tracedown"

    version = "0.4.8"

    repositories {
        mavenCentral()
    }

    // The Ktor Gradle plugin declares commons-lang3 as an open range
    // ([3.18.0,)), which makes every cold resolve list maven-metadata over
    // the network and fail outright when plugins.gradle.org is unreachable
    // (e.g. inside docker builds). Pin it so resolution is concrete and
    // fully cacheable.
    buildscript {
        configurations.all {
            resolutionStrategy.force("org.apache.commons:commons-lang3:3.20.0")
        }
    }

    // Ktor 3.5.2 ships Netty 4.2.16, which still carries one advisory on
    // netty-codec-http (CVE-2026-59903); 4.2.17 is clean on every module. The
    // same 4.2 line, so it is a drop-in until Ktor moves past it — then delete.
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty" && requested.name.startsWith("netty-")) {
                useVersion("4.2.17.Final")
                because("CVE-2026-50010 and siblings; Ktor 3.5.2 pins 4.2.16")
            }
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    // Every runnable service ships as a single self-contained jar (GitHub
    // release artifacts). schema-migrator is deliberately excluded: Flyway's
    // classpath scanning cannot find migration resources inside a merged jar —
    // it would connect, apply nothing, and report success — so the migrator
    // ships as its installDist-based distZip instead.
    plugins.withId("application") {
        if (name != "schema-migrator") {
            // ServiceLoader files must be CONCATENATED across dependency jars,
            // not first-one-wins — Flyway loads its migration resolvers this
            // way, and a dropped entry fails silently ("No migrations found").
            val mergeServiceFiles = tasks.register("mergeServiceFiles") {
                val out = project.layout.buildDirectory.dir("merged-service-files")
                // Resolved lazily but referenced through a captured FileCollection:
                // Task.project is unavailable at execution time under the
                // configuration cache (Gradle 10 makes it an error).
                val runtimeClasspath = project.configurations.getByName("runtimeClasspath")
                inputs.files(runtimeClasspath)
                outputs.dir(out)
                doLast {
                    val merged = linkedMapOf<String, MutableList<String>>()
                    runtimeClasspath.files
                        .filter { it.isFile && it.name.endsWith(".jar") }
                        .forEach { jar ->
                            java.util.zip.ZipFile(jar).use { zf ->
                                zf.entries().asSequence()
                                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                                    .forEach { entry ->
                                        val lines = zf.getInputStream(entry).bufferedReader().readLines()
                                        merged.getOrPut(entry.name) { mutableListOf() }.addAll(lines)
                                    }
                            }
                        }
                    val outDir = out.get().asFile
                    outDir.deleteRecursively()
                    merged.forEach { (name, lines) ->
                        val f = File(outDir, name)
                        f.parentFile.mkdirs()
                        f.writeText(lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                            .distinct().joinToString("\n"))
                    }
                }
            }

            tasks.register<Jar>("fatJar") {
                archiveClassifier.set("all")
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                dependsOn(mergeServiceFiles)
                // Signed dependencies (e.g. BouncyCastle) ship signature files
                // that cannot survive merging — the JVM then refuses to load
                // the jar ("Could not find or load main class").
                exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
                from(mergeServiceFiles.map { it.outputs.files.singleFile })
                from(project.configurations.getByName("runtimeClasspath").map { if (it.isDirectory) it else zipTree(it) }) {
                    exclude("META-INF/services/**")
                }
                manifest {
                    attributes["Main-Class"] = project.extensions.getByType<JavaApplication>().mainClass.get()
                }
                with(tasks.named<Jar>("jar").get())
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Testcontainers' bundled docker-java defaults to Docker API v1.32, which
        // Docker Engine 25+ rejects (minimum supported API is 1.40). docker-java
        // reads the target version from the `api.version` system property, so pin a
        // modern, widely-supported version to let integration tests reach the daemon.
        systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    }
}
