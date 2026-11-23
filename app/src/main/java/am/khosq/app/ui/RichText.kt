package am.khosq.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Message text the way WhatsApp writes it: *bold*, _italic_, ~strike~ and `mono`, and web addresses that open
 * when tapped. The markers are removed from the shown text; the stored message keeps them.
 */
private val FORMAT = Regex("(?<![\\p{L}\\p{N}])([*_~`])(?=\\S)(.+?)(?<=\\S)\\1(?![\\p{L}\\p{N}])")
private val LINK = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+")

fun richText(raw: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendFormatted(raw, linkColor, 0)
}

private fun styleFor(marker: Char): SpanStyle = when (marker) {
    '*' -> SpanStyle(fontWeight = FontWeight.Bold)
    '_' -> SpanStyle(fontStyle = FontStyle.Italic)
    '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    else -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x26808080))
}

private fun AnnotatedString.Builder.appendFormatted(text: String, linkColor: Color, depth: Int) {
    var last = 0
    for (m in FORMAT.findAll(text)) {
        if (m.range.first > last) appendLinks(text.substring(last, m.range.first), linkColor)
        val marker = m.groupValues[1][0]
        val inner = m.groupValues[2]
        withStyle(styleFor(marker)) {
            if (marker != '`' && depth < 2) appendFormatted(inner, linkColor, depth + 1) else appendLinks(inner, linkColor)
        }
        last = m.range.last + 1
    }
    if (last < text.length) appendLinks(text.substring(last), linkColor)
}

private fun AnnotatedString.Builder.appendLinks(text: String, linkColor: Color) {
    var last = 0
    for (m in LINK.findAll(text)) {
        var url = m.value
        while (url.isNotEmpty() && url.last() in ".,;:!?)]}\u00BB\"'") url = url.dropLast(1)
        if (url.length < 6) continue
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val href = if (url.startsWith("www.", ignoreCase = true)) "https://" + url else url
        val look = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        withLink(LinkAnnotation.Url(href, look)) { append(url) }
        last = m.range.first + url.length
    }
    if (last < text.length) append(text.substring(last))
}
