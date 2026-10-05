package com.loosecannon.servicetag.l10n

import com.loosecannon.servicetag.testing.PluralRules
import com.loosecannon.servicetag.testing.ResourcePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #102 — every shipped language pack says everything the English source says, with the same placeholders, and the
 * system's per-app language list offers exactly the packs that ship.
 *
 * A pack is a `values-<language>` directory ([ResourcePack.languagePacks]); `values/` is the English source. Nothing
 * here compares prose: the checks are names, placeholders, plural forms, patterns and technical tokens.
 */
class LocalizationCoverageTest {

    private val english = ResourcePack.english
    private val packs = ResourcePack.languagePacks

    @Test fun everyPackCoversEveryEnglishStringAndNothingElse() {
        val translatable = (english.strings.keys + english.plurals.keys) - english.untranslatable
        val problems = packs.flatMap { pack ->
            val names = pack.strings.keys + pack.plurals.keys
            val tag = pack.locale.toLanguageTag()
            (translatable - names).sorted().map { "$tag is missing $it" } +
                (names - english.strings.keys - english.plurals.keys).sorted().map { "$tag has $it, which English does not" } +
                (names intersect english.untranslatable).sorted().map { "$tag translates $it, which is translatable=\"false\"" } +
                (english.strings.keys intersect pack.plurals.keys).map { "$tag declares $it as plurals; English has a string" } +
                (english.plurals.keys intersect pack.strings.keys).map { "$tag declares $it as a string; English has plurals" }
        }
        assertEquals("a shipped language must not fall back to English (#102, acceptance 3)", emptyList<String>(), problems)
    }

    @Test fun noStringIsBlank() {
        val blank = (listOf(english) + packs).flatMap { pack ->
            pack.strings.filterValues { it.isBlank() }.keys.map { "${pack.directory.name}/$it" } +
                pack.plurals.flatMap { (name, forms) ->
                    forms.filterValues { it.isBlank() }.keys.map { "${pack.directory.name}/$name[$it]" }
                }
        }
        assertEquals(emptyList<String>(), blank)
    }

