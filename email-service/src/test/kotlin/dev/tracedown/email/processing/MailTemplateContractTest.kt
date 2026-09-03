package dev.tracedown.email.processing

import dev.tracedown.common.email.EmailMessage
import dev.tracedown.common.email.EmailTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * email-service's half of the contract with every service that sends mail.
 *
 * A sender publishes a **type plus variables**; this service resolves the type to
 * `<type with . → />.html`, first under the configured template directory and then
 * on the classpath, and substitutes `{{key}}` textually. Neither half fails loudly
 * when they disagree: a type with no template anywhere is logged as
 * `template not found` and the mail is *dropped*, and a placeholder no sender
 * supplies is delivered to the customer as the literal text `{{orgName}}`. So the
 * key sets are pinned on both sides — the sending services pin what they publish,
 * these pin what the templates consume — and a rename that lands on only one side
 * fails a build instead of losing or defacing mail.
 *
 * **Scope.** Only mail this edition ships is pinned here: the two `system.*`
 * templates api-gateway publishes, and the shared `layout.html`. Mail belonging to
 * a host application arrives through the template directory instead, and its
 * templates are pinned by a contract test in the repository that owns them. This
 * module knows nothing about any of it, which is the point of the seam.
 *
 * The second half of this file covers that seam, because it is the piece with a
 * way to go quietly wrong: a directory that resolves nothing still sends mail
 * (the packaged copy), and a directory that resolves the *wrong* file sends that.
 */
class MailTemplateContractTest {

    /**
     * Every named template this edition ships and the exact variable set its
     * sender publishes.
     *
     * Exact, not "at least": a template that reads a key nobody sends renders a
     * placeholder into the customer's inbox, and a key sent to a template that
     * ignores it is a fact somebody believed was being said and is not.
     */
    private val contract = mapOf(
        // api-gateway → InviteController.sendInviteEmail
        "system.invite" to setOf("inviterName", "orgName", "inviteLink"),
        // api-gateway → AuthController password reset
        "system.password-reset" to setOf("userName", "expiryMinutes", "resetLink"),
    )

    // ── the shipped templates ────────────────────────────────────────────────

    @Test
    fun `every named type resolves to a template`() {
        for (type in contract.keys) {
            assertTrue(
                classpathSource(type) != null,
                "no template for '$type' — email-service logs 'template not found' and DROPS the mail",
            )
        }
    }

    @Test
    fun `a template reads exactly the variables its sender publishes`() {
        for ((type, expected) in contract) {
            assertEquals(
                expected,
                placeholdersIn(classpathSource(type)!!),
                "'$type' reads a different variable set than its sender publishes",
            )
        }
    }

    @Test
    fun `a rendered template leaves no placeholder behind`() {
        for ((type, vars) in contract) {
            val html = render(type, vars.associateWith { "value-of-$it" })
            assertFalse(
                PLACEHOLDER.containsMatchIn(html),
                "'$type' rendered with an unsubstituted placeholder: " +
                    PLACEHOLDER.findAll(html).map { it.value }.toList(),
            )
            for (key in vars) {
                assertTrue("value-of-$key" in html, "'$type' never prints {{$key}}")
            }
        }
    }

    @Test
    fun `the whole document is the template, not a body fragment`() {
        // Named-template mode does NOT wrap in layout.html — only body mode does.
        // A fragment here would go out as a bare <p> with no <html> around it.
        for (type in contract.keys) {
            val html = classpathSource(type)!!
            assertTrue(html.trimStart().startsWith("<!DOCTYPE html>"), "'$type' is not a complete document")
            assertTrue("</html>" in html, "'$type' is not a complete document")
        }
    }

    @Test
    fun `a named template is delivered whole and is not wrapped in the layout`() {
        val html = render("system.invite", contract.getValue("system.invite").associateWith { "x" })
        assertEquals(
            1,
            Regex("<!DOCTYPE html>", RegexOption.IGNORE_CASE).findAll(html).count(),
            "the named template was wrapped in layout.html — two documents went out as one mail",
        )
        assertFalse("{{content}}" in html)
    }

    /**
     * These files ship in the open-source edition, so no template shipped *here*
     * may carry vocabulary this module has no business knowing.
     *
     * It is a constraint on this module's own resources, not on what a host may
     * say. A host whose notices need wording outside that vocabulary supplies
     * those templates through the template directory, where this constraint does
     * not reach — which is the point of the seam. Forcing the constraint onto
     * every mail the service can render, as this test once did, takes wording
     * away from the people who need it and gains this module nothing.
     *
     * The word list below is the only place these terms may appear in this
     * module: it is the guard, and a guard has to name what it forbids.
     */
    @Test
    fun `no shipped template carries commercial vocabulary`() {
        @Suppress("SpellCheckingInspection")
        val forbidden = listOf(
            "cloud", "billing", "billed", "tier", "plan", "subscription", "subscribe",
            "stripe", "quota", "overage", "invoice", "payment", "credit", "refund",
            "upgrade", "downgrade", "pricing", "limit",
        )
        for (file in shippedTemplates()) {
            val text = file.readText().lowercase()
            for (word in forbidden) {
                assertFalse(
                    Regex("\\b${Regex.escape(word)}").containsMatchIn(text),
                    "${file.name} contains '$word' — this module ships open source and " +
                        "must carry no host-specific commercial vocabulary",
                )
            }
        }
    }

