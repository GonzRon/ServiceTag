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
     * `(key, hash)` pairs where [FakeReminderProvider] keeps a map. If the contract below passes
     * for both then it is a property of the port and not of one fake's bookkeeping — including the
     * counter definitions, which [ReconcileReport] fixes rather than leaving to each provider.
     */
    private class SetBackedProvider : ReminderProvider {
        override val id: ProviderId = ProviderId.LOCAL
        private val held = mutableSetOf<Pair<SubjectKey, String>>()

        override suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport {
            val toHold = subjects.filterNot { it.state.isCleared }.map { it.key to it.contentHash }.toSet()
            val keptKeys = toHold.map { it.first }.toSet()
            // Counted by **key**, not by stamp: a subject whose hash moved was re-shown, not let go
            // of, so it is one `posted` and no `cleared`.
            val cleared = held.count { it.first !in keptKeys }
            val unchanged = toHold.count { it in held }
            held.clear()
            held += toHold
            return ReconcileReport(toHold.size - unchanged, cleared, unchanged, emptyList())
        }

        override suspend fun pullChanges(): List<RemoteChange> = emptyList()
        override suspend fun health(): List<HealthFinding> = emptyList()
    }

    private fun subject(
        id: String,
        dueOn: String?,
        state: SubjectState = SubjectState.Active,
    ): ReminderSubject {
        val rule = RuleFacts(TimeBasis.FIXED, 3, RecurrenceUnit.MONTH, hasMeter = false, seasonal = false)
        val due = dueOn?.let(LocalDate::parse)
        val body = if (state.isCleared) "" else "DUE"
        return ReminderSubject(
            key = SubjectKey.Schedule(ScheduleId(id)),
            title = "Filter change",
            body = body,
            dueOn = due,
            leadDays = 14,
            state = state,
            rule = rule,
            contentHash = ContentHash.of("Filter change", body, due, 14, state, rule),
        )
    }

    /**
     * The whole write surface, in the four steps that define it — asserted against any
     * implementation, because every one of them is a property of the port.
     *
     * 1. **Invariant 45:** the same list twice reports every subject `unchanged` and posts nothing.
     *    Because `reconcile` receives the desired state of the whole list, there is no per-subject
     *    call with which a caller could have produced a second effect even if it tried.
     * 2. **Absence is the cancel:** a subject dropped from the list is one the provider stops
     *    holding, and it is counted in `cleared`. This is the step the archived-group and
     *    reminders-switched-off rules lean on, since those subjects **leave** the list rather than
     *    arriving withdrawn — without it a provider keeps something standing with nothing to clear
     *    it, and an appending implementation passes everything else.
     * 3. **A cleared state is a cancel too:** a subject still in the list whose state says to let
     *    go is `cleared`, never `posted`, and is no longer held.
     * 4. **…and only once:** a standing withdrawal reports nothing at all on the next run, so a
     *    subject that stays in the list withdrawn for ever does not report a clearance for ever.
     */
    private suspend fun assertReconcileIsTheWholeWriteSurface(provider: ReminderProvider) {
        val subjects = listOf(subject("s1", "2026-04-20"), subject("s2", null))

        assertEquals(ReconcileReport(2, 0, 0, emptyList()), provider.reconcile(subjects))
        assertEquals(ReconcileReport(0, 0, 2, emptyList()), provider.reconcile(subjects))
        assertEquals(ReconcileReport(0, 1, 1, emptyList()), provider.reconcile(subjects.dropLast(1)))

        val withdrawn = listOf(subject("s1", "2026-04-20", SubjectState.Withdrawn))
        assertEquals(ReconcileReport(0, 1, 0, emptyList()), provider.reconcile(withdrawn))
        assertEquals(ReconcileReport(0, 0, 0, emptyList()), provider.reconcile(withdrawn))

        assertEquals(emptyList(), provider.pullChanges())
    }

    @Test
    fun reconcileTwiceWithTheSameListHasNoSecondEffect() = runTest {
        val provider = FakeReminderProvider()
        assertReconcileIsTheWholeWriteSurface(provider)

        assertEquals(5, provider.calls.size)
        assertEquals(provider.calls[0], provider.calls[1], "the first two calls carried identical lists")
        assertEquals(
            emptyList(),
            provider.held.keys.toList(),
            "the provider ends holding nothing: the last thing it was told was to let the one go",
        )

        // The same cancel, read off the provider's own bookkeeping rather than off its report: a
        // subject that left the list is one it is no longer holding, not merely one it did not count.
        val second = FakeReminderProvider()
        val both = listOf(subject("s1", "2026-04-20"), subject("s2", null))
        second.reconcile(both)
        second.reconcile(both.dropLast(1))
        assertEquals(
            listOf(SubjectKey.Schedule(ScheduleId("s1"))),
            second.held.keys.toList(),
            "the dropped subject is gone and the listed one is kept",
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
        assertReconcileIsTheWholeWriteSurface(SetBackedProvider())

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
        assertTrue(reminders.size >= 3, "the reminder package should not be smaller than its three files")
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

    /**
     * `SubjectState.Completed` is **reserved**: the spec's contract names four members and three
     * briefs compile against them, and 1.2 has no producer for this one.
     *
     * Decision 8's objection to a dead member — that it is a member something can write — is met by
     * making the write impossible to add quietly rather than by an instruction nobody reads. Every
     * place `core/src/main` mentions it, it shares a branch with `Withdrawn`, because both mean the
     * provider must stop holding the subject; there is no site that names it alone, so a future
     * producer has to take this pin out on purpose. The two guards on either side are what stop it
     * passing by deletion and by nobody mentioning it at all.
     */
    @Test
    fun nothingInCoreEverBuildsACompletedSubjectOnItsOwn() {
        assertTrue(
            "data object Completed : SubjectState" in sourceFile("$REMINDERS/ReminderPort.kt").readText(),
            "the member must still exist, or this would pass by deletion",
        )

        val reference = Regex("""\bSubjectState\.Completed\b""")
        val mentions = kotlinFilesUnder(CORE_MAIN).flatMap { file ->
            file.readText().lines().withIndex()
                .filter { (_, line) -> reference.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}" to line }
        }
        assertTrue(mentions.isNotEmpty(), "nothing mentions it at all, so this would pass vacuously")
        assertEquals(
            emptyList(),
            mentions.filterNot { (_, line) -> "SubjectState.Withdrawn" in line }.map { it.first },
            "a mention that does not share Withdrawn's branch is a producer this phase has no room for",
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
