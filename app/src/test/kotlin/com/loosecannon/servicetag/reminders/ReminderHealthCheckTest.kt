package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.reminders.HealthFinding
import com.loosecannon.servicetag.core.reminders.RepairAction
import com.loosecannon.servicetag.core.reminders.Severity
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backstop's unique work, as a value: what [enqueue] put there is what [enqueued] answers. */
internal class RecordingBackstop(private var isEnqueued: Boolean = true) : BackstopWork {
    var enqueues = 0
        private set

    override fun enqueued(): Boolean = isEnqueued

    override fun enqueue() {
        enqueues++
        isEnqueued = true
    }

    fun drop() {
        isEnqueued = false
    }
}

/** An in-memory [ScheduleRepository]: the rows a health run reads, and nothing derived. */
internal class FakeScheduleRepository : ScheduleRepository {
    val rows = linkedMapOf<String, MaintenanceSchedule>()

    override suspend fun upsert(schedule: MaintenanceSchedule) {
        rows[schedule.id.value] = schedule
    }

    override suspend fun get(id: ScheduleId): MaintenanceSchedule? = rows[id.value]
    override suspend fun all(): List<MaintenanceSchedule> = rows.values.toList()
    override suspend fun forAsset(assetId: AssetId): List<MaintenanceSchedule> = emptyList()
    override suspend fun forGroup(groupId: GroupId): List<MaintenanceSchedule> = emptyList()
    override suspend fun deleteAll() = rows.clear()
    override fun observeAll(): Flow<List<MaintenanceSchedule>> = flowOf(rows.values.toList())
}

/** In-memory assets: a health run reads them for one thing, the target's lifecycle. */
internal class FakeAssetRepository : AssetRepository {
    val rows = linkedMapOf<String, Asset>()

    override suspend fun upsert(asset: Asset) { rows[asset.id.value] = asset }
    override suspend fun get(id: AssetId): Asset? = rows[id.value]
    override suspend fun all(): List<Asset> = rows.values.toList()
    override suspend fun delete(id: AssetId) { rows.remove(id.value) }
    override suspend fun deleteAll() = rows.clear()
    override fun observeAll(): Flow<List<Asset>> = flowOf(rows.values.toList())
}

/** In-memory groups, for the same one question. */
internal class FakeGroupRepository : GroupRepository {
    val rows = linkedMapOf<String, MaintenanceGroup>()

    override suspend fun upsert(group: MaintenanceGroup) { rows[group.id.value] = group }
    override suspend fun get(id: GroupId): MaintenanceGroup? = rows[id.value]
    override suspend fun all(): List<MaintenanceGroup> = rows.values.toList()
    override suspend fun forAsset(assetId: AssetId): List<MaintenanceGroup> = emptyList()
    override suspend fun allWindowsFor(assetId: AssetId): List<MaintenanceGroup> = emptyList()
    override suspend fun deleteAll() = rows.clear()
    override fun observeAll(): Flow<List<MaintenanceGroup>> = flowOf(rows.values.toList())
    override fun observeForAsset(assetId: AssetId): Flow<List<MaintenanceGroup>> = flowOf(emptyList())
}

/** An asset in service unless a test retires or archives it. */
internal fun assetOf(
    id: String,
    status: AssetStatus = AssetStatus.ACTIVE,
    retiredOn: String? = null,
): Asset = Asset(
    id = AssetId(id),
    name = "Pump house filter",
    status = status,
    retiredOn = retiredOn,
    createdAt = dayMillis("2026-01-01"),
    updatedAt = dayMillis("2026-01-01"),
)

private class HealthKeyValueStore : KeyValueStore {
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
}

/**
 * A derived row as the recompute would have written it. Hand-built here rather than produced by the
 * engine because this suite is about the **detector**, not the derivation: the two fields any
 * finding reads are [lastCompletedMeter] (the meter baseline) and [effectiveDueOn] (whether there is
 * a date at all), and every test that uses one asserts the `statusOf` word it implies so no control
 * can be vacuous.
 */
