// Plugin portal first, Central as a fallback so plugin-classpath resolution
// (and its transitive deps) survives a plugins.gradle.org outage.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "tracedown-backend"

include(
    "tracedown-core-common",
    "schema-migrator",
    "api-gateway",
    "probe-scheduler",
    "result-ingestor",
    "notification-dispatcher",
    "email-service",
    "metrics-service",
    "aggregate-worker",
    "realtime-service",
    "tracedown-monolith"
)
