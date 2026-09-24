package com.loosecannon.servicetag.policy

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tripwire for #62: the UI-driven release harness stays retired unless someone edits this
 * test in the open.
 *
 * 1. No file under [SCANNED] names a screen-driving tool. "File" means what git tracks there plus
 *    anything new that git would not ignore, so a harness is caught before its first commit and a
 *    build directory is never read.
 * 2. `docs/release-proofs.md` has exactly one R4 row, and the only test it names is
 *    `ShareBoundaryTest`: the external boundary is three connected cases, not a scripted journey.
 *
 * A JVM test, so it runs in R1 and in CI's build job. The files it reads are declared as inputs of
 * this module's test tasks (`app/build.gradle.kts`), so a planted harness can never be answered by
 * an up-to-date or cached result.
 */
class ReleaseProofPolicyTest {

    private val root: File = repositoryRoot()

    @Test fun noScannedFileNamesAScreenDrivingTool() {
        val offenders = scannedFiles().flatMap { path ->
            val file = File(root, path)
            if (!file.isFile) return@flatMap emptyList()
            // Latin-1 decodes any byte, so a binary fixture is scanned rather than skipped.
            file.readText(Charsets.ISO_8859_1).lineSequence().withIndex()
                .mapNotNull { (at, line) ->
                    FORBIDDEN.find(line)?.let { "$path:${at + 1}: ${it.value}" }
                }
                .toList()
        }

        assertEquals(
            "UI driving is retired as a release layer (planning policy, \"Testing hierarchy\"; " +
                "docs/release-proofs.md). A black-box test must name the OS boundary it alone " +
                "demonstrates; everything else is an in-process test",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun theExternalBoundaryRowIsShareBoundaryTestAlone() {
        val rows = File(root, RUNBOOK).readLines()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { row -> row.trim('|').split('|').map { it.trim() } }
            .filter { cells -> cells.firstOrNull() == "R4" }

        assertEquals("$RUNBOOK must have exactly one R4 row", 1, rows.size)
        assertEquals(
            "R4 is ShareBoundaryTest and nothing else; a new row entry needs a new OS boundary",
            setOf("ShareBoundaryTest"),
            TEST_CLASS.findAll(rows.single().joinToString("|")).map { it.value }.toSet(),
        )
    }

    /** Tracked, plus untracked-but-not-ignored, under the four scanned roots. */
    private fun scannedFiles(): List<String> {
        val git = ProcessBuilder(
            listOf("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--") +
                SCANNED,
        ).directory(root).redirectErrorStream(true).start()
        val out = git.inputStream.readBytes().toString(Charsets.UTF_8)
        check(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0) {
            "git ls-files failed in ${root.path}: $out"
        }
        val files = out.split('\u0000').filter { it.isNotEmpty() }
        check(files.isNotEmpty()) { "git listed nothing under $SCANNED; the scan would prove nothing" }
        return files
    }

    private companion object {
        val SCANNED = listOf("tools", "app/src/androidTest", "share-test-sender", ".github")
        const val RUNBOOK = "docs/release-proofs.md"

        /** Whole tokens only: `performTextInput` and a word merely containing one never match. */
        val FORBIDDEN = Regex(
            """(?<![A-Za-z0-9_])(uiautomator|input\s+tap|input\s+text|dumpsys)(?![A-Za-z0-9_])""",
            RegexOption.IGNORE_CASE,
        )

        val TEST_CLASS = Regex("""\b[A-Z][A-Za-z0-9]*Test\b""")

        /**
         * Gradle runs unit tests from the module directory and an IDE may use the repository root,
         * so walk up to the directory that holds both the settings script and the runbook.
         */
        fun repositoryRoot(): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, RUNBOOK).isFile }
                ?: error("cannot find the repository root above ${File("").absolutePath}")
    }
}
