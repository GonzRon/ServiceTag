package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.RepairScheduleProviders
import java.time.LocalDate
import java.time.ZoneOffset
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
        RecomputeSchedules(
            schedules, states, events, closures, groups, assets, InMemorySeasonActivationRepository(), todayPort, clock,
        ) { ZoneOffset.UTC }
    private val build = BuildReminderSubjects(schedules, groups, recompute)

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
            // Schema 8: an asset with a window is CALENDAR, the rule the 7 -> 8 migration applies.
            seasonMode = if (seasonStartMmdd != null && seasonEndMmdd != null) SeasonMode.CALENDAR else SeasonMode.YEAR_ROUND,
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
    // hazard: an archived schedule still reminding. It arrives **withdrawn** — in the list, so the
    // provider is told to let go of it — and the negative half is that it is never active and never
    // parked, so it never reminds. A schedule nobody asked to be reminded about is simply absent,
    // and so is one whose group has been archived.
    // ------------------------------------------------------------------------------------------

    @Test
    fun anArchivedScheduleArrivesWithdrawnAndASwitchedOffOneIsAbsent() = runTest {
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

        val subjects = localSubjects()
        assertEquals(
            listOf("s-archived", "s-live", "s-live-group"),
            subjects.map { (it.key as SubjectKey.Schedule).scheduleId.value }.sorted(),
        )

        val archived = subjectFor(subjects, "s-archived")
        assertEquals(SubjectState.Withdrawn, archived.state)
        assertTrue(
            archived.state != SubjectState.Active && archived.state !is SubjectState.Parked,
            "an archived schedule is never active and never parked, so it never reminds",
        )
        assertEquals("", archived.body, "there is nothing to show for a subject being let go of")
        assertEquals(LocalDate.parse("2026-04-01"), archived.dueOn)

        assertEquals(SubjectState.Active, subjectFor(subjects, "s-live").state)
        assertEquals(SubjectState.Active, subjectFor(subjects, "s-live-group").state)
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
                servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
                policyOffsetDays = 0,
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
    // hazard: reminders re-deriving a season (spec §4.7, inv. 104). Every parked date comes from
    // the engine's own output — the actionable date, or the recompute's `quietUntil` — and none
    // from an `MM-DD` read here.
    // ------------------------------------------------------------------------------------------

    private val activations = InMemorySeasonActivationRepository()
    private val seasonalRecompute =
        RecomputeSchedules(schedules, states, events, closures, groups, assets, activations, todayPort, clock) { ZoneOffset.UTC }
    private val seasonalBuild = BuildReminderSubjects(schedules, groups, seasonalRecompute)

    private suspend fun seasonalSubjects(): List<ReminderSubject> {
        seasonalRecompute.all()
        return seasonalBuild.forProvider(ProviderId.LOCAL, today)
    }

    /**
     * Spec §4.7's six rows on one day, 10 Dec 2026, inside the winter break:
     *
     * - ARCHIVED → Withdrawn; PAUSED → Parked(null);
     * - DORMANT → Parked(A): an AT_START schedule with a ten-day offset on the mower's calendar comes
     *   back on 25 Apr 2027 — the season's start **plus the offset**, which no `MM-DD` read gives —
     *   and the hot tub, MANUAL and ended, is parked with no date at all;
     * - DEFERRED → Parked(A): a mower item due 20 Dec, inside the break, is held until 1 Apr 2027;
     * - quiet with a notifying status → Parked(the first day after the break): the snowblower,
     *   OVERDUE since 1 Nov, until 1 Mar 2027;
     * - otherwise Active: a CONTINUOUS item, and a quiet item whose status (OK) would not notify.
     */
    @Test
    fun theSubjectStateFollowsTheEngine() = runTest {
        today = LocalDate.parse("2026-12-10")
        assets.upsert(SeasonFixtures.mowerAsset())
        assets.upsert(SeasonFixtures.snowblowerAsset())
        assets.upsert(SeasonFixtures.hotTubAsset())
        seedAsset("plain")
        // START 3 Jan, END 16 Apr: ended, and never started again by 10 Dec in this history.
        SeasonFixtures.hotTubActivationsUpTo("2026-07-01").forEach { activations.insert(it) }

        val monthlyOnMower = scheduleOf(
            id = "s-archived", assetId = "mow", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-06-01",
            createdOn = "2026-05-01",
        )
        schedules.upsert(monthlyOnMower.copy(status = ScheduleStatus.ARCHIVED))
        schedules.upsert(monthlyOnMower.copy(id = ScheduleId("s-paused"), status = ScheduleStatus.PAUSED))
        schedules.upsert(
            monthlyOnMower.copy(
                id = ScheduleId("s-dormant"), servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 10,
            ),
        )
        schedules.upsert(SeasonFixtures.hotTubSchedule())
        events.upsert(SeasonFixtures.hotTubCompletedOnApril11())
        schedules.upsert(SeasonFixtures.mowerSchedule(id = "s-deferred", anchorOn = "2025-12-20", createdOn = "2025-11-01"))
        events.upsert(completionOf("e-def", occurredOn = "2026-03-05", occurrenceOn = "2025-12-20", assetId = "mow", scheduleId = "s-deferred"))
        schedules.upsert(SeasonFixtures.snowblowerSchedule())
        events.upsert(SeasonFixtures.snowblowerLastDone())
        schedules.upsert(SeasonFixtures.mowerSchedule(id = "s-quiet-ok"))
        events.upsert(SeasonFixtures.mowerJanuaryDone(scheduleId = "s-quiet-ok"))
        schedules.upsert(
            scheduleOf(id = "s-active", assetId = "plain", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-12-01", createdOn = "2026-11-01"),
        )

        val subjects = seasonalSubjects()
        fun state(id: String) = subjectFor(subjects, id).state

        assertEquals(SubjectState.Withdrawn, state("s-archived"))
        assertEquals(SubjectState.Parked(null), state("s-paused"))
        assertEquals(SubjectState.Parked(LocalDate.parse("2027-04-25")), state("s-dormant"))
        assertEquals("OUT OF SEASON", subjectFor(subjects, "s-dormant").body)
        assertEquals(SubjectState.Parked(null), state("s-tub"), "MANUAL: the next START is never predicted")
        assertEquals(SubjectState.Parked(LocalDate.parse("2027-04-01")), state("s-deferred"))
        assertEquals("DEFERRED", subjectFor(subjects, "s-deferred").body)
        assertEquals(SubjectState.Parked(LocalDate.parse("2027-03-01")), state("s-snow"))
        assertEquals("OVERDUE", subjectFor(subjects, "s-snow").body, "quiet parks the reminder, never the status")
        assertEquals(SubjectState.Active, state("s-quiet-ok"), "quiet, but OK has nothing to deliver")
        assertEquals(SubjectState.Active, state("s-active"))
        subjects.filter { it.state is SubjectState.Parked }.forEach { assertNull(it.dueOn, "a parked subject has no due date") }
    }

    /**
     * A stale row is derived, and a missing one too — never skipped (master plan §8.6). Rows written
     * on 15 Apr are read on 16 Apr, the day the season ended, with no rebuild in between: the subject
     * reports OUT OF SEASON, not yesterday's OVERDUE; and a schedule that has no row at all is still
     * handed to the provider. Nothing is written by the build.
     */
    @Test
    fun aStaleStateRowIsDerivedNotSkipped() = runTest {
        seedAsset("a1", seasonStartMmdd = "10-15", seasonEndMmdd = "04-15")
        val seasonal = scheduleOf(
            id = "s-stale", assetId = "a1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-04-01",
            servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0, createdOn = "2026-03-01",
        )
        schedules.upsert(seasonal)
        rebuild()
        assertEquals("OVERDUE", localSubjects().single().body)
        val stored = states.rows.toMap()

        today = LocalDate.parse("2026-04-16")
        schedules.upsert(seasonal.copy(id = ScheduleId("s-missing")))
        val subjects = localSubjects()
        assertEquals(listOf("s-missing", "s-stale"), subjects.map { (it.key as SubjectKey.Schedule).scheduleId.value })
        assertEquals("OUT OF SEASON", subjectFor(subjects, "s-stale").body)
        assertEquals(SubjectState.Parked(LocalDate.parse("2026-10-15")), subjectFor(subjects, "s-stale").state)
        assertEquals(subjectFor(subjects, "s-stale").state, subjectFor(subjects, "s-missing").state)
        assertEquals(stored, states.rows.toMap(), "the build wrote nothing")
    }

    /**
     * `RuleFacts.seasonal` is `servicePolicy ≠ CONTINUOUS`, false for every CONTINUOUS row, so a
     * CONTINUOUS subject hashes exactly as it did before 1.4 — and exactly the same on an asset with
     * a season and a break as on one with neither, because CONTINUOUS ignores both.
     */
    @Test
    fun continuousRowsKeepTheirContentHash() = runTest {
        today = LocalDate.parse("2026-12-10")
        assets.upsert(SeasonFixtures.snowblowerAsset())
        seedAsset("plain")
        val onSeasonal = scheduleOf(
            id = "s-seasonal", assetId = "snow", title = "Belt check", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-12-01", createdOn = "2026-11-01",
        )
        schedules.upsert(onSeasonal)
        schedules.upsert(onSeasonal.copy(id = ScheduleId("s-plain"), target = ScheduleTarget.AssetTarget(AssetId("plain"))))

        val subjects = seasonalSubjects()
        val expected = ContentHash.of(
            "Belt check", "OVERDUE", LocalDate.parse("2026-12-01"), 14, SubjectState.Active,
            RuleFacts(TimeBasis.FIXED, 1, RecurrenceUnit.MONTH, hasMeter = false, seasonal = false),
        )
        assertEquals(expected, subjectFor(subjects, "s-seasonal").contentHash)
        assertEquals(expected, subjectFor(subjects, "s-plain").contentHash)
    }

    /**
     * Spec §4.7's quiet row with F4's snowblower on 10 Dec: OVERDUE since 1 Nov, quiet in the break,
     * so it is parked until the first day after the break, 1 Mar 2027 — a date the recompute
     * evaluates on request, not one this builder works out from the break.
     */
    @Test
    fun aQuietNotifyingRowParksUntilTheFirstDayAfterTheBreak() = runTest {
        today = LocalDate.parse("2026-12-10")
        assets.upsert(SeasonFixtures.snowblowerAsset())
        schedules.upsert(SeasonFixtures.snowblowerSchedule())
        events.upsert(SeasonFixtures.snowblowerLastDone())

        val subject = seasonalSubjects().single()
        assertEquals(SubjectState.Parked(LocalDate.parse("2027-03-01")), subject.state)
        assertEquals("OVERDUE", subject.body)
        assertNull(subject.dueOn)
        assertEquals(LocalDate.parse("2027-03-01"), seasonalRecompute.quietUntil(SeasonFixtures.snowblowerSchedule()))
    }

    /**
     * An Active subject carries the **actionable** date, the one its status word is measured against
     * (controller ruling on concern 1). F4's snowblower on 2 Nov 2026 is OVERDUE against 1 Nov, the
     * pre-service point; its own date, 20 Dec, is not what a provider should say it is overdue since.
     */
    @Test
    fun anActiveSubjectCarriesTheActionableDate() = runTest {
        today = LocalDate.parse("2026-11-02")
        assets.upsert(SeasonFixtures.snowblowerAsset())
        schedules.upsert(SeasonFixtures.snowblowerSchedule())
        events.upsert(SeasonFixtures.snowblowerLastDone())

        val subject = seasonalSubjects().single()
        assertEquals(SubjectState.Active, subject.state)
        assertEquals("OVERDUE", subject.body)
        assertEquals(LocalDate.parse("2026-11-01"), subject.dueOn)
    }

    /**
     * The park date is part of the hash (`ContentHash` renders a parked state with its date), so it
     * has to be the same on every day of one break: two builds, on 10 Dec and on 20 Jan, hash alike,
     * and the provider is not told of a change that is not one.
     */
    @Test
    fun theQuietParkDateKeepsTheContentHashStable() = runTest {
        assets.upsert(SeasonFixtures.snowblowerAsset())
        schedules.upsert(SeasonFixtures.snowblowerSchedule())
        events.upsert(SeasonFixtures.snowblowerLastDone())

        today = LocalDate.parse("2026-12-10")
        val december = seasonalSubjects().single()
        today = LocalDate.parse("2027-01-20")
        val january = seasonalSubjects().single()
        assertEquals(december.state, january.state)
        assertEquals(december.contentHash, january.contentHash)
    }

    // ------------------------------------------------------------------------------------------
    // hazard: a group subject fanning out (D-15).
    // ------------------------------------------------------------------------------------------

    @Test
    fun aGroupTargetedScheduleIsOneSubjectCarryingItsProgress() = runTest {
        val members = listOf("a1", "a2", "a3", "a4", "a5")
        members.forEach { seedAsset(it) }
        groups.upsert(
            groupOf(
                id = "g1",
                // "a5" was removed **after** this round opened, so it is still required for it
                // (D-10). That is what separates the required set from the membership list here: a
                // denominator counted off the still-open windows would read four, not five.
                members = members.map { assetId ->
                    Triple(assetId, "2025-12-01", if (assetId == "a5") "2026-02-01" else null)
                },
            ),
        )
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
    // hazard: invariant 74 on the reminder surface — a round nobody is required for reaching a
    // provider as a dated obligation. The reminder projection is the one consumer of that rule with
    // no owner in the plan's list, and it is correct here only because the engine nulls both the
    // computed and the postponed date for a non-actionable round.
    // ------------------------------------------------------------------------------------------

    @Test
    fun anEmptiedGroupsRoundArrivesDatelessAndBodiless() = runTest {
        seedAsset("a1")
        // The one window closed before the schedule — and so before its first round — even opened.
        groups.upsert(
            groupOf(id = "g-empty", members = listOf(Triple("a1", "2025-11-01", "2025-12-01"))),
        )
        schedules.upsert(
            scheduleOf(
                id = "s1",
                assetId = null,
                groupId = "g-empty",
                timeInterval = 1,
                timeUnit = RecurrenceUnit.WEEK,
                anchorOn = "2026-04-06",
            ),
        )
        rebuild()

        val subject = localSubjects().single()
        assertNull(subject.dueOn, "a round nobody is required for is never handed over as due")
        assertEquals(
            "",
            subject.body,
            "and never told it needs a baseline, which is not what is missing",
        )
        // Emptiness is carried by the absent date and the empty body, not by a state of its own:
        // no `SubjectState` member means "there is nothing to do", and inventing one here would be
        // a rule neither the spec nor the brief states.
        assertEquals(SubjectState.Active, subject.state)
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
                servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
                policyOffsetDays = 0,
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
        // A unit with no interval: unreachable through command validation, reachable from a decoded
        // archive, which maps the two columns independently.
        schedules.upsert(
            scheduleOf(
                id = "s-stray-unit", timeUnit = RecurrenceUnit.MONTH,
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
        assertEquals(
            RuleFacts(null, null, null, hasMeter = true, seasonal = false),
            subjectFor(subjects, "s-stray-unit").rule,
            "no interval is no series, so all three series facts are null together",
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
    // #80: a providerless row is delivered by nobody; the repair makes it a LOCAL subject.
    // ------------------------------------------------------------------------------------------

    /**
     * #80 AC 6: before the repair a reminders-on, providerless schedule is filtered out before any
     * provider sees it; after `RepairScheduleProviders.apply()` it is a LOCAL subject like any other.
     */
    @Test
    fun aRepairedRowIsALocalSubject() = runTest {
        seedAsset("a1")
        schedules.upsert(
            scheduleOf(id = "s-providerless", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-04-01")
                .copy(providers = emptyList()),
        )
        rebuild()
        assertEquals(emptyList(), localSubjects(), "a providerless row reaches no provider")

        val report = RepairScheduleProviders(schedules, FakeUnitOfWork(schedules), clock).apply()
        assertEquals(listOf(ScheduleId("s-providerless")), report.repaired)

        val subject = subjectFor(localSubjects(), "s-providerless")
        assertEquals(SubjectState.Active, subject.state)
    }

    // ------------------------------------------------------------------------------------------
    // invariant 44's half: the desired list never depends on what a provider remembers.
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSubjectListTakesNoProviderBookkeepingAsAnInput() {
        assertEquals(
            listOf(
                ScheduleRepository::class.java,
                GroupRepository::class.java,
                RecomputeSchedules::class.java,
            ),
            BuildReminderSubjects::class.java.constructors.single().parameterTypes.toList(),
        )
    }
}
