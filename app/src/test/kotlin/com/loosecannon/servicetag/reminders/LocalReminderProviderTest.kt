package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.reminders.ReconcileReport
import com.loosecannon.servicetag.core.reminders.ReminderProvider
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.reminders.SubjectState
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification shade, as a value: what `notify` put there is what `standing` answers. */
internal class FakeReminderNotifications : ReminderNotifications {
    val items = linkedMapOf<String, ItemPost>()
    var summary: SummaryPost? = null
    val postedItems = mutableListOf<ItemPost>()
    val cancelled = mutableListOf<String>()

    /** B07: the quick actions each post arrived with, in post order. */
    val postedActions = mutableListOf<List<QuickAction>>()

    override fun standingItems(): Set<String> = items.keys.toSet()
    override fun standingSummary(): String? = summary?.tag

    override fun postItem(post: ItemPost, actions: List<QuickAction>) {
        items[post.tag] = post
        postedItems += post
        postedActions += actions
    }

    override fun postSummary(summary: SummaryPost) {
        this.summary = summary
    }

    override fun cancelItem(tag: String) {
        items.remove(tag)
        cancelled += tag
    }

    override fun cancelSummary() {
        summary = null
    }

    /** What a cleared app looks like: the shade is empty and nothing remembers it was not. */
    fun clearEverything() {
        items.clear()
        summary = null
    }
}

internal class FakeDeliveryRepository : ScheduleLocalDeliveryRepository {
    val rows = linkedMapOf<String, ScheduleLocalDelivery>()
    override suspend fun get(id: ScheduleId): ScheduleLocalDelivery? = rows[id.value]
    override suspend fun upsert(row: ScheduleLocalDelivery) { rows[row.scheduleId.value] = row }
    override suspend fun all(): List<ScheduleLocalDelivery> = rows.values.sortedBy { it.scheduleId.value }
    override suspend fun deleteAll() = rows.clear()
}

internal class GrantablePermission(var isGranted: Boolean = true) : NotificationPermission {
    override fun granted(): Boolean = isGranted
    override fun shouldExplain(): Boolean = false
    override suspend fun request(): Boolean = isGranted
}

/** Per channel, because muting one of the two must not silence the other (fix round 1, finding 2). */
internal class MutablePlatformState(
    var enabled: Boolean = true,
    val importances: MutableMap<String, ChannelImportance> = mutableMapOf(),
    var restriction: AppRestriction = AppRestriction.NORMAL,
) : PlatformState {
    override fun notificationsEnabled(): Boolean = enabled
    override fun channelImportance(channelId: String): ChannelImportance =
        importances[channelId] ?: ChannelImportance.DEFAULT
    override fun appRestricted(): AppRestriction = restriction
}

private class MapKeyValueStore : KeyValueStore {
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
}

/**
 * The `LOCAL` provider against the port's own contract, and against the four rules only a real
 * implementation can break.
 *
 * The first test is the shared contract helper's sequence — `ReminderPortContractTest`'s
 * `assertReconcileIsTheWholeWriteSurface`, **transcribed** rather than called: that helper is a
 * private member of a `:core` test class, and `:app`'s test source set has no dependency on
 * `:core`'s test artifact (adding one would be a build change beyond this brief's single declared
 * dependency decision). The sequence is identical, and it gains the **sixth** step the reviewer
 * asked for: a subject whose content hash moved reports `(1, 0, 0)` — re-shown, not let go of.
 */
class LocalReminderProviderTest {

    private val delivery = FakeDeliveryRepository()
    private val notifications = FakeReminderNotifications()
    private val permission = GrantablePermission()
    private val platform = MutablePlatformState()
    private val alarm = RecordingDigestAlarm(isArmed = true)
    private val prefs = AppPrefs(MapKeyValueStore())
    private val clock = Clock { Fixture.NOW }

