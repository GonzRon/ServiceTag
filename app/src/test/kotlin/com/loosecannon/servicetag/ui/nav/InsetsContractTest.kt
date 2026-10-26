package com.loosecannon.servicetag.ui.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #121 (1.8.2 plan §7.3, K1–K5, K7): what the source can pin about the window insets. The root applies the system
 * bars once and consumes them (K1); every `Scaffold` screen is classified, and the input screens pass the keyboard
 * insets to their `Scaffold` (K2); nothing else adds a keyboard inset of its own (K3); the bottom sheets keep
 * Material 3's handling (K4); the dialogs keep the platform's (K5); the write-tag screen's column scrolls (K7).
 *
 * Paths are relative to `app/src/main/kotlin/com/loosecannon/servicetag/`. Every pattern is matched on the code
 * alone, with comments and the contents of literals blanked (see [codeOnly]), so a comment or a string can neither
 * satisfy nor trip a check; a pattern that ends a line (`,$`, `($`) is matched on the code line with its trailing
 * blanks trimmed.
 */
class InsetsContractTest {

    /** K1: `NavDisplay`'s modifier pads with the outer `Scaffold`'s padding and consumes exactly that. */
    @Test fun theRootConsumesThePaddingItAppliesToNavDisplay() {
        val lines = source(ROOT).code.map { it.trimEnd() }
        val consuming = lines.indices.filter { ROOT_MODIFIER.matches(lines[it]) }
        assertEquals("one `modifier = Modifier.padding(padding).consumeWindowInsets(padding),` line in $ROOT", 1, consuming.size)
        val navDisplay = lines.indices.filter { Regex("""^\s*NavDisplay\($""").matches(lines[it]) }
        assertEquals("one anchored `NavDisplay(` in $ROOT", 1, navDisplay.size)
        val firstModifier = (navDisplay.single() + 1 until lines.size).firstOrNull { Regex("""^\s*modifier =""").containsMatchIn(lines[it]) }
        assertEquals("the consuming line is the first `modifier =` after `NavDisplay(`", firstModifier, consuming.single())
    }

    /** K1: the outer `Scaffold` keeps Material 3's default content insets; nothing between it and `) { padding ->` sets them. */
    @Test fun theOuterScaffoldKeepsMaterialsDefaultContentInsets() {
        val root = source(ROOT)
        val calls = root.calls(SCAFFOLD)
        assertEquals("one anchored `Scaffold(` in $ROOT", 1, calls.size)
        val call = calls.single()
        assertTrue(
            "the outer Scaffold's arguments close on `) { padding ->`: ${root.lines[call.closeLine].trim()}",
            root.code[call.closeLine].substring(call.closeColumn).startsWith(") { padding ->"),
        )
        val overriding = (call.openLine..call.closeLine).filter { Regex("""^\s*contentWindowInsets =""").containsMatchIn(root.code[it]) }
        assertTrue("no `contentWindowInsets =` in the outer Scaffold (lines ${overriding.map { it + 1 }})", overriding.isEmpty())
    }

    /** K2, classification: every file with an anchored `Scaffold(` is group B, group C or the root, and only one. */
    @Test fun everyScaffoldFileIsClassified() {
        assertEquals("group B, the input screens", 15, GROUP_B.size)
        assertEquals("group C's Scaffold files", 21, GROUP_C_SCAFFOLDS.size)
        assertTrue("group B and group C overlap: ${GROUP_B intersect GROUP_C_SCAFFOLDS}", (GROUP_B intersect GROUP_C_SCAFFOLDS).isEmpty())
        assertTrue("the root is in neither group", ROOT !in GROUP_B && ROOT !in GROUP_C_SCAFFOLDS)

        val scaffoldFiles = mainSources().filter { it.code.any { line -> SCAFFOLD.containsMatchIn(line) } }.map { it.path }.toSet()
        assertEquals(
            "the files with an anchored `Scaffold(` are group B, group C and the root; classify a new screen here",
            (GROUP_B + GROUP_C_SCAFFOLDS + ROOT).sorted(),
            scaffoldFiles.sorted(),
        )
    }

