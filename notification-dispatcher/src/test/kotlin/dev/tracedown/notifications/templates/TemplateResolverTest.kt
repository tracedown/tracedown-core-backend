package dev.tracedown.notifications.templates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TemplateResolverTest {

    @Test
    fun `returns structured template for expect trigger`() {
        val template = TemplateResolver.defaultTemplate("expect")
        assertTrue(template.contains("\${s.name}"))
        assertTrue(template.contains("\${trigger}"))
        // Per-scope failure details are now composed into ${conditions}.
        assertTrue(template.contains("\${conditions}"))
    }

    @Test
    fun `baseline_spike uses the structured template`() {
        assertEquals(TemplateResolver.defaultTemplate("expect"), TemplateResolver.defaultTemplate("baseline_spike"))
    }

    @Test
    fun `returns expect-check template for check trigger`() {
        val template = TemplateResolver.defaultTemplate("check")
        assertEquals(TemplateResolver.defaultTemplate("expect"), template)
    }

    @Test
    fun `returns assert template for assert trigger`() {
        val template = TemplateResolver.defaultTemplate("assert")
        assertTrue(template.contains("assertion failed"))
        assertTrue(template.contains("\${conditions}"))
        assertFalse(template.contains("\${scope}"))
    }

    @Test
    fun `returns timeout template for timeout trigger`() {
        val template = TemplateResolver.defaultTemplate("timeout")
        assertTrue(template.contains("timed out"))
        assertTrue(template.contains("\${ms}"))
        assertFalse(template.contains("\${expected}"))
    }

    @Test
    fun `returns default expect-check template for unknown trigger`() {
        val template = TemplateResolver.defaultTemplate("unknown_trigger")
        assertEquals(TemplateResolver.defaultTemplate("expect"), template)
    }

    @Test
    fun `expect and check share the same default template`() {
        assertEquals(
            TemplateResolver.defaultTemplate("expect"),
            TemplateResolver.defaultTemplate("check"),
        )
    }

    @Test
    fun `assert template differs from expect template`() {
        assertNotEquals(
            TemplateResolver.defaultTemplate("expect"),
            TemplateResolver.defaultTemplate("assert"),
        )
    }

    @Test
    fun `timeout template differs from expect and assert templates`() {
        val timeout = TemplateResolver.defaultTemplate("timeout")
        assertNotEquals(TemplateResolver.defaultTemplate("expect"), timeout)
        assertNotEquals(TemplateResolver.defaultTemplate("assert"), timeout)
    }

    @Test
    fun `default templates are valid TemplateRenderer inputs`() {
        val vars = mapOf(
            "s.name" to "API",
            "w.name" to "Prod",
            "p.name" to "Core",
            "url" to "https://example.com",
            "trigger" to "expect",
            "scope" to "status",
            "expected" to "200",
            "actual" to "503",
            "ms" to "1500",
        )

        for (trigger in listOf("expect", "check", "assert", "timeout")) {
            val template = TemplateResolver.defaultTemplate(trigger)
            val rendered = TemplateRenderer.render(template, vars)
            assertFalse(rendered.contains("\${"), "Unresolved variable in $trigger template: $rendered")
        }
    }
}
