package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonActivationOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonBindingOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #125 (U-C6, h9; #16's carry rule): a schedule's own season through the merge. Its rows merge as the asset's
 * activations do — insert or `IDENTICAL` by id, a different row under a taken id a conflict, and a row whose schedule
 * is nowhere a missing owner; the schedule compare includes `seasonSource` at format 21 and leaves it (and the stamp the
 * link moved) out against an older archive, so this phone's own format-20 export re-merges `IDENTICAL`; and an inserted
 * `OWN` schedule lands with its rows and **no binding**, stranded. The names are fictional.
 */
class ScheduleSeasonMergeTest {

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)

    /** The schedule as a format-20 export left it: on its asset's season, stamped on 2025-09-01. */
    private val opening: MaintenanceSchedule = scheduleOf(
        id = "s-open", assetId = "heater", title = "Example opening service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-10-01", createdOn = "2025-09-01",
    )

    /** The same row after the owner linked it on 2025-10-15: the link moved `updatedAt` and the source only. */
    private val linked: MaintenanceSchedule =
        opening.copy(seasonSource = ScheduleSeasonSource.OWN, updatedAt = dayMillis("2025-10-15"))

    private val rows: List<ScheduleSeasonActivation> = listOf(
        scheduleSeasonActivationOf("r-start", "s-open", SeasonAction.START, "2025-10-20", createdAt = 2_000L),
        scheduleSeasonActivationOf("r-end", "s-open", SeasonAction.END, "2026-04-01", createdAt = 3_000L),
    )

    private fun archive(
        formatVersion: Int = BackupCodec.FORMAT_VERSION,
        schedules: List<MaintenanceSchedule> = listOf(linked),
        seasonRows: List<ScheduleSeasonActivation> = rows,
    ): Backup = backupOf(
        BackupData(
            listOf(heater.toDto()), emptyList(), emptyList(),
            maintenanceSchedules = schedules.map { it.toDto() },
            scheduleSeasonActivations = seasonRows.map { it.toDto() },
        ),
        formatVersion,
    )

    private fun here(
        schedules: List<MaintenanceSchedule> = emptyList(),
        seasonRows: List<ScheduleSeasonActivation> = emptyList(),
    ) = MergeSnapshot(
        assets = listOf(heater), schedules = schedules, scheduleSeasonActivations = seasonRows,
        attachmentStoreConfigured = true,
    )

    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    @Test
    fun theRowsInsertByIdAndAReMergeIsIdentical() {
        val first = mergePlanOf(archive(), here())

        assertTrue(first.applicable)
        assertEquals(MergeVerdict.INSERT, first.decision(MergeTable.SCHEDULES, "s-open").verdict)
        assertEquals(MergeTally(2, 0, 0, 0), first.report().scheduleSeasonActivations)
        assertEquals(rows.toSet(), first.writes.scheduleSeasonActivations.toSet())
        assertEquals(listOf(linked), first.writes.schedules)

        val again = mergePlanOf(archive(), here(listOf(linked), rows))

        assertTrue(again.applicable)
        assertEquals(MergeVerdict.IDENTICAL, again.decision(MergeTable.SCHEDULES, "s-open").verdict)
        assertEquals(MergeTally(0, 2, 0, 0), again.report().scheduleSeasonActivations)
        assertEquals(emptyList(), again.writes.scheduleSeasonActivations)
        // The table is decided after the schedules, so its decisions follow theirs.
        assertTrue(MergeTable.SCHEDULE_SEASON_ACTIVATIONS.ordinal > MergeTable.SCHEDULES.ordinal)
    }

    @Test
    fun aDifferentRowUnderATakenIdConflictsAndARowWithNoScheduleIsAMissingOwner() {
        val moved = rows.first().copy(occurredOn = "2025-10-21")
        val conflicted = mergePlanOf(archive(), here(listOf(linked), listOf(moved, rows.last())))

        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULE_SEASON_ACTIVATIONS, "r-start", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS,
                "r-start",
            ),
            conflicted.decision(MergeTable.SCHEDULE_SEASON_ACTIVATIONS, "r-start"),
        )
        assertEquals(MergeWrites(), conflicted.writes)

        // a row whose schedule is neither here nor accepted by this plan has no owner to land on
        val orphaned = mergePlanOf(archive(schedules = emptyList()), here())
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULE_SEASON_ACTIVATIONS, "r-end", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE,
                "s-open",
            ),
            orphaned.decision(MergeTable.SCHEDULE_SEASON_ACTIVATIONS, "r-end"),
        )
    }

    /**
     * Hazard Z4's other half: this phone's own export from before the link re-merges as a conflict. A format-20 archive
     * cannot speak about a schedule's own season, so the local row compares without the source and the stamp the link
     * moved; every other field still counts.
     */
    @Test
    fun thisPhonesFormat20ExportReMergesIdenticalAgainstALocalOwnSchedule() {
        val old = archive(formatVersion = 20, schedules = listOf(opening), seasonRows = emptyList())

        assertEquals(
            MergeVerdict.IDENTICAL,
            mergePlanOf(old, here(listOf(linked), rows)).decision(MergeTable.SCHEDULES, "s-open").verdict,
        )
        assertEquals(
            MergeDecision(MergeTable.SCHEDULES, "s-open", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s-open"),
            mergePlanOf(old, here(listOf(linked.copy(title = "Example opening service, renamed"))))
                .decision(MergeTable.SCHEDULES, "s-open"),
        )
        // At format 21 the source is compared: the same pair is a conflict.
        assertEquals(
            MergeDecision(MergeTable.SCHEDULES, "s-open", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s-open"),
            mergePlanOf(archive(schedules = listOf(opening.copy(updatedAt = linked.updatedAt)), seasonRows = emptyList()), here(listOf(linked)))
                .decision(MergeTable.SCHEDULES, "s-open"),
        )
    }

    /** #16's carry rule: the binding never travels, so an inserted `OWN` schedule lands stranded, its rows with it. */
    @Test
    fun anInsertedOwnScheduleArrivesWithItsRowsAndNoBinding() = runTest {
        val donor = BackupInstall("set-donor")
        donor.assets.upsert(heater)
        donor.schedules.upsert(linked)
        rows.forEach { donor.scheduleSeasonActivations.insert(it) }
        donor.scheduleSeasonBindings.insert(scheduleSeasonBindingOf("s-open"))
        val bytes = donor.export.run().data

        val target = BackupInstall("set-target")
        val plan = target.build.run(bytes)
        target.apply.run(plan)

        assertEquals(ScheduleSeasonSource.OWN, target.schedules.get(ScheduleId("s-open"))!!.seasonSource)
        assertEquals(rows.sortedBy { it.occurredOn }, target.scheduleSeasonActivations.forSchedule(ScheduleId("s-open")))
        assertEquals(emptyList(), target.scheduleSeasonBindings.all())
    }
}