    /**
     * K2, application: every screen that takes typed input ([FORM_SCREENS], group B) passes
     * `contentWindowInsets = formContentInsets` to its `Scaffold`, directly among the call's own arguments, before the
     * `) { … ->` content lambda.
     */
    @Test fun everyFormScreenPassesTheKeyboardInsetsToItsScaffold() {
        for (path in FORM_SCREENS) {
            val file = source(path)
            val calls = file.calls(SCAFFOLD)
            assertTrue("$path has an anchored `Scaffold(`", calls.isNotEmpty())
            for (call in calls) {
                assertTrue(
                    "$path:${call.closeLine + 1}: the Scaffold's arguments close on its `) { … ->` content lambda",
                    CONTENT_LAMBDA.containsMatchIn(file.code[call.closeLine].substring(call.closeColumn)),
                )
                val passed = (call.openLine + 1 until call.closeLine)
                    .filter { FORM_INSETS_ARGUMENT.matches(file.code[it].trimEnd()) && call.depthAtLineStart(it) == 1 }
                assertEquals(
                    "$path:${call.openLine + 1}: one `contentWindowInsets = formContentInsets,` among the Scaffold's own arguments",
                    1,
                    passed.size,
                )
            }
        }
    }

    /** K2: `formContentInsets` is named by the form screens and its own file, and by no other file. */
    @Test fun onlyTheFormScreensNameFormContentInsets() {
        val naming = mainSources().filter { FORM_INSETS_NAME.containsMatchIn(it.codeText) }.map { it.path }.toSet() - FORM_INSETS_FILE
        assertEquals("the files naming `formContentInsets`, besides $FORM_INSETS_FILE", FORM_SCREENS.sorted(), naming.sorted())
        val inGroupC = (GROUP_C_SCAFFOLDS + GROUP_C_WITHOUT_SCAFFOLD).filter { FORM_INSETS_NAME.containsMatchIn(source(it).codeText) }
        assertTrue("no group C file names `formContentInsets`: $inGroupC", inGroupC.isEmpty())
    }

    /** K3: `formContentInsets` is defined once, in `ui/components/FormInsets.kt`. */
    @Test fun formContentInsetsIsDefinedOnceInFormInsets() {
        val definitions = mainSources().flatMap { file ->
            file.code.indices.filter { FORM_INSETS_DEFINITION.containsMatchIn(file.code[it]) }.map { "${file.path}:${it + 1}" }
        }
        assertEquals("one definition of `formContentInsets`", 1, definitions.size)
        assertTrue("it is in $FORM_INSETS_FILE: $definitions", definitions.single().startsWith("$FORM_INSETS_FILE:"))
    }

    /** K3: no `imePadding()` anywhere: the keyboard is added once, by `formContentInsets` or by Material 3's sheets. */
    @Test fun nothingAppliesImePadding() {
        val sites = mainLines { IME_PADDING.any { pattern -> pattern.containsMatchIn(it) } }
        assertTrue("no `imePadding()` in app/src/main/kotlin: $sites", sites.isEmpty())
    }

    /** K3 (F-16): `WindowInsets.ime` is read in `FormInsets.kt` and nowhere else. */
    @Test fun theKeyboardInsetIsReadOnlyInFormInsets() {
        val files = mainSources().filter { Regex("""WindowInsets\.ime""").containsMatchIn(it.codeText) }.map { it.path }.toSet()
        assertTrue("`WindowInsets.ime` outside $FORM_INSETS_FILE: ${files - FORM_INSETS_FILE}", (files - FORM_INSETS_FILE).isEmpty())
    }

