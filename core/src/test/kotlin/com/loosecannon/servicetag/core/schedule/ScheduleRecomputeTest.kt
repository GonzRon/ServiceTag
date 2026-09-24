package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `rebuild` itself: the pin, the fallback, purity, and the three structural facts about it. */
class ScheduleRecomputeTest {

    private fun on(date: String) = LocalDate.parse(date)

    private fun quarterly(createdOn: String = "2026-02-10", ruleChangedOn: String = createdOn) = scheduleOf(
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-01",
        createdOn = createdOn,
        ruleChangedOn = ruleChangedOn,
    )

    /**
     * The D-27 pin, all four facts in one test, because each of them is a different way the same
     * mistake shows up — computing the due date as "the first series date ≥ today":
     *
     * 1. a schedule created 2026-02-10 against a past anchor is due at the pinned series date,
     *    April 1, and not at the anchor it is already past;
     * 2. it stays **OVERDUE** as `T` advances with no history change — a due date that re-floated
     *    on today would be perpetually OK, which is how a real obligation disappears;
     * 3. after its sole completion is deleted it returns to the **same** pin (D5 §10.1's last row,
     *    which said "first series date ≥ T", is superseded);
     * 4. after a recurrence edit the floor becomes the **edit date**, which is what stops
     *    re-anchoring an old schedule today from pinning it immediately overdue.
     */
    @Test
    fun theFixedPinIsImmutableAndOnlyAnEditMovesItsFloor() {
        val schedule = quarterly()

        val atCreation = ScheduleRecompute.rebuild(
            schedule, emptyList(), emptyList(), emptyList(), on("2026-02-10"), ZoneOffset.UTC,
        )
        assertEquals("2026-04-01", atCreation.computedDueOn)
        assertEquals(TerminationKind.NONE, atCreation.lastTerminationKind)

        // (2) the pin does not re-float: same inputs, later T, same date — and OVERDUE stays OVERDUE
        val later = ScheduleRecompute.rebuild(
            schedule, emptyList(), emptyList(), emptyList(), on("2026-09-01"), ZoneOffset.UTC,
        )
        assertEquals("2026-04-01", later.computedDueOn)
        assertEquals(DueStatus.OVERDUE, statusOf(schedule, later, on("2026-09-01")))
        val muchLater = ScheduleRecompute.rebuild(
            schedule, emptyList(), emptyList(), emptyList(), on("2027-01-05"), ZoneOffset.UTC,
        )
        assertEquals("2026-04-01", muchLater.computedDueOn)
        assertEquals(DueStatus.OVERDUE, statusOf(schedule, muchLater, on("2027-01-05")))

        // (3) the sole completion, then its deletion, returns the same pin
        val done = completionOf("e1", occurredOn = "2026-07-15", occurrenceOn = "2026-04-01")
        assertEquals(
            "2026-10-01",
            ScheduleRecompute.rebuild(
                schedule, listOf(done), emptyList(), emptyList(), on("2026-08-01"), ZoneOffset.UTC,
            ).computedDueOn,
        )
        assertEquals(
            "2026-04-01",
            ScheduleRecompute.rebuild(
                schedule, emptyList(), emptyList(), emptyList(), on("2026-08-01"), ZoneOffset.UTC,
            ).computedDueOn,
        )

        // (4) a recurrence edit on 2026-06-20 moves the floor to the edit date
        val edited = quarterly(createdOn = "2026-02-10", ruleChangedOn = "2026-06-20")
        assertEquals(
            "2026-07-01",
            ScheduleRecompute.rebuild(
                edited, emptyList(), emptyList(), emptyList(), on("2026-06-21"), ZoneOffset.UTC,
            ).computedDueOn,
        )
    }