    // ── the template-directory seam ──────────────────────────────────────────

    @Test
    fun `a template directory adds a type this edition does not ship`(@TempDir root: File) {
        // The whole reason the seam exists: a host contributes mail of its own
        // without a line of it living in this module.
        write(root, "reports/weekly.html", "<!DOCTYPE html><html><body>Weekly report for {{orgName}}</body></html>")

        val html = render("reports.weekly", mapOf("orgName" to "Acme"), root.path)
        assertTrue("Weekly report for Acme" in html)
    }

    @Test
    fun `a template directory wins over the shipped template of the same name`(@TempDir root: File) {
        write(root, "system/invite.html", "<!DOCTYPE html><html><body>OVERRIDDEN {{inviteLink}}</body></html>")

        val html = render("system.invite", contract.getValue("system.invite").associateWith { "x" }, root.path)
        assertTrue("OVERRIDDEN" in html, "the directory must be consulted before the classpath")
        assertFalse("You're invited to join" in html, "the packaged copy still went out")
    }

    @Test
    fun `the layout is overridable the same way`(@TempDir root: File) {
        write(root, "layout.html", "<!DOCTYPE html><html><body>WRAPPER {{content}}</body></html>")

        val html = render(bodyEnvelope("<p>hello</p>"), root.path)
        assertTrue("WRAPPER" in html)
        assertTrue("hello" in html)
    }

    @Test
    fun `no configured directory leaves today's behaviour exactly as it was`() {
        // Unset and blank are both "packaged templates only" — the default any
        // existing deployment is already running on.
        val vars = contract.getValue("system.invite").associateWith { "x" }
        val expected = renderPackaged("system.invite", vars)
        assertEquals(expected, render("system.invite", vars, null))
        assertEquals(expected, render("system.invite", vars, "   "))
    }

    /** The shipped template rendered by hand, independent of the processor. */
    private fun renderPackaged(type: String, vars: Map<String, String>): String =
        // The processor's own order: brand rows first, then the sender's variables.
        vars.entries.fold(
            MailBranding().placeholders().entries.fold(classpathSource(type)!!) { acc, (k, v) -> acc.replace("{{$k}}", v) },
        ) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    @Test
    fun `a directory that does not exist falls back to the shipped templates`(@TempDir root: File) {
        val missing = File(root, "not-created-by-anyone")
        assertFalse(missing.exists())

        // A typo in the operator's configuration must cost the override, not all mail.
        val html = render("system.invite", contract.getValue("system.invite").associateWith { "x" }, missing.path)
        assertTrue("You're invited to join" in html)
    }

    @Test
    fun `a directory entry that is not a readable file falls back to the shipped template`(@TempDir root: File) {
        // A half-finished override — the directory is there, the file is not.
        File(root, "system").mkdirs()

        val html = render("system.invite", contract.getValue("system.invite").associateWith { "x" }, root.path)
        assertTrue("You're invited to join" in html)
    }

