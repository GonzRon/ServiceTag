package com.loosecannon.servicetag.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 3. Behaviour never keys on rendered words, and no date or number the owner reads or types takes a fixed pattern or
 *    locale (PR #106 review): both go through a typed value or `l10n/Localized.kt`.
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

    /**
     * #102 (PR #106 review): behaviour never keys on rendered text. A comparison with a localized getter — the screen
     * styling a line because it equals "Already here", a picker mapping a chosen label back to a value — reads the
     * owner's language as data: it breaks when a name happens to match the words, and when a retained view model
     * holds a sentence rendered in the language before a change. Carry a typed value and render it at the edge.
     * A qualifier that is an enum class (`TransferImportOutcome.ALREADY_HERE`) is a value, not words, and is allowed.
     */
    @Test fun noBehaviourKeysOnRenderedText() {
        val app = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val core = listOf(File(CORE_SOURCES), File("../$CORE_SOURCES"))
            .firstOrNull { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()
        val getters = app.flatMap { file -> GETTER.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        val enums = (app + core).flatMap { file -> ENUM.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("the scan found no localized getters; it would prove nothing", getters.size > 100)

        val names = getters.joinToString("|")
        val comparison = Regex("""(?:==|!=)\s*(?:(\w+)\.)?($names)\b|(?<![\w.])(?:(\w+)\.)?($names)\s*(?:==|!=)""")
        val offenders = app.sortedBy { it.path }.flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> COMMENT_LINE.containsMatchIn(line) }
                .filter { (_, line) ->
                    comparison.findAll(line).any { match ->
                        val qualifier = match.groupValues[1].ifEmpty { match.groupValues[3] }
                        qualifier.isEmpty() || qualifier !in enums
                    }
                }
                .map { (at, line) -> "${file.relativeTo(sources).invariantSeparatorsPath}:${at + 1}: ${line.trim()}" }
        }
        assertEquals("compare typed values, never rendered words (#102)", emptyList<String>(), offenders)
    }

    /**
     * #102 (PR #106 review): a date, a moment or a number the owner reads or types follows the rendering language. A
     * fixed pattern or locale is how an ISO stamp and an English decimal point reached the screen, and how "0,5" was
     * refused: `ofPattern(…)`, `Locale.US`, `Locale.getDefault()`, `toDoubleOrNull()`. Draw with `localizedDate`,
     * `localizedDateTime` and `localizedDecimal` and read with `parseLocalizedDecimal` (`l10n/Localized.kt`, which is
     * where the fixed formats live). A fixed format that never reaches the owner as words — a file name, an ISO form
     * value — says why on its line with `// l10n-ok: <reason>`.
     */
    @Test fun noFixedFormatReachesTheOwner() {
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(sources).invariantSeparatorsPath.substringBefore('/') in LANGUAGE_NEUTRAL + "l10n" }
            .toList()
        assertTrue("the scan found no Kotlin under ${sources.path}; it would prove nothing", files.size > 50)

        val offenders = files.sortedBy { it.path }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> fixedFormat(line) }
                .map { (at, line) -> "${file.relativeTo(sources).invariantSeparatorsPath}:${at + 1}: ${line.trim()}" }
        }
        assertEquals(
            "dates and numbers the owner reads or types go through l10n/Localized.kt (docs/localization.md); a fixed " +
                "format that is not drawn says so on its line with `// l10n-ok: <reason>`",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun theFormatHeuristicFlagsFixedFormatsAndSparesTheExempt() {
        assertTrue(fixedFormat("""    val stamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")"""))
        assertTrue(fixedFormat("""    String.format(Locale.US, "%.1f KB", kb)"""))
        assertTrue(fixedFormat("""    val value = text.trim().toDoubleOrNull()"""))
        assertTrue(fixedFormat("""    val day = date.format(formatter.withLocale(Locale.getDefault()))"""))
        assertFalse(fixedFormat("""    val name = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT) // l10n-ok: file names"""))
        assertFalse(fixedFormat("""    // ofPattern("d MMM uuuu") is what localizedDate replaced."""))
        assertFalse(fixedFormat("""    val leadDays = text.trim().toIntOrNull() ?: 0"""))
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
        const val CORE_SOURCES = "core/src/main/kotlin"

        /** A catalog entry that renders words: `val NAME: String get() = localized(...)`. */
        val GETTER = Regex("""\bval ([A-Z][A-Z0-9_]+): String get\(\) = localized""")
        val ENUM = Regex("""\benum class (\w+)""")

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

        val FIXED_FORMAT = Regex("""\.to(Double|Float)OrNull\(|\bLocale\.(US|UK|ENGLISH|ROOT|getDefault\(\))|\bofPattern\(|\bSimpleDateFormat\(""")

        /** Whether [line]'s code fixes a date pattern, a locale or an English number parse, and does not say why. */
        fun fixedFormat(line: String): Boolean =
            !COMMENT_LINE.containsMatchIn(line) && "l10n-ok" !in line && FIXED_FORMAT.containsMatchIn(line.substringBefore(" // "))

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
