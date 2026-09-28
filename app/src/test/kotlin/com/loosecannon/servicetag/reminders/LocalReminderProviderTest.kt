package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import com.loosecannon.servicetag.core.ports.DeadlineLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.BuildDeadlineSubjects
import com.loosecannon.servicetag.core.reminders.BuildLoanSubjects
import com.loosecannon.servicetag.core.reminders.BuildReminderSubjects
import com.loosecannon.servicetag.core.reminders.ProviderId
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
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
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

    /** #72: every post and cancel in the order the provider made them. */
    val log = mutableListOf<String>()

    override fun standingItems(): Set<String> = items.keys.toSet()
    override fun standingSummary(): String? = summary?.tag

    override fun postItem(post: ItemPost, actions: List<QuickAction>) {
        items[post.tag] = post
        postedItems += post
        postedActions += actions
        log += "post ${post.tag}"
    }

    override fun postSummary(summary: SummaryPost) {
        this.summary = summary
    }

    override fun cancelItem(tag: String) {
        items.remove(tag)
        cancelled += tag
        log += "cancel $tag"
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

/** #79: the deadline stamps in a map, keyed as the table's primary key is. */
internal class FakeDeadlineDeliveryRepository : DeadlineLocalDeliveryRepository {
    val rows = linkedMapOf<Pair<String, String>, DeadlineLocalDelivery>()
    override suspend fun get(kind: String, subjectId: String): DeadlineLocalDelivery? = rows[kind to subjectId]
    override suspend fun upsert(row: DeadlineLocalDelivery) { rows[row.kind to row.subjectId] = row }
    override suspend fun delete(kind: String, subjectId: String) { rows.remove(kind to subjectId) }
    override suspend fun all(): List<DeadlineLocalDelivery> =
        rows.values.sortedWith(compareBy({ it.kind }, { it.subjectId }))
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
    /** #79 (R79-14c): the platform boot count; null is a count that cannot be read. */
    var boot: Int? = 1,
) : PlatformState {
    override fun notificationsEnabled(): Boolean = enabled
    override fun channelImportance(channelId: String): ChannelImportance =
        importances[channelId] ?: ChannelImportance.DEFAULT
    override fun appRestricted(): AppRestriction = restriction
    override fun bootCount(): Int? = boot
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
    /** #72 fix round 1: the clock a test may move; every shipped case leaves it at `Fixture.NOW`. */
    private var nowMillis: Long = Fixture.NOW
    private val clock = Clock { nowMillis }

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

    // #79: the warranty half — real assets, the real subject builder and the real facts source.
    private val assets = FakeAssetRepository()
    private val deadlines = FakeDeadlineDeliveryRepository()
    private var today: LocalDate = LocalDate.parse("2031-06-10")

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
        deadlineFacts = DeadlineDeliveryFacts(
            assets,
            Today { today },
            loans,
            clock,
            digestHour = { 9 },
            zone = { ZoneOffset.UTC },
        ),
        deadlineDelivery = deadlines,
    )

    // #72: the loan half — the real builder and the real facts source over the loans. `Fixture.NOW` is
    // 2026-06-09 10:13 UTC, past that day's 09:00, so a loan due on or before it is past its digest hour.
    private val loans = FakeAssetLoanRepository()

    /** The loan subjects the sweep would hand this provider. */
    private suspend fun lentOut() = BuildLoanSubjects(loans).forProvider(ProviderId.LOCAL, today)

    private suspend fun drill(
        dueOn: String? = "2026-06-05",
        mode: LoanReminderMode = LoanReminderMode.ONCE,
        returnedOn: String? = null,
    ): AssetLoan {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1_000L, updatedAt = 1_000L))
        return sampleLoan(id = "l1", assetId = "a1", dueOn = dueOn, reminderMode = mode, returnedOn = returnedOn)
            .also { loans.upsert(it) }
    }

    /** The warranty subjects the sweep would hand this provider today. */
    private suspend fun warranties() = BuildDeadlineSubjects(assets).forProvider(ProviderId.LOCAL, today)

    private suspend fun heater(
        expiresOn: String? = "2031-06-30",
        lead: Int? = 30,
        status: AssetStatus = AssetStatus.ACTIVE,
        retiredOn: String? = null,
    ): Asset = Asset(
        id = AssetId("a1"),
        name = "Example Heater",
        status = status,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        warrantyExpiresOn = expiresOn,
        retiredOn = retiredOn,
        warrantyReminderLeadDays = lead,
    ).also { assets.upsert(it) }

    /** The owner swipes every warning away; the shade forgets it and nothing else does. */
    private fun swipe() = notifications.items.clear()

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
     * not read this brief. #79 widens both to the deadline stamp, `deadline_local_delivery` (C17).
     */
    @Test
    fun noDeliveryStateReachesAnExportOrAMerge() {
        val forbidden = Regex("""schedule_local_delivery|ScheduleLocalDelivery|deadline_local_delivery|DeadlineLocalDelivery""")
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
                parameters.any { "ScheduleLocalDelivery" in it || "DeadlineLocalDelivery" in it },
            )
        }
    }

    // #79 (C7, K1): the deadline family beside the schedule family, through the real provider.

    /**
     * One list with both families: each is posted with its own actions and stamped in its own
     * table, and the same list again is inert. A provider that still assumed every key was a
     * schedule's fails here with a class cast.
     */
    @Test
    fun aMixedListReconcilesAndEachFamilyKeepsItsOwnDelivery() = runTest {
        val provider = provider()
        heater()
        val schedule = subject("s1", "2026-06-15")
        val subjects = listOf(schedule) + warranties()
        val warranty = subjects.last()

        assertEquals(ReconcileReport(2, 0, 0, emptyList()), provider.reconcile(subjects))
        assertEquals(
            setOf(itemTag(schedule.key, schedule.contentHash), itemTag(warranty.key, warranty.contentHash)),
            notifications.standingItems(),
        )
        assertEquals(listOf("s1"), delivery.all().map { it.scheduleId.value })
        assertEquals(listOf("WARRANTY_EXPIRY" to "a1"), deadlines.all().map { it.kind to it.subjectId })
        assertEquals(
            listOf(listOf("Done", "Snooze 1 day", "Open"), listOf("Open")),
            notifications.postedActions.map { actions -> actions.map { it.label } },
        )
        assertEquals(QuickActionTarget.OpenAsset(AssetId("a1")), notifications.postedActions[1].single().target)

        assertEquals(ReconcileReport(0, 0, 2, emptyList()), provider.reconcile(subjects))
    }

    /**
     * R79-15 and AC 4: every way a warning stops being wanted — the date cleared (which clears the
     * lead), the lead cleared, the asset archived or retired, the expiry passed — takes it down and
     * forgets its stamp, because the subject is **absent** and absence forgets.
     */
    @Test
    fun anAbsentWarrantyIsTakenDownAndForgotten() = runTest {
        val provider = provider()
        val ways: List<Pair<String, suspend () -> Unit>> = listOf(
            "the date cleared" to { heater(expiresOn = null, lead = null) },
            "the lead cleared" to { heater(lead = null) },
            "archived" to { heater(status = AssetStatus.ARCHIVED) },
            "retired" to { heater(retiredOn = "2031-06-01") },
            "expired" to { today = LocalDate.parse("2031-07-01") },
        )

        ways.forEach { (way, change) ->
            today = LocalDate.parse("2031-06-10")
            heater()
            provider.reconcile(warranties())
            assertEquals(way, 1, notifications.standingItems().size)
            assertEquals(way, 1, deadlines.all().size)

            change()
            val report = provider.reconcile(warranties())

            assertEquals(way, emptySet<String>(), notifications.standingItems())
            assertEquals(way, emptyList<DeadlineLocalDelivery>(), deadlines.all())
            assertEquals(way, 1, report.cleared)
        }
    }

    /** A warning switched off and on again is announced again: its stamp went with it. */
    @Test
    fun reEnablingAnnouncesAgain() = runTest {
        val provider = provider()
        heater()
        provider.reconcile(warranties())
        heater(lead = null)
        provider.reconcile(warranties())

        heater()
        val report = provider.reconcile(warranties())

        assertEquals(1, report.posted)
        assertEquals(2, notifications.postedItems.size)
        assertEquals(1, deadlines.all().size)
    }

    /** Reminders switched off: warnings come down with everything else, and their stamps go too. */
    @Test
    fun silenceTakesWarningsDownAndForgetsThem() = runTest {
        val provider = provider()
        heater()
        provider.reconcile(warranties())

        prefs.remindersEnabled = false
        val report = provider.reconcile(warranties())
        assertEquals(emptySet<String>(), notifications.standingItems())
        assertEquals(emptyList<DeadlineLocalDelivery>(), deadlines.all())
        assertEquals(1, report.cleared)

        prefs.remindersEnabled = true
        assertEquals("switched back on, it is announced again", 1, provider.reconcile(warranties()).posted)
    }

    /**
     * R79-14b: a muted warranty channel posts nothing and stamps nothing, so un-muting announces at
     * once; and muting both maintenance channels never mutes a warning.
     */
    @Test
    fun aMutedWarrantyChannelPostsAndStampsNothing() = runTest {
        val provider = provider()
        heater()
        platform.importances[NotificationChannels.WARRANTY] = ChannelImportance.MUTED

        assertEquals(ReconcileReport(0, 0, 0, emptyList()), provider.reconcile(warranties()))
        assertEquals(emptyList<ItemPost>(), notifications.postedItems)
        assertEquals(emptyList<DeadlineLocalDelivery>(), deadlines.all())

        platform.importances.remove(NotificationChannels.WARRANTY)
        platform.importances[NotificationChannels.DUE] = ChannelImportance.MUTED
        platform.importances[NotificationChannels.OVERDUE] = ChannelImportance.MUTED
        assertEquals(1, provider.reconcile(warranties()).posted)
        assertEquals(listOf(NotificationChannels.WARRANTY), notifications.postedItems.map { it.channelId })
    }

    /** R79-14c: a restart clears the shade; the next boot count posts the warning once more, and only once. */
    @Test
    fun aWarningARestartTookDownIsPostedOnceMore() = runTest {
        val provider = provider()
        heater()
        platform.boot = 5
        provider.reconcile(warranties())
        assertEquals(listOf<Int?>(5), deadlines.all().map { it.announcedBoot })

        notifications.clearEverything()
        platform.boot = 6
        assertEquals(1, provider.reconcile(warranties()).posted)
        assertEquals(listOf<Int?>(6), deadlines.all().map { it.announcedBoot })

        swipe()
        assertEquals("swiped in this boot, it is final", 0, provider.reconcile(warranties()).posted)
        assertEquals(2, notifications.postedItems.size)
    }

    /** R79-14a: a warning the owner swiped stays gone for the rest of the boot, however many runs follow. */
    @Test
    fun aSwipeInTheSameBootIsFinal() = runTest {
        val provider = provider()
        heater()
        provider.reconcile(warranties())
        swipe()

        repeat(3) {
            assertEquals(ReconcileReport(0, 0, 0, emptyList()), provider.reconcile(warranties()))
        }
        assertEquals(1, notifications.postedItems.size)
    }

    /** R79-14c: a count that cannot be read — now, or when the stamp was written — is the same boot. */
    @Test
    fun anUnreadableBootCountIsTheSameBoot() = runTest {
        val provider = provider()
        heater()
        platform.boot = null
        provider.reconcile(warranties())
        assertEquals(listOf<Int?>(null), deadlines.all().map { it.announcedBoot })

        swipe()
        assertEquals("unreadable then and now", 0, provider.reconcile(warranties()).posted)

        platform.boot = 7
        assertEquals("unreadable then, readable now", 0, provider.reconcile(warranties()).posted)

        val stamped = deadlines.all().single()
        deadlines.upsert(stamped.copy(announcedBoot = 3))
        platform.boot = null
        assertEquals("readable then, unreadable now", 0, provider.reconcile(warranties()).posted)
        assertEquals(1, notifications.postedItems.size)
    }

    /** AC 5: a warning writes one device-local stamp and nothing else — no schedule row, no asset write. */
    @Test
    fun aWarrantyWarningWritesOneDeviceLocalRowAndNothingElse() = runTest {
        val provider = provider()
        val asset = heater()
        val subject = warranties().single()

        provider.reconcile(listOf(subject))

        assertEquals(
            listOf(DeadlineLocalDelivery("WARRANTY_EXPIRY", "a1", subject.contentHash, 1, Fixture.NOW)),
            deadlines.all(),
        )
        assertEquals(emptyList<ScheduleLocalDelivery>(), delivery.all())
        assertEquals(asset, assets.get(AssetId("a1")))
    }

    // #72 (C11, AC 8, 9): a loan's post, withdrawn by absence through the real provider.

    /**
     * Every way a loan stops being wanted — returned (Once and Until returned alike), its due date
     * cleared, its reminder set to None, its asset deleted (the cascade takes the loan) — takes the
     * post down and forgets the stamp, because the subject is **absent** and absence forgets. A
     * returned loan is never a Completed subject (R72-20).
     */
    @Test
    fun aReturnedLoanIsTakenDownAndForgotten() = runTest {
        val provider = provider()
        val ways: List<Pair<String, suspend () -> Unit>> = listOf(
            "returned, Once" to { drill(returnedOn = "2026-06-09") },
            "returned, Until returned" to { drill(mode = LoanReminderMode.UNTIL_RETURNED, returnedOn = "2026-06-09") },
            "the due date cleared" to { drill(dueOn = null, mode = LoanReminderMode.NONE) },
            "the reminder set to None" to { drill(mode = LoanReminderMode.NONE) },
            "the asset deleted" to { assets.delete(AssetId("a1")); loans.rows.clear() },
        )

        ways.forEach { (way, change) ->
            drill(mode = if (way.contains("Until")) LoanReminderMode.UNTIL_RETURNED else LoanReminderMode.ONCE)
            provider.reconcile(lentOut())
            assertEquals(way, 1, notifications.standingItems().size)
            assertEquals(way, listOf("LOAN_DUE_BACK" to "a1/l1"), deadlines.all().map { it.kind to it.subjectId })

            change()
            val subjects = lentOut()
            val report = provider.reconcile(subjects)

            assertEquals(way, emptyList<ReminderSubject>(), subjects)
            assertEquals(way, emptySet<String>(), notifications.standingItems())
            assertEquals(way, emptyList<DeadlineLocalDelivery>(), deadlines.all())
            assertEquals(way, 1, report.cleared)
        }
    }

    /**
     * A moved due date or a moved mode is new content: in one run the old tag is cancelled **first**
     * and the new one posted, and the stamp carries the new hash.
     */
    @Test
    fun aMovedDueDateOrModeCancelsTheOldTagFirst() = runTest {
        val provider = provider()
        listOf<Pair<String, suspend () -> Unit>>(
            "re-dated" to { drill(dueOn = "2026-06-04") },
            "mode moved" to { drill(mode = LoanReminderMode.UNTIL_RETURNED) },
        ).forEach { (way, move) ->
            drill()
            provider.reconcile(lentOut())
            val oldTag = notifications.standingItems().single()
            notifications.log.clear()

            move()
            val moved = lentOut().single()
            val report = provider.reconcile(listOf(moved))

            val newTag = itemTag(moved.key, moved.contentHash)
            assertNotEquals(way, oldTag, newTag)
            assertEquals(way, listOf("cancel $oldTag", "post $newTag"), notifications.log)
            assertEquals(way, listOf(moved.contentHash), deadlines.all().map { it.announcedHash })
            assertEquals(way, ReconcileReport(1, 0, 0, emptyList()), report)
        }
    }

    /**
     * R72-10 (fix round 1, MINOR-1): a retired asset's open loan and an archived asset's open loan
     * are both posted through the real builder, facts and provider, each named after its asset.
     */
    @Test
    fun aRetiredOrArchivedAssetsOpenLoanStillPosts() = runTest {
        val provider = provider()
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1_000L, updatedAt = 1_000L, retiredOn = "2026-06-01"))
        assets.upsert(
            Asset(id = AssetId("a2"), name = "Example Ladder", status = AssetStatus.ARCHIVED, createdAt = 1_000L, updatedAt = 1_000L),
        )
        loans.upsert(sampleLoan(id = "l1", assetId = "a1", dueOn = "2026-06-05"))
        loans.upsert(sampleLoan(id = "l2", assetId = "a2", dueOn = "2026-06-05", reminderMode = LoanReminderMode.UNTIL_RETURNED))

        assertEquals(ReconcileReport(2, 0, 0, emptyList()), provider.reconcile(lentOut()))
        assertEquals(
            listOf("Example Drill — Due back", "Example Ladder — Due back"),
            notifications.postedItems.map { it.title },
        )
    }

    /**
     * Fix round 1 (MAJOR-1 (a)): posted on its due day, a Once still showing the next morning is
     * re-posted in place — one post under the same tag, never a cancel — in the post-due words and
     * only-alert-once, and the shade holds that one notification.
     */
    @Test
    fun aStandingOnceIsRefreshedInPlaceAndNeverCancelled() = runTest {
        val provider = provider()
        today = LocalDate.parse("2026-06-09")
        drill(dueOn = "2026-06-09")
        assertEquals(1, provider.reconcile(lentOut()).posted)
        val tag = notifications.standingItems().single()
        assertEquals("DUE BACK", notifications.items.getValue(tag).statusWord)
        notifications.log.clear()

        today = LocalDate.parse("2026-06-10")
        nowMillis = java.time.Instant.parse("2026-06-10T09:00:00Z").toEpochMilli()
        val report = provider.reconcile(lentOut())

        assertEquals(listOf("post $tag"), notifications.log)
        assertEquals(setOf(tag), notifications.standingItems())
        val refreshed = notifications.items.getValue(tag)
        assertEquals("Lent to Sample Borrower. Was due back 9 Jun 2026.", refreshed.body)
        assertTrue(refreshed.onlyAlertOnce)
        assertEquals(ReconcileReport(0, 0, 1, emptyList()), report)
    }

    /** Reminders switched off: loan posts come down with everything else, and their stamps go too. */
    @Test
    fun silenceTakesLoanPostsDownAndForgetsThem() = runTest {
        val provider = provider()
        drill()
        provider.reconcile(lentOut())

        prefs.remindersEnabled = false
        val report = provider.reconcile(lentOut())
        assertEquals(emptySet<String>(), notifications.standingItems())
        assertEquals(emptyList<DeadlineLocalDelivery>(), deadlines.all())
        assertEquals(1, report.cleared)

        prefs.remindersEnabled = true
        assertEquals("switched back on, its stamp is gone and it is announced again", 1, provider.reconcile(lentOut()).posted)
    }

    /** AC 13: a loan post writes one device-local stamp and nothing else — no schedule row, no loan or asset write. */
    @Test
    fun aLoanPostWritesOneDeviceLocalRowAndNothingElse() = runTest {
        val provider = provider()
        val loan = drill()
        val asset = assets.get(AssetId("a1"))
        val subject = lentOut().single()

        provider.reconcile(listOf(subject))

        assertEquals(
            listOf(DeadlineLocalDelivery("LOAN_DUE_BACK", "a1/l1", subject.contentHash, 1, Fixture.NOW)),
            deadlines.all(),
        )
        assertEquals(emptyList<ScheduleLocalDelivery>(), delivery.all())
        assertEquals(listOf(loan), loans.all())
        assertEquals(asset, assets.get(AssetId("a1")))
        assertEquals(listOf(NotificationChannels.LOANS), notifications.postedItems.map { it.channelId })
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

    // ---- #77 (B2b, R77-20): the corrected defect, through the real builder, the real facts and this provider ----

    /**
     * One overdue maintenance schedule on "Example Water Heater", over the real Room tables. The subjects come
     * from `BuildReminderSubjects` built as `AppGraph`'s `subjectsFor` wires it — `FakeGraph` has no subject
     * builder (rm-3) — and the facts from `ScheduleDeliveryFacts` over the same tables, as `AppGraph` builds them.
     */
    private inner class Estate {
        val graph = FakeGraph().also { it.today = LocalDate.parse("2026-04-10") }
        private val subjects = BuildReminderSubjects(graph.schedules, graph.groups, graph.recomputeSchedules)
        private val provider = LocalReminderProvider(
            facts = ScheduleDeliveryFacts(
                graph.schedules, graph.scheduleStateReader, graph.assets, graph.groups, graph.definitions, graph.todayPort,
            ),
            delivery = delivery,
            notifications = notifications,
            permission = permission,
            platform = platform,
            alarm = alarm,
            prefs = prefs,
            clock = clock,
            quickActions = QuickActions(
                shapes = QuickActionShapeSource {
                    QuickActionShape(groupTargeted = false, completionMode = CompletionMode.QUICK, meterRule = false)
                },
                nonces = NonceStore(delivery, IdGenerator { "quick-nonce" }, clock),
            ),
        )

        suspend fun seed() {
            graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
            graph.schedules.upsert(scheduleOf("s1", assetId = "h1", title = "Flush the tank"))
            graph.recomputeSchedules.all()
        }

        /** One sweep: the subjects the builder answers today, reconciled. */
        suspend fun sweep(): ReconcileReport = provider.reconcile(subjects.forProvider(ProviderId.LOCAL, graph.today))
    }

    private fun estate(block: suspend Estate.() -> Unit) = runTest {
        val estate = Estate()
        try {
            estate.block()
        } finally {
            estate.graph.close()
        }
    }

    /**
     * R77-20: archiving an asset takes its standing maintenance post down — the schedule stays ACTIVE, and an
     * archived asset's live schedule arrives `Withdrawn`, so the provider cancels its tag (the defect: it kept
     * reminding, because only the schedule's own status was asked).
     */
    @Test
    fun archivingAnAssetTakesItsStandingMaintenancePostDown() = estate {
        seed()
        assertEquals("the overdue schedule is posted", 1, sweep().posted)
        val standing = notifications.standingItems()
        assertEquals(1, standing.size)

        graph.archiveAsset.run(AssetId("h1"))
        sweep()

        assertEquals("an archived asset's post is taken down", emptySet<String>(), notifications.standingItems())
        assertTrue("its tag was cancelled", notifications.cancelled.containsAll(standing))
    }

    /** R77-20: retiring an asset does the same — a retired asset's live schedule reminds no more. */
    @Test
    fun retiringAnAssetTakesItsStandingMaintenancePostDown() = estate {
        seed()
        assertEquals("the overdue schedule is posted", 1, sweep().posted)
        val standing = notifications.standingItems()
        assertEquals(1, standing.size)

        graph.retireAsset.retire(AssetId("h1"), "2026-04-05")
        sweep()

        assertEquals("a retired asset's post is taken down", emptySet<String>(), notifications.standingItems())
        assertTrue("its tag was cancelled", notifications.cancelled.containsAll(standing))
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
