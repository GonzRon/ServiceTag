package com.loosecannon.servicetag.core.journal

import java.text.Normalizer
import java.util.Locale

/**
 * The category identity rule (#74, C2). **The rule is persisted** — it is `asset_category`'s primary
 * key and an archive field — so changing it later is a migration plus a format bump.
 *
 * - [display]: Unicode NFC, trimmed, and every run of `Char.isWhitespace()` characters collapsed to
 *   one space. The owner's spelling otherwise kept.
 * - [of]: [display], then `lowercase(Locale.ROOT)`, and **no further case folding** — `ß` and the
 *   final sigma are not folded; that is stated, not accidental. `Locale.ROOT`, never the device's
 *   locale, so `INFO` keys as `info` on a Turkish phone too.
 *
 * Blank text has no key and is never promoted.
 */
object CategoryKey {

    /** The key of [text], or null when [text] is blank. */
    fun of(text: String): String? = display(text).takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /** [text] in NFC, trimmed, whitespace runs collapsed to one space; `""` when blank. */
    fun display(text: String): String {
        val normal = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
        return buildString(normal.length) {
            var inRun = false
            for (c in normal) {
                if (c.isWhitespace()) {
                    if (!inRun) append(' ')
                    inRun = true
                } else {
                    append(c)
                    inRun = false
                }
            }
        }
    }
}
