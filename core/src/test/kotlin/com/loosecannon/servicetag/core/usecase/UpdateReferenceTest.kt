package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.DocumentRole
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edit sheet's use case: Name, Description and (#91) the document role, and nothing else ever (I-1, I-6). The no-op arm
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
            refusal(update.run(id, UpdateReferenceCommand("Deck belt", "", role = null))),
        )
        assertEquals(0, uow.writesEntered)
    }

    @Test
    fun aBlankNameIsRefusedAfterSanitisation() = runTest {
        store()
        for (name in listOf("", "   ", "\u0000")) {
            assertEquals(
                ReferenceProblem.BlankName,
                refusal(update.run(id, UpdateReferenceCommand(name, "OEM parts lookup", role = null))),
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
            refusal(update.run(id, UpdateReferenceCommand(" Deck belt ", "OEM parts lookup ", role = null))),
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
                    role = null,
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

    /** I-1 and I-6 are unenforceable the moment this command can carry a `uri` or an `assetId`. #91 adds `role`. */
    @Test
    fun theCommandCannotCarryAUriAnOwnerOrAKind() {
        assertEquals(
            setOf("displayName", "description", "role"),
            UpdateReferenceCommand::class.java.declaredFields.map { it.name }.toSet(),
        )
    }

    // --- #91: the document role (R91-1, R91-3, C11) --------------------------------------------

    /** Row 23: a change of role alone is a change — it writes the role and moves `updatedAt`. */
    @Test
    fun aRoleOnlyChangeWritesAndMovesUpdatedAt() = runTest {
        store()
        val row = saved(update.run(id, UpdateReferenceCommand("Deck belt", "OEM parts lookup", DocumentRole.USER_MANUAL)))
        assertEquals(DocumentRole.USER_MANUAL, row.role)
        assertEquals(now, row.updatedAt)
        assertEquals(DocumentRole.USER_MANUAL, references.rows.getValue(id.value).role)
        assertEquals(1, uow.writesEntered)
    }

    /** Row 23: `Unchanged` includes the role — the same name, description and role write nothing. */
    @Test
    fun theSameRoleIsUnchangedAndWritesNothing() = runTest {
        references.upsert(stored.copy(role = DocumentRole.SERVICE_MANUAL))
        assertEquals(
            ReferenceProblem.Unchanged,
            refusal(update.run(id, UpdateReferenceCommand("Deck belt", "OEM parts lookup", DocumentRole.SERVICE_MANUAL))),
        )
        assertEquals(0, uow.writesEntered)
        assertEquals(2_000L, references.rows.getValue(id.value).updatedAt)
        assertEquals(DocumentRole.SERVICE_MANUAL, references.rows.getValue(id.value).role)
    }

    /** Row 23: a `null` role is "no role", a value — it clears a stored one and writes. */
    @Test
    fun clearingARoleWrites() = runTest {
        references.upsert(stored.copy(role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT))
        val row = saved(update.run(id, UpdateReferenceCommand("Deck belt", "OEM parts lookup", role = null)))
        assertNull(row.role)
        assertEquals(now, row.updatedAt)
        assertNull(references.rows.getValue(id.value).role)
        assertEquals(1, uow.writesEntered)
    }

    /** Row 23: a rename that carries the stored role (the edit sheet's `role = row.role`) keeps it. */
    @Test
    fun aRenameCarryingTheStoredRoleKeepsIt() = runTest {
        references.upsert(stored.copy(role = DocumentRole.USER_MANUAL))
        val row = saved(update.run(id, UpdateReferenceCommand("Deck belt (2026)", "OEM parts lookup", DocumentRole.USER_MANUAL)))
        assertEquals("Deck belt (2026)", row.displayName)
        assertEquals(DocumentRole.USER_MANUAL, row.role)
        assertEquals(DocumentRole.USER_MANUAL, references.rows.getValue(id.value).role)
    }

    /** Row 23: a role on a note link is refused (R91-1), and nothing is written. */
    @Test
    fun aRoleOnANoteRowIsRefusedAndNothingWritten() = runTest {
        val note = stored.copy(
            kind = ReferenceKind.NOTE_LINK,
            uri = "joplin://x-callback-url/openNote?id=example",
            scheme = "joplin",
        )
        references.upsert(note)
        for (role in DocumentRole.entries) {
            assertEquals(
                ReferenceProblem.RoleNotAllowed,
                refusal(update.run(id, UpdateReferenceCommand("Deck belt (2026)", "OEM parts lookup", role))),
            )
        }
        assertEquals(0, uow.writesEntered)
        assertEquals(note, references.rows.getValue(id.value))
    }
}
