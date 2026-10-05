package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.LocalizedText
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #102 — one `res/values*` directory read the way Android reads it, as a [LocalizedText] the JVM tests can install.
 *
 * [EnglishResources] is the source pack, `values/`. A language pack is read over it, as on a device, and every read
 * that had to fall back to English is recorded in [fallbacks] — a shipped language must not fall back silently on
 * a core workflow (#102, acceptance 3), and a test that walks one in each language asserts this set stays empty.
 * Plural forms are chosen by the language's CLDR rules ([PluralRules]).
 */
class ResourcePack(
    val directory: File,
    override val locale: Locale,
    private val fallback: ResourcePack? = null,
) : LocalizedText {
    val strings: Map<String, String>
    val plurals: Map<String, Map<String, String>>

    /** `<bool>` rules (`bools.xml`); optional in a pack, which then takes English's. */
    val flags: Map<String, Boolean>

    /** Names declared `translatable="false"`: the same in every language, never a fallback. */
    val untranslatable: Set<String>

    /** Names this pack had to read from [fallback] since it was made. */
    val fallbacks: MutableSet<String> = linkedSetOf()

    init {
        val parsed = parse(directory)
        strings = parsed.strings
        plurals = parsed.plurals
        flags = parsed.flags
        untranslatable = parsed.untranslatable
    }

    override fun string(id: Int): String = stringNamed(name(id, stringNames))

    override fun format(id: Int, args: Array<out Any?>): String = String.format(locale, string(id), *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val name = name(id, pluralNames)
        val forms = plurals[name] ?: fallback?.plurals?.get(name)?.also { fallbacks += name }
            ?: error("no plurals $name in ${directory.name}")
        val form = forms[PluralRules.select(locale, count)] ?: forms.getValue("other")
        return if (args.isEmpty()) form else String.format(locale, form, *args)
    }

    override fun flag(id: Int): Boolean {
        val name = name(id, boolNames)
        return flags[name] ?: fallback?.flags?.get(name) ?: error("no bool $name in ${directory.name}")
    }

    fun stringNamed(name: String): String = strings[name]
        ?: fallback?.strings?.get(name)?.also { if (name !in fallback.untranslatable) fallbacks += name }
        ?: error("no string $name in ${directory.name}")

    private fun name(id: Int, names: Map<Int, String>): String = names[id] ?: error("no resource name for id $id")

    class Parsed(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val flags: Map<String, Boolean>,
        val untranslatable: Set<String>,
    )

    companion object {
        /** The app's `R.string` / `R.plurals` ids by name. By reflection: `R.plurals` exists only once one is declared. */
        val stringNames: Map<Int, String> by lazy { idsToNames("string") }
        val pluralNames: Map<Int, String> by lazy { idsToNames("plurals") }
        val boolNames: Map<Int, String> by lazy { idsToNames("bool") }

        private fun idsToNames(type: String): Map<Int, String> =
            runCatching { Class.forName("${R::class.java.name}\$$type") }.getOrNull()?.fields.orEmpty()
                .filter { it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name }

        /** `src/main/res` from the module directory Gradle runs unit tests in, or from the repository root. */
        val resDirectory: File by lazy {
            listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
                ?: error("cannot find src/main/res from ${File(".").absolutePath}")
        }

        val english: ResourcePack by lazy { ResourcePack(File(resDirectory, "values"), Locale.ENGLISH) }

        /**
         * Every shipped language pack: a `values-<language>[-r<REGION>]` or `values-b+<bcp47>` directory holding
         * strings. Other qualifiers (`values-night`, `values-v31`) are not languages.
         */
        val languagePacks: List<ResourcePack> by lazy {
            resDirectory.listFiles().orEmpty()
                .mapNotNull { dir -> localeOf(dir.name)?.let { dir to it } }
                .filter { (dir, _) -> dir.listFiles().orEmpty().any { it.name.endsWith(".xml") } }
                .sortedBy { (dir, _) -> dir.name }
                .map { (dir, locale) -> ResourcePack(dir, locale, fallback = english) }
        }

        fun pack(languageTag: String): ResourcePack =
            languagePacks.firstOrNull { it.locale.toLanguageTag() == languageTag }
                ?: error("no language pack $languageTag; shipped: ${languagePacks.map { it.locale.toLanguageTag() }}")

        fun localeOf(directoryName: String): Locale? {
            if (!directoryName.startsWith("values-")) return null
            val qualifier = directoryName.removePrefix("values-")
            if (qualifier.startsWith("b+")) return Locale.forLanguageTag(qualifier.removePrefix("b+").replace('+', '-'))
            val match = Regex("([a-z]{2,3})(?:-r([A-Z]{2}))?").matchEntire(qualifier) ?: return null
            return Locale.Builder().setLanguage(match.groupValues[1]).setRegion(match.groupValues[2]).build()
        }

        /** Every `<string>` and `<plurals>` in the directory's XML files, as Android renders their text. */
        fun parse(dir: File): Parsed {
            val strings = linkedMapOf<String, String>()
            val plurals = linkedMapOf<String, Map<String, String>>()
            val flags = linkedMapOf<String, Boolean>()
            val untranslatable = linkedSetOf<String>()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
            for (file in files) {
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                for (element in root.childElements()) {
                    val name = element.getAttribute("name")
                    if (element.getAttribute("translatable") == "false") untranslatable += name
                    when (element.tagName) {
                        "string" -> check(strings.put(name, androidText(element)) == null) {
                            "${dir.name}: $name is declared twice"
                        }
                        "plurals" -> {
                            val forms = element.childElements().filter { it.tagName == "item" }
                                .associate { it.getAttribute("quantity") to androidText(it) }
                            check(plurals.put(name, forms) == null) { "${dir.name}: $name is declared twice" }
                        }
                        "bool" -> check(flags.put(name, element.textContent.trim().toBooleanStrict()) == null) {
                            "${dir.name}: $name is declared twice"
                        }
                    }
                }
            }
            return Parsed(strings, plurals, flags, untranslatable)
        }

        private fun Element.childElements(): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

        /**
         * aapt2's reading of a resource string: double quotes start and end a run whose whitespace is kept; outside
         * them a run of whitespace is one space and the ends are trimmed; a backslash escapes the next character,
         * with `\n`, `\t` and `\uXXXX` meaning what they mean in Kotlin. Markup inside a string is refused: the app's
         * strings are plain text.
         */
        fun androidText(element: Element): String {
            val raw = StringBuilder()
            for (i in 0 until element.childNodes.length) {
                val node = element.childNodes.item(i)
                when (node.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> raw.append(node.nodeValue)
                    Node.COMMENT_NODE -> Unit
                    else -> error("markup inside ${element.getAttribute("name")}: the app's strings are plain text")
                }
            }
            return unescape(raw.toString())
        }

        fun unescape(raw: String): String {
            val out = StringBuilder()
            // Whether each output character came from inside quotes or an escape: only plain spaces are trimmed.
            val kept = mutableListOf<Boolean>()
            var inQuotes = false
            var i = 0
            fun add(c: Char, keep: Boolean) {
                out.append(c)
                kept += keep
            }
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val e = raw[i + 1]
                        i += 2
                        when (e) {
                            'n' -> add('\n', true)
                            't' -> add('\t', true)
                            'u' -> {
                                add(raw.substring(i, i + 4).toInt(16).toChar(), true)
                                i += 4
                            }
                            else -> add(e, true)
                        }
                        continue
                    }
                    c == '"' -> inQuotes = !inQuotes
                    !inQuotes && c.isWhitespace() -> {
                        if (out.isEmpty() || !(out.last() == ' ' && !kept.last())) add(' ', false)
                    }
                    !inQuotes && c == '\'' -> error("unescaped apostrophe in \"$raw\": write \\'")
                    else -> add(c, inQuotes)
                }
                i++
            }
            var start = 0
            var end = out.length
            while (start < end && out[start] == ' ' && !kept[start]) start++
            while (end > start && out[end - 1] == ' ' && !kept[end - 1]) end--
            return out.substring(start, end)
        }
    }
}

