package com.loosecannon.servicetag.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #102 (acceptance 10) — the tripwire for English that bypasses the language packs, and for UI text leaking into the
 * language-neutral layers.
 *
 * 1. No line of the app's Kotlin outside `api/` and `data/` holds a string literal that reads as prose — two words,
 *    or one capitalised word — unless it is a log line, an exception or check message, or the line says why it stays
 *    with a trailing `// l10n-ok: <reason>` (a wire value, a MIME type, a test-only diagnostic). New words go in
 *    `res/values/strings_<area>.xml` and are read through `stringResource` or `localized`.
 * 2. The Developer API and Room never read UI text: they carry enum names, ids and the owner's own words, so a
 *    language change cannot change what is stored, backed up or served (#102, acceptance 7). `:core` — the domain,
 *    the backup and Transfer Pack codecs — has no Android resources to read in the first place.
 *
 * A heuristic, deliberately: it reads source lines, not a syntax tree, and errs towards flagging.
 */
class UiLiteralGuardTest {

    private val sources: File = listOf(File(SOURCES), File("app/$SOURCES")).firstOrNull { it.isDirectory }
        ?: error("cannot find $SOURCES from ${File(".").absolutePath}")

    @Test fun noUserVisibleEnglishLiteralBypassesTheLanguagePacks() {
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(sources).invariantSeparatorsPath.substringBefore('/') in LANGUAGE_NEUTRAL }
            .toList()
        assertTrue("the scan found no Kotlin under ${sources.path}; it would prove nothing", files.size > 50)

        val offenders = files.sortedBy { it.path }.flatMap { file ->
            file.readLines().withIndex().flatMap { (at, line) ->
                flagged(line).map { "${file.relativeTo(sources).invariantSeparatorsPath}:${at + 1}: \"$it\"" }
            }
        }
        assertEquals(
            "user-visible text belongs in res/values/strings_<area>.xml (docs/localization.md); a literal that is not " +
                "user-visible says so on its line with `// l10n-ok: <reason>`",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun theLanguageNeutralLayersReadNoUiText() {
        val offenders = LANGUAGE_NEUTRAL.flatMap { dir ->
            File(sources, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> UI_TEXT.containsMatchIn(line) }
                    .map { (at, line) -> "${file.relativeTo(sources).invariantSeparatorsPath}:${at + 1}: ${line.trim()}" }
            }
        }
        assertEquals(
            "the Developer API and Room carry language-neutral values only (#102)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun theHeuristicFlagsProseAndSparesCode() {
        assertEquals(listOf("Delete schedule"), flagged("""    Text("Delete schedule")"""))
        assertEquals(listOf("Cancel"), flagged("""internal const val CANCEL = "Cancel""""))
        assertEquals(listOf("Home Assistant has no entity \$id."), flagged("""fun x(id: String) = "Home Assistant has no entity ${'$'}id.""""))
        assertEquals(emptyList<String>(), flagged("""    Log.w(TAG, "the sweep failed; the next start repeats it")"""))
        assertEquals(emptyList<String>(), flagged("""    error("no schedule ${'$'}{id.value}")"""))
        assertEquals(emptyList<String>(), flagged("""    val type = "application/pdf""""))
        assertEquals(emptyList<String>(), flagged("""    putExtra("tag_format", format)"""))
        assertEquals(emptyList<String>(), flagged("""    val header = "Accept: application/json" // l10n-ok: HTTP header"""))
        assertEquals(emptyList<String>(), flagged("""    // A comment that says "Delete schedule" is not code."""))
        assertEquals(emptyList<String>(), flagged("""    Text(stringResource(R.string.maintenance_delete))"""))
    }

    private companion object {
        const val SOURCES = "src/main/kotlin/com/loosecannon/servicetag"

        /** Packages whose values are wire, storage or codec values, never words (#102). */
        val LANGUAGE_NEUTRAL = setOf("api", "data")

        val UI_TEXT = Regex("""\blocalized(Plural|Date|MonthDay|List)?\(|\bstringResource\(|\bR\.(string|plurals)\.""")

        val COMMENT_LINE = Regex("""^\s*(//|\*|/\*|import |package )""")
        val EXEMPT_CALL = Regex(
            """\b(Log\.[a-z]|error|require|requireNotNull|check|checkNotNull|TODO|println)\s*\(|Exception\(|Error\(|throw |@Suppress|@SerialName|@Query""",
        )
        val LITERAL = Regex(""""((?:[^"\\\n]|\\.)*)"""")
        val TEMPLATE = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_.]*""")
        val TWO_WORDS = Regex("""[A-Za-z][a-z']+[,.;:!?]?\s+[A-Za-z(]""")
        val ONE_WORD = Regex("""[A-Z][a-z]{2,}[.!?…]?""")

        /** The literals on [line] that read as prose, with interpolations left in place. */
        fun flagged(line: String): List<String> {
            if (COMMENT_LINE.containsMatchIn(line) || "l10n-ok" in line || EXEMPT_CALL.containsMatchIn(line)) return emptyList()
            val code = line.substringBefore(" // ")
            return LITERAL.findAll(code).map { it.groupValues[1] }.filter { literal ->
                val words = TEMPLATE.replace(literal, " ")
                TWO_WORDS.containsMatchIn(words) || ONE_WORD.matches(words.trim())
            }.toList()
        }
    }
}