    /**
     * The status is the facts', never the date's — which is the whole of carry-forward (c). Subjects
     * are DUE unless a test puts their key in [overdue], and a key this source has never been shown
     * answers null, which is how a vanished schedule is simulated.
     */
    private val facts = DeliveryFactsSource { key ->
        if (key !in known) {
            null
        } else {
            Fixture.facts(
                status = if (key in overdue) DueStatus.OVERDUE else DueStatus.DUE,
                ownerName = "Pump house filter",
            )
        }
    }
    private val known = mutableSetOf<SubjectKey>()
    private val overdue = mutableSetOf<SubjectKey>()

    private fun provider() = LocalReminderProvider(
        facts = facts,
        delivery = delivery,
        notifications = notifications,
        permission = permission,
        platform = platform,
        alarm = alarm,
        prefs = prefs,
        clock = clock,
        // B07's builder, over the same delivery rows: what it issues and which actions it issues
        // for are `QuickActionsTest`'s, and what matters here is that a run posts through it.
        quickActions = QuickActions(
            shapes = QuickActionShapeSource {
                QuickActionShape(groupTargeted = false, completionMode = CompletionMode.QUICK, meterRule = false)
            },
            nonces = NonceStore(delivery, IdGenerator { "quick-nonce" }, clock),
        ),
    )

    private fun subject(id: String, dueOn: String?, state: SubjectState = SubjectState.Active, stamp: String = "v1") =
        Fixture.subject(id, dueOn, state = state, stamp = stamp).also { known += it.key }

    @Test
    fun reconcileIsTheWholeWriteSurface() = runTest {
        val provider = provider()
        val subjects = listOf(subject("s1", "2026-04-20"), subject("s2", null))

        assertEquals(ReconcileReport(2, 0, 0, emptyList()), provider.reconcile(subjects))
        assertEquals(ReconcileReport(0, 0, 2, emptyList()), provider.reconcile(subjects))
        assertEquals(ReconcileReport(0, 1, 1, emptyList()), provider.reconcile(subjects.dropLast(1)))

        val withdrawn = listOf(subject("s1", "2026-04-20", SubjectState.Withdrawn))
        assertEquals(ReconcileReport(0, 1, 0, emptyList()), provider.reconcile(withdrawn))
        assertEquals(ReconcileReport(0, 0, 0, emptyList()), provider.reconcile(withdrawn))

        // The sixth step: back to showing it, then move its content. A hash that moved is one
        // `posted` and no `cleared` — the stale notification is replaced, not let go of.
        assertEquals(ReconcileReport(1, 0, 0, emptyList()), provider.reconcile(listOf(subject("s1", "2026-04-20"))))
        val moved = subject("s1", "2026-04-20", stamp = "v2")
        assertEquals(ReconcileReport(1, 0, 0, emptyList()), provider.reconcile(listOf(moved)))
        assertEquals("only the new form is showing", setOf(itemTag(moved.key, moved.contentHash)), notifications.standingItems())

        assertEquals(emptyList<Any>(), provider.pullChanges())
    }

    /**
     * D-21 and invariant 56, on the path the digest policy cannot see. **Absence is the cancel**, so
     * a subject that left the list has no input row at all — and its notification's nonce must go
     * with the notification, or the last value this app issued would still be accepted by a forged
     * broadcast with nothing standing to act on.
     */
    @Test
    fun aCancelledNotificationTakesItsNonceWithIt() = runTest {
        val provider = provider()
        val nonces = NonceStore(delivery, IdGenerator { "nonce-1" }, clock)
        val subject = subject("s1", "2026-04-20")

        provider.reconcile(listOf(subject))
        nonces.issue(ScheduleId("s1"))
        assertEquals("nonce-1", delivery.get(ScheduleId("s1"))?.actionNonce)

        // The subject leaves the list altogether — reminders switched off for it, or its group
        // archived — rather than arriving withdrawn.
        provider.reconcile(emptyList())

        assertEquals(emptySet<String>(), notifications.standingItems())
        assertEquals(null, delivery.get(ScheduleId("s1"))?.actionNonce)
        assertEquals(null, delivery.get(ScheduleId("s1"))?.nonceIssuedAt)
    }

