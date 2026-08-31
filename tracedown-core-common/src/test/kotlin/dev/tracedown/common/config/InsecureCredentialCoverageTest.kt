package dev.tracedown.common.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The forcing function behind the credential guard.
 *
 * A guard written as a list of known-bad literals only ever refuses the values
 * somebody remembered to add to it, and the values an operator actually reaches
 * for are whichever ones the tracked example files happen to ship. That is
 * exactly how it went wrong: the guard tested for the two literals in the
 * shipped `.conf` defaults while `docker/.env.example` — the only tracked file
 * carrying a complete variable set, and so the natural source for populating a
 * deployment — shipped two *different* ones, and both booted happily under
 * `DEPLOYMENT_ENV=production`.
 *
 * So this test reads the tracked files rather than restating their contents: it
 * finds every credential-shaped assignment in them and asserts the guard would
 * refuse the value in production. Adding a new dev default that the structural
 * checks cannot see fails the build until it is registered in
 * `insecure-credentials.txt`.
 */
class InsecureCredentialCoverageTest {

    /** Files that ship credential values an operator might copy into a deployment. */
    private val trackedSources = listOf(
        "docker/.env.example",
        "docker/deploy/.env.example",
        "scripts/bootstrap-agent.sh",
    )

    private val assignment = Regex("""^\s*([A-Za-z_][A-Za-z0-9_.]*)\s*[=:]\s*(.+?)\s*$""")
    private val credentialName = Regex("""secret|password|passwd|token|credential|key""", RegexOption.IGNORE_CASE)
    private val shellDefault = Regex("""^"?\$\{[A-Za-z_][A-Za-z0-9_]*:-(.*?)}"?$""")

    private data class Finding(val file: String, val line: Int, val name: String, val value: String)

    @Test
    fun `no credential value in a tracked file would be accepted in production`() {
        val findings = scan()
        assertTrue(
            findings.isNotEmpty(),
            "found no credential assignments at all — the scanner or the paths are wrong, " +
                "and this test would then pass no matter what the repository ships",
        )
        val accepted = findings.filter { SecretGuard.credentialWeakness(it.value) == null }
        assertTrue(
            accepted.isEmpty(),
            "these values are published in this repository but would be accepted as production " +
                "credentials:\n" +
                accepted.joinToString("\n") { "  ${it.file}:${it.line} ${it.name} = ${it.value}" } +
                "\nAdd each to tracedown-core-common/src/main/resources/" +
                SecretGuard.PUBLISHED_VALUES_RESOURCE,
        )
    }

    @Test
    fun `the values the docker env example ships are among the ones scanned`() {
        // Guards the scanner itself: if it ever stops seeing this file, the test
        // above becomes a no-op that still passes.
        val findings = scan().filter { it.file.endsWith("docker/.env.example") }
        assertNotNull(findings.firstOrNull { it.name == "PLATFORM_AES_KEY" })
        assertNotNull(findings.firstOrNull { it.name == "JWT_SECRET" })
    }

    @Test
    fun `every shipped conf default for a credential is refused too`() {
        val findings = repoRoot().listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { module ->
                val res = File(module, "src/main/resources")
                (res.listFiles()?.filter { it.extension == "conf" } ?: emptyList())
                    .flatMap { scanFile(it, "${module.name}/src/main/resources/${it.name}") }
            }
            ?: emptyList()
        assertTrue(findings.isNotEmpty(), "no .conf credential defaults found — scanner is wrong")
        val accepted = findings.filter { SecretGuard.credentialWeakness(it.value) == null }
        assertTrue(
            accepted.isEmpty(),
            "shipped configuration defaults that would be accepted in production:\n" +
                accepted.joinToString("\n") { "  ${it.file}:${it.line} ${it.name} = ${it.value}" },
        )
    }

    private fun scan(): List<Finding> = trackedSources.flatMap { relative ->
        val file = File(repoRoot(), relative)
        if (file.isFile) scanFile(file, relative) else emptyList()
    }

    private fun scanFile(file: File, label: String): List<Finding> =
        file.readLines().mapIndexedNotNull { index, raw ->
            if (raw.trimStart().startsWith("#")) return@mapIndexedNotNull null
            val match = assignment.find(raw) ?: return@mapIndexedNotNull null
            val name = match.groupValues[1]
            if (!credentialName.containsMatchIn(name)) return@mapIndexedNotNull null
            var value = match.groupValues[2].substringBefore('#').trim()
            shellDefault.find(value)?.let { value = it.groupValues[1] }
            value = value.trim().trim('"')
            // Not a literal: a variable reference, a command substitution, or a
            // URL path that happens to sit under a credential-ish name.
            if (value.isEmpty() || value.startsWith("$") || value.startsWith("/")) {
                return@mapIndexedNotNull null
            }
            Finding(label, index + 1, name, value)
        }

    /** The backend repository root, found by walking up to the Gradle settings file. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the backend repository root from ${System.getProperty("user.dir")}")
    }
}