private fun derivedState(
    id: String,
    lastCompletedMeter: Double? = null,
    computedDueMeter: Double? = null,
    currentMeter: Double? = null,
    effectiveDueOn: String? = "2026-10-01",
): ScheduleState = ScheduleState(
    scheduleId = ScheduleId(id),
    lastCompletedOn = null,
    lastCompletionEventId = null,
    lastCompletedMeter = lastCompletedMeter,
    currentMeter = currentMeter,
    computedDueMeter = computedDueMeter,
    lastTerminationEffectiveOn = null,
    lastTerminationKind = TerminationKind.NONE,
    computedDueOn = effectiveDueOn,
    effectiveDueOn = effectiveDueOn,
    policyPhase = PolicyPhase.ACTIVE,
    actionableDueOn = effectiveDueOn,
    policyReason = PolicyReason.NONE,
    quiet = false,
    computedForOn = TODAY.toString(),
    computedAt = dayMillis("2026-09-22"),
)

private val TODAY: LocalDate = LocalDate.parse("2026-09-22")

/**
 * The seven findings of #27, each with a positive test **and** a negative control (invariant 51),
 * plus the repair policy as a contract: only the alarm and the worker are ever repaired, running a
 * repair twice changes nothing, and nothing resolves a conflict (invariant 50).
 *
 * Off-device throughout. The three provider-side findings are read from B06's real
 * `LocalReminderProvider.health()` rather than re-derived here (carry-forward (a)), so the fold is
 * what is under test and the ratified sentences are drawn once in the codebase; the other four are
 * this class's own. B05's `PlatformState`, B06's alarm, the backstop's unique work and the two
 * repositories are all fakes.
 */
class ReminderHealthCheckTest {

    private val platform = MutablePlatformState()
    private val permission = GrantablePermission()
    private val alarm = RecordingDigestAlarm(isArmed = true)
    private val backstop = RecordingBackstop(isEnqueued = true)
    private val prefs = AppPrefs(HealthKeyValueStore())
    private val schedules = FakeScheduleRepository()
    private val assets = FakeAssetRepository()
    private val groups = FakeGroupRepository()
    private val states = mutableMapOf<String, ScheduleState>()
    private val clock = Clock { dayMillis("2026-09-22") }

    private fun provider() = LocalReminderProvider(
        // No subject is ever reconciled in this suite: `health()` reads the platform and the
        // preferences, never the shade, so a facts source that knows nothing is the honest fake.
        facts = DeliveryFactsSource { null },
        delivery = FakeDeliveryRepository(),
        notifications = FakeReminderNotifications(),
        permission = permission,
        platform = platform,
        alarm = alarm,
        prefs = prefs,
        clock = clock,
        quickActions = QuickActions(
            shapes = QuickActionShapeSource { null },
            nonces = NonceStore(FakeDeliveryRepository(), IdGenerator { "nonce" }, clock),
        ),
    )

    private fun check() = ReminderHealthCheck(
        provider = provider(),
        platform = platform,
        backstop = backstop,
        alarm = alarm,
        schedules = schedules,
        states = ScheduleStateReader { states[it.value] },
        assets = assets,
        groups = groups,
        // The production check moves its blocking platform reads off the caller's thread; the
        // suite runs them on the test dispatcher so nothing is left in flight at assertion time.
        io = Dispatchers.Unconfined,
    )

    private suspend fun codes(): List<String> = check().run().map { it.code }

    /**
     * Stores the schedule, its derived row, and — unless the test has already put one there — a
     * target **in service**.
     *
     * The seeding matters: with the target bound in place, a schedule whose asset this fake has
     * never heard of is silent *because of the bound*, which would make every other control silent
     * for the wrong reason. A test that is about the bound puts its own out-of-service row in first.
     */
    private suspend fun add(schedule: MaintenanceSchedule, state: ScheduleState? = null) {
        schedules.upsert(schedule)
        state?.let { states[schedule.id.value] = it }
        when (val target = schedule.target) {
            is ScheduleTarget.AssetTarget ->
                if (assets.get(target.assetId) == null) assets.upsert(assetOf(target.assetId.value))
            is ScheduleTarget.GroupTarget ->
                if (groups.get(target.groupId) == null) groups.upsert(groupOf(target.groupId.value))
        }
    }

    // ---------------------------------------------------------------- NOTIFICATIONS_BLOCKED