    /**
     * Invariant 45 and #21 AC 7, read off the platform rather than off the report: the second run
     * hands the shade **nothing**, and the withdrawal's nonce is gone with its notification (D-21).
     */
    @Test
    fun reconcileTwiceWithTheSameListPostsNothingTheSecondTime() = runTest {
        val provider = provider()
        val subjects = listOf(subject("s1", "2026-04-20"), subject("s2", "2026-04-21"))

        provider.reconcile(subjects)
        val afterFirst = notifications.postedItems.size
        val summaryAfterFirst = notifications.summary

        provider.reconcile(subjects)

        assertEquals("no item was handed to the platform again", afterFirst, notifications.postedItems.size)
        assertEquals("the summary was not re-announced either", summaryAfterFirst, notifications.summary)
        assertEquals(emptyList<String>(), notifications.cancelled)
    }

    /**
     * The matrix's "the projection is not rebuildable" row (invariant 44). Cancel every
     * notification, cancel the alarm, clear the delivery table — a cleared app — and **one**
     * `reconcile` restores the posted set and the armed alarm from schedule state alone. The
     * provider remembers nothing between calls, so this is a property of its construction and not
     * of a cache that happened to be cold.
     */
    @Test
    fun oneReconcileRebuildsThePostedSetAndTheAlarmFromNothing() = runTest {
        val provider = provider()
        val subjects = listOf(subject("s1", "2026-04-20"), subject("s2", "2026-04-21"))
        provider.reconcile(subjects)
        val postedWhenHealthy = notifications.standingItems()

        notifications.clearEverything()
        alarm.cancel()
        delivery.deleteAll()

        val report = provider.reconcile(subjects)

        assertEquals(postedWhenHealthy, notifications.standingItems())
        assertTrue("the armed alarm is half of this provider's projection", alarm.armed())
        assertEquals(2, report.posted)
        assertEquals(
            "the rows it had to invent are written back, so the next run is quiet",
            listOf("s1", "s2"),
            delivery.all().map { it.scheduleId.value },
        )
    }

    /**
     * The matrix's "denied permission disabling things" row (invariant 61, D-22). With the
     * permission denied the alarm is still armed, the preferences are still writable, the recompute
     * still runs (its seam is not this class's and is proven in `BackstopWorkerTest`), nothing is
     * posted, and **exactly one** finding is produced. A guard that skipped arming would leave the
     * machinery dead the moment the owner granted the permission.
     */
    @Test
    fun aDeniedPermissionDisablesNothingAndProducesExactlyOneFinding() = runTest {
        val provider = provider()
        permission.isGranted = false
        alarm.cancel()

        val report = provider.reconcile(listOf(subject("s1", "2026-04-20")))

        assertTrue("the alarm is armed even though nothing can be posted", alarm.armed())
        assertEquals(ReconcileReport(0, 0, 0, report.problems), report)
        assertEquals(1, report.problems.size)
        assertEquals(emptyList<ItemPost>(), notifications.postedItems)

        prefs.digestHour = 7
        assertEquals("the preferences are still writable", 7, prefs.digestHour)

        val findings = provider.health()
        assertEquals(listOf("NOTIFICATIONS_BLOCKED"), findings.map { it.code })
        assertEquals(
            "Notifications are turned off, so maintenance reminders will not arrive.",
            findings.single().message,
        )
    }

