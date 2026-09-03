package dev.tracedown.email.processing

/**
 * The chrome every outgoing mail carries: a branded header band and, optionally,
 * a small-print footer — rendered once here and substituted into the layout and
 * into every named template as `{{brandHeader}}` / `{{brandFooter}}`.
 *
 * The header is the product's own (name, colours, an optional logo); the footer
 * is the deployment's. Whatever an operator wants under every message — an
 * imprint line, links to their own pages, how to reach them — goes in
 * [footerHtml] (`EMAIL_FOOTER_HTML`) and appears on all mail, whichever service
 * sent it and whether it was a template or a pre-baked body. Templates therefore
 * never carry links to pages this service cannot know exist; the operator
 * supplies them.
 *
 * Everything here is trusted operator configuration, inserted as HTML. The only
 * escaping is of the attribute values we place ourselves.
 */
data class MailBranding(
    /** Absolute URL of a small logo (rendered at 28×28); null shows the wordmark alone. */
    val logoUrl: String? = null,
    /** Where the header links to; null leaves it a plain header. */
    val productUrl: String? = null,
    /** Small-print HTML under every message; null or blank drops the footer row. */
    val footerHtml: String? = null,
    val productName: String = "Tracedown",
) {
    /** A full `<tr>` for the top of the mail card. Never empty. */
    fun headerRow(): String {
        val logo = logoUrl?.takeIf { it.isNotBlank() }?.let {
            """<img src="${attr(it)}" width="28" height="28" alt="" style="width:28px;height:28px;vertical-align:middle;border:0;margin-right:10px;">"""
        } ?: ""
        val wordmark = """<span style="font-size:17px;font-weight:700;letter-spacing:-0.01em;color:$TEXT_ON_DARK;vertical-align:middle;">${escape(productName)}</span>"""
        val inner = productUrl?.takeIf { it.isNotBlank() }?.let {
            """<a href="${attr(it)}" style="text-decoration:none;color:$TEXT_ON_DARK;">$logo$wordmark</a>"""
        } ?: "$logo$wordmark"
        return """<tr><td style="padding:18px 40px;background-color:$DARK;border-top:4px solid $ACCENT;">$inner</td></tr>"""
    }

    /** A full `<tr>` for the bottom of the mail card, or empty when there is no small print. */
    fun footerRow(): String {
        val html = footerHtml?.trim().orEmpty()
        if (html.isEmpty()) return ""
        return """<tr><td style="padding:20px 40px 28px;border-top:1px solid $RULE;"><p style="margin:0;font-size:12px;line-height:1.7;color:$MUTED;">$html</p></td></tr>"""
    }

    /** The two placeholders, as the processor substitutes them. */
    fun placeholders(): Map<String, String> = mapOf(
        HEADER_KEY to headerRow(),
        FOOTER_KEY to footerRow(),
    )

    companion object {
        const val HEADER_KEY = "brandHeader"
        const val FOOTER_KEY = "brandFooter"

        /** The dashboard's palette, so mail reads as the same product. */
        const val DARK = "#222729"
        const val ACCENT = "#ff5e5b"
        const val TEXT_ON_DARK = "#f2f5f4"
        const val RULE = "#e4e4e7"
        const val MUTED = "#71717a"

        private fun escape(s: String) = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun attr(s: String) = escape(s).replace("\"", "&quot;")
    }
}