    /**
     * The app-level toggle off, and then the narrower fact the toggle cannot see: a **channel** the
     * owner muted while notifications are otherwise on. Both are `NOTIFICATIONS_BLOCKED`, ERROR,
     * with a repair that can only ever take the owner to the system screen.
     */
    @Test
    fun notificationsOffAndAMutedChannelAreBothTheBlockedFinding() = runTest {
        platform.enabled = false
        val blocked = check().run().single { it.code == "NOTIFICATIONS_BLOCKED" }
        assertEquals(Severity.ERROR, blocked.severity)
        assertEquals(
            "Notifications are turned off, so maintenance reminders will not arrive.",
            blocked.message,
        )
        assertEquals(RepairAction.OpenSystemSettings("OPEN_NOTIFICATION_SETTINGS"), blocked.repair)

        platform.enabled = true
        platform.importances[NotificationChannels.DUE] = ChannelImportance.MUTED
        assertEquals(
            "a muted channel is the same finding: reading only the app toggle would miss it",
            listOf("NOTIFICATIONS_BLOCKED"),
            codes(),
        )
    }

    /** The control: nothing muted, nothing denied, and the screen says nothing at all. */
    @Test
    fun everythingDeliveringIsSilent() = runTest {
        platform.importances[NotificationChannels.DUE] = ChannelImportance.DEFAULT
        platform.importances[NotificationChannels.OVERDUE] = ChannelImportance.HIGH
        assertEquals(emptyList<String>(), codes())
    }

    // ---------------------------------------------------------------- DIGEST_ALARM_MISSING

    /**
     * The alarm cancelled with reminders on: the finding, and the **repair re-arms it**. Running the
     * repair a second time arms nothing more (#27 AC 3) — that is what makes it safe for the
     * backstop to apply on every run.
     */
    @Test
    fun aCancelledAlarmIsTheFindingAndTheRepairReArmsItOnce() = runTest {
        alarm.cancel()
        val finding = check().run().single { it.code == "DIGEST_ALARM_MISSING" }
        assertEquals(Severity.WARN, finding.severity)
        assertEquals(
            "The daily reminder check is not scheduled, so today's maintenance may go unannounced.",
            finding.message,
        )
        assertEquals(RepairAction.Automatic("ARM_DIGEST_ALARM"), finding.repair)

        val armsBefore = alarm.arms
        check().repair(finding)
        assertTrue("the repair armed it", alarm.armed())
        assertEquals(armsBefore + 1, alarm.arms)

        check().repair(finding)
        assertEquals("the second run changes nothing", armsBefore + 1, alarm.arms)
        assertEquals(emptyList<String>(), codes())
    }

    /**
     * Two controls, and the second is the one that matters: an owner who switched reminders off did
     * not ask to be told the alarm is gone. Without that condition the finding fires for ever on a
     * phone that is behaving exactly as it was told to.
     */
    @Test
    fun anArmedAlarmIsSilentAndSoIsACancelledOneWithRemindersOff() = runTest {
        assertFalse("DIGEST_ALARM_MISSING" in codes())

        prefs.remindersEnabled = false
        alarm.cancel()
        assertFalse(
            "reminders off: the missing alarm is not a finding",
            "DIGEST_ALARM_MISSING" in codes(),
        )
    }

    // ---------------------------------------------------------------- BACKSTOP_WORK_MISSING

    /**
     * No unique work: the finding, and the repair enqueues **exactly one** request. A repair that
     * enqueued without asking first is the duplicate-periodic-work the policy forbids.
     */
    @Test
    fun noUniqueWorkIsTheFindingAndTheRepairEnqueuesExactlyOne() = runTest {
        backstop.drop()
        val finding = check().run().single { it.code == "BACKSTOP_WORK_MISSING" }
        assertEquals(Severity.WARN, finding.severity)
        assertEquals(
            "The background safety check is not running, so a missed reminder would not be caught.",
            finding.message,
        )
        assertEquals(RepairAction.Automatic("ENQUEUE_BACKSTOP"), finding.repair)

        check().repair(finding)
        assertEquals(1, backstop.enqueues)
        check().repair(finding)
        assertEquals("the second run enqueues nothing", 1, backstop.enqueues)
        assertEquals(emptyList<String>(), codes())
    }