    /**
     * The global switch behaves exactly as a denied permission does, for the same reason: it
     * silences delivery and disables nothing. Its finding is the ratified `REMINDERS_GLOBALLY_OFF`
     * sentence.
     *
     * And it **takes down what was showing** (fix round 1, finding 5). D-22 requires that nothing
     * be *disabled* — the alarm is still armed below — but it does not require a stale posted set to
     * outlive the switch that stopped the posting: those notifications carry no action and no
     * content intent, so leaving them would leave the owner inert text to swipe by hand while the
     * health screen says reminders are off. Their nonces go with them, which is D-21's
     * "on … reconcile" clause.
     */
    @Test
    fun theGlobalSwitchSilencesAndTakesDownWhatWasShowing() = runTest {
        val provider = provider()
        val nonces = NonceStore(delivery, IdGenerator { "nonce-1" }, clock)
        provider.reconcile(listOf(subject("s1", "2026-04-20"), subject("s2", "2026-04-21")))
        nonces.issue(ScheduleId("s1"))
        assertEquals(2, notifications.standingItems().size)

        prefs.remindersEnabled = false
        val report = provider.reconcile(listOf(subject("s1", "2026-04-20"), subject("s2", "2026-04-21")))

        assertEquals(0, report.posted)
        assertEquals("cleared honestly carries what was taken down", 2, report.cleared)
        assertEquals(emptySet<String>(), notifications.standingItems())
        assertEquals(null, notifications.summary)
        assertEquals("a nonce with nothing standing to authorise is gone", null, delivery.get(ScheduleId("s1"))?.actionNonce)
        assertTrue("and nothing was disabled: the alarm is still armed", alarm.armed())

        assertEquals(listOf("REMINDERS_GLOBALLY_OFF"), provider.health().map { it.code })
        assertEquals("Reminders are turned off in ServiceTag.", provider.health().single().message)
    }

    /**
     * Muting one channel is not a global mute (fix round 1, finding 2). An owner who sets
     * "Maintenance due" to *None* has silenced the DEFAULT channel and nothing else, so the
     * **OVERDUE item is still posted** on the un-muted HIGH channel — a notification Android itself
     * would have delivered.
     *
     * `health()` still folds any muted or absent channel into `NOTIFICATIONS_BLOCKED` (R8), because
     * that is the one code the spec ships for "the system will not show this" and it is true of the
     * half that is muted.
     */
    @Test
    fun aMutedDueChannelStillPostsTheOverdueItem() = runTest {
        val provider = provider()
        overdue += SubjectKey.Schedule(ScheduleId("s2"))
        platform.importances[NotificationChannels.DUE] = ChannelImportance.MUTED

        val report = provider.reconcile(listOf(subject("s1", "2026-04-20"), subject("s2", "2026-04-21")))

        assertEquals(
            "the overdue item went out; the due one is the platform's own refusal",
            listOf(NotificationChannels.OVERDUE),
            notifications.postedItems.map { it.channelId },
        )
        assertEquals(1, report.posted)
        assertEquals(listOf("NOTIFICATIONS_BLOCKED"), provider.health().map { it.code })
        assertEquals(
            "Notifications are turned off, so maintenance reminders will not arrive.",
            provider.health().single().message,
        )
    }

    /** A missing alarm is its own finding, with its own ratified sentence. */
    @Test
    fun aMissingAlarmIsItsOwnFinding() = runTest {
        val provider = provider()
        alarm.cancel()

        assertEquals(
            "The daily reminder check is not scheduled, so today's maintenance may go unannounced.",
            provider.health().single { it.code == "DIGEST_ALARM_MISSING" }.message,
        )
    }

    /**
     * The matrix's "the notification signalling by colour alone" row (#11's and #21's Visual design
     * sections, D12 §5). The posted notification carries the **ratified status word**, the two
     * statuses differ in **text** and not only in an accent, and the accent itself is read from the
     * semantic tokens — asserted at the source, because no value assertion can see a hex literal
     * that has not been written yet.
     */
    @Test
    fun theDueAndOverdueDistinctionSurvivesWithColourRemoved() = runTest {
        val due = DigestPolicy.decide(
            listOf(DeliveryInput(Fixture.subject("s1", "2026-06-15"), Fixture.facts(DueStatus.DUE), null)),
            emptySet(),
            null,
            Fixture.NOW,
        ).posts.single()
        val overdue = DigestPolicy.decide(
            listOf(DeliveryInput(Fixture.subject("s2", "2026-05-30"), Fixture.facts(DueStatus.OVERDUE), null)),
            emptySet(),
            null,
            Fixture.NOW,
        ).posts.single()

        assertEquals("DUE", due.statusWord)
        assertEquals("OVERDUE", overdue.statusWord)
        assertNotEquals("the bodies differ too, so the word is not the only carrier", due.body, overdue.body)

        val source = sourceFile("kotlin/com/loosecannon/servicetag/reminders/Notifications.kt").readText()
        assertTrue("the accent comes from the semantic tokens", "ServiceTagLightSemanticColors" in source)
        assertTrue("the status word is put on the notification", "setSubText(post.statusWord)" in source)
        assertFalse(
            "no improvised colour: an operational meaning never comes from a raw hex here",
            Regex("""0x[0-9A-Fa-f]{6,8}""").containsMatchIn(source),
        )
    }

