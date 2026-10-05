package com.loosecannon.servicetag.l10n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #102 — RTL readiness: no layout assumes left-to-right. No shipped language reads right to left yet, but the first
 * one that does must only need its pack and `android:supportsRtl`, not a hunt through the screens. Layout uses
 * start/end (`Alignment.Start`, `padding(start = …)`, `TextAlign.Start`) and directional icons come from
 * `Icons.AutoMirrored`, which flip with the layout.
 */
class LayoutDirectionGuardTest {

    @Test fun noLayoutAssumesLeftToRight() {
        val sources = listOf(File(SOURCES), File("app/$SOURCES")).firstOrNull { it.isDirectory }
            ?: error("cannot find $SOURCES from ${File(".").absolutePath}")
        val offenders = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                    .filter { (_, line) -> ABSOLUTE.containsMatchIn(line) }
                    .map { (at, line) -> "${file.relativeTo(sources).invariantSeparatorsPath}:${at + 1}: ${line.trim()}" }
            }.toList()
        assertEquals("use start/end and Icons.AutoMirrored, never left/right", emptyList<String>(), offenders)
    }

    private companion object {
        const val SOURCES = "src/main/kotlin/com/loosecannon/servicetag"

        val ABSOLUTE = Regex(
            """\b(Alignment|AbsoluteAlignment)\.(Left|Right|CenterLeft|CenterRight|TopLeft|TopRight|BottomLeft|BottomRight)\b|""" +
                """\bTextAlign\.(Left|Right)\b|\bArrangement\.Absolute\b|\babsolutePadding\(|\babsoluteOffset\(|""" +
                """\bpadding\([^)]*\b(left|right)\s*=|""" +
                """\bIcons\.(Filled|Outlined|Default|Rounded|Sharp|TwoTone)\.(ArrowBack|ArrowForward|ArrowBackIos|ArrowForwardIos|""" +
                """KeyboardArrowLeft|KeyboardArrowRight|NavigateBefore|NavigateNext|Send|OpenInNew|List|Sort|Undo|Redo|Login|Logout)\b""",
        )
    }
}