    /**
     * The floor is read in the **owner's** calendar, not UTC's (controller ruling, 2026-09-22).
     *
     * One instant, two zones. A schedule stamped 21:00 on September 22 in a fixed UTC−05:00 zone
     * and anchored on that same September 22 is due **that day**: the owner said "start today", and
     * a schedule may be due on the owner's own day. Read at UTC the same
     * instant is already the 23rd, so the floor lands past the anchor and the first occurrence is
     * pushed a whole interval out — three months of silence on work the owner asked for now. That
     * was the shipped answer for the last four hours of every day in every zone west of UTC, and
     * it is what this test pins shut.
     *
     * `zone` is still an argument, so the purity invariant 16 protects is untouched: this test
     * gets two different answers out of `rebuild` by handing it two different zones, never by
     * moving a device. The owner's zone is `Etc/GMT+5` (UTC−05:00, no DST) rather than a real
     * place's: no transition is exercised here, only the negative offset the 21:00-to-midnight-UTC
     * crossing needs, so a fixed offset keeps the hazard (owner's day vs. UTC's) and nothing else.
     */
    @Test
    fun theFloorIsTheOwnersDateAndNotUtcs() {
        val evening = LocalDate.parse("2026-09-22")
            .atTime(21, 0)
            .atZone(ZoneId.of("Etc/GMT+5"))
            .toInstant()
            .toEpochMilli()
        val saved = scheduleOf(
            timeInterval = 3,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = TimeBasis.FIXED,
            anchorOn = "2026-09-22",
            createdOn = "2026-09-22",
        ).copy(createdAt = evening, updatedAt = evening, ruleChangedAt = evening)

        fun dueIn(zone: ZoneId) = ScheduleRecompute.rebuild(
            saved, emptyList(), emptyList(), emptyList(), on("2026-09-22"), zone,
        )

        val owners = dueIn(ZoneId.of("Etc/GMT+5"))
        assertEquals("2026-09-22", owners.computedDueOn)
        assertEquals(DueStatus.DUE, statusOf(saved, owners, on("2026-09-22")))

        // the same instant, read in the zone the engine used to assume: a day later, so the anchor
        // is skipped and the obligation moves a whole quarter
        assertEquals("2026-12-22", dueIn(ZoneOffset.UTC).computedDueOn)
    }

    /**
     * A pre-1.2 completion, or one re-pointed to a schedule by hand, carries no `occurrence_on`.
     * The fallback is the D5 §5 reconstruction — the largest series date `<= occurred_on` — and it
     * is **approximate for exactly one case, the early completion**: the same March 20 completion
     * that yields July 1 with its key yields April 1 without one. The test asserts the approximate
     * answer deliberately: a fallback that threw would make an old row unusable, and one that used
     * today would make it wrong.
     */
    @Test
    fun aCompletionWithNoOccurrenceKeyFallsBackToTheSeriesAndIsApproximate() {
        val schedule = quarterly()
        val keyless = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = null)
        val state = ScheduleRecompute.rebuild(
            schedule, listOf(keyless), emptyList(), emptyList(), on("2026-03-21"), ZoneOffset.UTC,
        )

        assertEquals("2026-04-01", state.computedDueOn)
        assertEquals("2026-03-20", state.lastTerminationEffectiveOn)

        val keyed = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01")
        assertNotEquals(
            state.computedDueOn,
            ScheduleRecompute.rebuild(
                schedule, listOf(keyed), emptyList(), emptyList(), on("2026-03-21"), ZoneOffset.UTC,
            ).computedDueOn,
        )