    @Test
    fun `a path that resolves outside the directory is refused`(@TempDir root: File) {
        val outside = Files.createTempDirectory("outside-the-root").toFile()
        try {
            File(outside, "secret.html").writeText("<!DOCTYPE html><html><body>TOP SECRET</body></html>")
            // A symlink is the traversal that survives a textual check: every
            // segment of `escape/secret.html` looks ordinary and it still lands
            // outside the root, so containment is asserted on the canonical path.
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())

            val sent = sendAll(listOf(envelope("escape.secret", emptyMap())), root.path)
            assertTrue(
                sent.isEmpty(),
                "a template path escaping the root was read and mailed: ${sent.firstOrNull()?.htmlBody}",
            )
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `a refused path does not poison the rest of the mail path`(@TempDir root: File) {
        val outside = Files.createTempDirectory("outside-the-root").toFile()
        try {
            File(outside, "secret.html").writeText("<!DOCTYPE html><html><body>TOP SECRET</body></html>")
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())

            val vars = contract.getValue("system.invite").associateWith { "x" }
            val sent = sendAll(
                listOf(envelope("escape.secret", emptyMap()), envelope("system.invite", vars)),
                root.path,
            )
            // One refusal, one ordinary invite: a bad override costs its own
            // mail and nothing else.
            assertEquals(1, sent.size)
            assertTrue("You're invited to join" in sent.single().htmlBody)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `an absolute or parent-walking type never reaches the filesystem`(@TempDir root: File) {
        // `type` has its dots turned into separators, so `.etc.passwd` asks for
        // `/etc/passwd.html` and `..foo` asks for `//foo.html`. Neither may be
        // treated as a path relative to the root.
        val sent = sendAll(
            listOf(envelope(".etc.passwd", emptyMap()), envelope("..system..invite", emptyMap())),
            root.path,
        )
        assertTrue(sent.isEmpty(), "an escaping type resolved to something: ${sent.map { it.htmlBody }}")
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun write(root: File, relative: String, content: String) {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /** Renders [type] the way the queue consumer would, and returns the HTML sent. */
    private fun render(type: String, vars: Map<String, String>, templateDir: String? = null): String =
        render(envelope(type, vars), templateDir)

    private fun render(envelope: JsonObject, templateDir: String?): String {
        val sent = sendAll(listOf(envelope), templateDir)
        assertEquals(1, sent.size, "the envelope produced no mail — the template did not resolve")
        return sent[0].htmlBody
    }

    /** Runs [envelopes] through one processor and returns everything it sent. */
    private fun sendAll(
        envelopes: List<JsonObject>,
        templateDir: String?,
        branding: MailBranding = MailBranding(),
    ): List<EmailMessage> {
        val sent = CopyOnWriteArrayList<EmailMessage>()
        val transport = object : EmailTransport {
            override fun send(message: EmailMessage) { sent.add(message) }
            override fun sendBatch(messages: List<EmailMessage>) { sent.addAll(messages) }
            override fun close() {}
        }
        val processor = RecordingProcessor(transport, templateDir, branding)
        envelopes.forEach { processor.process(it) }
        return sent
    }

    private fun envelope(type: String, vars: Map<String, String>): JsonObject = buildJsonObject {
        put("id", "contract-$type-${vars.size}")
        put("to", "recipient@example.com")
        put("subject", "contract")
        put("type", type)
        put("vars", buildJsonObject { vars.forEach { (k, v) -> put(k, v) } })
        put("source", "contract-test")
        put("createdAt", "2026-05-05T12:00:00Z")
    }

    private fun bodyEnvelope(body: String): JsonObject = buildJsonObject {
        put("id", "contract-body")
        put("to", "recipient@example.com")
        put("subject", "contract")
        put("body", body)
        put("source", "contract-test")
        put("createdAt", "2026-05-05T12:00:00Z")
    }

    private fun classpathSource(type: String): String? =
        javaClass.getResourceAsStream("/email-templates/${type.replace('.', '/')}.html")
            ?.bufferedReader()?.readText()

    /**
     * The placeholders a *sender* must supply: every `{{key}}` in the template
     * except the two brand rows, which the processor itself resolves from its
     * branding configuration (see [MailBranding]).
     */
    private fun placeholdersIn(html: String): Set<String> =
        PLACEHOLDER.findAll(html).map { it.groupValues[1] }.toSet() - BRAND_KEYS

    @Test
    fun `every shipped template carries the brand header and footer rows`() {
        // Named templates are whole documents, so the chrome cannot come from
        // the layout — each template has to place the two rows itself, or its
        // mail goes out unbranded and without the deployment's small print.
        for (file in shippedTemplates()) {
            if (file.name == "layout.html") continue
            val html = file.readText()
            for (key in BRAND_KEYS) {
                assertTrue("{{$key}}" in html, "${file.name} does not place {{$key}}")
            }
        }
    }

    @Test
    fun `the brand rows are resolved in templates and in the layout`() {
        val branding = MailBranding(
            logoUrl = "https://example.test/logo.png",
            productUrl = "https://example.test",
            footerHtml = "<a href=\"https://example.test/terms\">Terms</a>",
        )
        val sent = sendAll(
            listOf(envelope("system.invite", contract.getValue("system.invite").associateWith { "x" }), bodyEnvelope("hello")),
            templateDir = null,
            branding = branding,
        )
        assertEquals(2, sent.size)
        for (html in sent.map { it.htmlBody }) {
            assertFalse("{{brandHeader}}" in html || "{{brandFooter}}" in html, "brand placeholder left behind")
            assertTrue("https://example.test/logo.png" in html, "logo not rendered")
            assertTrue("https://example.test/terms" in html, "host footer not rendered")
        }
    }

    @Test
    fun `no branding configured still resolves both rows`() {
        val html = render("system.invite", contract.getValue("system.invite").associateWith { "x" })
        assertFalse("{{brandHeader}}" in html)
        assertFalse("{{brandFooter}}" in html)
        assertTrue("Tracedown" in html, "the wordmark header is the default")
    }

    /** Every template packaged in this module, so the purity sweep cannot miss one. */
    private fun shippedTemplates(): List<File> {
        val root = File(javaClass.getResource("/email-templates")!!.toURI())
        return root.walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
            .also { assertTrue(it.size > contract.size, "template scan found nothing") }
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{([A-Za-z0-9_]+)}}")
        /** Resolved by the processor, not by any sender. */
        val BRAND_KEYS = setOf(MailBranding.HEADER_KEY, MailBranding.FOOTER_KEY)
    }

    /** The processor with idempotency disabled — there is no Redis in a unit test. */
    private class RecordingProcessor(
        transport: EmailTransport,
        templateDir: String?,
        branding: MailBranding = MailBranding(),
    ) : EmailProcessor(transport, null, templateDir, branding) {
        override fun checkIdempotency(id: String): Boolean = true
    }
}
