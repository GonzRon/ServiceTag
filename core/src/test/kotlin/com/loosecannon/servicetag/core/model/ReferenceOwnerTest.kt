package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.testing.RecordingReferenceRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #69 (C12; row 21): a link's owner and the one map from it to the owner its saved document gets. The three owners
 * deliberately share the id string `x1`, so a map that crossed owners would hand the document to another row's owner
 * rather than fail. The names are fictional.
 */
class ReferenceOwnerTest {

    private val onAsset = ReferenceOwner.OfAsset(AssetId("x1"))
    private val onSupplyItem = ReferenceOwner.OfSupplyItem(SupplyId("x1"))
    private val onComponent = ReferenceOwner.OfInstalledComponent(InstalledComponentId("x1"))

    /** Hazard: a saved document landing on another owner. Each owner maps to its same-named attachment owner. */
    @Test
    fun asAttachmentOwnerIsTotalAndSameNamed() {
        assertEquals(
            listOf(
                AttachmentOwner.OfAsset(AssetId("x1")),
                AttachmentOwner.OfSupplyItem(SupplyId("x1")),
                AttachmentOwner.OfInstalledComponent(InstalledComponentId("x1")),
            ),
            listOf(onAsset, onSupplyItem, onComponent).map { it.asAttachmentOwner() },
        )
    }

    /** I2: an edit of a SupplyItem's or a component's link keeps its owner; only the edited fields move. */
    @Test
    fun updateReferenceNeverChangesTheOwner() = runTest {
        val references = RecordingReferenceRepository()
        val update = UpdateReference(references, RecordingUnitOfWork(references), Clock { 9_000L })
        for ((i, owner) in listOf(onAsset, onSupplyItem, onComponent).withIndex()) {
            val stored = AssetReference(
                id = ReferenceId("r$i"), owner = owner, kind = ReferenceKind.WEB_URL,
                uri = "https://example.invalid/battery/$i", displayName = "Example 12 V Battery data sheet",
                description = "", scheme = "https", createdAt = 1_000L, updatedAt = 2_000L,
            )
            references.upsert(stored)

            val result = update.run(stored.id, UpdateReferenceCommand("Example data sheet", "rev 2", DocumentRole.USER_MANUAL))

            assertTrue(result is ReferenceResult.Ok, "expected a saved row, got $result")
            assertEquals(owner, result.value.owner)
            assertEquals(
                stored.copy(displayName = "Example data sheet", description = "rev 2", role = DocumentRole.USER_MANUAL, updatedAt = 9_000L),
                references.get(stored.id),
            )
        }
    }
}
