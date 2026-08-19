package dev.tracedown.notifications.delivery

import dev.tracedown.notifications.templates.TemplateRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests TemplateRenderer integration with webhook-style body templates.
 *
 * WebhookDeliveryService renders the body template using TemplateRenderer.render
 * before sending HTTP requests. These tests verify that webhook body templates
 * with typical variable patterns produce the expected output.
 */
class WebhookDeliveryServiceTest {

    @Test
    fun `renders webhook body with text and service name vars`() {
        val bodyTemplate = """{"text": "${"\${text}"}", "service": "${"\${s.name}"}"}"""
        val vars = mapOf(
            "text" to "API Health in Production.Core call to https://api.example.com has \"expect\": [status: expected 200, got 503]",
            "s.name" to "API Health",
        )

        val rendered = TemplateRenderer.render(bodyTemplate, vars)
        assertTrue(rendered.contains("API Health in Production.Core"))
        assertTrue(rendered.contains("\"service\": \"API Health\""))
    }

    @Test
    fun `renders webhook body with all common vars`() {
        val bodyTemplate = """{
  "service": "${"\${s.name}"}",
  "workspace": "${"\${w.name}"}",
  "project": "${"\${p.name}"}",
  "url": "${"\${url}"}",
  "status": "${"\${status}"}",
  "trigger": "${"\${trigger}"}",
  "responseTimeMs": ${"\${ms}"}
}"""
        val vars = mapOf(
            "s.name" to "Payment API",
            "w.name" to "Production",
            "p.name" to "Payments",
            "url" to "https://api.example.com/payments",
            "status" to "fail",
            "trigger" to "expect",
            "ms" to "450",
        )

        val rendered = TemplateRenderer.render(bodyTemplate, vars)
        assertTrue(rendered.contains("\"service\": \"Payment API\""))
        assertTrue(rendered.contains("\"url\": \"https://api.example.com/payments\""))
        assertTrue(rendered.contains("\"responseTimeMs\": 450"))
    }

    @Test
    fun `renders webhook body with missing vars as empty strings`() {
        val bodyTemplate = """{"text": "${"\${text}"}", "extra": "${"\${nonexistent}"}"}"""
        val vars = mapOf("text" to "Alert fired")

        val rendered = TemplateRenderer.render(bodyTemplate, vars)
        assertEquals("""{"text": "Alert fired", "extra": ""}""", rendered)
    }

    @Test
    fun `renders Slack-style webhook body`() {
        val bodyTemplate = """{"text": ":alert: ${"\${s.name}"} is ${"\${status}"} - ${"\${text}"}"}"""
        val vars = mapOf(
            "s.name" to "Auth Service",
            "status" to "fail",
            "text" to "expected 200, got 503",
        )

        val rendered = TemplateRenderer.render(bodyTemplate, vars)
        assertEquals("""{"text": ":alert: Auth Service is fail - expected 200, got 503"}""", rendered)
    }

    @Test
    fun `renders webhook body with scope and assertion details`() {
        val bodyTemplate = """{"alert": "${"\${s.name}"} ${"\${trigger}"} failure", "scope": "${"\${scope}"}", "expected": "${"\${expected}"}", "actual": "${"\${actual}"}"}"""
        val vars = mapOf(
            "s.name" to "API Health",
            "trigger" to "expect",
            "scope" to "status",
            "expected" to "200",
            "actual" to "503",
        )

        val rendered = TemplateRenderer.render(bodyTemplate, vars)
        assertTrue(rendered.contains("\"scope\": \"status\""))
        assertTrue(rendered.contains("\"expected\": \"200\""))
        assertTrue(rendered.contains("\"actual\": \"503\""))
    }

    @Test
    fun `plain text webhook body renders correctly`() {
        val bodyTemplate = "Service \${s.name} in \${w.name}/\${p.name} is \${status}"
        val vars = mapOf(
            "s.name" to "Orders",
            "w.name" to "Production",
            "p.name" to "E-Commerce",
            "status" to "fail",
        )

        val rendered = TemplateRenderer.render(bodyTemplate, vars)
        assertEquals("Service Orders in Production/E-Commerce is fail", rendered)
    }

    // ── URL org-variable resolution ($o.key) ──

    private val service = WebhookDeliveryService()

    @Test
    fun `resolves org variable in webhook url`() {
        val url = service.resolveUrl(
            "https://api.telegram.org/bot\$o.telegramToken/sendMessage",
            mapOf("telegramToken" to "123456:AA-Ff1234567890"),
        )
        assertEquals("https://api.telegram.org/bot123456:AA-Ff1234567890/sendMessage", url)
    }

    @Test
    fun `resolves multiple refs and leaves plain urls untouched`() {
        assertEquals(
            "https://hooks.example.com/tok",
            service.resolveUrl("https://hooks.example.com/tok", mapOf("x" to "y")),
        )
        val url = service.resolveUrl(
            "https://h.example.com/\$o.a/x/\$o.b",
            mapOf("a" to "AA", "b" to "BB"),
        )
        assertEquals("https://h.example.com/AA/x/BB", url)
    }

    @Test
    fun `unknown reference is left intact rather than dropped`() {
        val url = service.resolveUrl("https://h.example.com/\$o.missing/x", emptyMap())
        assertEquals("https://h.example.com/\$o.missing/x", url)
    }

    // ── URL webhook-variable resolution ($h.key) ──

    @Test
    fun `resolves webhook variable in webhook url`() {
        val url = service.resolveUrl(
            "https://api.telegram.org/bot\$h.botToken/sendMessage",
            emptyMap(),
            mapOf("botToken" to "123456:AA-Ff1234567890"),
        )
        assertEquals("https://api.telegram.org/bot123456:AA-Ff1234567890/sendMessage", url)
    }

    @Test
    fun `resolves org and webhook refs together from their own scopes`() {
        val url = service.resolveUrl(
            "https://h.example.com/\$o.a/x/\$h.b",
            mapOf("a" to "ORG"),
            mapOf("b" to "HOOK"),
        )
        assertEquals("https://h.example.com/ORG/x/HOOK", url)
    }

    @Test
    fun `webhook ref never resolves from org variables and vice versa`() {
        // The same key in the wrong scope's map must not leak across.
        val url = service.resolveUrl(
            "https://h.example.com/\$h.token/\$o.token",
            mapOf("token" to "ORG"),
            emptyMap(),
        )
        assertEquals("https://h.example.com/\$h.token/ORG", url)
    }
}