    /** The control: work already enqueued, and nothing is said about it. */
    @Test
    fun anEnqueuedBackstopIsSilent() = runTest {
        assertFalse("BACKSTOP_WORK_MISSING" in codes())
        assertEquals("and nothing was enqueued in the asking", 0, backstop.enqueues)
    }

    // ---------------------------------------------------------------- APP_RESTRICTED

    /**
     * Each of the two restricted states the OEM can apply. One ratified sentence covers both — the
     * cost is the same either way — and the repair can only open a settings screen: the app never
     * repairs a restriction and never asks for an exemption it does not need (#24).
     */
    @Test
    fun eachRestrictedStateIsTheRestrictionFinding() = runTest {
        listOf(AppRestriction.BATTERY_RESTRICTED, AppRestriction.STANDBY_RESTRICTED).forEach { state ->
            platform.restriction = state
            val finding = check().run().single { it.code == "APP_RESTRICTED" }
            assertEquals("severity for $state", Severity.WARN, finding.severity)
            assertEquals(
                "This phone is holding ServiceTag back in the background, so reminders may arrive late or not at all.",
                finding.message,
            )
            assertEquals(RepairAction.OpenSystemSettings("OPEN_BATTERY_SETTINGS"), finding.repair)
        }
    }

    /** The control: a normal bucket, and no nag on every launch. */
    @Test
    fun anUnrestrictedPhoneIsSilent() = runTest {
        platform.restriction = AppRestriction.NORMAL
        assertFalse("APP_RESTRICTED" in codes())
        assertEquals("and nothing else was invented either", emptyList<String>(), codes())
    }

    // ---------------------------------------------------------------- REMINDERS_GLOBALLY_OFF

    /** The owner's own switch: INFO, so the badge stays dark, with a one-tap way back on. */
    @Test
    fun theGlobalSwitchOffIsAnInformationalFinding() = runTest {
        prefs.remindersEnabled = false
        val finding = check().run().single { it.code == "REMINDERS_GLOBALLY_OFF" }
        assertEquals(Severity.INFO, finding.severity)
        assertEquals("Reminders are turned off in ServiceTag.", finding.message)
        assertEquals(RepairAction.OpenInApp("TURN_REMINDERS_ON"), finding.repair)
    }

    /** The control: the switch on — its default — and nothing said about it. */
    @Test
    fun theGlobalSwitchOnIsSilent() = runTest {
        assertTrue("an install that has never touched it reminds", prefs.remindersEnabled)
        assertFalse("REMINDERS_GLOBALLY_OFF" in codes())
        assertEquals("and nothing else was invented either", emptyList<String>(), codes())
    }

    // ---------------------------------------------------------------- SCHEDULE_NO_PROVIDER

    /** An ACTIVE schedule asking to be reminded through nothing at all. */
    @Test
    fun anActiveScheduleWithNoEnabledProviderRowIsTheFinding() = runTest {
        add(
            scheduleOf(id = "s1", assetId = "a1").copy(providers = emptyList()),
            derivedState("s1"),
        )
        val finding = check().run().single { it.code == "SCHEDULE_NO_PROVIDER" }
        assertEquals(Severity.WARN, finding.severity)
        assertEquals("1 schedules have reminders switched on but no way to deliver them.", finding.message)
        assertEquals(RepairAction.OpenInApp("OPEN_SCHEDULE:s1"), finding.repair)
    }

    /**
     * Three controls on one condition, because the condition has three clauses: an enabled row, a
     * disabled schedule and an archived one. Without the lifecycle clause every archived schedule
     * on the phone raises a finding for ever.
     */
    @Test
    fun anEnabledRowOrANonActiveScheduleIsSilent() = runTest {
        add(scheduleOf(id = "s1", assetId = "a1"), derivedState("s1"))
        assertFalse("an enabled LOCAL row is the healthy case", "SCHEDULE_NO_PROVIDER" in codes())

        schedules.rows.clear()
        add(
            scheduleOf(id = "s2", assetId = "a1", status = ScheduleStatus.PAUSED).copy(providers = emptyList()),
            derivedState("s2"),
        )
        assertFalse("paused", "SCHEDULE_NO_PROVIDER" in codes())

        schedules.rows.clear()
        add(
            scheduleOf(id = "s3", assetId = "a1", status = ScheduleStatus.ARCHIVED).copy(providers = emptyList()),
            derivedState("s3"),
        )
        assertFalse("archived", "SCHEDULE_NO_PROVIDER" in codes())
    }