    /**
     * The matrix's "delivery state leaking into a backup" row (invariants 64, 65). Read two ways,
     * because either alone is weak: the delivery table's names appear nowhere in the backup or
     * merge sources, and **no backup use case can even be handed the port** — a reflective check
     * over the four constructors, which is what catches a parameter added later by someone who did
     * not read this brief.
     */
    @Test
    fun noDeliveryStateReachesAnExportOrAMerge() {
        val forbidden = Regex("""schedule_local_delivery|ScheduleLocalDelivery""")
        listOf("backup", "merge").forEach { directory ->
            coreSources("core/src/main/kotlin/com/loosecannon/servicetag/core/$directory").forEach { file ->
                assertFalse(
                    "${file.path} must not name the device-local delivery table",
                    forbidden.containsMatchIn(file.readText()),
                )
            }
        }

        listOf(
            ExportBackupSet::class.java,
            ImportBackupReplace::class.java,
            BuildBackupMergePlan::class.java,
            ApplyBackupMergePlan::class.java,
        ).forEach { type ->
            val parameters = type.constructors.flatMap { it.parameterTypes.toList() }.map { it.name }
            assertFalse(
                "${type.simpleName} must not take the delivery port",
                parameters.any { "ScheduleLocalDelivery" in it },
            )
        }
    }

    /**
     * The snooze and the nonce, as B07 and B09 consume them. One column of one device-local row
     * each: the snooze moves no date and writes no event (invariant 20), and a stale, missing or
     * already-used nonce is refused and writes nothing (invariant 56) — which is what leaves
     * invariants 55 and 56 provable by B07.
     */
    @Test
    fun theSnoozeAndTheNonceWriteOneDeviceLocalRowAndNothingElse() = runTest {
        val id = ScheduleId("s1")
        val snooze = ReminderSnooze(delivery, clock)
        val nonces = NonceStore(delivery, IdGenerator { "nonce-1" }, clock)

        snooze.snooze(id, Fixture.NOW + 86_400_000L)
        assertEquals(Fixture.NOW + 86_400_000L, delivery.get(id)?.snoozedUntilAt)

        val issued = nonces.issue(id)
        assertEquals("nonce-1", issued)
        assertEquals("issuing a nonce does not disturb the snooze", Fixture.NOW + 86_400_000L, delivery.get(id)?.snoozedUntilAt)

        assertFalse("a value that was never issued", nonces.consume(id, "forged"))
        assertEquals("nonce-1", delivery.get(id)?.actionNonce)
        assertTrue(nonces.consume(id, issued))
        assertEquals(null, delivery.get(id)?.actionNonce)
        assertFalse("already used", nonces.consume(id, issued))
        assertFalse("missing row", nonces.consume(ScheduleId("nobody"), "nonce-1"))
        assertEquals(null, delivery.rows["nobody"])
    }
}

/**
 * Every `.kt` under a repository-root-relative directory. Gradle runs this module's unit tests with
 * the module directory as the working directory and an IDE may use the repository root, so both are
 * tried — the same convention `ManifestContractTest` and `MigrationTestSupport` use.
 */
private fun coreSources(relative: String): List<File> {
    val directory = listOf(File(relative), File("../$relative")).firstOrNull { it.isDirectory }
        ?: error("cannot find $relative from ${File(".").absolutePath}")
    return directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
