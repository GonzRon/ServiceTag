package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The why-line (spec §10.5, §10.7 S85–S91; master plan §13.3, plan decision 27): one line per
 * schedule row, the first that applies — DORMANT ▸ DEFERRED ▸ quiet ▸ the policy's reason ▸ none —
 * each ratified string verbatim, with its date in the app's display shape.
 *
 * Every row here is built with the facts the projection hands over; the function reads them and
 * derives nothing, so a plain row is the whole fixture.
 */
class WhyLinesTest {

    private fun line(item: DueItem): String? = whyLine(item, ::displayDate)

    @Test fun s85HeldUntilTheActionableDate() {
        val held = row(
            status = DueStatus.DEFERRED,
            policyReason = PolicyReason.AFTER_BREAK,
            actionableDueOn = "2027-03-01",
            effectiveDueOn = "2026-12-20",
        )
        assertEquals("Held until 1 Mar 2027 because of the maintenance break", line(held))
    }

    @Test fun s86MadeDueBeforeTheSeasonStarts() {
        assertEquals(
            "Made due before the season starts",
            line(row(status = DueStatus.OVERDUE, policyReason = PolicyReason.BEFORE_SEASON, seasonMode = SeasonMode.CALENDAR)),
        )
    }

    @Test fun s87MadeDueBeforeTheMaintenanceBreak() {
        assertEquals(
            "Made due before the maintenance break",
            line(row(status = DueStatus.DUE, policyReason = PolicyReason.BEFORE_BREAK)),
        )
    }

    @Test fun s88MovedFromTheDateItWasDue() {
        val moved = row(
            status = DueStatus.DUE,
            policyReason = PolicyReason.SEASON_START,
            seasonMode = SeasonMode.CALENDAR,
            effectiveDueOn = "2026-02-10",
            actionableDueOn = "2026-04-15",
        )
        // The date it was moved **from** is `P ?: R` — the effective date — never the actionable one.
        assertEquals("Moved from 10 Feb 2026 because the season was not running", line(moved))
    }

    @Test fun s89OutOfSeasonUntilTheNextStart() {
        val dormant = row(
            status = DueStatus.INACTIVE_SEASON,
            policyPhase = PolicyPhase.DORMANT,
            seasonMode = SeasonMode.CALENDAR,
            dormantUntil = "2027-04-15",
        )
        assertEquals("Out of season until 15 Apr 2027", line(dormant))
    }

    @Test fun s90OutOfSeasonUntilYouStartIt() {
        val manual = row(
            status = DueStatus.INACTIVE_SEASON,
            policyPhase = PolicyPhase.DORMANT,
            policyReason = PolicyReason.AWAITING_START,
            seasonMode = SeasonMode.MANUAL,
        )
        assertEquals("Out of season until you start it", line(manual))
    }

    @Test fun s91RemindersWaitForTheBreakToEnd() {
        // A quiet OVERDUE row keeps its place and says only this (quiet changes delivery, never
        // placement).
        assertEquals(
            "Reminders wait for the maintenance break to end",
            line(row(status = DueStatus.OVERDUE, policyReason = PolicyReason.NONE, quiet = true)),
        )
    }

    /**
     * Plan decision 27's precedence, one layer peeled at a time over a row that carries every fact
     * at once: DORMANT wins over DEFERRED, DEFERRED over quiet, quiet over the reason, and the
     * reason is drawn only when nothing above it applies.
     */
    @Test fun precedenceDormantDeferredQuietReason() {
        val everything = row(
            status = DueStatus.DEFERRED,
            policyPhase = PolicyPhase.DORMANT,
            policyReason = PolicyReason.BEFORE_SEASON,
            quiet = true,
            seasonMode = SeasonMode.CALENDAR,
            dormantUntil = "2027-04-15",
            actionableDueOn = "2027-03-01",
        )
        assertEquals("DORMANT first", "Out of season until 15 Apr 2027", line(everything))

        val deferredAndQuiet = everything.copy(policyPhase = PolicyPhase.ACTIVE, dormantUntil = null)
        assertEquals(
            "DEFERRED before quiet",
            "Held until 1 Mar 2027 because of the maintenance break",
            line(deferredAndQuiet),
        )

        val quietWithAReason = deferredAndQuiet.copy(status = DueStatus.OVERDUE)
        assertEquals("quiet before the reason", "Reminders wait for the maintenance break to end", line(quietWithAReason))

        assertEquals("the reason last", "Made due before the season starts", line(quietWithAReason.copy(quiet = false)))
    }

    /**
     * `POLICY_INAPPLICABLE` draws nothing on a row (the editor says S77), and neither does a row with
     * no reason at all, or one the break moved and that is no longer held.
     */
    @Test fun policyInapplicableDrawsNothing() {
        assertNull(line(row(status = DueStatus.OVERDUE, policyReason = PolicyReason.POLICY_INAPPLICABLE)))
        assertNull(line(row(status = DueStatus.OK, policyReason = PolicyReason.NONE)))
        assertNull(line(row(status = DueStatus.DUE, policyReason = PolicyReason.AFTER_BREAK, actionableDueOn = "2026-03-01")))
    }

    /** A group has no season: a group row is never drawn with a season line, whatever its phase. */
    @Test fun aGroupRowHasNoSeasonLine() {
        val group = row(status = DueStatus.INACTIVE_SEASON, policyPhase = PolicyPhase.DORMANT, seasonMode = null)
            .copy(target = ScheduleTarget.GroupTarget(GroupId("g1")))
        assertNull(line(group))
    }

    private companion object {
        @Suppress("LongParameterList")
        fun row(
            status: DueStatus,
            policyReason: PolicyReason = PolicyReason.NONE,
            policyPhase: PolicyPhase = PolicyPhase.ACTIVE,
            quiet: Boolean = false,
            seasonMode: SeasonMode? = SeasonMode.YEAR_ROUND,
            effectiveDueOn: String? = null,
            actionableDueOn: String? = null,
            dormantUntil: String? = null,
        ): DueItem = DueItem(
            scheduleId = ScheduleId("s1"),
            title = "Engine oil service",
            target = ScheduleTarget.AssetTarget(AssetId("gen")),
            assetName = "Generator",
            parentName = null,
            category = null,
            status = status,
            section = null,
            requiredSetEmpty = false,
            effectiveDueOn = effectiveDueOn?.let(LocalDate::parse),
            actionableDueOn = actionableDueOn?.let(LocalDate::parse),
            policyReason = policyReason,
            policyPhase = policyPhase,
            quiet = quiet,
            seasonMode = seasonMode,
            dormantUntil = dormantUntil?.let(LocalDate::parse),
            computedDueMeter = null,
            currentMeter = null,
            meterUnit = null,
            lastCompletedOn = null,
            completionMode = CompletionMode.QUICK,
            remindersEnabled = true,
            membersRequired = null,
            membersComplete = null,
            snoozedUntil = null,
            health = null,
            assetCondition = null,
            rank = 0,
        )
    }
}