    @Test fun placeholdersMatchTheEnglishSource() {
        val problems = mutableListOf<String>()
        for ((name, text) in english.strings) problems += positionalProblems("values/$name", text)
        for ((name, forms) in english.plurals) forms.forEach { (q, text) -> problems += positionalProblems("values/$name[$q]", text) }

        for (pack in packs) {
            val tag = pack.locale.toLanguageTag()
            for ((name, text) in pack.strings) {
                val source = english.strings[name] ?: continue
                if (placeholders(text) != placeholders(source)) {
                    problems += "$tag/$name has ${placeholders(text)}, English ${placeholders(source)}"
                }
            }
            for ((name, forms) in pack.plurals) {
                val source = english.plurals[name]?.get("other") ?: continue
                forms.forEach { (q, text) ->
                    if (placeholders(text) != placeholders(source)) {
                        problems += "$tag/$name[$q] has ${placeholders(text)}, English ${placeholders(source)}"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    /**
     * Every quantity form shows its number through a placeholder, never a literal "1" — Russian's `one` also takes
     * 21 and French's takes 0 — and each language gives the forms its everyday counts select.
     */
    @Test fun pluralsGiveEachLanguagesFormsAndShowTheirNumber() {
        val problems = mutableListOf<String>()
        for ((name, forms) in english.plurals) {
            if ("other" !in forms) problems += "values/$name has no other form"
            forms.forEach { (q, text) -> if (placeholders(text).isEmpty()) problems += "values/$name[$q] shows no number" }
        }
        for (pack in packs) {
            val tag = pack.locale.toLanguageTag()
            val required = PluralRules.requiredForms(pack.locale)
            for ((name, forms) in pack.plurals) {
                (required - forms.keys).forEach { problems += "$tag/$name lacks the $it form" }
                forms.forEach { (q, text) -> if (placeholders(text).isEmpty()) problems += "$tag/$name[$q] shows no number" }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    /** Every string and every plural form formats, in its own language, with arguments of the types it declares. */
    @Test fun everyStringFormatsInItsOwnLanguage() {
        val counts = listOf(0, 1, 2, 3, 5, 11, 12, 21, 22, 25, 101, 111, 1_000_000)
        val problems = mutableListOf<String>()
        for (pack in listOf(english) + packs) {
            for ((name, text) in pack.strings) {
                if (placeholders(text).isEmpty()) continue
                runCatching { String.format(pack.locale, text, *sampleArguments(text, 2)) }
                    .onFailure { problems += "${pack.directory.name}/$name: ${it.message}" }
            }
            for ((name, forms) in pack.plurals) {
                for (n in counts) {
                    val form = forms[PluralRules.select(pack.locale, n)] ?: forms["other"] ?: continue
                    runCatching { String.format(pack.locale, form, *sampleArguments(form, n)) }
                        .onFailure { problems += "${pack.directory.name}/$name($n): ${it.message}" }
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun theDisplayDatePatternsAreEachLanguagesOwnAndEnglishIsUnchanged() {
        val day = LocalDate.of(2026, 3, 1)
        assertEquals("1 Mar 2026", day.format(DateTimeFormatter.ofPattern(english.strings.getValue("format_date_display"), english.locale)))
        assertEquals("1 Mar", day.format(DateTimeFormatter.ofPattern(english.strings.getValue("format_date_month_day"), english.locale)))
        assertEquals(
            "1 Mar 2026, 09:30",
            day.atTime(9, 30).format(DateTimeFormatter.ofPattern(english.strings.getValue("format_date_time_display"), english.locale)),
        )
        val problems = packs.flatMap { pack ->
            listOf("format_date_display", "format_date_month_day", "format_date_time_display").mapNotNull { name ->
                val pattern = pack.strings[name] ?: return@mapNotNull null
                runCatching { day.atTime(9, 30).format(DateTimeFormatter.ofPattern(pattern, pack.locale)) }
                    .fold({ if (it.isBlank()) "${pack.locale.toLanguageTag()}/$name renders nothing" else null }) {
                        "${pack.locale.toLanguageTag()}/$name is not a date pattern: ${it.message}"
                    }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    /** Each pack names its own language, which picks the plural rules on a phone whose language has no pack. */
    @Test fun everyPackNamesTheLanguageItIsWrittenIn() {
        assertEquals("en", english.strings["format_language"])
        val wrong = packs.filter { it.strings["format_language"] != it.locale.language }
            .map { "${it.directory.name} says ${it.strings["format_language"]}, not ${it.locale.language}" }
        assertEquals(emptyList<String>(), wrong)
    }

    /** Product names and URL schemes are not translated (#102, translation quality): a translation keeps each one. */
    @Test fun technicalTokensSurviveTranslation() {
        val problems = mutableListOf<String>()
        for (pack in packs) {
            val tag = pack.locale.toLanguageTag()
            for ((name, text) in pack.strings) {
                val source = english.strings[name] ?: continue
                TOKENS.filter { it in source && it !in text }.forEach { problems += "$tag/$name drops \"$it\"" }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    /**
     * Android's per-app language setting offers what `res/xml/locales_config.xml` lists (API 33+): English and
     * exactly the shipped packs, so the owner can never pick a language that would show English throughout.
     */
    @Test fun theLocaleConfigListsExactlyTheShippedLanguages() {
        val config = File(ResourcePack.resDirectory, "xml/locales_config.xml")
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(config).documentElement
        val locales = root.getElementsByTagName("locale")
        val listed = (0 until locales.length).map { (locales.item(it) as Element).getAttributeNS(ANDROID, "name") }
        assertEquals("English, the source, comes first", "en", listed.firstOrNull())
        assertEquals(packs.map { it.locale.toLanguageTag() }.sorted(), listed.drop(1).sorted())

        val manifest = File(ResourcePack.resDirectory.parentFile, "AndroidManifest.xml").readText()
        assertTrue(
            "the application must declare android:localeConfig=\"@xml/locales_config\"",
            Regex("""<application\b[^>]*android:localeConfig="@xml/locales_config"""").containsMatchIn(manifest),
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        val TOKENS = listOf("https://", "http://", "ServiceTag", "NoteTag", "Home Assistant")

        val SPEC = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")

        /** `index:conversion` for every argument a string takes; a lone unindexed placeholder is argument 1. */
        fun placeholders(text: String): Set<String> =
            SPEC.findAll(text).filter { it.groupValues[2] != "%" }
                .map { "${it.groupValues[1].ifEmpty { "1" }}:${it.groupValues[2]}" }.toSet()

        fun positionalProblems(where: String, text: String): List<String> {
            val specs = SPEC.findAll(text).filter { it.groupValues[2] != "%" }.toList()
            return if (specs.size > 1 && specs.any { it.groupValues[1].isEmpty() }) {
                listOf("$where: several placeholders must all be positional, so a translation can reorder them")
            } else {
                emptyList()
            }
        }

        /** An argument of the declared type for every placeholder, by index; [count] stands in for numbers. */
        fun sampleArguments(text: String, count: Int): Array<Any?> {
            val byIndex = SPEC.findAll(text).filter { it.groupValues[2] != "%" }
                .associate { (it.groupValues[1].ifEmpty { "1" }).toInt() to it.groupValues[2] }
            val size = byIndex.keys.maxOrNull() ?: 0
            return Array(size) { i ->
                when (byIndex[i + 1]) {
                    "d", "x", "X" -> count
                    "f", "e", "E", "g", "G" -> count.toDouble()
                    "c" -> 'x'
                    else -> "Example ${i + 1}"
                }
            }
        }
    }
}