    /** A schedule that was never asked to remind anyone is not missing a way to do it. */
    @Test
    fun aScheduleWithRemindersOffIsSilent() = runTest {
        add(
            scheduleOf(id = "s1", assetId = "a1").copy(providers = emptyList(), remindersEnabled = false),
            derivedState("s1"),
        )
        assertFalse("SCHEDULE_NO_PROVIDER" in codes())
    }

    // ---------------------------------------------------------------- NO_DATA

    /**
     * A meter rule with no baseline — neither a completion carrying a reading nor an `anchorMeter`.
     * It keys off the **baseline**, not the status word, which is what the gate's constraint
     * requires (master plan §17.1a).
     */
    @Test
    fun aMeterRuleWithNoBaselineIsTheFinding() = runTest {
        add(
            scheduleOf(
                id = "s1",
                assetId = "a1",
                timeInterval = null,
                timeUnit = null,
                anchorOn = null,
                meterDefinitionId = "d1",
                meterInterval = 100.0,
            ),
            derivedState("s1", effectiveDueOn = null),
        )
        val finding = check().run().single { it.code == "NO_DATA" }
        assertEquals(Severity.WARN, finding.severity)
        assertEquals("1 schedules need a meter reading before they can come due.", finding.message)
        assertEquals(RepairAction.OpenInApp("LOG_METER_READING:s1"), finding.repair)
    }

    /** The control: the baseline supplied, so there is nothing left to log. */
    @Test
    fun aMeterRuleWithAnAnchorIsSilent() = runTest {
        add(
            scheduleOf(
                id = "s1",
                assetId = "a1",
                timeInterval = null,
                timeUnit = null,
                anchorOn = null,
                meterDefinitionId = "d1",
                meterInterval = 100.0,
                anchorMeter = 500.0,
            ),
            derivedState("s1", lastCompletedMeter = 500.0, computedDueMeter = 600.0, effectiveDueOn = null),
        )
        assertFalse("NO_DATA" in codes())
    }

    /**
     * The gate's second control, and the whole reason this detector reads the baseline: a
     * **group-targeted** schedule whose round obliges nobody reports the **same status word** and
     * must never be told to log a meter reading. The status assertion is what keeps this control
     * from being vacuous — a detector keyed off the enum would have fired here.
     */
    @Test
    fun anEmptyRequiredSetIsNeverTheMeterFinding() = runTest {
        val schedule = scheduleOf(id = "g1", groupId = "grp1")
        // What the recompute writes for a round with an empty required set: no date at all.
        val state = derivedState("g1", effectiveDueOn = null)
        add(schedule, state)

        assertEquals(
            "the status word really is the shared one",
            DueStatus.NO_DATA,
            statusOf(schedule, state, TODAY),
        )
        assertEquals("and no finding of any kind is raised for it", emptyList<String>(), codes())
    }

    /**
     * `NO_DATA`'s lifecycle control (fix round 1, S1). A **PAUSED** schedule carrying a meter rule
     * and no baseline is told nothing: "need\[s\] a meter reading before they can come due" is
     * false of a schedule that cannot come due, and §11.1's ruling is that a paused schedule needs
     * nothing. The same clause `SCHEDULE_NO_PROVIDER` already carried, for the reason the brief
     * gives for it.
     *
     * Non-vacuous by construction: the identical schedule raises the finding while it is ACTIVE.
     */
    @Test
    fun aPausedMeterScheduleWithNoBaselineIsSilent() = runTest {
        val meterRule = scheduleOf(
            id = "s1",
            assetId = "a1",
            timeInterval = null,
            timeUnit = null,
            anchorOn = null,
            meterDefinitionId = "d1",
            meterInterval = 100.0,
        )
        add(meterRule, derivedState("s1", effectiveDueOn = null))
        assertEquals("active, it is the finding", listOf("NO_DATA"), codes())

        schedules.upsert(meterRule.copy(status = ScheduleStatus.PAUSED))
        assertEquals("paused, it needs nothing", emptyList<String>(), codes())
    }