    /**
     * K3 (F-16): `windowInsetsPadding(` and the built-in inset paddings (`safeDrawingPadding()` and
     * `safeContentPadding()`, which add the keyboard, and the bar and cutout paddings), and the raw bar and safe-area
     * insets (`WindowInsets.navigationBars`, `WindowInsets.safeDrawing` and the like), only in the share intake, whose
     * exact sites K6 counts.
     */
    @Test fun rawInsetPaddingOnlyInTheShareIntake() {
        val files = mainSources().filter { RAW_INSET_PADDING.containsMatchIn(it.codeText) }.map { it.path }.toSet()
        assertTrue("raw inset padding outside the share intake: ${files - SHARE_INTAKE}", (files - SHARE_INTAKE).isEmpty())
    }

    /**
     * K4 (F-5): the bottom sheets keep Material 3 1.4.0's own keyboard handling. Fourteen `ModalBottomSheet(` calls
     * in eleven files, a sheet after `->` included; none passes `contentWindowInsets`, read inside the call's own
     * argument list (so a `Scaffold`'s argument in the same file is not the sheet's); every input sheet opens fully
     * expanded.
     */
    @Test fun theBottomSheetsKeepMaterialsInsets() {
        val calls = mainSources().flatMap { file -> file.calls(SHEET).map { file to it } }
        assertEquals("`ModalBottomSheet(` calls in app/src/main/kotlin", 14, calls.size)
        val sheetFiles = calls.map { (file, _) -> file.path }.toSet()
        assertEquals("files with a `ModalBottomSheet(`", 11, sheetFiles.size)
        assertEquals(
            "the sheet files are group E and the three without typed input; classify a new sheet here",
            (GROUP_E + SHEETS_WITHOUT_INPUT).sorted(),
            sheetFiles.sorted(),
        )

        for ((file, call) in calls) {
            assertTrue(
                "${file.path}:${call.openLine + 1}: the sheet passes no `contentWindowInsets`",
                !Regex("""\bcontentWindowInsets\s*=""").containsMatchIn(file.argumentText(call)),
            )
        }
        for (path in GROUP_E) {
            val file = source(path)
            val expanded = file.code.sumOf { Regex("""\bskipPartiallyExpanded\s*=\s*true\b""").findAll(it).count() }
            assertEquals("$path: every sheet opens fully expanded", file.calls(SHEET).size, expanded)
        }
    }

    /** K5: no dialog is taken out of the platform's keyboard handling. */
    @Test fun noDialogDrawsUnderTheSystemWindows() {
        val sites = mainLines { Regex("""decorFitsSystemWindows\s*=\s*false""").containsMatchIn(it) }
        assertTrue("no `decorFitsSystemWindows = false` in app/src/main/kotlin: $sites", sites.isEmpty())
    }

    /**
     * K7 (D7): `WriteTagScreen`'s content column, the first `Column(` in its `Scaffold`'s content lambda, scrolls inside
     * the `Scaffold`'s padding, so its placement field can be brought above the keyboard and its lock switch reached in
     * landscape.
     */
    @Test fun theWriteTagColumnScrollsInsideTheScaffoldPadding() {
        val file = source(WRITE_TAG)
        val scaffold = file.calls(SCAFFOLD).single()
        val column = file.calls(COLUMN).firstOrNull { it.openLine > scaffold.closeLine }
            ?: error("$WRITE_TAG: no anchored `Column(` after the Scaffold's content lambda opens")
        val at = "$WRITE_TAG:${column.openLine + 1}"
        val modifier = file.arguments(column).singleOrNull { it.startsWith("modifier =") }
            ?: error("$at: the content column passes no `modifier =`")
        val chain = modifier.replace(Regex("""\s+"""), "")
        val padded = chain.indexOf(".padding(padding)")
        assertTrue("$at: the content column pads with the Scaffold's padding: $chain", padded >= 0)
        assertTrue(
            "$at: the content column's `.verticalScroll(` follows `.padding(padding)`: $chain",
            chain.indexOf(".verticalScroll(", padded) >= 0,
        )
    }

