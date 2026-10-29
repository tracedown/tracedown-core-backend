package dev.tracedown.notifications.templates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TemplateRendererTest {

    @Test
    fun `renders simple variable`() {
        val result = TemplateRenderer.render("Hello \${name}!", mapOf("name" to "Alice"))
        assertEquals("Hello Alice!", result)
    }

    @Test
    fun `renders multiple variables`() {
        val template = "\${s.name} in \${w.name}.\${p.name} failed"
        val vars = mapOf("s.name" to "API", "w.name" to "Prod", "p.name" to "Core")
        assertEquals("API in Prod.Core failed", TemplateRenderer.render(template, vars))
    }

    @Test
    fun `missing variable renders as empty string`() {
        val result = TemplateRenderer.render("Hello \${unknown}!", mapOf("name" to "Alice"))
        assertEquals("Hello !", result)
    }

    @Test
    fun `escaped dollar-brace renders as literal`() {
        val result = TemplateRenderer.render("Use \\\${var} syntax", mapOf("var" to "NOPE"))
        assertEquals("Use \${var} syntax", result)
    }

    @Test
    fun `backslash not followed by dollar-brace is literal`() {
        val result = TemplateRenderer.render("path\\to\\file", emptyMap())
        assertEquals("path\\to\\file", result)
    }

    @Test
    fun `unclosed brace is rendered as literal`() {
        val result = TemplateRenderer.render("broken \${unclosed", emptyMap())
        assertEquals("broken \${unclosed", result)
    }

    @Test
    fun `empty template returns empty`() {
        assertEquals("", TemplateRenderer.render("", emptyMap()))
    }

    @Test
    fun `template with no variables passes through`() {
        val text = "No variables here."
        assertEquals(text, TemplateRenderer.render(text, emptyMap()))
    }

    @Test
    fun `renderHtml wraps values in styled spans`() {
        val result = TemplateRenderer.renderHtml("Status: \${status}", mapOf("status" to "503"))
        assertTrue(result.contains("<span class=\"var\">503</span>"))
        assertTrue(result.contains("Status: "))
    }

    @Test
    fun `renderHtml escapes HTML in values`() {
        val result = TemplateRenderer.renderHtml("\${val}", mapOf("val" to "<script>alert(1)</script>"))
        assertFalse(result.contains("<script>"))
        assertTrue(result.contains("&lt;script&gt;"))
    }

    @Test
    fun `renderHtml escapes literal text`() {
        val result = TemplateRenderer.renderHtml("a < b", emptyMap())
        assertTrue(result.contains("&lt;"))
    }

    @Test
    fun `renderHtml keeps line breaks between messages`() {
        val result = TemplateRenderer.renderHtml("first message\nsecond message", emptyMap())
        assertEquals("first message<br>\nsecond message", result)
    }
}