    /**
     * The target-in-service control for **both** store findings (fix round 1, S2). A schedule on a
     * retired asset, or on an archived group, is an obligation the app has already withdrawn from
     * delivery — `BuildReminderSubjects` hands a retired one to the provider as `Withdrawn` — so
     * reporting "no way to deliver them" would be reporting a problem nothing is trying to solve,
     * and the repair would send the owner to edit a schedule on equipment they retired.
     *
     * Non-vacuous twice over: each finding is shown first with its target in service, and the only
     * thing that changes is the target's lifecycle.
     *
     * **Which finding sits on which target matters.** `SCHEDULE_NO_PROVIDER` is proved on the
     * **group** target and `NO_DATA` on the **asset** target, because a group-targeted schedule
     * carries no meter rule (invariant 2) and so can never reach `NO_DATA`'s
     * missing-baseline condition. Pairing them the other way round would prove one of the two
     * bounds with a row the domain forbids.
     */
    @Test
    fun aScheduleOnAnOutOfServiceTargetIsSilent() = runTest {
        add(
            scheduleOf(id = "g1", groupId = "grp1").copy(providers = emptyList()),
            derivedState("g1"),
        )
        add(
            scheduleOf(
                id = "s1",
                assetId = "a1",
                meterDefinitionId = "d1",
                meterInterval = 100.0,
            ),
            derivedState("s1"),
        )
        assertEquals(
            "in service, both findings stand",
            listOf("SCHEDULE_NO_PROVIDER", "NO_DATA"),
            codes(),
        )

        groups.upsert(groupOf("grp1", archivedAt = dayMillis("2026-09-01")))
        assets.upsert(assetOf("a1", retiredOn = "2026-09-01"))

        assertEquals("out of service, neither does", emptyList<String>(), codes())
    }

    // ---------------------------------------------------------------- the repair policy

    /**
     * Invariant 50 and #27 AC 6, behaviourally: across every state this suite can put the phone in,
     * the **only** findings carrying an `Automatic` repair are the alarm's and the worker's. A merge
     * conflict is not among them and cannot be — nothing in the check reads a merge plan — and the
     * structural half of the same claim is the anchored grep in this brief's review gate.
     */
    @Test
    fun theOnlyAutomaticRepairsAreTheAlarmAndTheWorker() = runTest {
        platform.enabled = false
        platform.restriction = AppRestriction.BATTERY_RESTRICTED
        alarm.cancel()
        backstop.drop()
        add(
            scheduleOf(id = "s1", assetId = "a1").copy(providers = emptyList()),
            derivedState("s1"),
        )
        add(
            scheduleOf(
                id = "s2",
                assetId = "a1",
                timeInterval = null,
                timeUnit = null,
                anchorOn = null,
                meterDefinitionId = "d1",
                meterInterval = 100.0,
            ),
            derivedState("s2", effectiveDueOn = null),
        )

        val findings = check().run()
        assertEquals(
            setOf("ARM_DIGEST_ALARM", "ENQUEUE_BACKSTOP"),
            findings.mapNotNull { (it.repair as? RepairAction.Automatic)?.code }.toSet(),
        )
        assertEquals("all six of them", 6, findings.size)
    }

    /**
     * A repair is applied to an `Automatic` finding and to nothing else: handed a finding whose
     * repair opens a screen, `repair` writes nothing. The owner decides those, which is the whole
     * of the repair policy for everything the app cannot be certain about.
     */
    @Test
    fun repairIgnoresEveryFindingItIsNotCertainAbout() = runTest {
        alarm.cancel()
        backstop.drop()
        val check = check()

        listOf(
            HealthFinding("APP_RESTRICTED", Severity.WARN, "m", RepairAction.OpenSystemSettings("OPEN_BATTERY_SETTINGS")),
            HealthFinding("REMINDERS_GLOBALLY_OFF", Severity.INFO, "m", RepairAction.OpenInApp("TURN_REMINDERS_ON")),
            HealthFinding("SOMETHING_ELSE", Severity.WARN, "m", null),
        ).forEach { check.repair(it) }

        assertFalse("the alarm was left alone", alarm.armed())
        assertEquals("and so was the worker", 0, backstop.enqueues)
    }