    private class Source(val path: String, val text: String) {
        val lines: List<String> = text.lines()
        val code: List<String> = codeOnly(lines)
        val codeText: String = code.joinToString("\n")

        /** Every call whose line matches [opening]; the call's `(` is the first one at or after the match's end − 1. */
        fun calls(opening: Regex): List<Call> = code.indices.mapNotNull { index ->
            val match = opening.find(code[index]) ?: return@mapNotNull null
            val open = code[index].indexOf('(', match.range.last)
            check(open >= 0) { "$path:${index + 1}: no `(` after ${opening.pattern}" }
            call(index, open)
        }

        fun argumentText(call: Call): String = buildString {
            for (index in call.openLine..call.closeLine) {
                val from = if (index == call.openLine) call.openColumn + 1 else 0
                val to = if (index == call.closeLine) call.closeColumn else code[index].length
                append(code[index], from, to).append('\n')
            }
        }

        /** The call's own arguments, split at its top-level commas and trimmed; a trailing comma leaves none empty. */
        fun arguments(call: Call): List<String> {
            val text = argumentText(call)
            val arguments = mutableListOf<String>()
            var depth = 0
            var from = 0
            for ((at, char) in text.withIndex()) {
                when (char) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        arguments += text.substring(from, at).trim()
                        from = at + 1
                    }
                }
            }
            arguments += text.substring(from).trim()
            return arguments.filter { it.isNotEmpty() }
        }

        private fun call(openLine: Int, openColumn: Int): Call {
            val depths = mutableMapOf<Int, Int>()
            var depth = 0
            for (index in openLine until code.size) {
                if (index > openLine) depths[index] = depth
                val from = if (index == openLine) openColumn else 0
                for (column in from until code[index].length) {
                    when (code[index][column]) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) return Call(openLine, openColumn, index, column, depths)
                    }
                }
            }
            error("$path:${openLine + 1}: the call never closes")
        }
    }

    /** A call's balanced argument list: its `(` and matching `)`, and the parenthesis depth at each later line's start. */
    private class Call(
        val openLine: Int,
        val openColumn: Int,
        val closeLine: Int,
        val closeColumn: Int,
        private val depths: Map<Int, Int>,
    ) {
        fun depthAtLineStart(line: Int): Int = depths.getValue(line)
    }

    private companion object {
        const val ROOT = "ui/nav/ServiceTagRoot.kt"
        const val FORM_INSETS_FILE = "ui/components/FormInsets.kt"
        const val WRITE_TAG = "ui/scan/WriteTagScreen.kt"

        val SCAFFOLD = Regex("""^\s*Scaffold\(""")
        val COLUMN = Regex("""^\s*Column\(""")
        val SHEET = Regex("""^\s*(\S.*->\s*)?ModalBottomSheet\(""")
        val ROOT_MODIFIER = Regex("""^\s*modifier = Modifier\.padding\(padding\)\.consumeWindowInsets\(padding\),$""")
        val CONTENT_LAMBDA = Regex("""^\)\s*\{\s*\w+\s*->""")
        val FORM_INSETS_ARGUMENT = Regex("""^\s*contentWindowInsets = formContentInsets,$""")
        val FORM_INSETS_NAME = Regex("""\bformContentInsets\b""")
        val FORM_INSETS_DEFINITION = Regex("""^\s*(internal |public |private )?val formContentInsets\b""")
        val IME_PADDING = listOf(Regex("""\.imePadding\(\)"""), Regex("""^\s*imePadding\(\)"""))
        val RAW_INSET_PADDING = Regex(
            """windowInsetsPadding\(|\b(safeDrawing|safeContent|systemBars|navigationBars|statusBars|displayCutout)Padding\(""" +
                """|WindowInsets\.(safeDrawing|safeContent|systemBars|navigationBars|statusBars|displayCutout)\b""",
        )

        /** Group B (§4.2): the `Scaffold` screens that take typed input in their own content. */
        val GROUP_B = setOf(
            "ui/journal/EventEntryScreen.kt",
            "ui/asset/AssetEditScreen.kt",
            "ui/setup/DefinitionEditScreen.kt",
            "ui/setup/ProfileEditScreen.kt",
            "ui/supplies/SupplyEditScreen.kt",
            "ui/health/HealthSubjectEditScreen.kt",
            "ui/maintenance/GroupEditScreen.kt",
            "ui/maintenance/ScheduleEditScreen.kt",
            "ui/service/ServiceCaseEditScreen.kt",
            "ui/loan/LoanEditScreen.kt",
            "ui/replace/ReplaceAssetScreen.kt",
            "ui/transfer/TransferFlowScreen.kt",
            "ui/homeassistant/HomeAssistantScreen.kt",
            "ui/asset/AssetsScreen.kt",
            "ui/scan/WriteTagScreen.kt",
        )

        /** Group C (§4.3): the `Scaffold` screens with no typed input of their own. */
        val GROUP_C_SCAFFOLDS = setOf(
            "ui/dashboard/DashboardScreen.kt",
            "ui/maintenance/MaintenanceScreen.kt",
            "ui/asset/AssetDetailScreen.kt",
            "ui/setup/AssetSetupScreen.kt",
            "ui/backup/BackupScreen.kt",
            "ui/settings/CategoriesScreen.kt",
            "ui/api/DeveloperApiScreen.kt",
            "ui/journal/EventDetailScreen.kt",
            "ui/maintenance/GroupDetailScreen.kt",
            "ui/installed/InstalledComponentDetailScreen.kt",
            "ui/installed/InstalledComponentsListScreen.kt",
            "ui/maintenance/MaintenanceGroupsScreen.kt",
            "ui/maintenance/MaintenanceSheet.kt",
            "ui/maintenance/ReminderHealthScreen.kt",
            "ui/maintenance/ScheduleDetailScreen.kt",
            "ui/scan/ScanScreen.kt",
            "ui/service/ServiceCaseScreen.kt",
            "ui/settings/SettingsScreen.kt",
            "ui/supplies/SupplyDetailScreen.kt",
            "ui/supplies/SupplyListScreen.kt",
            "ui/transfer/import/TransferImportScreen.kt",
        )

        /** Group C's one route without a `Scaffold` (C22): the bars reach it from the root alone. */
        val GROUP_C_WITHOUT_SCAFFOLD = setOf("ui/scan/TagResultSheet.kt")

        /**
         * K2's closure: the screens that pass `contentWindowInsets = formContentInsets` are group B, all fifteen (B1–B6
         * since T4, B7–B15 since T5), and no other file names it.
         */
        val FORM_SCREENS = GROUP_B

        /** Group E (§4.5): the files whose bottom sheets take typed input. */
        val GROUP_E = setOf(
            "ui/asset/LinkSeasonSyncSheet.kt",
            "ui/attachments/AttachmentEditSheet.kt",
            "ui/condition/ChangeConditionSheet.kt",
            "ui/installed/InstalledComponentSheets.kt",
            "ui/references/MaterializeSheet.kt",
            "ui/references/ReferenceSheets.kt",
            "ui/supplies/AssetSuppliesSection.kt",
            "ui/service/ServiceCaseScreen.kt",
        )

        /** The sheet files with no typed input (§4.5): the overwrite sheet, the bind picker, the supply item picker. */
        val SHEETS_WITHOUT_INPUT = setOf(
            "ui/scan/WriteTagScreen.kt",
            "ui/scan/TagResultSheet.kt",
            "ui/supplies/SupplyItemPicker.kt",
        )

        val SHARE_INTAKE = setOf("share/ShareIntakeScreen.kt", "share/ShareIntakeActivity.kt")

        private const val PACKAGE = "src/main/kotlin/com/loosecannon/servicetag"

        fun packageRoot(): File = listOf(File(PACKAGE), File("app/$PACKAGE")).firstOrNull { it.isDirectory }
            ?: error("cannot find $PACKAGE from ${File(".").absolutePath}")

        fun source(path: String): Source =
            mainSources().singleOrNull { it.path == path } ?: error("cannot find $path under $PACKAGE")

        private val sources: List<Source> by lazy {
            val root = packageRoot()
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { Source(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
                .sortedBy { it.path }
                .toList()
        }

        /** Every Kotlin file under app/src/main/kotlin/com/loosecannon/servicetag, read once per run. */
        fun mainSources(): List<Source> = sources

        /** `path:line` of every line under app/src/main/kotlin whose code [matches]. */
        fun mainLines(matches: (String) -> Boolean): List<String> = mainSources().flatMap { file ->
            file.code.indices.filter { matches(file.code[it]) }.map { "${file.path}:${it + 1}" }
        }
    }
}

/**
 * Each of [lines] with everything that is not code blanked to spaces, column for column: `//` and `/* … */`
 * comments, and the contents of string and character literals. A string template's `${…}` stays code. Parentheses
 * counted on the result are the code's own.
 */
private fun codeOnly(lines: List<String>): List<String> {
    val contexts = ArrayDeque<Literal>()
    var commentDepth = 0
    return lines.map { line ->
        val out = StringBuilder(line.length)
        var at = 0
        fun blank(count: Int) {
            repeat(count) { out.append(' ') }
            at += count
        }
        while (at < line.length) {
            if (commentDepth > 0) {
                when {
                    line.startsWith("*/", at) -> { commentDepth--; blank(2) }
                    line.startsWith("/*", at) -> { commentDepth++; blank(2) }
                    else -> blank(1)
                }
                continue
            }
            when (val inside = contexts.lastOrNull()) {
                Literal.Quoted, Literal.Raw -> when {
                    inside == Literal.Quoted && line[at] == '\\' -> blank(minOf(2, line.length - at))
                    line.startsWith("\${", at) -> { contexts.addLast(Literal.Template()); blank(2) }
                    inside == Literal.Raw && line.startsWith("\"\"\"", at) -> { contexts.removeLast(); blank(3) }
                    inside == Literal.Quoted && line[at] == '"' -> { contexts.removeLast(); blank(1) }
                    else -> blank(1)
                }
                is Literal.Template, null -> when {
                    line.startsWith("//", at) -> blank(line.length - at)
                    line.startsWith("/*", at) -> { commentDepth++; blank(2) }
                    line.startsWith("\"\"\"", at) -> { contexts.addLast(Literal.Raw); blank(3) }
                    line[at] == '"' -> { contexts.addLast(Literal.Quoted); blank(1) }
                    line[at] == '\'' -> {
                        var end = at + 1
                        if (end < line.length && line[end] == '\\') end++
                        end = line.indexOf('\'', end + 1).takeIf { it >= 0 } ?: (line.length - 1)
                        blank(end - at + 1)
                    }
                    inside is Literal.Template && line[at] == '{' -> { inside.braces++; out.append('{'); at++ }
                    inside is Literal.Template && line[at] == '}' && inside.braces == 0 -> { contexts.removeLast(); blank(1) }
                    inside is Literal.Template && line[at] == '}' -> { inside.braces--; out.append('}'); at++ }
                    else -> { out.append(line[at]); at++ }
                }
            }
        }
        // A quoted string never spans lines: whatever a line leaves open from its first quoted string on closes with it.
        val firstQuoted = contexts.indexOf(Literal.Quoted)
        if (firstQuoted >= 0) while (contexts.size > firstQuoted) contexts.removeLast()
        out.toString()
    }
}

/** An open literal context while blanking: a quoted string, a raw string, or a template's braces inside either. */
private sealed interface Literal {
    data object Quoted : Literal
    data object Raw : Literal
    class Template(var braces: Int = 0) : Literal
}
