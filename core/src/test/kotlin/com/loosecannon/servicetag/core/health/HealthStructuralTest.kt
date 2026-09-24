package com.loosecannon.servicetag.core.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Health is a pure function that stores nothing (inv. 82, 111): read off the source, because the
 * shape of a well-meant change — "let me cache the score", "let me check the snooze here" — is an
 * import, and no runtime assertion sees an import.
 */
class HealthStructuralTest {

    private val healthSources: List<File> = repoRoot()
        .resolve("core/src/main/kotlin/com/loosecannon/servicetag/core/health")
        .also { check(it.isDirectory) { "no health package at $it" } }
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun theHealthPackageImportsNoPortAndNoDeliveryType() {
        assertTrue(healthSources.map { it.name }.containsAll(listOf("AssetHealthEngine.kt", "HealthClock.kt", "HealthScore.kt")))
        val forbidden = Regex("""^import .*\.(ports\.[A-Za-z]*Repository|ports\.UnitOfWork|ports\.ScheduleLocalDelivery[A-Za-z]*)$""")
        val snooze = Regex("""^import .*[Ss]nooz""")
        val offenders = healthSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: $line".takeIf { forbidden.matches(line.trim()) || snooze.containsMatchIn(line.trim()) }
            }
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun theHealthPackageReadsNoClock() {
        val clock = Regex("""(\bLocalDate\.now\(|\bInstant\.now\(|System\.currentTimeMillis\(|^import .*\.ports\.(Clock|Today)$)""")
        val offenders = healthSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: $line".takeIf { clock.containsMatchIn(line.trim()) }
            }
        }
        assertEquals(emptyList(), offenders, "`T` is an argument; the engine never asks what day it is")
    }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
        }
        return dir
    }
}
