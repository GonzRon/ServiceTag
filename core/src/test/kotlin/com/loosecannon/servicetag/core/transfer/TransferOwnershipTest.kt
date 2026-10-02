package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
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
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.testing.installedComponentOf
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * #77 (NOTE 2 of the whole-branch review) — the one map from an [EntangledRef] to the held assets that own the row it
 * lands on, which M3 and the write guard share. It fails **closed**: a target it cannot place is owned by every held
 * asset, never by none, so a future reference can neither slip past the merge nor past the guard. Fictional ids only.
 */
class TransferOwnershipTest {

    private val definitions = mapOf(DefinitionId("d1") to TransferFixtures.definitionOf("d1", "h1"))

    /** #69 (row 17): a current tray on h1 and a removed one on x1, the second named `h1` as the asset is. */
    private val components = listOf(
        installedComponentOf("c1", assetId = "h1", name = "Example Battery Tray"),
        installedComponentOf("h1", assetId = "x1", name = "Example Spare Tray", removedOn = "2026-09-20"),
    ).associateBy { it.id }

    private val lookup = object : OwnerLookup {
        override fun event(id: EventId): AssetEvent? = null
        override fun case(id: ServiceCaseId): ServiceCase? = null
        override fun schedule(id: ScheduleId): MaintenanceSchedule? = null
        override fun group(id: GroupId): MaintenanceGroup? = null
        override fun link(id: LinkId): ExternalLink? = null
        override fun definition(id: DefinitionId): MeasurementDefinition? = definitions[id]
        override fun profile(id: ProfileId): EventProfile? = null
        override fun installedComponent(id: InstalledComponentId): InstalledComponent? = components[id]
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

    // ---- #69 (C5 I5, row 17): a file's owner, resolved to the asset it counts as -------------------------------------

    /** A SupplyItem is global: its file names no owner ref, so it resolves to no asset — even under an asset's id string. */
    @Test
    fun aSupplyItemsResourceHasNoOwner() {
        val file = fileOf("f1", AttachmentOwner.OfSupplyItem(SupplyId("h1")))

        assertEquals(emptyList(), TransferOwnership.of(file))
        assertEquals(emptySet(), TransferOwnership.resolve(TransferOwnership.of(file), lookup))
    }

    /** A component's file names its component, and resolves through the lookup to the component's asset, removed or not. */
    @Test
    fun aComponentsResourceResolvesToItsAsset() {
        val current = fileOf("f2", AttachmentOwner.OfInstalledComponent(InstalledComponentId("c1")))
        val removed = fileOf("f3", AttachmentOwner.OfInstalledComponent(InstalledComponentId("h1")))

        assertEquals(listOf(OwnerRef.OfInstalledComponent(InstalledComponentId("c1"))), TransferOwnership.of(current))
        assertEquals(setOf(AssetId("h1")), TransferOwnership.resolve(TransferOwnership.of(current), lookup))
        assertEquals(
            setOf(AssetId("x1")),
            TransferOwnership.resolve(TransferOwnership.of(removed), lookup),
            "the removed tray named h1 is x1's, never the asset h1",
        )
    }

    /** A component the lookup cannot find owns nothing more, as every other row [TransferOwnership.resolve] reads. */
    @Test
    fun anUnknownComponentResolvesToNothing() {
        val file = fileOf("f4", AttachmentOwner.OfInstalledComponent(InstalledComponentId("x1")))

        assertEquals(emptySet(), TransferOwnership.resolve(TransferOwnership.of(file), lookup))
    }

    private fun fileOf(id: String, owner: AttachmentOwner) = Attachment(
        id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "$id file",
        mimeType = "application/pdf", sizeBytes = 4L, sha256 = "ab".repeat(32),
        storageLocator = "${AttachmentLocator.dirFor(owner)}/$id.pdf", capturedOn = null, createdAt = 100L, updatedAt = 100L,
    )
}
