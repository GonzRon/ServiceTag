package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.testing.RecordingReferenceRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The edit sheet's use case: Name and Description, and nothing else ever (I-1, I-6). The no-op arm
 * is load-bearing rather than a convenience — `IDENTICAL` compares `updatedAt`, so a write on every
 * call would make a re-imported archive `CONTENT_DIFFERS` on the next merge.
 */
class UpdateReferenceTest {

    private val references = RecordingReferenceRepository()
    private val uow = RecordingUnitOfWork(references)
    private var now = 9_000L
    private val update = UpdateReference(references, uow, Clock { now })

    private val id = ReferenceId("ref-1")
    private val stored = AssetReference(
        id = id,
        assetId = AssetId("a1"),
        kind = ReferenceKind.WEB_URL,
        uri = "https://example-mower.invalid/xt1?a=1#frag",
        displayName = "Deck belt",
        description = "OEM parts lookup",
        scheme = "https",
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private suspend fun store() {
        references.upsert(stored)
    }

    private fun refusal(result: ReferenceResult<AssetReference>): ReferenceProblem {
        assertTrue(result is ReferenceResult.Refused, "expected a refusal, got $result")
        return result.problem
    }

    private fun saved(result: ReferenceResult<AssetReference>): AssetReference {
        assertTrue(result is ReferenceResult.Ok, "expected a saved row, got $result")
        return result.value
    }

    @Test
    fun anAbsentRowIsNoSuchReferenceAndOpensNoTransaction() = runTest {
        assertEquals(
            ReferenceProblem.NoSuchReference,
            refusal(update.run(id, UpdateReferenceCommand("Deck belt", ""))),
        )
        assertEquals(0, uow.writesEntered)
    }

    @Test
    fun aBlankNameIsRefusedAfterSanitisation() = runTest {
        store()
        for (name in listOf("", "   ", "\u0000")) {
            assertEquals(
                ReferenceProblem.BlankName,
                refusal(update.run(id, UpdateReferenceCommand(name, "OEM parts lookup"))),
            )
        }
        assertEquals(0, uow.writesEntered)
        assertEquals(2_000L, references.rows.getValue(id.value).updatedAt)
    }

    @Test
    fun aNoOpUpdateIsUnchangedAndDoesNotMoveTheTimestamp() = runTest {
        store()
        assertEquals(
            ReferenceProblem.Unchanged,
            refusal(update.run(id, UpdateReferenceCommand(" Deck belt ", "OEM parts lookup "))),
        )
        assertEquals(0, uow.writesEntered)
        assertEquals(2_000L, references.rows.getValue(id.value).updatedAt)
    }

    @Test
    fun onlyTheNameTheDescriptionAndTheTimestampMove() = runTest {
        store()
        val row = saved(
            update.run(
                id,
                UpdateReferenceCommand(
                    displayName = "c".repeat(MAX_REFERENCE_NAME_CHARS + 1),
                    description = "d".repeat(MAX_REFERENCE_DESCRIPTION_CHARS + 1),
                ),
            ),
        )
        assertEquals(MAX_REFERENCE_NAME_CHARS, row.displayName.length)
        assertEquals(MAX_REFERENCE_DESCRIPTION_CHARS, row.description.length)
        assertEquals(now, row.updatedAt)
        assertEquals(stored.uri, row.uri)
        assertEquals(stored.assetId, row.assetId)
        assertEquals(stored.kind, row.kind)
        assertEquals(stored.scheme, row.scheme)
        assertEquals(stored.createdAt, row.createdAt)
        assertEquals(1, uow.writesEntered)
    }

    /** I-1 and I-6 are unenforceable the moment this command can carry a `uri` or an `assetId`. */
    @Test
    fun theCommandCannotCarryAUriAnOwnerOrAKind() {
        assertEquals(
            setOf("displayName", "description"),
            UpdateReferenceCommand::class.java.declaredFields.map { it.name }.toSet(),
        )
    }
}
