package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.readingOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The notification body agrees with its status word (the principle of B02's review carry-forward,
 * routed to B07 as review O3): the meter sentence is chosen only while the **actionable** date —
 * the one the status word is measured against — has not come due.
 *
 * A snowblower (spec F4: CALENDAR 15 Nov to 31 Mar, the winter break inside it, PRE_SERVICE with a
 * 14-day margin) is pulled to 1 Nov while its canonical date is 20 Dec, and its hour meter has
 * crossed too. On 10 Nov it is OVERDUE by the date, so its body is the date sentence. A CONTINUOUS
 * mower with a crossed meter and a date still ahead is the control: its body is the meter sentence.
 */
class ScheduleDeliveryFactsTest {

    private val graph = FakeGraph().also { it.today = LocalDate.parse("2026-11-10") }

    @After fun tearDown() = graph.close()

    private fun facts() = ScheduleDeliveryFacts(
        graph.schedules, graph.scheduleStateReader, graph.assets, graph.groups, graph.definitions, graph.todayPort,
    )

    @Test fun aRowPulledBeforeItsSeasonNamesItsDateNotItsMeter() = runTest {
        graph.assets.upsert(
            assetRow(
                "snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31",
                breakStart = "12-01", breakEnd = "02-28",
            ),
        )
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        graph.definitions.upsert(meterDefinitionOf("d-snow", assetId = "snow"))
        graph.definitions.upsert(meterDefinitionOf("d-mow", assetId = "mow"))
        graph.events.upsert(readingOf("r-snow", assetId = "snow", definitionId = "d-snow", value = 60.0, occurredOn = "2026-11-05"))
        graph.events.upsert(readingOf("r-mow", assetId = "mow", definitionId = "d-mow", value = 60.0, occurredOn = "2026-11-05"))
        graph.schedules.upsert(
            scheduleOf(
                "s-snow", assetId = "snow", title = "Engine oil service", timeInterval = 2, timeUnit = RecurrenceUnit.YEAR,
                anchorOn = "2026-12-20", createdOn = "2026-06-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
                meterDefinitionId = "d-snow", meterInterval = 50.0, anchorMeter = 0.0,
            ),
        )
        graph.schedules.upsert(
            scheduleOf(
                "s-mow", assetId = "mow", title = "Engine oil service", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
                anchorOn = "2027-01-01", createdOn = "2026-06-01",
                meterDefinitionId = "d-mow", meterInterval = 50.0, anchorMeter = 0.0,
            ),
        )

        val snow = facts().factsFor(SubjectKey.Schedule(ScheduleId("s-snow")))!!
        val snowState = graph.scheduleStateReader.stateOf(ScheduleId("s-snow"))!!
        assertEquals("pulled to 1 Nov", "2026-11-01", snowState.actionableDueOn)
        assertEquals("while its canonical date is 20 Dec", "2026-12-20", snowState.effectiveDueOn)
        assertEquals(DueStatus.OVERDUE, snow.status)
        assertNull("the date came due, so the date is the sentence", snow.meter)

        val mow = facts().factsFor(SubjectKey.Schedule(ScheduleId("s-mow")))!!
        assertEquals(MeterReading(dueAt = "50", now = "60", unit = "h"), mow.meter)
    }
}
