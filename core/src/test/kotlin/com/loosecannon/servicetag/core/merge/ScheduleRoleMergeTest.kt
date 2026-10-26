package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #114 (A3 rows 47, 48; C19, O-9, B): the merge's compare and its one-coverage claim for the Season Start role, and the
 * event marker's compare. The names are fictional.
 */
class ScheduleRoleMergeTest {

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)
    private val boiler: Asset = assetOf("boiler", "Example Boiler", SeasonMode.MANUAL)

    /** The schedule as a format-20 export left it: no role, stamped on 2025-09-01. */
    private val annual: MaintenanceSchedule = scheduleOf(
        id = "s-annual", assetId = "heater", title = "Example annual service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-10-01", createdOn = "2025-09-01",
    )

    /** The same row after the owner gave it the role on 2025-10-01: the role write moved `updatedAt` only. */
    private val annualWithRole: MaintenanceSchedule =
        annual.copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2025-10-01", updatedAt = dayMillis("2025-10-01"))

    private fun roleOn(id: String, assetId: String? = null, groupId: String? = null, since: String = "2025-10-01") =
        scheduleOf(id = id, assetId = assetId, groupId = groupId, title = "Example pre-season check $id")
            .copy(role = ScheduleRole.SEASON_START, roleSinceOn = since)

    private fun archive(
        formatVersion: Int,
        assets: List<Asset> = listOf(heater),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        events: List<AssetEvent> = emptyList(),
    ): Backup = backupOf(
        BackupData(
            assets.map { it.toDto() }, emptyList(), emptyList(),
            maintenanceGroups = groups.map { it.toDto() },
            maintenanceSchedules = schedules.map { it.toDto() },
            assetEvents = events.map { it.toDto() },
        ),
        formatVersion,
    )

    private fun here(
        assets: List<Asset> = listOf(heater),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        events: List<AssetEvent> = emptyList(),
    ) = MergeSnapshot(
        assets = assets, groups = groups, schedules = schedules, events = events, attachmentStoreConfigured = true,
    )

    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    // --- row 47: the compare -----------------------------------------------------------------

    /**
     * Hazard: this phone's own export from before the role re-merges as a conflict. A format-20 archive cannot speak
     * about roles, so the local row is compared without `role`, `roleSinceOn` and the stamp the role write moved.
     */
    @Test
    fun thisPhonesPreRoleExportReMergesIdentical() {
        val plan = mergePlanOf(archive(20, schedules = listOf(annual)), here(schedules = listOf(annualWithRole)))

        assertEquals(MergeVerdict.IDENTICAL, plan.decision(MergeTable.SCHEDULES, "s-annual").verdict)
        assertTrue(plan.applicable)
        // Every other field still counts: a retitle here is still a conflict against the old export.
        val retitled = annualWithRole.copy(title = "Example annual service, renamed")
        val conflicted = mergePlanOf(archive(20, schedules = listOf(annual)), here(schedules = listOf(retitled)))
        assertEquals(
            MergeDecision(MergeTable.SCHEDULES, "s-annual", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s-annual"),
            conflicted.decision(MergeTable.SCHEDULES, "s-annual"),
        )
    }

    @Test
    fun aFormat21ArchiveComparesTheRole() {
        fun verdictOf(incoming: MaintenanceSchedule, local: MaintenanceSchedule): MergeDecision =
            mergePlanOf(archive(21, schedules = listOf(incoming)), here(schedules = listOf(local)))
                .decision(MergeTable.SCHEDULES, "s-annual")

        assertEquals(MergeVerdict.IDENTICAL, verdictOf(annualWithRole, annualWithRole).verdict)
        for (incoming in listOf(annual, annualWithRole.copy(roleSinceOn = "2025-10-02"))) {
            assertEquals(
                MergeDecision(MergeTable.SCHEDULES, "s-annual", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s-annual"),
                verdictOf(incoming, annualWithRole),
            )
        }
        assertEquals(MergeReason.CONTENT_DIFFERS, verdictOf(annualWithRole, annual).reason)
    }

    /** B: the marker is the installation's, never the archive's — at every format, and nothing is written. */
    @Test
    fun aMatchedEventComparesWithoutItsMarkerAndKeepsTheLocalOne() {
        val service = completionOf("e1", "2025-10-05", "2025-10-01", assetId = "heater", scheduleId = "s-annual")
        // A format-20 archive cannot carry the marker (its codec refuses `imported = true`), so only the local row has it.
        val cases = listOf(20, 21).flatMap { format ->
            listOf(Triple(format, service, service.copy(imported = true))) +
                if (format >= 21) listOf(Triple(format, service.copy(imported = true), service)) else emptyList()
        }
        for ((format, incoming, local) in cases) {
            val schedule = if (format >= 21) annualWithRole else annual
            val plan = mergePlanOf(
                archive(format, schedules = listOf(schedule), events = listOf(incoming)),
                here(schedules = listOf(annualWithRole), events = listOf(local)),
            )

            assertEquals(MergeVerdict.IDENTICAL, plan.decision(MergeTable.EVENTS, "e1").verdict, "format $format")
            assertTrue(plan.writes.events.isEmpty(), "format $format")
        }
        // Any other difference still counts.
        val renoted = mergePlanOf(
            archive(21, schedules = listOf(annualWithRole), events = listOf(service.copy(notes = "Cleaned"))),
            here(schedules = listOf(annualWithRole), events = listOf(service.copy(imported = true))),
        )
        assertEquals(MergeReason.CONTENT_DIFFERS, renoted.decision(MergeTable.EVENTS, "e1").reason)
    }

    // --- row 48: one coverage per asset (O-9) ----------------------------------------------------

    /** A destination role already covers the asset, on its own schedule or through a group the archive brings. */
    @Test
    fun aSecondCoverageIsHeldByALocalRow() {
        val local = roleOn("s-local", assetId = "heater")
        val onTheAsset = mergePlanOf(
            archive(21, schedules = listOf(roleOn("s-new", assetId = "heater"))),
            here(schedules = listOf(local)),
        )
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULES, "s-new", MergeVerdict.CONFLICT, MergeReason.SEASON_START_HELD_BY_A_LOCAL_ROW, "s-local",
            ),
            onTheAsset.decision(MergeTable.SCHEDULES, "s-new"),
        )
        assertTrue(onTheAsset.writes.schedules.isEmpty())

        val group = groupOf("g-new", "Example pre-season run", members = listOf(Triple("heater", "2025-09-01", null)))
        val throughAGroup = mergePlanOf(
            archive(21, groups = listOf(group), schedules = listOf(roleOn("s-group", groupId = "g-new"))),
            here(schedules = listOf(local)),
        )
        assertEquals(MergeVerdict.INSERT, throughAGroup.decision(MergeTable.GROUPS, "g-new").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULES, "s-group", MergeVerdict.CONFLICT, MergeReason.SEASON_START_HELD_BY_A_LOCAL_ROW, "s-local",
            ),
            throughAGroup.decision(MergeTable.SCHEDULES, "s-group"),
        )
        // An archived local role covers nothing, and a role-less row claims nothing: both insert.
        val archived = local.copy(status = com.loosecannon.servicetag.core.model.ScheduleStatus.ARCHIVED)
        val free = mergePlanOf(
            archive(21, schedules = listOf(roleOn("s-new", assetId = "heater"))),
            here(schedules = listOf(archived, scheduleOf(id = "s-plain", assetId = "heater"))),
        )
        assertEquals(MergeVerdict.INSERT, free.decision(MergeTable.SCHEDULES, "s-new").verdict)
    }

    /**
     * C1's shape, the owner's own: the destination's role is a GROUP schedule over the heater, so the seed reaches the
     * asset through this phone's groups. A member whose window is closed is no longer covered, and the role inserts.
     */
    @Test
    fun aLocalGroupRoleHoldsTheAssetsOfItsOpenMembers() {
        fun localGroup(removedOn: String?) =
            groupOf("g-local", "Example pre-season check", members = listOf(Triple("heater", "2025-09-01", removedOn)))
        val localRole = roleOn("s-local-group", groupId = "g-local")
        val incoming = archive(21, schedules = listOf(roleOn("s-new", assetId = "heater")))

        val held = mergePlanOf(incoming, here(groups = listOf(localGroup(null)), schedules = listOf(localRole)))
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULES, "s-new", MergeVerdict.CONFLICT, MergeReason.SEASON_START_HELD_BY_A_LOCAL_ROW,
                "s-local-group",
            ),
            held.decision(MergeTable.SCHEDULES, "s-new"),
        )
        assertTrue(held.writes.schedules.isEmpty())

        val closed = mergePlanOf(incoming, here(groups = listOf(localGroup("2025-10-01")), schedules = listOf(localRole)))
        assertEquals(MergeVerdict.INSERT, closed.decision(MergeTable.SCHEDULES, "s-new").verdict)
    }

    @Test
    fun twoArchiveRolesOverOneAssetAreDuplicated() {
        val group = groupOf("g-run", "Example pre-season run", members = listOf(Triple("boiler", "2025-09-01", null)))
        val plan = mergePlanOf(
            archive(
                21, assets = listOf(boiler), groups = listOf(group),
                schedules = listOf(roleOn("s-a", assetId = "boiler"), roleOn("s-b", groupId = "g-run")),
            ),
            here(assets = emptyList()),
        )

        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.SCHEDULES, "s-a").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULES, "s-b", MergeVerdict.CONFLICT, MergeReason.SEASON_START_DUPLICATED_IN_ARCHIVE, "s-a",
            ),
            plan.decision(MergeTable.SCHEDULES, "s-b"),
        )
        assertTrue(plan.writes.schedules.isEmpty())
        // Two roles over two different assets are no duplicate.
        val apart = mergePlanOf(
            archive(
                21, assets = listOf(heater, boiler),
                schedules = listOf(roleOn("s-a", assetId = "boiler"), roleOn("s-b", assetId = "heater")),
            ),
            here(assets = emptyList()),
        )
        assertTrue(apart.applicable)
    }

    /** The apply writes the row as planned: an ordinary merge lands the archive's role and boundary verbatim. */
    @Test
    fun noApplyCodeMoved() = runTest {
        val install = BackupInstall("set-here")
        val role = roleOn("s-role", assetId = "heater")
        val service = completionOf("e1", "2025-10-05", "2025-10-01", assetId = "heater", scheduleId = "s-role")
            .copy(imported = true)
        val plan = install.build.run(
            archive(21, schedules = listOf(role), events = listOf(service)), emptyMap(), emptySet(),
        )

        install.apply.run(plan)

        assertEquals(role, install.schedules.get(ScheduleId("s-role")))
        assertEquals(service, install.events.get(EventId("e1")))
        assertEquals(plan.writes.schedules, listOf(role))
    }
}
