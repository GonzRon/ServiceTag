package com.loosecannon.servicetag.core.references

/** Constants, not preferences: #18 owns preferences and these are shapes of a row (D-17). */

/** Spec §4.3. Longer than this is a paste accident, not a link. */
const val MAX_REFERENCE_URI_CHARS = 2_048

/** `display_name`'s column rule (spec §3.2), and the cap on a share's subject and title extras. */
const val MAX_REFERENCE_NAME_CHARS = 200

/** `description`'s column rule (spec §3.2); it may be empty, never unbounded. */
const val MAX_REFERENCE_DESCRIPTION_CHARS = 2_000

/**
 * How much shared text `ShareTextParser.firstUri` will read: 64 KiB, the same number as the
 * `text/uri-list` stream cap (plan §18.20) for the same reason — an `EXTRA_TEXT` is whatever the
 * sharing app chose to send, and a scan is work done on the intake screen's thread.
 *
 * It is **not** that cap: the uri-list one bounds a stream, which `:core` never sees, and stays
 * B03's. The accepted trade here is that a URI lying beyond this many characters is not found and
 * the share is offered as a note instead.
 */
const val MAX_SHARE_TEXT_CHARS = 65_536

/**
 * Two sanitisers over text nobody here chose: a display name arrives from a sharing app's subject
 * or title extra, and a filename from whatever a provider called a file (spec §4.3).
 */
object ReferenceText {

    /**
     * Trim, drop control characters, turn every kind of whitespace into a blank, collapse runs of
     * blanks and cap at [MAX_REFERENCE_NAME_CHARS].
     *
     * The order matters: a name of nothing but a NUL byte sanitises to empty, so the blank-name
     * refusal has to be asked **after** this and not before — `"\u0000".isEmpty()` is false.
     */
    fun sanitiseName(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch.isWhitespace() -> append(' ')
                    ch.isISOControl() -> Unit
                    else -> append(ch)
                }
            }
        }
        return cleaned.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_REFERENCE_NAME_CHARS)
            .trim()
    }

    /**
     * A path-free basename: no separators, no `..` segment, no NUL, no newline; empty when nothing
     * survives, which the caller reads as "this file named itself nothing useful" and names it
     * something else.
     */
    fun sanitiseFilename(raw: String): String {
        val basename = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = buildString(basename.length) {
            for (ch in basename) {
                if (!ch.isISOControl()) append(ch)
            }
        }.trim()
        if (cleaned == "." || cleaned == "..") return ""
        return cleaned.take(MAX_REFERENCE_NAME_CHARS)
    }
}
