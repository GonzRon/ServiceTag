package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.FakeReminderProvider
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The port's own contract: what `reconcile` guarantees, and what the port refuses to know.
 *
 * Both halves are here because both are properties of the *interface* rather than of any one
 * implementation. The first is behavioural and is asserted against two independent fakes, which is
 * the whole of the second-provider proof 1.2 can make: `ProviderId` carries one member, so a second
 * enabled row is unconstructable, and what is provable instead is that a second implementation
 * compiles against the same signatures and passes the same assertions (master plan decision 8).
 * The second half is structural and is read off the source, because no runtime assertion can see an
 * import that is not there.
 */
class ReminderPortContractTest {

    /**
     * A second implementation, written differently on purpose: it keeps one flat set of
     * `key|hash` strings where [FakeReminderProvider] keeps a map. If the contract below passes for
     * both then it is a property of the port and not of one fake's bookkeeping.
     */
    private class SetBackedProvider : ReminderProvider {
        override val id: ProviderId = ProviderId.LOCAL
        private val held = mutableSetOf<String>()
        private fun stamp(subject: ReminderSubject) = "${subject.key}|${subject.contentHash}"

        override suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport {
            val wanted = subjects.map(::stamp).toSet()
            val unchanged = wanted.count { it in held }
            val cleared = held.count { it !in wanted }
            held.clear()
            held += wanted
            return ReconcileReport(wanted.size - unchanged, cleared, unchanged, emptyList())
        }

        override suspend fun pullChanges(): List<RemoteChange> = emptyList()
        override suspend fun health(): List<HealthFinding> = emptyList()
    }

    private fun subject(id: String, dueOn: String?): ReminderSubject {
        val state = SubjectState.Active
        val rule = RuleFacts(TimeBasis.FIXED, 3, RecurrenceUnit.MONTH, hasMeter = false, seasonal = false)
        val due = dueOn?.let(LocalDate::parse)
        return ReminderSubject(
            key = SubjectKey.Schedule(ScheduleId(id)),
            title = "Filter change",
            body = "DUE",
            dueOn = due,
            leadDays = 14,
            state = state,
            rule = rule,
            contentHash = ContentHash.of("Filter change", "DUE", due, 14, state, rule),
        )
    }

    /**
     * Invariant 45. The second call reports every subject `unchanged`, posts nothing and clears
     * nothing — and because `reconcile` receives the desired state of the whole list, there is no
     * per-subject call with which a caller could have produced a second effect even if it tried.
     */
    private suspend fun assertReconcileIsIdempotent(provider: ReminderProvider) {
        val subjects = listOf(subject("s1", "2026-04-20"), subject("s2", null))

        assertEquals(ReconcileReport(2, 0, 0, emptyList()), provider.reconcile(subjects))
        assertEquals(ReconcileReport(0, 0, 2, emptyList()), provider.reconcile(subjects))
        assertEquals(emptyList(), provider.pullChanges())
    }

    @Test
    fun reconcileTwiceWithTheSameListHasNoSecondEffect() = runTest {
        val provider = FakeReminderProvider()
        assertReconcileIsIdempotent(provider)

        assertEquals(2, provider.calls.size)
        assertEquals(provider.calls[0], provider.calls[1], "the two calls carried identical lists")
        assertEquals(
            provider.calls.last().associate { it.key to it.contentHash },
            provider.held,
            "what the provider holds after the second call is exactly what the first left",
        )
    }

    /**
     * The other half of #28 AC 4 (invariant 49): a second provider is **additive**. `reconcile`
     * names no provider in its signature, the provider is carried by the port's `id` and by the key
     * of the caller's map, and a second implementation of the same interface passes the same
     * contract. The behavioural half — two lists actually built for two providers — lands with the
     * second real provider in Phase 5.
     */
    @Test
    fun reconcileNamesNoProviderAndASecondImplementationNeedsNoPortChange() = runTest {
        assertReconcileIsIdempotent(SetBackedProvider())

        val reconcile = ReminderProvider::class.java.methods.single { it.name == "reconcile" }
        assertEquals(
            listOf(List::class.java.name, "kotlin.coroutines.Continuation"),
            reconcile.parameterTypes.map { it.name },
            "reconcile takes the desired list and nothing provider-shaped",
        )
        assertEquals(
            ProviderId::class.java,
            ReminderProvider::class.java.methods.single { it.name == "getId" }.returnType,
        )
    }

    /**
     * Invariant 48, read off the source tree. `:core` names no Android type anywhere, and the
     * reminder package names no provider product and no delivery mechanism: a port that mentioned
     * one would have stopped being a port.
     *
     * The two enumerations are here for the same reason the brief's gate greps them: a member added
     * to either is a subject something can write and nothing can deliver.
     */
    @Test
    fun theReminderPortNamesNoAndroidTypeAndNoDeliveryMechanism() {
        assertTrue(kotlinFilesUnder(CORE_MAIN).size > 40, "the module should not be this small")
        assertEquals(
            emptyList(),
            kotlinFilesUnder(CORE_MAIN)
                .filter { Regex("""(?m)^import (android|androidx)\.""").containsMatchIn(it.readText()) }
                .map { it.name },
        )

        val reminders = kotlinFilesUnder(REMINDERS)
        assertEquals(3, reminders.size, "the reminder package is three files")
        val forbidden = Regex("""\b(todoist|notification|alarm|workmanager)\b""", RegexOption.IGNORE_CASE)
        assertEquals(
            emptyList(),
            reminders.filter { forbidden.containsMatchIn(it.readText()) }.map { it.name },
        )

        assertEquals(listOf(ProviderId.LOCAL), ProviderId.entries.toList())
        val port = sourceFile("$REMINDERS/ReminderPort.kt").readText()
        assertEquals(
            listOf("LOCAL"),
            Regex("""enum class ProviderId \{ ([^}]*) \}""").find(port)!!
                .groupValues[1].split(",").map { it.trim() },
        )
        val subjectKeyBlock = Regex(
            """sealed interface SubjectKey \{(.*?)\n\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(port)!!.groupValues[1]
        assertEquals(
            listOf("Schedule"),
            Regex("""data (?:class|object) (\w+)""").findAll(subjectKeyBlock)
                .map { it.groupValues[1] }
                .toList(),
        )
    }

    private companion object {
        const val CORE_MAIN = "core/src/main"
        const val REMINDERS = "core/src/main/kotlin/com/loosecannon/servicetag/core/reminders"

        /**
         * The repository root, found by walking up to the settings script: Gradle runs a module's
         * JVM tests with the module directory as the working directory, and an IDE run
         * configuration may use the root, so neither can be assumed.
         */
        fun repoRoot(): File {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("cannot find the repository root")
            }
            return dir
        }

        fun sourceFile(relative: String): File = File(repoRoot(), relative)
            .also { check(it.exists()) { "no such source path: $relative" } }

        fun kotlinFilesUnder(relative: String): List<File> = sourceFile(relative)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }
}
