package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.seasonReadiness
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonActivationOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonBindingOf
import com.loosecannon.servicetag.core.transfer.TransferInstall
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #114 (A3 row 49c; A, B, I31): a returning asset keeps its local role, `roleSinceOn` and each of its own events'
 * marker, on two installations over the real `CreateTransferPack` → `MarkTransferredOut` → `ImportTransferPack` path
 * both ways; only rows new to it take the insert rules. The names are fictional.
 *
 * #125 (U-C6, h10): a pack carries a schedule's own season rows with their schedule and never its binding; a returning
 * asset's schedule keeps this phone's own source through the return carry, whatever the pack says.
 */
class TransferRoundTripRoleTest {

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)

    private val annual: MaintenanceSchedule = scheduleOf(
        id = "s-annual", assetId = "heater", title = "Example annual service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-10-01", createdOn = "2025-09-01",
    ).copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2025-10-01")

    /** A completion of the role schedule, written at midnight UTC of its own day. */
    private fun service(id: String, on: String) =
        completionOf(id, on, on, assetId = "heater", scheduleId = "s-annual")

    @Test
    fun aReturnKeepsItsOwnRoleAndItsOwnServicesMarker() = runTest {
        // A: the role from 2025-10-01 and A's own service on 2025-10-05.
        val a = TransferInstall("set-a")
        a.raw.assets.upsert(heater)
        a.raw.schedules.upsert(annual)
        a.raw.events.upsert(service("e-a", "2025-10-05"))
        val out = a.pack("pack-out", "heater")
        a.mark(out)

        // B imports it on 2025-11-01, records its own service on 2025-11-03, and packs it back.
        val b = TransferInstall("set-b")
        b.today = LocalDate.parse("2025-11-01")
        assertIs<TransferImportResult.Imported>(b.import(out.bytes))
        assertEquals("2025-11-01", b.raw.schedules.get(ScheduleId("s-annual"))!!.roleSinceOn)
        assertTrue(b.raw.events.get(EventId("e-a"))!!.imported)
        b.raw.events.upsert(service("e-b", "2025-11-03"))
        val back = b.pack("pack-back", "heater")
        b.mark(back)

        // A imports the return on a later day.
        a.today = LocalDate.parse("2025-12-01")
        assertIs<TransferImportResult.Imported>(a.import(back.bytes))

        val schedule = a.raw.schedules.get(ScheduleId("s-annual"))!!
        assertEquals(ScheduleRole.SEASON_START, schedule.role)
        assertEquals("2025-10-01", schedule.roleSinceOn)
        assertFalse(a.raw.events.get(EventId("e-a"))!!.imported)
        assertTrue(a.raw.events.get(EventId("e-b"))!!.imported)

        val readiness = seasonReadiness(
            a.raw.assets.get(AssetId("heater"))!!, a.raw.assets.all(), a.raw.schedules.all(), a.raw.groups.all(),
            a.raw.events.all(), a.raw.activations.all(), LocalDate.parse("2025-12-01"), ZoneOffset.UTC,
        )
        assertEquals(LocalDate.parse("2025-10-05"), assertIs<Readiness.Ready>(readiness).serviceOn)
    }

    @Test
    fun aPackCarriesAScheduleOwnSeasonRowsAndAReturnKeepsItsLocalSource() = runTest {
        val opening = scheduleOf(
            id = "s-open", assetId = "heater", title = "Example opening service", timeInterval = 1,
            timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-10-01", createdOn = "2025-09-01",
        ).copy(seasonSource = ScheduleSeasonSource.OWN)
        val start = scheduleSeasonActivationOf("r-start", "s-open", SeasonAction.START, "2025-10-20", createdAt = 2_000L)
        // A: the schedule follows its own switch, with one row and its device-local binding.
        val a = TransferInstall("set-a")
        a.raw.assets.upsert(heater)
        a.raw.schedules.upsert(opening)
        a.raw.scheduleSeasonActivations.insert(start)
        a.raw.scheduleSeasonBindings.insert(scheduleSeasonBindingOf("s-open"))
        val out = a.pack("pack-out", "heater")
        a.mark(out)

        // B imports it: the schedule on its own season with its row, and no binding (stranded).
        val b = TransferInstall("set-b")
        b.today = LocalDate.parse("2025-11-01")
        assertIs<TransferImportResult.Imported>(b.import(out.bytes))
        assertEquals(ScheduleSeasonSource.OWN, b.raw.schedules.get(ScheduleId("s-open"))!!.seasonSource)
        assertEquals(listOf(start), b.raw.scheduleSeasonActivations.forSchedule(ScheduleId("s-open")))
        assertEquals(emptyList(), b.raw.scheduleSeasonBindings.all())
        // B returns the schedule to its asset's season, and packs it back.
        b.raw.schedules.upsert(b.raw.schedules.get(ScheduleId("s-open"))!!.copy(seasonSource = ScheduleSeasonSource.ASSET))
        val back = b.pack("pack-back", "heater")
        b.mark(back)

        // A imports the return: its own source is kept, the rows come back with the pack.
        a.today = LocalDate.parse("2025-12-01")
        assertIs<TransferImportResult.Imported>(a.import(back.bytes))
        assertEquals(ScheduleSeasonSource.OWN, a.raw.schedules.get(ScheduleId("s-open"))!!.seasonSource)
        assertEquals(listOf(start), a.raw.scheduleSeasonActivations.forSchedule(ScheduleId("s-open")))
    }
}
