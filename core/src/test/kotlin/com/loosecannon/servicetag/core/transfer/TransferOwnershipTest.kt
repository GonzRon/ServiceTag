package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * #77 (NOTE 2 of the whole-branch review) — the one map from an [EntangledRef] to the held assets that own the row it
 * lands on, which M3 and the write guard share. It fails **closed**: a target it cannot place is owned by every held
 * asset, never by none, so a future reference can neither slip past the merge nor past the guard. Fictional ids only.
 */
class TransferOwnershipTest {

    private val definitions = mapOf(DefinitionId("d1") to TransferFixtures.definitionOf("d1", "h1"))

    private val lookup = object : OwnerLookup {
        override fun event(id: EventId): AssetEvent? = null
        override fun case(id: ServiceCaseId): ServiceCase? = null
        override fun schedule(id: ScheduleId): MaintenanceSchedule? = null
        override fun group(id: GroupId): MaintenanceGroup? = null
        override fun link(id: LinkId): ExternalLink? = null
        override fun definition(id: DefinitionId): MeasurementDefinition? = definitions[id]
        override fun profile(id: ProfileId): EventProfile? = null
        override fun installedComponent(id: InstalledComponentId): InstalledComponent? = null
    }

    private val held = setOf(AssetId("x1"), AssetId("h1"))

    @Test
    fun aKnownTargetIsOwnedByItsHeldAsset() {
        assertEquals(setOf(AssetId("h1")), TransferOwnership.heldOwnersOf(EntangledRef("assets", "h2", "assets", "h1"), held, lookup))
        assertEquals(
            setOf(AssetId("h1")),
            TransferOwnership.heldOwnersOf(EntangledRef("assetEvents", "e9", "measurementDefinitions", "d1"), held, lookup),
        )
    }

    @Test
    fun anUnmappedOrUnresolvedTargetIsOwnedByEveryHeldAsset() {
        val every = listOf(AssetId("h1"), AssetId("x1"))
        assertEquals(
            every,
            TransferOwnership.heldOwnersOf(EntangledRef("futureRows", "r1", "futureTargets", "t1"), held, lookup).toList(),
            "a target table the map does not know",
        )
        assertEquals(
            every,
            TransferOwnership.heldOwnersOf(EntangledRef("assetEvents", "e9", "eventProfiles", "p404"), held, lookup).toList(),
            "a target row the lookup cannot find",
        )
    }
}
