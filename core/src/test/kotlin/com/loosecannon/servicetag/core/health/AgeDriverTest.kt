package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.HealthFixtures.PACK_BATTERY_REPLACED
import com.loosecannon.servicetag.core.testing.HealthFixtures.PACK_LOAD_TEST
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec §6.2 and §6.4: AGE counts from the latest qualifying REPLACEMENT (inv. 113). */
class AgeDriverTest {

    private val ups = HealthFixtures.upsAsset()
    private val upsBattery = HealthFixtures.upsBatteryAge()
    private val upsEvents = listOf(HealthFixtures.upsBatteryReplaced(), HealthFixtures.upsSelfTest())

    private val pack = HealthFixtures.packAsset()
    private val packBattery = HealthFixtures.packBatteryAge()
    private val packProfiles = setOf(PACK_BATTERY_REPLACED, PACK_LOAD_TEST)
    private val packEvents = listOf(HealthFixtures.packBatteryReplaced(), HealthFixtures.packLoadTest())

    private fun upsOn(date: String, events: List<AssetEvent> = upsEvents) =
        HealthFixtures.healthOn(date, ups, listOf(upsBattery), events = events).subjects.single()

    private fun packOn(
        date: String,
        events: List<AssetEvent> = packEvents,
        profiles: Set<ProfileId> = packProfiles,
    ) = HealthFixtures.healthOn(date, pack, listOf(packBattery), events = events, profileExists = { it in profiles }).subjects.single()

    @Test
    fun theLatestQualifyingReplacementIsTheBaseline() {
        val f1 = upsOn("2026-09-24")
        assertEquals(SubjectValue.Scored(18, HealthBand.CRITICAL, 1576), f1.value, "F1: any REPLACEMENT, 2022-06-01")
        assertEquals(listOf(DriverLine.Replaced(LocalDate.parse("2022-06-01"), 1576)), f1.lines)

        val f2 = packOn("2026-09-24")
        assertEquals(SubjectValue.Scored(47, HealthBand.WARNING, 1233), f2.value, "F2: the \"Battery replaced\" REPLACEMENT, 2023-05-10")
        assertEquals(listOf(DriverLine.Replaced(LocalDate.parse("2023-05-10"), 1233)), f2.lines)
        assertEquals(HealthBand.NOMINAL, (packOn("2026-05-08").value as SubjectValue.Scored).band)
        assertEquals(HealthBand.WARNING, (packOn("2026-05-09").value as SubjectValue.Scored).band, "WARNING from 9 May 2026")
        assertEquals(HealthBand.WARNING, (packOn("2027-05-08").value as SubjectValue.Scored).band)
        assertEquals(HealthBand.CRITICAL, (packOn("2027-05-09").value as SubjectValue.Scored).band, "CRITICAL from 9 May 2027")

        // The latest by EventChronology, not the first found: an older replacement changes nothing,
        // and a later one logged with another REPLACEMENT quick action does not qualify for F2.
        val older = HealthFixtures.eventOf("e-pack-2021", "pack", EventKind.REPLACEMENT, "Battery replaced", "2021-01-15", PACK_BATTERY_REPLACED.value)
        val otherAction = HealthFixtures.eventOf("e-pack-cells", "pack", EventKind.REPLACEMENT, "Cells swapped", "2026-02-01", "p-pack-cells")
        assertEquals(f2.value, packOn("2026-09-24", packEvents + older + otherAction, packProfiles + ProfileId("p-pack-cells")).value)
        val sameDayLater = HealthFixtures.eventOf(
            "e-pack-same-day", "pack", EventKind.REPLACEMENT, "Battery replaced", "2023-05-10", PACK_BATTERY_REPLACED.value,
            createdAt = HealthFixtures.packBatteryReplaced().createdAt + 1,
        )
        assertEquals(1233, (packOn("2026-09-24", packEvents + sameDayLater).value as SubjectValue.Scored).trackedDays)
    }

    /** The C.2 split: an INSPECTION load test is never a baseline, with or without a quick action. */
    @Test
    fun aLoadTestChangesNothing() {
        assertEquals(upsOn("2026-09-24", listOf(HealthFixtures.upsBatteryReplaced())).value, upsOn("2026-09-24").value, "F1's self-test")
        val laterLoadTest = HealthFixtures.packLoadTest(on = "2026-09-23")
        assertEquals(packOn("2026-09-24").value, packOn("2026-09-24", packEvents + laterLoadTest).value, "F2's load test")
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.NO_REPLACEMENT), upsOn("2026-09-24", listOf(HealthFixtures.upsSelfTest())).value)
        assertEquals(listOf(DriverLine.NoReplacement), upsOn("2026-09-24", listOf(HealthFixtures.upsSelfTest())).lines)
    }

    /** #61 AC 12: a new REPLACEMENT resets the value and erases nothing; deleting it restores the previous one. */
    @Test
    fun aNewReplacementResetsAndDeletingItRestores() {
        val replaced = HealthFixtures.eventOf("e-ups-2026", "ups", EventKind.REPLACEMENT, "Battery replaced", "2026-09-27")
        val after = upsOn("2026-09-27", upsEvents + replaced)
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), after.value, "F1: a REPLACEMENT on 27 Sep → 100")
        assertEquals(listOf(DriverLine.Replaced(LocalDate.parse("2026-09-27"), 0)), after.lines)
        assertEquals(SubjectValue.Scored(17, HealthBand.CRITICAL, 1579), upsOn("2026-09-27").value, "deleting it restores 2022-06-01")
    }

    /** Controller ruling: a baseline dated after today reads as age 0 — a score of 100 — never a negative age. */
    @Test
    fun aBaselineDatedAfterTodayReadsAsAgeZero() {
        val ahead = HealthFixtures.eventOf("e-ups-ahead", "ups", EventKind.REPLACEMENT, "Battery replaced", "2026-09-30")
        val read = upsOn("2026-09-24", upsEvents + ahead)
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), read.value)
        assertEquals(listOf(DriverLine.Replaced(LocalDate.parse("2026-09-30"), 0)), read.lines, "dated as logged, aged 0")
    }

    /** Inv. 113: a deleted baseline quick action leaves NOT TRACKED — never "any replacement". */
    @Test
    fun aMissingBaselineProfileIsNotTrackedNeverAnyReplacement() {
        val gone = packOn("2026-09-24", profiles = setOf(PACK_LOAD_TEST))
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.PROFILE_REMOVED), gone.value)
        assertEquals(listOf(DriverLine.ProfileRemoved), gone.lines)
        // Even with a qualifying-looking REPLACEMENT under another quick action on the asset.
        val otherAction = HealthFixtures.eventOf("e-pack-cells", "pack", EventKind.REPLACEMENT, "Cells swapped", "2026-02-01", "p-pack-cells")
        assertEquals(
            SubjectValue.NotTracked(NotTrackedReason.PROFILE_REMOVED),
            packOn("2026-09-24", packEvents + otherAction, setOf(PACK_LOAD_TEST, ProfileId("p-pack-cells"))).value,
        )
    }
}
