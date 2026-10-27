package com.loosecannon.servicetag.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #114 (C33; A5a's carry): the graph's completion flow is given the asset store's read for an asset's name, so an
 * asset's Season Start service prompt draws its line (P114-14). `CompletionFlow.assetNames` defaults to a lookup that
 * answers null, so a missing argument would draw nothing in the app while every unit test passing its own lookup stays
 * green: this file is CI's guard on the production wiring. Patterns are anchored on a line's code, so a comment naming
 * them never matches.
 */
class CompletionFlowWiringTest {

    @Test fun theGraphsCompletionFlowReadsTheAssetsName() {
        val lines = source("di/AppGraph.kt")
        val wired = lines.indices.filter { WIRED.matches(lines[it]) }
        assertEquals("one `assetNames = { assets.get(it)?.name },` line in AppGraph.kt", 1, wired.size)
        val call = callSpan(lines, Regex("""^\s*val completionFlow: CompletionFlow = CompletionFlow\($"""))
        assertTrue("the line sits inside the graph's `CompletionFlow(` call ($call)", wired.single() in call)
    }

    /** From the first line [opening] matches to the line where its parentheses close, counting code only. */
    private fun callSpan(lines: List<String>, opening: Regex): IntRange {
        val start = lines.indexOfFirst { opening.matches(it) }
        assertTrue("a line matching $opening", start >= 0)
        var depth = 0
        for (index in start until lines.size) {
            val code = lines[index].substringBefore("//")
            depth += code.count { it == '(' } - code.count { it == ')' }
            if (depth <= 0) return start..index
        }
        error("the call opened at line ${start + 1} never closes")
    }

    private fun source(relative: String): List<String> {
        val path = "src/main/kotlin/com/loosecannon/servicetag/$relative"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")
        return file.readLines()
    }

    private companion object {
        val WIRED = Regex("""^\s*assetNames = \{ assets\.get\(it\)\?\.name \},$""")
    }
}
