package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.without
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #124 (N2b; C42; row 148) — the answer in the archive and the merge: format 21 carries `IN_SERVICE_AFTER_OPENING_SERVICE`
 * as its name; a format ≤ 20 archive carrying it was built by hand and is refused; a merge or a Transfer Pack inserting a
 * live schedule that counts from its asset's opening service where no live role covers the asset once the plan's
 * accepted rows are in is a `CONFLICT` (`SEASON_START_SERVICE_MISSING`), and one whose role is among those rows is
 * inserted. The names are fictional.
 */
class OpeningServiceMergeTest {

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)
    private val boiler: Asset = assetOf("boiler", "Example Boiler", SeasonMode.MANUAL)

    private fun dependent(id: String, assetId: String = "heater", status: ScheduleStatus = ScheduleStatus.ACTIVE) = scheduleOf(
        id = id, assetId = assetId, title = "Example filter check $id", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2025-10-01", servicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, policyOffsetDays = 14,
        status = status,
    )

    private fun roleOn(id: String, assetId: String? = null, groupId: String? = null, status: ScheduleStatus = ScheduleStatus.ACTIVE) =
        scheduleOf(id = id, assetId = assetId, groupId = groupId, title = "Example annual service $id", status = status)
            .copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2025-10-01")

    private fun data(
        assets: List<Asset> = listOf(heater),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
    ) = BackupData(
        assets.map { it.toDto() }, emptyList(), emptyList(),
        maintenanceGroups = groups.map { it.toDto() },
        maintenanceSchedules = schedules.map { it.toDto() },
    )

    private fun archive(
        assets: List<Asset> = listOf(heater),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
    ): Backup = backupOf(data(assets, groups, schedules), 21)

    private fun here(
        assets: List<Asset> = listOf(heater),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
    ) = MergeSnapshot(assets = assets, groups = groups, schedules = schedules, attachmentStoreConfigured = true)

    private fun MergePlan.decision(id: String): MergeDecision =
        decisions.single { it.table == MergeTable.SCHEDULES && it.id == id }

    private fun missing(id: String, assetId: String = "heater") = MergeDecision(
        MergeTable.SCHEDULES, id, MergeVerdict.CONFLICT, MergeReason.SEASON_START_SERVICE_MISSING, assetId,
    )

    // --- the format --------------------------------------------------------------------------

    @Test
    fun format21CarriesTheAnswerByName() {
        val schedules = listOf(roleOn("s-role", assetId = "heater"), dependent("d1"))
        val bytes = archiveOf(data(schedules = schedules))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(schedules.toSet(), decoded.data.maintenanceSchedules.map { it.toDomain() }.toSet())
        assertEquals(
            "IN_SERVICE_AFTER_OPENING_SERVICE",
            decoded.data.maintenanceSchedules.single { it.id == "d1" }.servicePolicy,
        )
    }

    @Test
    fun aFormat20ArchiveCarryingTheAnswerIsRefused() {
        val tree = dataTreeOf(archiveOf(data(schedules = listOf(dependent("d1")))))
            .editRows("maintenanceSchedules") { it.without("role", "roleSinceOn") }
            .editRows("assetEvents") { it.without("imported") }

        val message = assertFailsWith<BackupCorrupt> { BackupCodec.decode(sealed(tree, formatVersion = 20)) }.message!!

        assertTrue(
            "maintenanceSchedules: a format 20 archive cannot carry a schedule counting from the opening service" in message,
            message,
        )
        assertTrue(message.endsWith("(schedule d1)"), message)
        BackupCodec.decode(sealed(tree, formatVersion = 21))
    }

    // --- the merge ---------------------------------------------------------------------------

    @Test
    fun aDependentWithNoLiveRoleOnItsAssetConflicts() {
        val plan = mergePlanOf(archive(schedules = listOf(dependent("d1"))), here())

        assertEquals(missing("d1"), plan.decision("d1"))
        assertFalse(plan.applicable)
        assertTrue(plan.writes.schedules.isEmpty())
        // An archived role here, or an archived one in the archive, covers nothing.
        val archivedRoles = mergePlanOf(
            archive(schedules = listOf(roleOn("s-new", assetId = "heater", status = ScheduleStatus.ARCHIVED), dependent("d1"))),
            here(schedules = listOf(roleOn("s-old", assetId = "heater", status = ScheduleStatus.ARCHIVED))),
        )
        assertEquals(missing("d1"), archivedRoles.decision("d1"))
    }

    @Test
    fun aDependentWhoseRoleIsAmongTheAcceptedRowsIsInserted() {
        // The role's id sorts after the dependent's: the claim is read once every schedule row is decided.
        val plan = mergePlanOf(archive(schedules = listOf(dependent("a-dep"), roleOn("z-role", assetId = "heater"))), here())

        assertEquals(MergeVerdict.INSERT, plan.decision("a-dep").verdict)
        assertEquals(MergeVerdict.INSERT, plan.decision("z-role").verdict)
        assertTrue(plan.applicable)
        assertEquals(setOf("a-dep", "z-role"), plan.writes.schedules.map { it.id.value }.toSet())
    }

    @Test
    fun aLocalRoleOrAnAcceptedGroupRoleCovers() {
        val local = mergePlanOf(
            archive(schedules = listOf(dependent("d1"))),
            here(schedules = listOf(roleOn("s-local", assetId = "heater"))),
        )
        assertEquals(MergeVerdict.INSERT, local.decision("d1").verdict, "this phone's own role")

        val group = groupOf(id = "g1", name = "Example pre-season group", members = listOf(Triple("boiler", "2025-01-01", null)))
        val throughAGroup = mergePlanOf(
            archive(assets = listOf(boiler), groups = listOf(group), schedules = listOf(dependent("d1", assetId = "boiler"), roleOn("sg", groupId = "g1"))),
            here(assets = listOf(boiler)),
        )
        assertEquals(MergeVerdict.INSERT, throughAGroup.decision("d1").verdict, "the archive's group role")
    }

    @Test
    fun aRoleThatConflictsCoversNothing() {
        // The archive's role is refused (a second coverage), so its dependent is left with no accepted role… unless the
        // local holder covers the asset, which it does here: the dependent is inserted under this phone's role.
        val plan = mergePlanOf(
            archive(schedules = listOf(roleOn("s-new", assetId = "heater"), dependent("d1"))),
            here(schedules = listOf(roleOn("s-local", assetId = "heater"))),
        )
        assertEquals(MergeReason.SEASON_START_HELD_BY_A_LOCAL_ROW, plan.decision("s-new").reason)
        assertEquals(MergeVerdict.INSERT, plan.decision("d1").verdict)

        // A role refused for its own reason (a meter this phone does not have) covers nothing either.
        val meterless = roleOn("s-new", assetId = "heater").copy(meterDefinitionId = DefinitionId("example-missing-meter"))
        val orphanRole = mergePlanOf(archive(schedules = listOf(meterless, dependent("d1"))), here())
        assertEquals(MergeReason.OWNER_NOT_AVAILABLE, orphanRole.decision("s-new").reason)
        assertEquals(missing("d1"), orphanRole.decision("d1"))
    }

    @Test
    fun anArchivedDependentNeedsNoRole() {
        val plan = mergePlanOf(archive(schedules = listOf(dependent("d1", status = ScheduleStatus.ARCHIVED))), here())

        assertEquals(MergeVerdict.INSERT, plan.decision("d1").verdict)
    }

    @Test
    fun aTransferPackIsTheSamePlanner() {
        val context = ImportContext(LocalDate.parse("2026-10-20"))

        val alone = mergePlanOf(archive(schedules = listOf(dependent("d1"))), here(), context = context)
        assertEquals(missing("d1"), alone.decision("d1"))

        val withItsRole = mergePlanOf(
            archive(schedules = listOf(dependent("d1"), roleOn("s-role", assetId = "heater"))), here(), context = context,
        )
        assertEquals(MergeVerdict.INSERT, withItsRole.decision("d1").verdict)
    }
}