/**
 * CLDR's integer plural categories for the shipped languages — what Android's `getQuantityString` selects on a
 * device for a whole-number count. A language not listed here has no pack yet; add its rule with its pack.
 */
object PluralRules {
    fun select(locale: Locale, n: Int): String {
        val i = kotlin.math.abs(n)
        val millions = i != 0 && i % 1_000_000 == 0
        return when (locale.language) {
            "en", "de" -> if (i == 1) "one" else "other"
            "it", "es" -> when {
                i == 1 -> "one"
                millions -> "many"
                else -> "other"
            }
            "fr", "pt" -> when {
                i == 0 || i == 1 -> "one"
                millions -> "many"
                else -> "other"
            }
            "hi" -> if (i == 0 || i == 1) "one" else "other"
            "ru" -> when {
                i % 10 == 1 && i % 100 != 11 -> "one"
                i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
                else -> "many"
            }
            "ja", "zh" -> "other"
            else -> error("no plural rule for ${locale.toLanguageTag()}: add it with the pack")
        }
    }

    /**
     * The forms a language's `<plurals>` must give. `many` in Spanish, French, Italian and Portuguese only takes
     * exact millions, which read like `other` in every count this app shows, so it may be left to fall back to
     * `other`; Russian's `few` and `many` are everyday counts and are required.
     */
    fun requiredForms(locale: Locale): Set<String> = when (locale.language) {
        "en", "de", "hi", "it", "es", "fr", "pt" -> setOf("one", "other")
        "ru" -> setOf("one", "few", "many", "other")
        "ja", "zh" -> setOf("other")
        else -> error("no plural rule for ${locale.toLanguageTag()}: add it with the pack")
    }
}
