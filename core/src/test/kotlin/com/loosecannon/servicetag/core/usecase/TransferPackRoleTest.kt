package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.merge.ImportContext
import com.loosecannon.servicetag.core.merge.MergeSnapshot
import com.loosecannon.servicetag.core.merge.ReturnCarry
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.seasonReadiness
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.transfer.TransferInstall
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #114 (A3 rows 49, 49a, 49b; C20, A01, B, O-8, O-20): the Season Start role and the event marker through a Transfer
 * Pack and an ordinary merge. The seller's "Example Heater" (`MANUAL`) carries the role on "Example annual service"
 * from 2025-10-01; the names are fictional.
 */
class TransferPackRoleTest {

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)

    private val annual: MaintenanceSchedule = scheduleOf(
        id = "s-annual", assetId = "heater", title = "Example annual service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-10-01", createdOn = "2025-09-01",
    ).copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2025-10-01")

    private val filter: MaintenanceSchedule = scheduleOf(
        id = "s-filter", assetId = "heater", title = "Example filter check", timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-10-01", createdOn = "2025-09-01",
    )

    /** A completion of the role schedule, written at midnight UTC of its own day. */
    private fun service(id: String, on: String, occurrenceOn: String = on) =
        completionOf(id, on, occurrenceOn, assetId = "heater", scheduleId = "s-annual")

    private fun day(iso: String): LocalDate = LocalDate.parse(iso)

    private fun backup(vararg events: AssetEvent, schedules: List<MaintenanceSchedule> = listOf(annual)): Backup =
        backupOf(
            BackupData(
                assets = listOf(heater.toDto()),
                nfcTags = emptyList(),
                externalLinks = emptyList(),
                maintenanceSchedules = schedules.map { it.toDto() },
                assetEvents = events.map { it.toDto() },
            ),
        )

    private val empty = MergeSnapshot(attachmentStoreConfigured = true)

    /** The seller: the heater, its two schedules and [events], straight into its stores. */
    private suspend fun seller(vararg events: AssetEvent): TransferInstall = TransferInstall("set-seller").also { s ->
        s.raw.assets.upsert(heater)
        s.raw.schedules.upsert(annual)
        s.raw.schedules.upsert(filter)
        events.forEach { s.raw.events.upsert(it) }
    }

    private suspend fun TransferInstall.readinessOn(today: String): Readiness = seasonReadiness(
        raw.assets.get(AssetId("heater"))!!, raw.assets.all(), raw.schedules.all(), raw.groups.all(), raw.events.all(),
        raw.activations.all(), day(today), ZoneOffset.UTC,
    )

    private suspend fun TransferInstall.annualHere(): MaintenanceSchedule = raw.schedules.get(ScheduleId("s-annual"))!!

    // --- row 49: the planner (scenario 8, scenario 7) -----------------------------------------------

    /** Scenario 8: the recipient's role counts from its own import day, never the predecessor's boundary. */
    @Test
    fun anInsertedRoleScheduleIsRecommissionedOnTheImportDay() {
        val plan = mergePlanOf(
            backup(schedules = listOf(annual, filter)), empty, context = ImportContext(day("2026-10-20")),
        )

        val written = plan.writes.schedules.associateBy { it.id.value }
        assertEquals(annual.copy(roleSinceOn = "2026-10-20"), written.getValue("s-annual"))
        assertEquals(filter, written.getValue("s-filter"))
        assertEquals(day("2026-10-20"), plan.context?.importDay)
    }

    @Test
    fun aReturnCarryKeepsTheLocalRoleFields() {
        val cleared = filter.copy(id = ScheduleId("s-cleared"))
        val carry = ReturnCarry(
            roles = mapOf(
                ScheduleId("s-annual") to (ScheduleRole.SEASON_START to "2025-09-15"),
                // The pack says SEASON_START; this phone had no role on it: the local NONE wins.
                ScheduleId("s-cleared") to (ScheduleRole.NONE to null),
            ),
            eventImported = mapOf(EventId("e-own") to false),
        )
        val packRole = cleared.copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-01-01")

        val plan = mergePlanOf(
            backup(
                service("e-own", "2025-10-05").copy(imported = true), service("e-borrowed", "2026-01-05"),
                schedules = listOf(annual.copy(roleSinceOn = "2026-01-01"), packRole),
            ),
            empty, context = ImportContext(day("2026-10-20")), carry = carry,
        )

        val schedules = plan.writes.schedules.associateBy { it.id.value }
        assertEquals("2025-09-15", schedules.getValue("s-annual").roleSinceOn)
        assertEquals(ScheduleRole.NONE, schedules.getValue("s-cleared").role)
        assertEquals(null, schedules.getValue("s-cleared").roleSinceOn)
        val events = plan.writes.events.associate { it.id.value to it.imported }
        assertEquals(mapOf("e-own" to false, "e-borrowed" to true), events)
    }

    /** Scenario 7 (O-20): an ordinary merge has no context, and writes the archive's boundary and markers verbatim. */
    @Test
    fun aBackupMergeKeepsTheArchivesBoundary() {
        val plan = mergePlanOf(
            backup(service("e-own", "2025-10-05"), service("e-pack", "2026-01-05").copy(imported = true)), empty,
        )

        assertEquals(listOf(annual), plan.writes.schedules)
        assertEquals(mapOf("e-own" to false, "e-pack" to true), plan.writes.events.associate { it.id.value to it.imported })
        assertEquals(null, plan.context)
    }

    // --- row 49a: one context, preview to apply (A01) ------------------------------------------------

    @Test
    fun previewBeforeMidnightImportAfterWritesThePreviewsDay() = runTest {
        val pack = seller().pack("pack-1", "heater")
        val buyer = TransferInstall("set-buyer")
        buyer.today = day("2026-10-20")
        val ready = buyer.ready(pack.bytes)

        buyer.today = day("2026-10-21")
        val result = buyer.importer.import(ready) { ByteArrayInputStream(pack.bytes) }

        assertIs<TransferImportResult.Imported>(result)
        assertEquals(annual.copy(roleSinceOn = "2026-10-20"), buyer.annualHere())
    }

    @Test
    fun reImportIsIdentical() = runTest {
        val pack = seller(service("e-sold", "2025-10-05")).pack("pack-1", "heater")
        val buyer = TransferInstall("set-buyer")
        buyer.today = day("2026-10-20")
        assertIs<TransferImportResult.Imported>(buyer.import(pack.bytes))

        buyer.today = day("2026-11-02")
        val again = buyer.ready(pack.bytes)

        assertEquals(TransferImportOutcome.ALREADY_HERE, again.outcome)
        assertEquals("2026-10-20", buyer.annualHere().roleSinceOn)
        assertTrue(buyer.raw.events.get(EventId("e-sold"))!!.imported)
    }

    /** A: the asset comes home; its role is this phone's again, not the import day. */
    @Test
    fun aReturningPackKeepsTheLocalRoleFields() = runTest {
        val home = seller()
        val out = home.pack("pack-out", "heater")
        home.mark(out)
        val borrower = TransferInstall("set-borrower")
        borrower.today = day("2026-10-20")
        assertIs<TransferImportResult.Imported>(borrower.import(out.bytes))
        assertEquals("2026-10-20", borrower.annualHere().roleSinceOn)
        val back = borrower.pack("pack-back", "heater")

        home.today = day("2026-11-02")
        assertIs<TransferImportResult.Imported>(home.import(back.bytes))

        assertEquals(annual, home.annualHere())
    }

    @Test
    fun anOrdinaryBackupMergeKeepsTheArchivesValues() = runTest {
        val install = TransferInstall("set-here")
        install.today = day("2026-10-20")
        val archive = backup(service("e-own", "2025-10-05"), service("e-pack", "2026-01-05").copy(imported = true))

        install.apply.run(install.build.run(archive, emptyMap(), emptySet()))

        assertEquals(annual, install.annualHere())
        assertEquals(false, install.raw.events.get(EventId("e-own"))!!.imported)
        assertEquals(true, install.raw.events.get(EventId("e-pack"))!!.imported)
    }

    /** The fingerprint names a plan built with another import day, with none, or with another carry. */
    @Test
    fun aPlanWithAnotherContextOrCarryIsStale() {
        val archive = backup(service("e-own", "2025-10-05"))
        val onTheTwentieth = mergePlanOf(archive, empty, context = ImportContext(day("2026-10-20")))
        val onTheTwentyFirst = mergePlanOf(archive, empty, context = ImportContext(day("2026-10-21")))
        val noContext = mergePlanOf(archive, empty)

        assertEquals(onTheTwentieth.decisions, onTheTwentyFirst.decisions)
        assertNotEquals(onTheTwentieth.fingerprint, onTheTwentyFirst.fingerprint)
        assertNotEquals(onTheTwentieth.fingerprint, noContext.fingerprint)
        assertEquals(onTheTwentieth.fingerprint, mergePlanOf(archive, empty, context = ImportContext(day("2026-10-20"))).fingerprint)

        fun carried(since: String) = ReturnCarry(mapOf(ScheduleId("s-annual") to (ScheduleRole.SEASON_START to since)), emptyMap())
        val context = ImportContext(day("2026-10-20"))
        val first = mergePlanOf(archive, empty, context = context, carry = carried("2025-10-01"))
        val second = mergePlanOf(archive, empty, context = context, carry = carried("2025-09-01"))
        assertEquals(first.decisions, second.decisions)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.fingerprint, onTheTwentieth.fingerprint)
    }

    // --- row 49b: the marker (B, O-8, O-20) --------------------------------------------------------

    /** The seller's service, dated the import day, comes in imported and never counts here. */
    @Test
    fun aPackInsertedEventIsImported() = runTest {
        val pack = seller(service("e-sold", "2026-10-20")).pack("pack-1", "heater")
        val buyer = TransferInstall("set-buyer")
        buyer.today = day("2026-10-20")

        assertIs<TransferImportResult.Imported>(buyer.import(pack.bytes))

        assertTrue(buyer.raw.events.get(EventId("e-sold"))!!.imported)
        val readiness = assertIs<Readiness.NotReady>(buyer.readinessOn("2026-10-20"))
        assertEquals(NotReadyReason.INHERITED_SERVICE_ONLY, readiness.reason)
    }

    @Test
    fun aFutureDatedPackServiceNeverCounts() = runTest {
        val pack = seller(service("e-ahead", "2026-10-25")).pack("pack-1", "heater")
        val buyer = TransferInstall("set-buyer")
        buyer.today = day("2026-10-20")
        assertIs<TransferImportResult.Imported>(buyer.import(pack.bytes))

        for (today in listOf("2026-10-20", "2026-10-25", "2026-10-30", "2027-03-01")) {
            assertIs<Readiness.NotReady>(buyer.readinessOn(today), today)
        }
        assertTrue(buyer.raw.events.get(EventId("e-ahead"))!!.imported)
    }

    /** O-20: the owner's own archive merged back marks nothing; its service counts as recorded. */
    @Test
    fun aBackupMergeOfTheOwnersArchiveMarksNothing() = runTest {
        val install = TransferInstall("set-owner")
        val archive = backup(service("e-own", "2025-10-05"))

        install.apply.run(install.build.run(archive, emptyMap(), emptySet()))

        assertEquals(false, install.raw.events.get(EventId("e-own"))!!.imported)
        assertEquals(Readiness.Ready::class, install.readinessOn("2025-10-10")::class)
        assertEquals(day("2025-10-05"), (install.readinessOn("2025-10-10") as Readiness.Ready).serviceOn)
    }

    @Test
    fun aReplaceRestoreKeepsEachMarkerVerbatim() = runTest {
        val install = BackupInstall("set-owner")
        val own = service("e-own", "2025-10-05")
        val bought = service("e-bought", "2025-11-05").copy(imported = true)
        val archive = archiveOf(backup(own, bought).data)

        install.replace.run(archive)

        assertEquals(own, install.events.get(EventId("e-own")))
        assertEquals(bought, install.events.get(EventId("e-bought")))
        assertEquals(annual, install.schedules.get(ScheduleId("s-annual")))
    }
}
