package com.loosecannon.servicetag.core.references

/** A URI found in shared text, with the label a Markdown link gave it — the name prefill. */
data class ParsedShare(val uri: String, val label: String?)

/**
 * What a share's text yields (spec §4.4). The rule the whole object exists to keep is #35's:
 * **raw text is never stored as a URI.** Text holding no URI token yields null and the intake
 * screen offers a journal note instead; nothing here guesses a scheme onto prose.
 *
 * `text/uri-list` needs no second parser: one URI per line with `#` comments is a strict subset of
 * "the first URI token in the text", once comment lines are skipped — which is what [firstUri]
 * does, so a list whose first line is a comment yields the line below it.
 */
object ShareTextParser {

    /** A scheme, then at least one character that is not whitespace or a quoting delimiter. */
    private val TOKEN = Regex("[A-Za-z][A-Za-z0-9+.\\-]*:[^\\s<>\"']+")

    /** `[label](uri)`, matched against the text *before* a token, so the token is that link's. */
    private val MARKDOWN_OPENER = Regex("\\[([^\\[\\]]*)]\\($")

    fun firstUri(text: String): ParsedShare? {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            // The uri-list comment convention, applied to every line: a commented-out URI is not
            // the share's URI, and on plain text a leading `#` is a heading rather than a link.
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val match = TOKEN.find(line) ?: continue
            val uri = trimTrailingPunctuation(match.value)
            if (uri.isEmpty()) continue
            val label = MARKDOWN_OPENER.find(line.substring(0, match.range.first))
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            return ParsedShare(uri, label)
        }
        return null
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
