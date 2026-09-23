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
 */
object ShareTextParser {

    /** A scheme, then at least one character that is not whitespace or a quoting delimiter. */
    private val TOKEN = Regex("[A-Za-z][A-Za-z0-9+.\\-]*:[^\\s<>\"']+")

    /** `[label](uri)`, matched against the text *before* a token, so the token is that link's. */
    private val MARKDOWN_OPENER = Regex("\\[([^\\[\\]]*)]\\($")

    /** The schemes the tiers name. Everything else has to look hierarchical to count as a URI. */
    private val NAMED_SCHEMES: Set<String> = LinkLaunchPolicy.ALLOWED + LinkLaunchPolicy.BLOCKED

    fun firstUri(text: String): ParsedShare? {
        val (commented, rest) = text.lines().partition { it.trimStart().startsWith("#") }
        return firstIn(rest) ?: firstIn(commented)
    }

    private fun firstIn(lines: List<String>): ParsedShare? {
        for (line in lines) {
            var from = 0
            while (from < line.length) {
                val match = TOKEN.find(line, from) ?: break
                val uri = trimTrailingPunctuation(match.value)
                if (isPlausible(uri)) {
                    val label = MARKDOWN_OPENER.find(line.substring(0, match.range.first))
                        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    return ParsedShare(uri, label)
                }
                // Prose, not a URI — so look again from one character in, because the real link is
                // very often glued to the far side of the colon that fooled us.
                from = match.range.first + 1
            }
        }
        return null
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
    private fun isPlausible(candidate: String): Boolean {
        val colon = candidate.indexOf(':')
        if (colon <= 0) return false
        val rest = candidate.substring(colon + 1)
        if (rest.isEmpty()) return false
        return rest.startsWith("//") || candidate.substring(0, colon).lowercase() in NAMED_SCHEMES
    }

    /**
     * Sentence punctuation is not part of a URI, and neither is the bracket that closed a Markdown
     * link. A closing bracket is kept when the URI opened one itself, because `?a=(1)` is its own.
     */
    private fun trimTrailingPunctuation(token: String): String {
        var end = token.length
        while (end > 0) {
            val last = token[end - 1]
            val drop = when (last) {
                '.', ',', ';', ':', '!', '?', '’' -> true
                ')' -> token.count { it == ')' } > token.count { it == '(' }
                ']' -> token.count { it == ']' } > token.count { it == '[' }
                '}' -> token.count { it == '}' } > token.count { it == '{' }
                else -> false
            }
            if (!drop) break
            end -= 1
        }
        return token.substring(0, end)
    }
}
