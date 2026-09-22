package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What a provider is asked to hold, derived from schedule state and nothing else.
 *
 * Every test here is one hazard class of the brief's matrix. The failure the file exists to prevent
 * is a projection that quietly knows more than schedule state — a subject carrying the schedule
 * entity, a parked obligation filtered out of the list instead of parked in it, a group fanned out
 * into one subject per member, or a hash over the whole row that makes every unrelated edit look
 * like a change.
 */
class BuildReminderSubjectsTest {

    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)

    private var today = LocalDate.parse("2026-04-15")
    private val todayPort = Today { today }
    private val clock = Clock { dayMillis("2026-04-15") }

    private val recompute =
        RecomputeSchedules(schedules, states, events, closures, groups, assets, todayPort, clock)
    private val build = BuildReminderSubjects(schedules, states, groups, assets, recompute)

    private suspend fun seedAsset(
        id: String,
        seasonStartMmdd: String? = null,
        seasonEndMmdd: String? = null,
        status: AssetStatus = AssetStatus.ACTIVE,
    ): AssetId {
        val asset = Asset(
            id = AssetId(id),
            name = "Pump $id",
            status = status,
            createdAt = dayMillis("2026-01-01"),
            updatedAt = dayMillis("2026-01-01"),
            seasonStartMmdd = seasonStartMmdd,
            seasonEndMmdd = seasonEndMmdd,
        )
        assets.upsert(asset)
        return asset.id
    }

    /** The one write path into derived state, run over everything the test has seeded. */
    private suspend fun rebuild() = recompute.all()

    private suspend fun localSubjects(): List<ReminderSubject> =
        build.forProvider(ProviderId.LOCAL, today)

    private fun subjectFor(subjects: List<ReminderSubject>, id: String): ReminderSubject =
        subjects.single { it.key == SubjectKey.Schedule(ScheduleId(id)) }

    // ------------------------------------------------------------------------------------------
    // hazard: an archived schedule — or an archived group's schedule — still reminding, and the
    // negative control that a switched-off reminder is simply absent.
    // ------------------------------------------------------------------------------------------

    @Test
    fun aWithdrawnOrSwitchedOffScheduleIsNeverASubject() = runTest {
        seedAsset("a1")
        val liveGroup = groupOf(id = "g1", members = listOf(Triple("a1", "2025-12-01", null)))
        val deadGroup = groupOf(
            id = "g2",
            archivedAt = dayMillis("2026-02-01"),
            members = listOf(Triple("a1", "2025-12-01", null)),
        )
        groups.upsert(liveGroup)
        groups.upsert(deadGroup)

        val live = scheduleOf(
            id = "s-live", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-04-01",
        )
        schedules.upsert(live)
        schedules.upsert(live.copy(id = ScheduleId("s-archived"), status = ScheduleStatus.ARCHIVED))
        schedules.upsert(live.copy(id = ScheduleId("s-off"), remindersEnabled = false))
        schedules.upsert(
            live.copy(
                id = ScheduleId("s-disabled-row"),
                providers = listOf(ScheduleProviderRow("LOCAL", enabled = false)),
            ),
        )
        schedules.upsert(live.copy(id = ScheduleId("s-no-row"), providers = emptyList()))
        schedules.upsert(
            live.copy(
                id = ScheduleId("s-unknown-row"),
                providers = listOf(ScheduleProviderRow("ALMANAC", enabled = true)),
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-dead-group",
                assetId = null,
                groupId = "g2",
                timeInterval = 1,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-04-01",
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-live-group",
                assetId = null,
                groupId = "g1",
                timeInterval = 1,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-04-01",
            ),
        )
        rebuild()

        assertEquals(
            listOf("s-live", "s-live-group"),
            localSubjects().map { (it.key as SubjectKey.Schedule).scheduleId.value }.sorted(),
        )
    }

    // ------------------------------------------------------------------------------------------
    // hazard: a parked schedule vanishing (invariant 47).
    // ------------------------------------------------------------------------------------------

    @Test
    fun aPausedAndAnOutOfSeasonScheduleArriveParkedAndNeitherIsOverdue() = runTest {
        seedAsset("a1")
        seedAsset("a2", seasonStartMmdd = "05-01", seasonEndMmdd = "09-30")
        schedules.upsert(
            scheduleOf(
                id = "s-paused",
                assetId = "a1",
                timeInterval = 1,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
                status = ScheduleStatus.PAUSED,
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-season",
                assetId = "a2",
                timeInterval = 1,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
                seasonBehavior = SeasonBehavior.FOLLOW_ASSET,
            ),
        )
        rebuild()

        val subjects = localSubjects()
        assertEquals(2, subjects.size, "a parked obligation is parked in the list, never absent")

        val paused = subjectFor(subjects, "s-paused")
        assertEquals(SubjectState.Parked(null), paused.state)
        assertNull(paused.dueOn, "a parked subject has no due date and so can never read overdue")
        assertEquals("PAUSED", paused.body)

        val season = subjectFor(subjects, "s-season")
        assertEquals(SubjectState.Parked(LocalDate.parse("2026-05-01")), season.state)
        assertNull(season.dueOn)
        assertEquals("OUT OF SEASON", season.body)
    }

    // ------------------------------------------------------------------------------------------
    // hazard: a group subject fanning out (D-15).
    // ------------------------------------------------------------------------------------------

    @Test
    fun aGroupTargetedScheduleIsOneSubjectCarryingItsProgress() = runTest {
        val members = listOf("a1", "a2", "a3", "a4", "a5")
        members.forEach { seedAsset(it) }
        groups.upsert(groupOf(id = "g1", members = members.map { Triple(it, "2025-12-01", null) }))
        schedules.upsert(
            scheduleOf(
                id = "s1",
                assetId = null,
                groupId = "g1",
                timeInterval = 1,
                timeUnit = RecurrenceUnit.WEEK,
                anchorOn = "2026-04-06",
            ),
        )
        members.take(3).forEachIndexed { index, assetId ->
            events.upsert(
                completionOf(
                    id = "e${index + 1}",
                    occurredOn = "2026-04-07",
                    occurrenceOn = "2026-04-06",
                    assetId = assetId,
                ),
            )
        }
        rebuild()

        val subjects = localSubjects()
        assertEquals(1, subjects.size, "a group is one subject, never one per member")
        assertEquals("OVERDUE · 3 of 5 complete", subjects.single().body)
        assertEquals(LocalDate.parse("2026-04-06"), subjects.single().dueOn)
    }

    // ------------------------------------------------------------------------------------------
    // hazard: a meter-only subject with no date, and a meter rule with no baseline.
    // ------------------------------------------------------------------------------------------

    @Test
    fun aMeterSubjectCarriesNoFabricatedDate() = runTest {
        seedAsset("a1")
        seedAsset("a2")
        schedules.upsert(
            scheduleOf(
                id = "s-meter",
                assetId = "a1",
                meterDefinitionId = "d1",
                meterInterval = 250.0,
                anchorMeter = 100.0,
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-no-baseline",
                assetId = "a2",
                meterDefinitionId = "d1",
                meterInterval = 250.0,
                anchorMeter = null,
            ),
        )
        events.upsert(readingOf(id = "r1", occurredOn = "2026-04-10", definitionId = "d1", value = 140.0))
        rebuild()

        val subjects = localSubjects()
        val meter = subjectFor(subjects, "s-meter")
        assertNull(meter.dueOn, "a meter rule has no date to alarm on")
        assertEquals(true, meter.rule?.hasMeter)
        assertEquals(SubjectState.Active, meter.state)

        val noBaseline = subjectFor(subjects, "s-no-baseline")
        assertEquals(
            SubjectState.Active,
            noBaseline.state,
            "a missing baseline is a repair, not a withdrawal",
        )
        assertNull(noBaseline.dueOn)
        assertEquals("NO BASELINE", noBaseline.body)
    }

    // ------------------------------------------------------------------------------------------
    // hazard: the rule's facts lost, and the entity carried instead.
    // ------------------------------------------------------------------------------------------

    @Test
    fun ruleFactsCarryTheRuleAndTheSubjectCarriesNoEntity() = runTest {
        seedAsset("a1")
        schedules.upsert(
            scheduleOf(
                id = "s-fixed", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH,
                timeBasis = TimeBasis.FIXED, anchorOn = "2026-04-01",
                seasonBehavior = SeasonBehavior.FOLLOW_ASSET,
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-completion", timeInterval = 30, timeUnit = RecurrenceUnit.DAY,
                timeBasis = TimeBasis.COMPLETION, anchorOn = "2026-04-01",
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-meter-only", meterDefinitionId = "d1", meterInterval = 250.0, anchorMeter = 0.0,
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-both", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
                timeBasis = TimeBasis.FIXED, anchorOn = "2026-04-01",
                meterDefinitionId = "d1", meterInterval = 250.0, anchorMeter = 0.0,
            ),
        )
        rebuild()

        val subjects = localSubjects()
        assertEquals(
            RuleFacts(TimeBasis.FIXED, 3, RecurrenceUnit.MONTH, hasMeter = false, seasonal = true),
            subjectFor(subjects, "s-fixed").rule,
        )
        assertEquals(
            RuleFacts(TimeBasis.COMPLETION, 30, RecurrenceUnit.DAY, hasMeter = false, seasonal = false),
            subjectFor(subjects, "s-completion").rule,
        )
        assertEquals(
            RuleFacts(null, null, null, hasMeter = true, seasonal = false),
            subjectFor(subjects, "s-meter-only").rule,
        )
        assertEquals(
            RuleFacts(TimeBasis.FIXED, 1, RecurrenceUnit.YEAR, hasMeter = true, seasonal = false),
            subjectFor(subjects, "s-both").rule,
        )

        val carried = ReminderSubject::class.java.declaredFields.map { it.type.name }
        listOf(
            "com.loosecannon.servicetag.core.model.MaintenanceSchedule",
            "com.loosecannon.servicetag.core.model.ScheduleState",
            "com.loosecannon.servicetag.core.model.MaintenanceGroup",
        ).forEach { entity ->
            assertTrue(entity !in carried, "the subject must not carry $entity")
        }
    }

    // ------------------------------------------------------------------------------------------
    // hazard: provenance leaking into a subject (#4 AC 7).
    // ------------------------------------------------------------------------------------------

    @Test
    fun aHandEnteredAndATelemetryFedReadingProduceIdenticalSubjects() = runTest {
        seedAsset("a1")
        seedAsset("a2")
        schedules.upsert(
            scheduleOf(
                id = "s-manual", assetId = "a1", title = "Oil change",
                meterDefinitionId = "d1", meterInterval = 250.0, anchorMeter = 0.0,
            ),
        )
        schedules.upsert(
            scheduleOf(
                id = "s-telemetry", assetId = "a2", title = "Oil change",
                meterDefinitionId = "d1", meterInterval = 250.0, anchorMeter = 0.0,
            ),
        )
        events.upsert(readingOf(id = "r1", occurredOn = "2026-04-10", definitionId = "d1", value = 300.0))
        events.upsert(
            readingOf(
                id = "r2", occurredOn = "2026-04-10", definitionId = "d1", value = 300.0, assetId = "a2",
            ).copy(source = EventSource.TELEMETRY),
        )
        rebuild()

        val subjects = localSubjects()
        val manual = subjectFor(subjects, "s-manual")
        val telemetry = subjectFor(subjects, "s-telemetry")
        assertEquals(manual.contentHash, telemetry.contentHash)
        assertEquals(manual, telemetry.copy(key = manual.key))
    }

    // ------------------------------------------------------------------------------------------
    // hazard: a no-op update churning the provider (invariant 46).
    // ------------------------------------------------------------------------------------------

    @Test
    fun theHashCoversWhatAProviderShowsAndNothingElse() = runTest {
        seedAsset("a1")
        val schedule = scheduleOf(
            id = "s1", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-04-01",
        )
        schedules.upsert(schedule)
        rebuild()

        val first = localSubjects().single().contentHash
        assertEquals(first, localSubjects().single().contentHash, "the same state must hash the same")

        // An edit the provider cannot see. `updated_at` is deliberately left alone: a write that is
        // not a rule change does not bump it, which is what keeps the pin's floor still.
        schedules.upsert(schedule.copy(description = "now with a note nobody delivers"))
        rebuild()
        assertEquals(first, localSubjects().single().contentHash)

        schedules.upsert(schedule.copy(postponedDueOn = "2026-05-10"))
        rebuild()
        val postponed = localSubjects().single()
        assertEquals(LocalDate.parse("2026-05-10"), postponed.dueOn)
        assertNotEquals(first, postponed.contentHash)
    }

    // ------------------------------------------------------------------------------------------
    // hazard: two providers needing a port change — the half 1.2 can prove (master plan decision 8).
    // ------------------------------------------------------------------------------------------

    @Test
    fun theProviderIsAParameterAndEveryListIsKeyedByIt() = runTest {
        assertEquals(listOf(ProviderId.LOCAL), ProviderId.entries.toList())

        seedAsset("a1")
        schedules.upsert(
            scheduleOf(id = "s1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-04-01"),
        )
        rebuild()

        val all = build.all(today)
        assertEquals(
            ProviderId.entries.toSet(),
            all.keys,
            "every provider is asked, so a stale one is cleared",
        )
        assertEquals(localSubjects(), all.getValue(ProviderId.LOCAL))

        val forProvider = BuildReminderSubjects::class.java.methods.single { it.name == "forProvider" }
        assertEquals(
            ProviderId::class.java,
            forProvider.parameterTypes.first(),
            "the provider is a parameter, which is what makes a second one additive",
        )
    }

    // ------------------------------------------------------------------------------------------
    // invariant 44's half: the desired list never depends on what a provider remembers.
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSubjectListTakesNoProviderBookkeepingAsAnInput() {
        assertEquals(
            listOf(
                ScheduleRepository::class.java,
                ScheduleStateRepository::class.java,
                GroupRepository::class.java,
                AssetRepository::class.java,
                RecomputeSchedules::class.java,
            ),
            BuildReminderSubjects::class.java.constructors.single().parameterTypes.toList(),
        )
    }
}
