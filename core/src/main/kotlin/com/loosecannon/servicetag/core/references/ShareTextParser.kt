package com.loosecannon.servicetag.core.references

/** A URI found in shared text, with the label a Markdown link gave it — the name prefill. */
data class ParsedShare(val uri: String, val label: String?)

/**
 * What a share's text yields (spec §4.4). The rule the whole object exists to keep is #35's:
 * **raw text is never stored as a URI.** Text holding no URI token yields null and the intake
 * screen offers a journal note instead; nothing here guesses a scheme onto prose.
 *
 * `text/uri-list` needs no second parser, and [firstUri] is given no MIME type to branch on: a
 * `#` line is **deprioritised rather than skipped**, so a commented-out URI never wins over a real
 * one — the uri-list rule — while `text/plain`, which spec §4.4 scans whole, still gives up the
 * link in a Markdown heading when that is the only URI the share carried.
 *
 * **The scan is linear**, because it runs on the intake screen's thread over an `EXTRA_TEXT` a
 * stranger chose: every colon is examined exactly once, and only the first candidate that could be
 * a URI has its token read.
 */
object ShareTextParser {

    /** `[label](uri)`, matched against the text *before* a token, so the token is that link's. */
    private val MARKDOWN_OPENER = Regex("\\[([^\\[\\]]*)]\\($")

    /** The schemes the tiers name. Everything else has to look hierarchical to count as a URI. */
    private val NAMED_SCHEMES: Set<String> = LinkLaunchPolicy.ALLOWED + LinkLaunchPolicy.BLOCKED

    fun firstUri(text: String): ParsedShare? {
        val (commented, rest) = text.lines().partition { it.trimStart().startsWith("#") }
        return firstIn(rest) ?: firstIn(commented)
    }

    /**
     * A colon at a time, forward, never back. Each colon is **spent** whether or not it turned out
     * to be a scheme's, which is what keeps a colon-dense share linear — and, behaviourally, is why
     * a word that merely ends in a scheme is not that scheme: `xmailto:` is not a `mailto:`.
     */
    private fun firstIn(lines: List<String>): ParsedShare? {
        for (line in lines) {
            var from = 0
            while (from < line.length) {
                val colon = line.indexOf(':', from)
                if (colon < 0) break
                from = colon + 1
                val start = schemeStartBefore(line, colon) ?: continue
                if (!isPlausible(line, start, colon)) continue
                val uri = trimTrailingPunctuation(tokenAt(line, start))
                // `mailto:.` trims back to a scheme with nothing after it, which is not a URI.
                if (uri.length <= colon - start + 1) continue
                return ParsedShare(uri, labelBefore(line, start))
            }
        }
        return null
    }

    /**
     * Where the scheme in front of [colon] begins, or null when there is none. RFC 3986's
     * production, read backwards: letters, digits, `+`, `-` and `.`, beginning with a letter —
     * which is why `android-app` is a scheme and a run starting `9` is not, though the letter
     * inside it may still begin one.
     */
    private fun schemeStartBefore(line: String, colon: Int): Int? {
        var start = colon
        while (start > 0 && isSchemeChar(line[start - 1])) start -= 1
        while (start < colon && !isAsciiLetter(line[start])) start += 1
        return if (start < colon) start else null
    }

    /**
     * A colon does not make a scheme. Apps share `Title:URL` with no space between them, and a
     * Markdown label may carry a colon of its own; taking either as a scheme would store a URI
     * that can never open, and `uri` is immutable once written (I-1), so the only repair is delete
     * and re-add.
     *
     * A candidate qualifies when it is written with a `//` authority, or when its scheme is one
     * the tier tables name — which keeps an opaque `joplin:x-callback-url/…` and a `mailto:` while
     * `belt:` and `Note:` lose to the real link beside them. An unknown-but-hierarchical
     * `zotero://…` still qualifies, so the confirmation tier is not closed off.
     */
    private fun isPlausible(line: String, start: Int, colon: Int): Boolean {
        if (colon + 1 >= line.length || isDelimiter(line[colon + 1])) return false
        if (line.startsWith("//", colon + 1)) return true
        return line.substring(start, colon).lowercase() in NAMED_SCHEMES
    }

    /** The whole token from [start]: everything up to a blank or a quoting delimiter. */
    private fun tokenAt(line: String, start: Int): String {
        var end = start
        while (end < line.length && !isDelimiter(line[end])) end += 1
        return line.substring(start, end)
    }

    private fun labelBefore(line: String, start: Int): String? =
        MARKDOWN_OPENER.find(line.substring(0, start))
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Sentence punctuation is not part of a URI, and neither is the bracket that closed a Markdown
     * link. A closing bracket is kept when the URI opened one itself, because `?a=(1)` is its own.
     */
    private fun trimTrailingPunctuation(token: String): String {
        var parens = 0
        var brackets = 0
        var braces = 0
        for (ch in token) {
            when (ch) {
                '(' -> parens += 1
                ')' -> parens -= 1
                '[' -> brackets += 1
                ']' -> brackets -= 1
                '{' -> braces += 1
                '}' -> braces -= 1
            }
        }
        var end = token.length
        while (end > 0) {
            val drop = when (token[end - 1]) {
                '.', ',', ';', ':', '!', '?', '’' -> true
                ')' -> parens < 0
                ']' -> brackets < 0
                '}' -> braces < 0
                else -> false
            }
            if (!drop) break
            end -= 1
        }
        return token.substring(0, end)
    }

    private fun isAsciiLetter(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'

    private fun isSchemeChar(ch: Char): Boolean =
        isAsciiLetter(ch) || ch in '0'..'9' || ch == '+' || ch == '-' || ch == '.'

    private fun isDelimiter(ch: Char): Boolean =
        ch.isWhitespace() || ch == '<' || ch == '>' || ch == '"' || ch == '\''
}
