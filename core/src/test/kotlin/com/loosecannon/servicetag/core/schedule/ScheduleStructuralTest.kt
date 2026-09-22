package com.loosecannon.servicetag.core.schedule

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three facts about the engine that are properties of the **whole source tree** rather than of
 * any one function, read off the source because no runtime assertion can see them: one writer into
 * derived state, no stored status anywhere, and no read site for the two deferred columns.
 *
 * Each of them is the shape of a plausible, well-meant change — "let me fix up that state row
 * here", "let me cache the status for the dashboard", "let me implement season re-entry while I am
 * in here" — which is exactly why a test rather than a paragraph.
 */
class ScheduleStructuralTest {

    /**
     * Invariant 17: `rebuild` is the only write path into `schedule_state`.
     *
     * Asserted as a **write** list rather than a name list: every file in `src/main` is scanned for
     * a property typed `ScheduleStateRepository` and then for an `upsert` against that property, and
     * tree-wide there must be exactly one such call, in the recompute. Naming the port is fine and
     * will become commoner — a dashboard read model and a `GET` handler both have to hold it to
     * *read* derived state — so a test that fixed the set of files naming it would have to be
     * loosened by every legitimate reader, which is how an assertion like this one dies. Paths are
     * compared whole, not by basename, so two files with one name cannot stand in for each other.
     */
    @Test
    fun scheduleStateHasExactlyOneWriter() {
        val holder = Regex("""val (\w+): ScheduleStateRepository""")
        val writers = mutableMapOf<String, Int>()
        var holders = 0
        mainSourceFiles().forEach { file ->
            val text = file.readText()
            val names = holder.findAll(text).map { it.groupValues[1] }.toList()
            holders += names.size
            val calls = names.sumOf { name -> Regex("""\b$name\.upsert\(""").findAll(text).count() }
            if (calls > 0) writers[file.relativeTo(repoRoot()).path] = calls
        }
        assertTrue(holders > 0, "nothing holds the port at all, so this would pass vacuously")
        assertEquals(
            mapOf("core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/RecomputeSchedules.kt" to 1),
            writers,
        )
    }

    /**
     * Invariant 18: status is never stored in any form. The derived word is named `DueStatus`
     * precisely so this can be asserted — `ScheduleStatus` is the stored lifecycle column, a
     * different type with a different name — and it must appear nowhere in the backup format, the
     * merge or the Room layer, which are the three places a value "cached for the dashboard" would
     * come to rest.
     */
    @Test
    fun noDueStatusNameIsEverPersisted() {
        val persistence = listOf(
            "core/src/main/kotlin/com/loosecannon/servicetag/core/backup",
            "core/src/main/kotlin/com/loosecannon/servicetag/core/merge",
            "app/src/main/kotlin/com/loosecannon/servicetag/data",
        ).flatMap { path -> kotlinFilesUnder(path) }
        assertTrue(persistence.size > 10, "the persistence layer should not be this small")
        assertEquals(
            emptyList(),
            persistence.filter { Regex("""\bDueStatus\b""").containsMatchIn(it.readText()) }.map { it.name },
        )
    }

    /**
     * Invariant 26: `seasonReentry` and `seasonReentryOffsetDays` are **stored and never read** in
     * 1.2. They exist in the model, the backup DTO, the entity, the mappers and the command, so the
     * column never has to be added later; implementing the re-entry semantics early would put a
     * read site inside the engine, which is what the first half looks for. The second half is the
     * negative control: a "fix" that deleted the columns would pass the grep and fail the brief.
     */
    @Test
    fun theDeferredReentryColumnsAreStoredAndNeverRead() {
        assertEquals(
            emptyList(),
            kotlinFilesUnder("core/src/main/kotlin/com/loosecannon/servicetag/core/schedule")
                .filter { "seasonReentry" in it.readText() }
                .map { it.name },
        )

        listOf(
            "core/src/main/kotlin/com/loosecannon/servicetag/core/model/Maintenance.kt",
            "core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupFormat.kt",
            "core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ScheduleCommands.kt",
            "app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/MaintenanceEntities.kt",
            "app/src/main/kotlin/com/loosecannon/servicetag/data/room/MaintenanceMappers.kt",
        ).forEach { path ->
            assertTrue(
                "seasonReentry" in sourceFile(path).readText(),
                "$path must still carry the deferred columns",
            )
        }
    }

    /**
     * The engine reads no clock: `T` arrives as an argument and `Today` is a port its callers hold,
     * which is what makes `rebuild` idempotent and every date test deterministic.
     */
    @Test
    fun theEngineNeverReadsAClock() {
        assertEquals(
            emptyList(),
            kotlinFilesUnder("core/src/main/kotlin/com/loosecannon/servicetag/core/schedule")
                .filter { "nowMillis()" in it.readText() }
                .map { it.name },
        )
    }

    /** `:core` stays Android-free: the engine is a JVM library, provable by its imports. */
    @Test
    fun theCoreModuleNamesNoAndroidImport() {
        val offenders = kotlinFilesUnder("core/src/main")
            .filter { Regex("""(?m)^import (android|androidx)\.""").containsMatchIn(it.readText()) }
        assertEquals(emptyList(), offenders.map { it.name })
    }

    private companion object {
        /**
         * The repository root, found by walking up to the settings script: Gradle runs a module's
         * JVM tests with the module directory as the working directory, and an IDE run
         * configuration may use the root, so neither can be assumed.
         */
        fun repoRoot(): File {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
            }
            return dir
        }

        fun sourceFile(relative: String): File = File(repoRoot(), relative)
            .also { check(it.exists()) { "no such source path: $relative" } }

        fun kotlinFilesUnder(relative: String): List<File> = sourceFile(relative)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        fun mainSourceFiles(): List<File> =
            kotlinFilesUnder("core/src/main") + kotlinFilesUnder("app/src/main")
    }
}
