package com.loosecannon.servicetag.ui.maintenance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #123 W1 (§3.7): the graph's reminder sweep reaches the schedule detail, the group detail and the Log maintenance
 * picker. Every `reconcile` is defaulted to the no-op, so a missing argument would run nothing in the app while every
 * unit test, which passes its own sweep, stays green: this file is CI's only guard on the wiring.
 *
 * The patterns are anchored on a line's code from its first non-blank character, so a comment naming them never
 * matches.
 */
class ReminderSweepWiringTest {

    @Test fun theScheduleDetailsGraphConstructorPassesTheSweep() {
        assertWiredInsideTheGraphConstructor("ScheduleDetailViewModel.kt")
    }

    @Test fun theGroupDetailsGraphConstructorPassesTheSweep() {
        assertWiredInsideTheGraphConstructor("GroupDetailViewModel.kt")
    }

    @Test fun theRootPassesTheSweepToTheLogMaintenancePicker() {
        val lines = source("ui/nav/ServiceTagRoot.kt")
        val wired = lines.indices.filter { WIRED.matches(lines[it]) }
        assertEquals("one `reconcile = graph.reminderReconcile,` line in ServiceTagRoot.kt", 1, wired.size)
        val call = callSpan(lines, Regex("""^\s*LogMaintenancePicker\($"""))
        assertTrue("the line sits inside the `LogMaintenancePicker(` call ($call)", wired.single() in call)
    }

    @Test fun thePickersQuickCompletionSweepsAfterItsWrite() {
        val lines = source("ui/maintenance/CompletionFlow.kt")
        val start = lines.indexOfFirst { Regex("""^\s*fun LogMaintenancePicker\($""").matches(it) }
        assertTrue("CompletionFlow.kt declares `fun LogMaintenancePicker(`", start >= 0)
        val picker = lines.drop(start)
        val completes = picker.filter { Regex("""^\s*(val \w+ = )?flow\.complete\(""").containsMatchIn(it) }
        assertEquals("the picker completes through the flow on one line", 1, completes.size)
        assertTrue(
            "the picker's `flow.complete(` passes a trailing lambda naming `runAfterWrite`: ${completes.single().trim()}",
            Regex("""flow\.complete\([^)]*\)\s*\{\s*reconcile\.runAfterWrite\(\w+\)\s*\}\s*$""")
                .containsMatchIn(completes.single()),
        )
    }

    private fun assertWiredInsideTheGraphConstructor(file: String) {
        val lines = source("ui/maintenance/$file")
        val wired = lines.indices.filter { WIRED.matches(lines[it]) }
        assertEquals("one `reconcile = graph.reminderReconcile,` line in $file", 1, wired.size)
        val constructor = callSpan(lines, Regex("""^\s*constructor\(graph: AppGraph\b.*"""))
        assertTrue("the line sits inside $file's `constructor(graph: AppGraph` block ($constructor)", wired.single() in constructor)
    }

    /**
     * The lines from the first one [opening] matches to the line where its parentheses close, counted on code only:
     * each line's `//` comment is dropped before counting.
     */
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
        val WIRED = Regex("""^\s*reconcile = graph\.reminderReconcile,$""")
    }
}