        // a completion before the anchor has no series date at or before it; the anchor is the key
        val ancient = completionOf("e2", occurredOn = "2025-11-02", occurrenceOn = null)
        assertEquals(
            listOf("2026-01-01"),
            ScheduleRecompute.terminations(schedule, listOf(ancient), emptyList(), emptyList())
                .map { it.occurrenceOn },
        )
    }

    /**
     * `rebuild` is a pure, idempotent function of (config, events, closures, membership, `T`, the
     * zone `T` is on) (invariants 15, 16), asserted as properties **over the function**: no
     * repository, no clock, no device — the zone is the newest member of that tuple and an
     * argument for exactly this reason. Applying it again to the same inputs gives the same state, the input lists are
     * left exactly as they were, and the order the events arrive in does not matter.
     */
    @Test
    fun rebuildIsIdempotentAndPure() {
        val schedule = quarterly()
        val events = listOf(
            completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01"),
            readingOf("r1", "2026-05-01", "engine_hours", 12.0),
        )
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-07-01", closedOn = "2026-07-20"))
        val before = events.toList() to closures.toList()

        val first = ScheduleRecompute.rebuild(schedule, events, closures, emptyList(), on("2026-08-01"), ZoneOffset.UTC)
        val second = ScheduleRecompute.rebuild(
            schedule, events, closures, emptyList(), on("2026-08-01"), ZoneOffset.UTC,
        )
        assertEquals(first, second)
        assertEquals(before.first, events)
        assertEquals(before.second, closures)

        val shuffled = ScheduleRecompute.rebuild(
            schedule, events.asReversed(), closures.asReversed(), emptyList(), on("2026-08-01"), ZoneOffset.UTC,
        )
        assertEquals(first, shuffled)

        // nothing in the state is a clock reading: two runs a long way apart in wall-clock time
        // produce byte-identical rows for the same `T`.
        assertEquals(0L, first.computedAt)
        assertEquals("2026-08-01", first.computedForOn)
    }

    /** Invariant 10: the sort key is null only when the schedule has no time rule at all. */
    @Test
    fun aMeterOnlyScheduleHasNoDueDate() {
        val meterOnly = scheduleOf(
            title = "Oil change",
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
            meterLead = 5.0,
        )
        val state = ScheduleRecompute.rebuild(
            meterOnly, emptyList(), emptyList(), emptyList(), on("2026-06-01"), ZoneOffset.UTC,
        )
        assertNull(state.computedDueOn)
        assertNull(state.effectiveDueOn)
        assertEquals(170.0, state.computedDueMeter)
    }

    /**
     * A meter can be misread, and a reading lower than the last one is accepted. The engine uses
     * **the latest by date, not the maximum**: taking the maximum would let one fat-fingered
     * reading raise the current value for good, and the threshold with it.
     */
    @Test
    fun theEngineUsesTheLatestMeterReadingNotTheHighest() {
        val meterOnly = scheduleOf(
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
            meterLead = 5.0,
        )
        val events = listOf(
            readingOf("r1", "2026-05-01", "engine_hours", 200.0),
            readingOf("r2", "2026-06-01", "engine_hours", 150.0),
        )
        val state = ScheduleRecompute.rebuild(
            meterOnly, events, emptyList(), emptyList(), on("2026-06-02"), ZoneOffset.UTC,
        )
        assertEquals(150.0, state.currentMeter)
    }

    /**
     * An event may carry **two** measurements of one definition — nothing in the model forbids it
     * and an imported archive can hold it — and only one of them need carry a number. The reading
     * is found by asking the same question twice, `definitionId` **and** a non-null `valueNum`, so
     * a numberless measurement sitting first in the list cannot swallow the reading behind it and
     * drop the schedule back a step.
     */
    @Test
    fun aReadingIsNotLostBehindANumberlessMeasurementOfTheSameDefinition() {
        val meterOnly = scheduleOf(
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
        )
        val plain = readingOf("r1", "2026-06-01", "engine_hours", 180.0)
        val noisy = plain.copy(
            measurements = listOf(
                Measurement(
                    id = "m-note",
                    definitionId = DefinitionId("engine_hours"),
                    valueNum = null,
                    valueText = "unreadable",
                    unit = "h",
                    sortOrder = 0,
                ),
            ) + plain.measurements,
        )
        val state = ScheduleRecompute.rebuild(
            meterOnly, listOf(noisy), emptyList(), emptyList(), on("2026-06-02"), ZoneOffset.UTC,
        )
        assertEquals(180.0, state.currentMeter)
    }

    /**
     * **Regression — a termination dated a whole interval before its own round used to stick the
     * schedule on that round for ever.**
     *
     * On the COMPLETION basis the series restarts at the effective date, and a bare `E + interval`
     * lands back on the occurrence just satisfied whenever `E + interval <= D`. The schedule then
     * reported that date permanently: the round could never be completed again (the unique index)
     * and closing it was refused, so nothing could move it. Any past date is a legal `occurredOn`
     * or `closedOn` (D-25), so it was reachable from the app and the API, and it broke invariant 9's
     * "at most one current occurrence".
     *
     * The fix is the same bound FIXED already carried: strictly after `max(D, E)`. Asserted here on
     * a completion, a closure and the boundary case where `E + interval` lands exactly on `D`.
     */
    @Test
    fun aTerminationAnIntervalBeforeItsOwnRoundStillAdvancesTheSchedule() {
        val monthly = scheduleOf(
            timeInterval = 1,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2026-02-01",
            createdOn = "2026-01-01",
        )
        assertEquals("2026-02-01", rebuildOn(monthly, emptyList(), emptyList()), "the anchor, untouched")

        // E + interval lands exactly on D, the round it satisfied: the old answer, and the bug.
        val backdated = completionOf("e1", occurredOn = "2026-01-01", occurrenceOn = "2026-02-01")
        assertEquals("2026-03-01", rebuildOn(monthly, listOf(backdated), emptyList()))

        // Further back still: the restarted series is walked to its first date after the round just
        // satisfied — one next occurrence, never a backlog of the ones it skipped past.
        val muchEarlier = completionOf("e1", occurredOn = "2025-11-05", occurrenceOn = "2026-02-01")
        assertEquals("2026-02-05", rebuildOn(monthly, listOf(muchEarlier), emptyList()))

        // And a closure, which is a termination with a date and advances the same way.
        val closedEarly = closureOf("oc1", occurrenceOn = "2026-02-01", closedOn = "2026-01-01")
        assertEquals("2026-03-01", rebuildOn(monthly, emptyList(), listOf(closedEarly)))

        // The ordinary cases are untouched: a termination on or after its own round still steps once.
        val onTime = completionOf("e1", occurredOn = "2026-02-01", occurrenceOn = "2026-02-01")
        assertEquals("2026-03-01", rebuildOn(monthly, listOf(onTime), emptyList()))
        val late = completionOf("e1", occurredOn = "2026-04-10", occurrenceOn = "2026-02-01")
        assertEquals("2026-05-10", rebuildOn(monthly, listOf(late), emptyList()))
    }

    /**
     * The property the regression above is a special case of: **the current occurrence is never one
     * that has already terminated.** Asserted over both bases and a spread of effective dates
     * either side of the round's own key, because that is the whole family the bug lived in and one
     * example would not have caught the closure half of it.
     */
    @Test
    fun theCurrentOccurrenceIsNeverOneThatAlreadyTerminated() {
        val dates = listOf("2025-10-01", "2026-01-01", "2026-01-31", "2026-02-01", "2026-04-10")
        for (basis in TimeBasis.entries) {
            for (interval in listOf(1, 3)) {
                val schedule = scheduleOf(
                    timeInterval = interval,
                    timeUnit = RecurrenceUnit.MONTH,
                    timeBasis = basis,
                    anchorOn = "2026-02-01",
                    createdOn = "2026-01-01",
                )
                for (date in dates) {
                    val done = completionOf("e1", occurredOn = date, occurrenceOn = "2026-02-01")
                    val closed = closureOf("oc1", occurrenceOn = "2026-02-01", closedOn = date)
                    for ((label, next) in listOf(
                        "completed on $date" to rebuildOn(schedule, listOf(done), emptyList()),
                        "closed on $date" to rebuildOn(schedule, emptyList(), listOf(closed)),
                    )) {
                        assertTrue(
                            next!! > "2026-02-01",
                            "$basis/$interval $label: the round just terminated is still current at $next",
                        )
                    }
                }
            }
        }
    }

    /** `computedDueOn` for one history, with no membership: the asset-target case. */
    private fun rebuildOn(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        closures: List<OccurrenceClosure>,
    ): String? = ScheduleRecompute
        .rebuild(schedule, events, closures, emptyList(), on("2026-05-01"), ZoneOffset.UTC)
        .computedDueOn

    /**
     * The group seam, now filled: `rebuild` takes membership as a parameter and the required set of
     * a group occurrence is derived from it. The same rows that used to answer "not a termination"
     * because nobody was required now terminate the round when the member who *is* required has
     * done the work — and the negative half still holds, because membership is what decides it:
     * hand the same history no windows at all and the round obliges nobody, which is never a
     * termination (invariant 77).
     */
    @Test
    fun aGroupTargetedOccurrenceTerminatesOnItsRequiredMembership() {
        val group = scheduleOf(
            assetId = null,
            groupId = "g1",
            timeInterval = 3,
            timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-01-01",
            createdOn = "2026-02-10",
        )
        val done = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01")
        val membership = groupOf(members = listOf(Triple("a1", "2026-01-05", null))).members

        assertEquals(
            listOf(Termination("2026-04-01", "2026-03-20", TerminationKind.COMPLETED)),
            ScheduleRecompute.terminations(group, listOf(done), emptyList(), membership),
        )
        assertEquals(
            emptyList(),
            ScheduleRecompute.terminations(group, listOf(done), emptyList(), emptyList()),
        )
    }

    /**
     * `O` (master plan §8.1, D-28): the last termination's **effective** date, or — for a schedule
     * that has never terminated — the date of `rule_changed_at` in the zone the caller names. Never
     * `updated_at`, which a title edit moves: here it is a whole month later than the rule change and
     * must not be what the opened-before guard reads.
     */
    @Test
    fun openedIsTheLastTerminationElseTheRuleChangeDate() {
        val schedule = quarterly(createdOn = "2026-01-10", ruleChangedOn = "2026-02-01")
            .copy(updatedAt = dayMillis("2026-03-01"), servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14)
        val never = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2026-03-15"), ZoneOffset.UTC)
        assertEquals(on("2026-02-01"), ScheduleRecompute.policyInputsOf(schedule, never, ZoneOffset.UTC).openedOn)
        // Read in the owner's zone: 1 Feb 00:00 UTC is still 31 Jan five hours west.
        assertEquals(on("2026-01-31"), ScheduleRecompute.policyInputsOf(schedule, never, ZoneId.of("Etc/GMT+5")).openedOn)

        val events = listOf(completionOf("e1", occurredOn = "2026-04-10", occurrenceOn = "2026-04-01"))
        val terminated = ScheduleRecompute.rebuild(schedule, events, emptyList(), emptyList(), on("2026-04-15"), ZoneOffset.UTC)
        val inputs = ScheduleRecompute.policyInputsOf(schedule, terminated, ZoneOffset.UTC)
        assertEquals(on("2026-04-10"), inputs.openedOn, "the termination's effective date, not its key")
        assertEquals(on("2026-07-01"), inputs.rawDueOn)
        assertEquals(ServicePolicy.PRE_SERVICE, inputs.policy)
        assertEquals(-14, inputs.offsetDays)

        // The guard reads it: the snowblower's R 20 Nov is pulled to 1 Nov only when O is before 1 Nov.
        val snowSeason = SeasonFixtures.snowblowerAsset().seasonInputs(emptyList())
        val snow = SeasonFixtures.snowblowerSchedule().copy(
            anchorOn = "2026-11-20",
            timeInterval = 1,
            timeUnit = RecurrenceUnit.YEAR,
            createdAt = dayMillis("2026-10-01"),
            ruleChangedAt = dayMillis("2026-10-20"),
            updatedAt = dayMillis("2026-11-05"),
        )
        val pulled = ScheduleRecompute.rebuild(snow, emptyList(), emptyList(), emptyList(), on("2026-10-25"), ZoneOffset.UTC, snowSeason)
        assertEquals("2026-11-20", pulled.computedDueOn)
        assertEquals("2026-11-01", pulled.actionableDueOn, "opened 20 Oct, before the 1 Nov point")
        assertEquals(PolicyReason.BEFORE_SEASON, pulled.policyReason)
    }

    /**
     * Invariant 16 with the season as an input: `rebuild` is a pure, idempotent function of its
     * arguments including [SeasonInputs] and the zone. Two calls agree; the season's activation rows
     * may arrive in any order; and the answer is the one for the `T` it is handed — a CALENDAR winter
     * season read in January is in season, whatever day the machine running this thinks it is.
     */
    @Test
    fun rebuildIsPureAndIdempotentWithSeasonInputs() {
        val schedule = quarterly().copy(servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 5)
        val winter = SeasonFixtures.seasonOf(
            SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31", breakStart = "12-20", breakEnd = "01-05",
        )
        val t = on("2027-01-10")
        val first = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), t, ZoneOffset.UTC, winter)
        val second = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), t, ZoneOffset.UTC, winter.copy())
        assertEquals(first, second)
        assertEquals(PolicyPhase.ACTIVE, first.policyPhase, "10 Jan 2027 is in the 11-15 → 03-31 season")
        assertEquals("2026-11-20", first.actionableDueOn, "AT_START: the season's start plus five days")
        assertEquals(PolicyReason.SEASON_START, first.policyReason)
        assertEquals(false, first.quiet)
        val summer = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2027-07-01"), ZoneOffset.UTC, winter)
        assertEquals(PolicyPhase.DORMANT, summer.policyPhase)
        assertEquals("2027-11-20", summer.actionableDueOn)
        val christmas = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2026-12-25"), ZoneOffset.UTC, winter)
        assertEquals(true, christmas.quiet)

        val rows = listOf(
            SeasonFixtures.activationOf("x1", "a1", SeasonAction.START, "2026-11-01"),
            SeasonFixtures.activationOf("x2", "a1", SeasonAction.END, "2027-03-01"),
            SeasonFixtures.activationOf("x3", "a1", SeasonAction.START, "2027-06-01"),
        )
        val manual = SeasonFixtures.seasonOf(SeasonMode.MANUAL, activations = rows)
        val ordered = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2027-06-10"), ZoneOffset.UTC, manual)
        val shuffled = ScheduleRecompute.rebuild(
            schedule, emptyList(), emptyList(), emptyList(), on("2027-06-10"), ZoneOffset.UTC, manual.copy(activations = rows.reversed()),
        )
        assertEquals(ordered, shuffled)
        assertEquals("2027-06-06", ordered.actionableDueOn, "the recorded START plus five days")
        assertEquals(0L, ordered.computedAt, "no clock inside")
    }
}