    /**
     * One composed idempotence proof (#27 AC 3's second half): `runAndRepair` applies both automatic
     * repairs, the second call finds nothing left to do, and neither the alarm nor the worker was
     * touched a second time. This is the shape the backstop runs on every period.
     */
    @Test
    fun runAndRepairIsIdempotent() = runTest {
        alarm.cancel()
        backstop.drop()
        val check = check()

        assertEquals(emptyList<String>(), check.runAndRepair().map { it.code })
        val arms = alarm.arms
        val enqueues = backstop.enqueues
        assertEquals(1, enqueues)

        assertEquals(emptyList<String>(), check.runAndRepair().map { it.code })
        assertEquals("the alarm was armed once", arms, alarm.arms)
        assertEquals("and the worker enqueued once", enqueues, backstop.enqueues)
    }

    /**
     * Invariant 61 and D-22, from the health side: with the permission denied **nothing** is
     * disabled — the alarm is still armed, the backstop still enqueued, the preferences still
     * writable and the schedule rows still read — and **exactly one** finding is produced.
     */
    @Test
    fun aDeniedPermissionDisablesNothingAndProducesExactlyOneFinding() = runTest {
        permission.isGranted = false
        add(scheduleOf(id = "s1", assetId = "a1"), derivedState("s1"))

        assertEquals(listOf("NOTIFICATIONS_BLOCKED"), codes())
        assertTrue("the alarm is still armed", alarm.armed())
        assertTrue("the backstop is still enqueued", backstop.enqueued())
        prefs.digestHour = 7
        assertEquals("the preferences are still writable", 7, prefs.digestHour)
        assertEquals("and the schedules are still readable", 1, schedules.all().size)
    }

    /**
     * Worst first, so the position on the screen carries the severity as well as the icon and the
     * wording do.
     *
     * The WARN is the restriction and **not** the alarm, deliberately: turning the global switch off
     * to get the INFO also suppresses `DIGEST_ALARM_MISSING`, which is the contract the control
     * above asserts. One state cannot produce all three any other way.
     */
    @Test
    fun findingsComeBackWorstFirst() = runTest {
        platform.enabled = false
        platform.restriction = AppRestriction.BATTERY_RESTRICTED
        prefs.remindersEnabled = false

        assertEquals(
            listOf("NOTIFICATIONS_BLOCKED", "APP_RESTRICTED", "REMINDERS_GLOBALLY_OFF"),
            check().run().map { it.code },
        )
        assertEquals(
            listOf(Severity.ERROR, Severity.WARN, Severity.INFO),
            check().run().map { it.severity },
        )
    }

    /**
     * A structural assertion, because a placeholder finding is a lie about a feature that does not
     * exist: none of the six Todoist codes appears anywhere in production source. They are Phase 5,
     * and 1.2 ships not even a stub of one.
     */
    @Test
    fun noPhaseFiveFindingCodeAppearsInProductionSource() = runTest {
        val phaseFive = listOf(
            "TODOIST_DISCONNECTED",
            "PROJECTION_MISSING",
            "PROJECTION_CONFLICT",
            "PROJECTION_DUE_DRIFT",
            "SYNC_STALE",
            "OUTBOX_FAILING",
        )
        val offenders = productionSourceFiles().flatMap { file ->
            val text = file.readText()
            phaseFive.filter { it in text }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** Every `.kt` under `:app`'s and `:core`'s main source sets. */
    private fun productionSourceFiles(): List<File> = listOf(
        "app/src/main/kotlin" to "src/main/kotlin",
        "core/src/main/kotlin" to "../core/src/main/kotlin",
    ).mapNotNull { (fromRoot, fromModule) ->
        listOf(File(fromRoot), File(fromModule)).firstOrNull { it.isDirectory }
    }.also { roots ->
        assertEquals("both module source roots were found", 2, roots.size)
    }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
