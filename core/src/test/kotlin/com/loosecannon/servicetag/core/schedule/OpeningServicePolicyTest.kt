package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.SeasonFixtures.activationOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #124 (C40): `IN_SERVICE_AFTER_OPENING_SERVICE` in the pure engine. In season it waits with no date
 * until its asset's opening service is known (`openingServiceOn`, C9's `serviceOn`, fed by N2a), and
 * then counts from the later of that day and the cycle's start, plus "Days after"; out of season it is
 * `IN_SERVICE_AT_START` with the same offset, case for case; the break and quiet are the in-service
 * policies' own. Rows 134–137.
 */
class OpeningServicePolicyTest {

    private fun on(date: String) = LocalDate.parse(date)

    /** A heating season, 1 Oct – 30 Apr, wrapping the year end. On 15 Nov 2026 its cycle began 1 Oct 2026. */
    private val calendar = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, "10-01", "04-30"))

    /** The same season with a holiday break, 20 Dec – 5 Jan. */
    private val calendarWithBreak =
        SeasonContext.of(seasonOf(SeasonMode.CALENDAR, "10-01", "04-30", breakStart = "12-20", breakEnd = "01-05"))

    /** A MANUAL asset started on 5 Oct 2026 and still running. */
    private val manual = SeasonContext.of(
        seasonOf(SeasonMode.MANUAL, activations = listOf(activationOf("act-1", "tub", SeasonAction.START, "2026-10-05"))),
    )

    /** A MANUAL asset whose last season ended on 30 Apr 2026: out of season, no next START predicted. */
    private val manualEnded = SeasonContext.of(
        seasonOf(
            SeasonMode.MANUAL,
            activations = listOf(
                activationOf("act-1", "tub", SeasonAction.START, "2025-10-05"),
                activationOf("act-2", "tub", SeasonAction.END, "2026-04-30"),
            ),
        ),
    )

    private fun inputs(
        raw: String,
        served: String?,
        offset: Int? = 0,
        policy: ServicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE,
        postponed: String? = null,
    ) = PolicyInputs(
        policy = policy,
        offsetDays = offset,
        rawDueOn = on(raw),
        postponedDueOn = postponed?.let(::on),
        openedOn = on("2026-04-01"),
        openingServiceOn = served?.let(::on),
    )

    private fun evaluate(inputs: PolicyInputs, season: SeasonContext?, at: String = "2026-11-15") =
        ServicePolicyEngine.evaluate(inputs, season, on(at))

    // --- row 134: not ready means no date -------------------------------------------------------

    /** In season with no qualifying opening service: no date, DORMANT, waiting — on both seasonal modes. */
    @Test
    fun inSeasonWithoutAnOpeningServiceItWaitsWithNoDate() {
        for ((label, season) in listOf("CALENDAR" to calendar, "MANUAL" to manual)) {
            for (raw in listOf("2026-09-20", "2026-10-10", "2026-12-20")) {
                for (offset in listOf(0, 14)) {
                    val outcome = evaluate(inputs(raw, served = null, offset = offset), season)
                    val what = "$label, R = $raw, offset $offset"
                    assertNull(outcome.actionableOn, "$what: not ready means no date, never the season's start")
                    assertEquals(PolicyReason.AWAITING_OPENING_SERVICE, outcome.reason, what)
                    assertEquals(PolicyPhase.DORMANT, outcome.phase, "$what: held, so not counted and no reminder")
                    assertEquals(false, outcome.quiet, what)
                    assertNull(outcome.quietUntil, what)
                }
            }
        }
    }

    // --- row 135: early from the season's start, late from its own day --------------------------

    /**
     * A service done before the cycle began counts from the cycle's start (`SEASON_START` when that
     * moved the date); one done after counts from its own day (`OPENING_SERVICE`); "Days after" is
     * added either way; a later raw date is never dragged back.
     */
    @Test
    fun itCountsFromTheLaterOfTheServiceAndTheCyclesStart() {
        data class Case(
            val season: SeasonContext,
            val raw: String,
            val served: String,
            val offset: Int,
            val expected: String,
            val reason: PolicyReason,
        )
        val cases = listOf(
            // Done early (20 Sep), before the 1 Oct start: the season's start, plus the offset.
            Case(calendar, "2026-09-25", "2026-09-20", 0, "2026-10-01", PolicyReason.SEASON_START),
            Case(calendar, "2026-09-25", "2026-09-20", 14, "2026-10-15", PolicyReason.SEASON_START),
            // Done late (20 Oct): its own day, plus the offset.
            Case(calendar, "2026-10-10", "2026-10-20", 0, "2026-10-20", PolicyReason.OPENING_SERVICE),
            Case(calendar, "2026-10-10", "2026-10-20", 14, "2026-11-03", PolicyReason.OPENING_SERVICE),
            // A raw date already later than the opening is kept (inv. 97), early or late.
            Case(calendar, "2026-12-01", "2026-10-20", 14, "2026-12-01", PolicyReason.NONE),
            Case(calendar, "2026-10-08", "2026-09-20", 0, "2026-10-08", PolicyReason.NONE),
            // Equal to the opening is not a move.
            Case(calendar, "2026-11-03", "2026-10-20", 14, "2026-11-03", PolicyReason.NONE),
            // MANUAL: the START on 5 Oct is the cycle's start.
            Case(manual, "2026-09-25", "2026-10-01", 0, "2026-10-05", PolicyReason.SEASON_START),
            Case(manual, "2026-09-25", "2026-10-01", 14, "2026-10-19", PolicyReason.SEASON_START),
            Case(manual, "2026-10-06", "2026-10-12", 0, "2026-10-12", PolicyReason.OPENING_SERVICE),
            Case(manual, "2026-10-06", "2026-10-12", 14, "2026-10-26", PolicyReason.OPENING_SERVICE),
            Case(manual, "2026-12-01", "2026-10-12", 14, "2026-12-01", PolicyReason.NONE),
        )
        for (case in cases) {
            val outcome = evaluate(inputs(case.raw, case.served, case.offset), case.season)
            val what = "R = ${case.raw}, served ${case.served}, offset ${case.offset}"
            assertEquals(on(case.expected), outcome.actionableOn, what)
            assertEquals(case.reason, outcome.reason, what)
            assertEquals(PolicyPhase.ACTIVE, outcome.phase, what)
        }

        // A postponement is the date the policy moves, as for the other in-service policies.
        val postponed = evaluate(inputs("2026-10-10", "2026-10-20", 0, postponed = "2026-11-20"), calendar)
        assertEquals(on("2026-11-20"), postponed.actionableOn)
        assertEquals(PolicyReason.NONE, postponed.reason)
    }

    // --- row 136: out of season as today -------------------------------------------------------

    /**
     * Out of season the value is `IN_SERVICE_AT_START` with the same offset, outcome for outcome —
     * a CALENDAR asset re-entering at its next start, a MANUAL one waiting for its START — whatever
     * `openingServiceOn` holds.
     */
    @Test
    fun outOfSeasonItIsAtStartCaseForCase() {
        val seasons = listOf(
            "CALENDAR" to calendar,
            "CALENDAR with a break" to calendarWithBreak,
            "MANUAL ended" to manualEnded,
        )
        val days = listOf("2026-05-01", "2026-06-15", "2026-09-30")
        val raws = listOf("2026-03-01", "2026-06-01", "2026-10-03", "2026-12-24", "2027-02-01")
        val served = listOf(null, "2026-04-10", "2026-06-01", "2026-10-20")
        var compared = 0
        for ((label, season) in seasons) {
            for (at in days) {
                for (raw in raws) {
                    for (offset in listOf(0, 14, 90)) {
                        val atStart = evaluate(inputs(raw, null, offset, ServicePolicy.IN_SERVICE_AT_START), season, at)
                        for (day in served) {
                            val opening = evaluate(inputs(raw, day, offset), season, at)
                            assertEquals(atStart, opening, "$label on $at, R = $raw, offset $offset, served $day")
                            compared++
                        }
                    }
                }
            }
        }
        assertEquals(3 * 3 * 5 * 3 * 4, compared)

        // The two shapes the comparison covers, stated once: a CALENDAR date re-enters at the next
        // start, and a MANUAL asset waits for a START that is never predicted.
        val reentered = evaluate(inputs("2026-06-01", "2026-10-20", 14), calendar, "2026-06-15")
        assertEquals(on("2026-10-15"), reentered.actionableOn)
        assertEquals(PolicyReason.SEASON_START, reentered.reason)
        assertEquals(PolicyPhase.DORMANT, reentered.phase)
        val waiting = evaluate(inputs("2026-06-01", "2026-04-10", 0), manualEnded, "2026-06-15")
        assertNull(waiting.actionableOn)
        assertEquals(PolicyReason.AWAITING_START, waiting.reason)
        assertEquals(PolicyPhase.DORMANT, waiting.phase)
    }

    // --- row 137: the break, quiet, and where it does not apply ---------------------------------

    /** A date the opening moves into the break goes to the first allowed day after it (AFTER_BREAK). */
    @Test
    fun aMovedDateInsideTheBreakGoesToTheFirstAllowedDay() {
        val moved = evaluate(inputs("2026-11-01", "2026-12-10", 14), calendarWithBreak, "2026-12-15")
        assertEquals(on("2027-01-06"), moved.actionableOn, "10 Dec + 14 = 24 Dec, inside 20 Dec – 5 Jan")
        assertEquals(PolicyReason.AFTER_BREAK, moved.reason)
        assertEquals(PolicyPhase.ACTIVE, moved.phase)

        // An unmoved date in the break goes there too, as the other in-service policies' do.
        val raw = evaluate(inputs("2026-12-28", "2026-10-20", 0), calendarWithBreak, "2026-12-15")
        assertEquals(on("2027-01-06"), raw.actionableOn)
        assertEquals(PolicyReason.AFTER_BREAK, raw.reason)
    }

    /** Quiet belongs to the day, as for the other in-service policies — while waiting too. */
    @Test
    fun quietIsTheInServicePoliciesOwn() {
        for (at in listOf("2026-12-20", "2026-12-31", "2027-01-05", "2026-12-19", "2027-01-06", "2026-07-01")) {
            val atStart = evaluate(inputs("2026-11-01", null, 0, ServicePolicy.IN_SERVICE_AT_START), calendarWithBreak, at)
            for (served in listOf(null, "2026-10-20")) {
                val opening = evaluate(inputs("2026-11-01", served, 0), calendarWithBreak, at)
                assertEquals(atStart.quiet, opening.quiet, "quiet on $at, served $served")
                assertEquals(atStart.quietUntil, opening.quietUntil, "quietUntil on $at, served $served")
            }
        }
        val inBreak = evaluate(inputs("2026-11-01", null, 0), calendarWithBreak, "2026-12-25")
        assertEquals(true, inBreak.quiet)
        assertEquals(on("2027-01-06"), inBreak.quietUntil)
    }

    /**
     * No season to count in — a YEAR_ROUND asset, no context at all (a group target or a gone asset),
     * or a CALENDAR asset whose window is missing (merge-only) — is POLICY_INAPPLICABLE on the raw
     * date, whatever `openingServiceOn` holds. Every command refuses these; only a merge stores them.
     */
    @Test
    fun withoutASeasonItIsInapplicable() {
        val yearRound = SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND))
        val windowless = SeasonContext.of(seasonOf(SeasonMode.CALENDAR))
        for ((label, season) in listOf("YEAR_ROUND" to yearRound, "no season" to null, "windowless CALENDAR" to windowless)) {
            for (served in listOf(null, "2026-10-20")) {
                val outcome = evaluate(inputs("2026-10-10", served, 14), season)
                val what = "$label, served $served"
                assertEquals(on("2026-10-10"), outcome.actionableOn, what)
                assertEquals(PolicyReason.POLICY_INAPPLICABLE, outcome.reason, what)
                assertEquals(PolicyPhase.ACTIVE, outcome.phase, what)
            }
        }
    }

    /** A meter-only schedule has no date to move: it is phase-only, as the other in-service policies are (O-7). */
    @Test
    fun aMeterOnlyScheduleIsPhaseOnly() {
        val meterOnly = inputs("2026-10-10", null).copy(rawDueOn = null)
        val inSeason = evaluate(meterOnly, calendar)
        assertNull(inSeason.actionableOn)
        assertEquals(PolicyReason.NONE, inSeason.reason)
        assertEquals(PolicyPhase.ACTIVE, inSeason.phase)
        assertEquals(PolicyPhase.DORMANT, evaluate(meterOnly, calendar, "2026-06-15").phase)
    }
}
