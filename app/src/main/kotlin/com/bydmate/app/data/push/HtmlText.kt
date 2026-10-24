package com.bydmate.app.data.push

/**
 * Telegram HTML as plain text for the text-only channels. A link keeps its url after the label
 * («label url», only when they differ) so a report's map link survives as something the reader
 * can still open; `<br>`/`<p>` become newlines, every other tag goes, entities come back.
 */
internal fun htmlToText(html: String): String {
    val links = LINK.replace(html) { m ->
        val url = m.groupValues[1]
        val label = m.groupValues[2]
        if (url.isBlank() || url == label) label else "$label $url"
    }
    // One pass over the entities: a sequential replace would decode «&amp;lt;» twice.
    return ENTITIES.replace(BREAKS.replace(links, "\n").replace(TAG, "")) { m ->
        ENTITY_NAMES[m.groupValues[1]] ?: m.value
    }
}

// The report's own links carry quoted hrefs (TelegramReportBuilder.escapeAttribute); DOT_MATCHES_ALL
// so a label wrapped by the builder never breaks the match.
private val LINK = Regex("""<a\s+href="([^"]*)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val BREAKS = Regex("""<br\s*/?>|</p>|<p[^>]*>""")
private val TAG = Regex("""<[^>]*>""")

// The six entities TelegramReportBuilder can emit.
private val ENTITY_NAMES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "#39" to "'",
    "nbsp" to "\u00A0",
)
private val ENTITIES = Regex("&(amp|lt|gt|quot|\\#39|nbsp);")
