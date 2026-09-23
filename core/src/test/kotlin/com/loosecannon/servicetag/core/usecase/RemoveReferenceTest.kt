package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.testing.RecordingReferenceRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hard delete, because there is nothing to orphan: one metadata row, no bytes, no artifacts
 * entry (I-3). A soft delete would leave a row every reader had to learn to filter.
 */
class RemoveReferenceTest {

    private val references = RecordingReferenceRepository()
    private val uow = RecordingUnitOfWork(references)
    private val remove = RemoveReference(references, uow)

    private val id = ReferenceId("ref-1")

    private suspend fun store() {
        references.upsert(
            AssetReference(
                id = id,
                assetId = AssetId("a1"),
                kind = ReferenceKind.WEB_URL,
                uri = "https://example-mower.invalid/xt1",
                displayName = "Deck belt",
                description = "",
                scheme = "https",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }

    @Test
    fun theRowIsGoneAndASecondCallIsNoSuchReference() = runTest {
        store()
        assertTrue(remove.run(id) is ReferenceResult.Ok)
        assertTrue(references.rows.isEmpty())
        assertEquals(1, references.deletes)

        val again = remove.run(id)
        assertTrue(again is ReferenceResult.Refused)
        assertEquals(ReferenceProblem.NoSuchReference, again.problem)
        assertEquals(1, references.deletes)
    }

    @Test
    fun anAbsentRowOpensNoTransaction() = runTest {
        assertTrue(remove.run(id) is ReferenceResult.Refused)
        assertEquals(0, uow.writesEntered)
        assertEquals(0, references.deletes)
    }
}
