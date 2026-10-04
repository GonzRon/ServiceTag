package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.LocalizedText
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #102 — the JVM tests' [LocalizedText]: the English source in `src/main/res/values/`, read the way Android reads
 * it. `META-INF/services` registers it, so a unit test with no Android resources renders every word the app would
 * render on an English device, and the tests that pin ratified English wording keep pinning it after the words
 * moved out of Kotlin.
 *
 * An id is mapped to its name through the generated `R` class, the name to its text through the XML.
 */
class EnglishResources : LocalizedText {
    override val locale: Locale get() = Locale.ENGLISH

    override fun string(id: Int): String = Catalog.strings[name(id, Catalog.stringNames)]
        ?: error("no English string ${name(id, Catalog.stringNames)} in res/values")

    override fun format(id: Int, args: Array<out Any?>): String = String.format(locale, string(id), *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val forms = Catalog.plurals[name(id, Catalog.pluralNames)]
            ?: error("no English plurals ${name(id, Catalog.pluralNames)} in res/values")
        // English has two forms: `one` for exactly 1, `other` for everything else (CLDR).
        val form = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
        return if (args.isEmpty()) form else String.format(locale, form, *args)
    }

    private fun name(id: Int, names: Map<Int, String>): String = names[id] ?: error("no resource name for id $id")

    /** Parsed once per test JVM. */
    internal object Catalog {
        val stringNames: Map<Int, String> by lazy { idsToNames("string") }
        val pluralNames: Map<Int, String> by lazy { idsToNames("plurals") }

        private val parsed: Pair<Map<String, String>, Map<String, Map<String, String>>> by lazy {
            parse(valuesDirectory("src/main/res/values"))
        }
        val strings: Map<String, String> get() = parsed.first
        val plurals: Map<String, Map<String, String>> get() = parsed.second

        /** By name: `R.plurals` exists only once some resource declares a `<plurals>`. */
        private fun idsToNames(type: String): Map<Int, String> =
            runCatching { Class.forName("${R::class.java.name}\$$type") }.getOrNull()?.fields.orEmpty()
                .filter { it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name }
    }

    companion object {
        /** `src/main/res/<dir>` from the module directory Gradle runs unit tests in, or from the repository root. */
        fun valuesDirectory(relative: String): File =
            listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
                ?: error("cannot find $relative from ${File(".").absolutePath}")

        /** Every `<string>` and `<plurals>` in the directory's XML files, as Android renders their text. */
        fun parse(dir: File): Pair<Map<String, String>, Map<String, Map<String, String>>> {
            val strings = linkedMapOf<String, String>()
            val plurals = linkedMapOf<String, Map<String, String>>()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
            for (file in files) {
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                for (element in root.childElements()) {
                    val name = element.getAttribute("name")
                    when (element.tagName) {
                        "string" -> check(strings.put(name, androidText(element)) == null) { "$name is declared twice" }
                        "plurals" -> {
                            val forms = element.childElements().filter { it.tagName == "item" }
                                .associate { it.getAttribute("quantity") to androidText(it) }
                            check(plurals.put(name, forms) == null) { "$name is declared twice" }
                        }
                    }
                }
            }
            return strings to plurals
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
            // Whether each output character came from inside quotes: only unquoted spaces are trimmed at the ends.
            val quoted = mutableListOf<Boolean>()
            var inQuotes = false
            var i = 0
            fun add(c: Char, q: Boolean) {
                out.append(c)
                quoted += q
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
                        if (out.isEmpty() || !(out.last() == ' ' && !quoted.last())) add(' ', false)
                    }
                    !inQuotes && c == '\'' -> error("unescaped apostrophe in \"$raw\": write \\'")
                    else -> add(c, inQuotes)
                }
                i++
            }
            var start = 0
            var end = out.length
            while (start < end && out[start] == ' ' && !quoted[start]) start++
            while (end > start && out[end - 1] == ' ' && !quoted[end - 1]) end--
            return out.substring(start, end)
        }
    }
}
