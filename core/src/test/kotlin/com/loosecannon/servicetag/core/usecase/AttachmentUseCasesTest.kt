package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryInstalledComponentRepository
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentUseCasesTest {

    private val attachments = InMemoryAttachmentRepository()
    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val supplyItems = InMemorySupplyItemRepository()
    private val installedComponents = InMemoryInstalledComponentRepository(supplyItems)
    private val uow = FakeUnitOfWork(assets, events, attachments)
    private val storage = FakeAttachmentStorage()
    private val store: InMemoryAttachmentStore get() = storage.store
    private var now = 5_000L
    private var seq = 0
    private val ids = IdGenerator { "att-${++seq}" }

    private val add = AddAttachment(
        attachments, assets, events, supplyItems, installedComponents, storage, uow, ids, Clock { now },
    )
    private val update = UpdateAttachment(attachments, uow, Clock { now })
    private val remove = DeleteAttachment(attachments, storage, uow)

    private val payload = "1-2-3 easy installation".toByteArray()
    private fun source() = ByteSource { payload.inputStream() }

    private suspend fun asset(id: String = "a1"): AssetId {
        assets.upsert(Asset(id = AssetId(id), name = "Hot tub", createdAt = 1L, updatedAt = 1L))
        return AssetId(id)
    }

    private suspend fun event(id: String = "e1", assetId: String = "a1"): EventId {
        events.upsert(
            AssetEvent(
                id = EventId(id), assetId = AssetId(assetId), kind = EventKind.MAINTENANCE,
                title = "Filter change", profileId = null, occurredOn = "2026-09-15",
                occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
                sourceRef = null, createdAt = 1L, updatedAt = 1L,
                measurements = emptyList(), consumables = emptyList(),
            ),
        )
        return EventId(id)
    }

    private fun cmd(
        name: String = "1-2-3 Easy Installation Guide.pdf",
        mime: String = "application/pdf",
        size: Long? = null,
        kind: AttachmentKind? = null,
    ) = AddAttachmentCommand(displayName = name, mimeType = mime, sizeBytes = size, kind = kind)

    @Test fun addPutsTheBytesFirstThenTheRow() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val result = add.run(owner, cmd(), source())
        val row = (result as AttachmentResult.Ok).value

        assertEquals("assets/a1/att-1.pdf", row.storageLocator)
        assertEquals(AttachmentKind.DOCUMENT, row.kind)          // inferred from application/pdf
        assertEquals(payload.size.toLong(), row.sizeBytes)
        assertEquals(InMemoryAttachmentStore.sha256Hex(payload), row.sha256)
        assertEquals(5_000L, row.createdAt)
        assertEquals(5_000L, row.updatedAt)
        assertEquals(row, attachments.rows["att-1"])
        assertTrue(store.exists("assets/a1/att-1.pdf"))
        assertEquals(1, uow.commits)
    }

    @Test fun addToAnEventUsesTheEventDirectory() = runTest {
        asset()
        val owner = AttachmentOwner.OfEvent(event())
        val row = (
            add.run(owner, cmd(name = "photo.jpg", mime = "image/jpeg"), source())
                as AttachmentResult.Ok
            ).value
        assertEquals("events/e1/att-1.jpg", row.storageLocator)
        assertEquals(AttachmentKind.PHOTO, row.kind)
        assertEquals(listOf(row), attachments.forOwner(owner))
    }

    @Test fun aCameraCaptureIsAPhotoWhateverTheMimeSays() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (
            add.run(
                owner,
                AddAttachmentCommand(
                    displayName = "capture.bin",
                    mimeType = "application/octet-stream",
                    fromCamera = true,
                ),
                source(),
            ) as AttachmentResult.Ok
            ).value
        assertEquals(AttachmentKind.PHOTO, row.kind)
    }

    @Test fun anExplicitKindBeatsTheInference() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (
            add.run(owner, cmd(kind = AttachmentKind.WARRANTY), source()) as AttachmentResult.Ok
            ).value
        assertEquals(AttachmentKind.WARRANTY, row.kind)
    }

    @Test fun addRefusesABlankNameAMissingOwnerAndNoStore() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.BlankName),
            add.run(owner, cmd(name = "   "), source()),
        )
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.OwnerMissing),
            add.run(AttachmentOwner.OfAsset(AssetId("nope")), cmd(), source()),
        )
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.OwnerMissing),
            add.run(AttachmentOwner.OfEvent(EventId("nope")), cmd(), source()),
        )

        storage.state = StoreState.NotConfigured
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.NoStore),
            add.run(owner, cmd(), source()),
        )
        storage.state = StoreState.AccessLost("Attachments")
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.StoreUnavailable),
            add.run(owner, cmd(), source()),
        )

        // nothing was written, either way
        assertTrue(attachments.rows.isEmpty())
        assertTrue(store.files.isEmpty())
        assertEquals(0, uow.commits)
    }

    @Test fun addRefusesAnOversizeFileBeforeCopyingAnyByte() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.TooLarge(MAX_ATTACHMENT_BYTES)),
            add.run(owner, cmd(size = MAX_ATTACHMENT_BYTES + 1), source()),
        )
        assertTrue(store.files.isEmpty())
        // and the limit is inclusive: exactly 256 MiB is allowed through the guard
        assertTrue(add.run(owner, cmd(size = MAX_ATTACHMENT_BYTES), source()) is AttachmentResult.Ok)
    }

    /**
     * A camera reports no size, so the only number to guard on is the one the store came back
     * with. This rig over-reports it without writing 256 MiB anywhere.
     */
    @Test fun addRefusesAnOversizeFileTheProviderNeverDeclared() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val oversized = RiggedStore(reportedSize = MAX_ATTACHMENT_BYTES + 1)
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(oversized), uow, ids, Clock { now },
        )

        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.TooLarge(MAX_ATTACHMENT_BYTES)),
            adder.run(owner, cmd(), source()),
        )
        assertTrue(attachments.rows.isEmpty())
        assertFalse(oversized.inner.exists("assets/a1/att-1.pdf"))   // its bytes were removed
        assertEquals(1, oversized.deleteAttempts)
        assertEquals(0, uow.commits)
    }

    /** Cleaning up after the refusal is itself best effort: it cannot turn into a thrown error. */
    @Test fun anOversizeRefusalSurvivesACleanupDeleteThatThrows() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val brittle = RiggedStore(
            reportedSize = MAX_ATTACHMENT_BYTES + 1,
            failDeleteWith = { StoreIoException("rigged delete failure") },
        )
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(brittle), uow, ids, Clock { now },
        )

        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.TooLarge(MAX_ATTACHMENT_BYTES)),
            adder.run(owner, cmd(), source()),
        )
        assertEquals(1, brittle.deleteAttempts)
        assertTrue(attachments.rows.isEmpty())
        assertTrue(brittle.inner.exists("assets/a1/att-1.pdf"))   // an orphan, but still refused
        assertEquals(0, uow.commits)
    }

    @Test fun aFailedRowWriteDeletesTheBytesItHadAlreadyWritten() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        attachments.failOnUpsert = 1
        assertFailsWith<RiggedFailure> { add.run(owner, cmd(), source()) }
        assertTrue(attachments.rows.isEmpty())
        assertFalse(store.exists("assets/a1/att-1.pdf"))
        assertEquals(1, store.deletes)
        assertEquals(1, uow.rollbacks)
    }

    /** The row-write failure is what the caller needs; a failing cleanup must not take its place. */
    @Test fun aCleanupDeleteThatThrowsDoesNotHideTheRowWriteFailure() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val brittle = RiggedStore(failDeleteWith = { StoreIoException("rigged delete failure") })
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(brittle), uow, ids, Clock { now },
        )
        attachments.failOnUpsert = 1

        val boom = assertFailsWith<RiggedFailure> { adder.run(owner, cmd(), source()) }

        assertTrue(boom.suppressedExceptions.any { it is StoreIoException })   // not lost, either
        assertEquals(1, brittle.deleteAttempts)
        assertTrue(attachments.rows.isEmpty())
        assertEquals(1, uow.rollbacks)
    }

    @Test fun updateChangesMetadataAndNeverTheLocator() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (add.run(owner, cmd(), source()) as AttachmentResult.Ok).value
        now = 9_000L
        val saved = (
            update.run(
                row.id,
                UpdateAttachmentCommand(
                    displayName = "  Installation guide  ",
                    kind = AttachmentKind.MANUAL,
                    capturedOn = "2026-09-14",
                    notes = " keep ",
                    role = row.role,
                ),
            ) as AttachmentResult.Ok
            ).value

        assertEquals("Installation guide", saved.displayName)
        assertEquals(AttachmentKind.MANUAL, saved.kind)
        assertEquals("2026-09-14", saved.capturedOn)
        assertEquals("keep", saved.notes)
        assertEquals(row.storageLocator, saved.storageLocator)   // bytes did not move
        assertEquals(row.sha256, saved.sha256)
        assertEquals(row.createdAt, saved.createdAt)
        assertEquals(9_000L, saved.updatedAt)
        assertEquals(saved, attachments.rows[row.id.value])
        assertTrue(store.exists(row.storageLocator))
    }

    @Test fun updateRefusesBlankUnchangedAndUnknown() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (add.run(owner, cmd(), source()) as AttachmentResult.Ok).value
        val same = UpdateAttachmentCommand(row.displayName, row.kind, row.capturedOn, row.notes, row.role)

        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.Unchanged),
            update.run(row.id, same),
        )
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.BlankName),
            update.run(row.id, same.copy(displayName = " ")),
        )
        assertEquals(
            AttachmentResult.Refused(AttachmentProblem.OwnerMissing),
            update.run(AttachmentId("nope"), same),
        )
        assertEquals(row, attachments.rows[row.id.value])   // untouched by all three
        assertEquals(1, uow.commits)   // only the add committed
    }

    @Test fun deleteRemovesTheRowThenTheBytes() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (add.run(owner, cmd(), source()) as AttachmentResult.Ok).value
        remove.run(row.id)
        assertTrue(attachments.rows.isEmpty())
        assertFalse(store.exists(row.storageLocator))
        // an unknown id is a no-op: it opens no transaction and deletes no bytes
        remove.run(AttachmentId("nope"))
        assertEquals(2, uow.commits)   // add + delete; the no-op opened no transaction
        assertEquals(1, store.deletes)

        // and a store that will not delete is not surfaced: the row still goes
        val orphaned = (add.run(owner, cmd(), source()) as AttachmentResult.Ok).value
        storage.state = StoreState.AccessLost("Attachments")
        remove.run(orphaned.id)
        assertTrue(attachments.rows.isEmpty())
        assertEquals(4, uow.commits)
        assertTrue(store.exists(orphaned.storageLocator))   // an orphan for 4B to sweep
    }

    /** The point of the best-effort sweep: a store that throws still loses its row. */
    @Test fun aStoreThatThrowsOnDeleteDoesNotHoldOntoTheRow() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val brittle = RiggedStore(failDeleteWith = { StoreIoException("rigged delete failure") })
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(brittle), uow, ids, Clock { now },
        )
        val row = (adder.run(owner, cmd(), source()) as AttachmentResult.Ok).value

        DeleteAttachment(attachments, OneStore(brittle), uow).run(row.id)

        assertTrue(attachments.rows.isEmpty())
        assertEquals(1, brittle.deleteAttempts)               // it was asked, and it refused
        assertTrue(brittle.inner.exists(row.storageLocator))  // an orphan, not a failure
    }

    /**
     * The sweep's allowance is a broken destination, not cancellation: swallowing that would let a
     * cancelled caller watch `run` return normally.
     */
    @Test fun aCancelledSweepIsNotSwallowed() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val cancelling = RiggedStore(failDeleteWith = { CancellationException("cancelled") })
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(cancelling), uow, ids, Clock { now },
        )
        val row = (adder.run(owner, cmd(), source()) as AttachmentResult.Ok).value

        assertFailsWith<CancellationException> {
            DeleteAttachment(attachments, OneStore(cancelling), uow).run(row.id)
        }
        assertTrue(attachments.rows.isEmpty())   // the row went first, inside the transaction
        assertEquals(1, cancelling.deleteAttempts)
    }

    // --- #67: the document role (C1, C2) -------------------------------------------------------

    /**
     * C1: a role belongs to an asset's document. On an event it is a programming error the UI and
     * the codec never reach, and it is refused **before** a byte is copied: no `put`, no row.
     */
    @Test fun aRoleOnAnEventIsRejectedBeforeAnyCopy() = runTest {
        asset()
        val owner = AttachmentOwner.OfEvent(event())
        val spy = RiggedStore()
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(spy), uow, ids, Clock { now },
        )

        assertFailsWith<IllegalArgumentException> {
            adder.run(owner, cmd().copy(role = DocumentRole.USER_MANUAL), source())
        }

        assertEquals(0, spy.puts)
        assertTrue(spy.inner.files.isEmpty())
        assertTrue(attachments.rows.isEmpty())
        assertEquals(0, uow.commits)
    }

    /** The role rides the command onto the row; it never chooses the kind (R67-7). */
    @Test fun addAttachmentCarriesTheRole() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())

        val row = (
            add.run(owner, cmd().copy(role = DocumentRole.USER_MANUAL), source()) as AttachmentResult.Ok
            ).value

        assertEquals(DocumentRole.USER_MANUAL, row.role)
        assertEquals(AttachmentKind.DOCUMENT, row.kind)   // still inferred from application/pdf
        assertEquals(row, attachments.rows[row.id.value])
        val plain = (add.run(owner, cmd(name = "Other.pdf"), source()) as AttachmentResult.Ok).value
        assertNull(plain.role)
    }

    /**
     * AC 5: giving a document a role is metadata. The guarantee that no byte is copied or deleted is
     * the use case's **shape**: `UpdateAttachment` is built without any attachment storage or store,
     * so it has nothing to call `put` or `delete` on. That is asserted directly, over every
     * constructor, so wiring a store in turns this case red. The row then changes in its role and its
     * stamp only — the locator, the hash and the size are its own — and the bytes are still there.
     */
    @Test fun aRoleOnlyUpdateWritesNoBytes() = runTest {
        val parameterTypes = UpdateAttachment::class.java.constructors.flatMap { it.parameterTypes.asList() }
        assertTrue(parameterTypes.isNotEmpty(), "reflection saw no constructor parameters at all")
        val storeTypes = listOf(AttachmentStorage::class.java, AttachmentStore::class.java)
        assertEquals(
            emptyList(),
            parameterTypes.filter { type -> storeTypes.any { it.isAssignableFrom(type) } },
            "UpdateAttachment must not be able to reach the bytes",
        )

        val owner = AttachmentOwner.OfAsset(asset())
        val row = (add.run(owner, cmd(), source()) as AttachmentResult.Ok).value
        now = 9_000L

        val saved = (
            update.run(
                row.id,
                UpdateAttachmentCommand(
                    row.displayName, row.kind, row.capturedOn, row.notes,
                    role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT,
                ),
            ) as AttachmentResult.Ok
            ).value

        assertEquals(row.copy(role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, updatedAt = 9_000L), saved)
        assertEquals(row.storageLocator, saved.storageLocator)
        assertEquals(row.sha256, saved.sha256)
        assertEquals(row.sizeBytes, saved.sizeBytes)
        assertEquals(saved, attachments.rows[row.id.value])
        assertTrue(store.exists(row.storageLocator))
        assertEquals(0, store.deletes)
    }

    /** C2: every caller passes the row's own role, so a rename keeps it. */
    @Test fun anUpdateCarryingTheRowsRoleKeepsIt() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (
            add.run(owner, cmd().copy(role = DocumentRole.SERVICE_MANUAL), source()) as AttachmentResult.Ok
            ).value

        val renamed = (
            update.run(
                row.id,
                UpdateAttachmentCommand("Service manual.pdf", row.kind, row.capturedOn, row.notes, row.role),
            ) as AttachmentResult.Ok
            ).value

        assertEquals("Service manual.pdf", renamed.displayName)
        assertEquals(DocumentRole.SERVICE_MANUAL, renamed.role)
        assertEquals(renamed, attachments.rows[row.id.value])
    }

    /** C2: the command's null is "no role", so an update carrying it clears one — and is a change. */
    @Test fun aNullRoleInAnUpdateClearsIt() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (
            add.run(owner, cmd().copy(role = DocumentRole.USER_MANUAL), source()) as AttachmentResult.Ok
            ).value

        val cleared = (
            update.run(row.id, UpdateAttachmentCommand(row.displayName, row.kind, row.capturedOn, row.notes, null))
                as AttachmentResult.Ok
            ).value

        assertNull(cleared.role)
        assertNull(attachments.rows.getValue(row.id.value).role)
    }

    /** C1, the update's half: an event's file cannot be given a role either, and nothing is written. */
    @Test fun aRoleOnAnEventRowIsRejectedByAnUpdate() = runTest {
        asset()
        val row = (
            add.run(AttachmentOwner.OfEvent(event()), cmd(), source()) as AttachmentResult.Ok
            ).value

        assertFailsWith<IllegalArgumentException> {
            update.run(
                row.id,
                UpdateAttachmentCommand(row.displayName, row.kind, row.capturedOn, row.notes, DocumentRole.USER_MANUAL),
            )
        }

        assertEquals(row, attachments.rows[row.id.value])
        assertEquals(1, uow.commits)   // only the add committed
    }

    // ---- #85 row 18 (C12): the command's one new field ----

    private val manualSource = AttachmentSource(
        uri = "https://manuals.example.invalid/pool-pump/manual.pdf?lang=en",
        resolvedUri = "https://cdn.example.invalid/files/manual.pdf",
        retrievedAt = 4_000L,
        name = "Example Pool Pump manual",
    )

    /** Every shipped caller passes no source: its row is the shipped row, field for field, name-extension locator included. */
    @Test fun withoutASourceTheRowIsTheShippedRow() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())

        val row = (
            add.run(owner, cmd(name = " Example scan.jpeg ", mime = "image/jpeg"), source()) as AttachmentResult.Ok
            ).value

        val shipped = Attachment(
            id = AttachmentId("att-1"), owner = owner, kind = AttachmentKind.PHOTO, displayName = "Example scan.jpeg",
            mimeType = "image/jpeg", sizeBytes = payload.size.toLong(),
            sha256 = InMemoryAttachmentStore.sha256Hex(payload), storageLocator = "assets/a1/att-1.jpeg",
            capturedOn = null, notes = "", createdAt = 5_000L, updatedAt = 5_000L, role = null,
        )
        assertEquals(shipped, row)
        assertNull(row.source)
        assertEquals(shipped, attachments.rows["att-1"])
        assertEquals(1, uow.commits)
    }

    @Test fun aSourceIsCopiedOntoTheRow() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())

        val row = (
            add.run(owner, cmd().copy(source = manualSource), source()) as AttachmentResult.Ok
            ).value

        assertEquals(manualSource, row.source)
        assertEquals(row, attachments.rows[row.id.value])
        assertEquals(1, uow.commits)
    }

    /**
     * Planner finding 5 (review m8): a reference's name is a title, not a filename, so a sourced add takes its
     * extension from the type alone — "manuals.example.invalid" must never store a PDF as `<id>.invalid`.
     */
    @Test fun aSourcedAddTakesItsExtensionFromTheType() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())

        val row = (
            add.run(owner, cmd(name = "manuals.example.invalid").copy(source = manualSource), source())
                as AttachmentResult.Ok
            ).value

        assertEquals("assets/a1/att-1.pdf", row.storageLocator)
        assertEquals("manuals.example.invalid", row.displayName)   // the name itself is kept as given
        assertTrue(store.exists("assets/a1/att-1.pdf"))
        val unsourced = (add.run(owner, cmd(name = "manuals.example.invalid"), source()) as AttachmentResult.Ok).value
        assertEquals("assets/a1/att-2.invalid", unsourced.storageLocator)   // the shipped rule, unchanged
    }

    /** C12: provenance can never enter malformed. A caller's mistake, refused before a byte is copied. */
    @Test fun aMalformedSourceIsAProgrammingError() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val spy = RiggedStore()
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(spy), uow, ids, Clock { now },
        )
        val malformed = listOf(
            manualSource.copy(uri = "http://manuals.example.invalid/pool-pump/manual.pdf"),
            manualSource.copy(resolvedUri = "https://cdn.example.invalid/files/manual.pdf?token=abc"),
            manualSource.copy(resolvedUri = "https://cdn.example.invalid/files;sid=AB12/manual.pdf"),
            manualSource.copy(   // query-free, so the "stored only when it differs" rule is the one that answers
                uri = "https://manuals.example.invalid/pool-pump/manual.pdf",
                resolvedUri = "https://manuals.example.invalid/pool-pump/manual.pdf",
            ),
            manualSource.copy(retrievedAt = 0L),
            manualSource.copy(name = "  "),
        )

        malformed.forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad.toString()) {
                adder.run(owner, cmd().copy(source = bad), source())
            }
        }

        assertEquals(0, spy.puts)
        assertTrue(attachments.rows.isEmpty())
        assertEquals(0, uow.commits)
    }

    // --- #92 (C13, row 17): the preset id, the API upload's derived one ---------------------------------------------

    private val preset = AttachmentId("d1c2b3a4-5e6f-8a7b-9c8d-0e1f2a3b4c5d")

    @Test fun aPresetIdIsTheRowsAndItsLocators() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (add.run(owner, cmd(), source(), presetId = preset) as AttachmentResult.Ok).value
        assertEquals(preset, row.id)
        assertEquals("assets/a1/${preset.value}.pdf", row.storageLocator)
        assertEquals(row, attachments.rows[preset.value])
        assertTrue(store.exists(row.storageLocator))
        assertEquals(0, seq)   // nothing minted
        assertEquals(1, uow.commits)
    }

    @Test fun noPresetIdMintsAsShipped() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val row = (add.run(owner, cmd(), source()) as AttachmentResult.Ok).value
        assertEquals(AttachmentId("att-1"), row.id)
        assertEquals("assets/a1/att-1.pdf", row.storageLocator)
        assertEquals(1, seq)
    }

    @Test fun aPresetIdOfAnExistingRowThrowsBeforePutAndTouchesNothing() = runTest {
        val owner = AttachmentOwner.OfAsset(asset())
        val spy = RiggedStore()
        val adder = AddAttachment(
            attachments, assets, events, supplyItems, installedComponents, OneStore(spy), uow, ids, Clock { now },
        )
        val existing = (adder.run(owner, cmd(), source(), presetId = preset) as AttachmentResult.Ok).value
        val bytes = spy.inner.files.getValue(existing.storageLocator).copyOf()
        val commits = uow.commits
        val other = "a different file".toByteArray()

        assertFailsWith<IllegalArgumentException> {
            adder.run(owner, cmd(name = "Other.pdf"), ByteSource { other.inputStream() }, presetId = preset)
        }

        assertEquals(1, spy.puts)   // the first add's, and no other
        assertEquals(existing, attachments.rows[preset.value])
        assertTrue(bytes.contentEquals(spy.inner.files.getValue(existing.storageLocator)))
        assertEquals(commits, uow.commits)
    }

    // --- #69: a SupplyItem's and an installed component's own files (B2a row 16; C5, R69-10) ---------

    private suspend fun supplyItem(id: String, archivedAt: Long? = null): SupplyId {
        supplyItems.upsert(supplyItemOf(id, "Example 12 V Battery", archivedAt = archivedAt))
        return SupplyId(id)
    }

    private suspend fun component(id: String, removedOn: String? = null): InstalledComponentId {
        installedComponents.insert(installedComponentOf(id, assetId = "a1", installedOn = "2026-09-01", removedOn = removedOn))
        return InstalledComponentId(id)
    }

    /** Hazard: a SupplyItem refused as an owner, or an archived one (R69-10). Both take a file, with a role (R69-6). */
    @Test fun addsToASupplyItemArchivedIncluded() = runTest {
        val battery = AttachmentOwner.OfSupplyItem(supplyItem("s1"))
        val retired = AttachmentOwner.OfSupplyItem(supplyItem("s2", archivedAt = 3_000L))

        val manual = (add.run(battery, cmd().copy(role = DocumentRole.USER_MANUAL), source()) as AttachmentResult.Ok).value
        val sheet = (add.run(retired, cmd(name = "Example data sheet.pdf"), source()) as AttachmentResult.Ok).value

        assertEquals(battery, manual.owner)
        assertEquals(DocumentRole.USER_MANUAL, manual.role)
        assertEquals(listOf(manual), attachments.forOwner(battery))
        assertEquals(listOf(sheet), attachments.forOwner(retired))
        assertEquals(2, uow.commits)
    }

    /** Hazard: a removed component refused as an owner. A current one and a removed one both take a file. */
    @Test fun addsToAComponentRemovedIncluded() = runTest {
        asset()
        val tray = AttachmentOwner.OfInstalledComponent(component("c1"))
        val old = AttachmentOwner.OfInstalledComponent(component("c2", removedOn = "2026-09-10"))

        val photo = (add.run(tray, cmd(name = "Installed.jpg", mime = "image/jpeg"), source()) as AttachmentResult.Ok).value
        val label = (add.run(old, cmd(name = "Label.jpg", mime = "image/jpeg"), source()) as AttachmentResult.Ok).value

        assertEquals(listOf(photo), attachments.forOwner(tray))
        assertEquals(listOf(label), attachments.forOwner(old))
        assertEquals(2, uow.commits)
    }

    /** I4: the bytes land under the owner's own directory, `supply-items/<id>` or `installed-components/<id>`. */
    @Test fun bytesLandUnderTheOwnersDirectory() = runTest {
        asset()
        val battery = AttachmentOwner.OfSupplyItem(supplyItem("s1"))
        val tray = AttachmentOwner.OfInstalledComponent(component("c1"))

        val manual = (add.run(battery, cmd(), source()) as AttachmentResult.Ok).value
        val photo = (add.run(tray, cmd(name = "Installed.jpg", mime = "image/jpeg"), source()) as AttachmentResult.Ok).value

        assertEquals("supply-items/s1/att-1.pdf", manual.storageLocator)
        assertEquals("installed-components/c1/att-2.jpg", photo.storageLocator)
        assertEquals(setOf("supply-items/s1/att-1.pdf", "installed-components/c1/att-2.jpg"), store.files.keys)
    }

    /**
     * Hazard: an owner that is not there answered as present. A SupplyItem or a component nobody stored is
     * `OwnerMissing` — including one whose id string is another owner's (an asset's, a SupplyItem's) — and nothing is
     * copied or written.
     */
    @Test fun anUnknownSupplyItemOrComponentIsOwnerMissing() = runTest {
        asset("x1")
        supplyItem("s1")
        val unknown = listOf(
            AttachmentOwner.OfSupplyItem(SupplyId("nope")),
            AttachmentOwner.OfSupplyItem(SupplyId("x1")),
            AttachmentOwner.OfInstalledComponent(InstalledComponentId("nope")),
            AttachmentOwner.OfInstalledComponent(InstalledComponentId("s1")),
        )

        for (owner in unknown) {
            assertEquals(AttachmentResult.Refused(AttachmentProblem.OwnerMissing), add.run(owner, cmd(), source()), "$owner")
        }

        assertTrue(attachments.rows.isEmpty())
        assertTrue(store.files.isEmpty())
        assertEquals(0, uow.commits)
    }

    /** R69-6 keeps R67-11's one refusal: a role on an entry's file is still a caller's mistake, before any copy. */
    @Test fun aRoleOnAnEntryStillThrows() = runTest {
        asset()
        val entry = AttachmentOwner.OfEvent(event())

        val refusal = assertFailsWith<IllegalArgumentException> {
            add.run(entry, cmd().copy(role = DocumentRole.SERVICE_MANUAL), source())
        }

        assertTrue("not an entry's" in refusal.message!!, "unhelpful: ${refusal.message}")
        assertTrue(store.files.isEmpty())
        assertTrue(attachments.rows.isEmpty())
        assertEquals(0, uow.commits)
    }
}

/**
 * The in-memory store with the two lies a use-case test needs: what `put` claims the bytes weigh,
 * and what `delete` raises instead of deleting.
 */
private class RiggedStore(
    private val reportedSize: Long? = null,
    private val failDeleteWith: (() -> Throwable)? = null,
) : AttachmentStore {
    val inner = InMemoryAttachmentStore()
    var deleteAttempts = 0
        private set

    /** Every copy anyone asked for, so "no bytes were written" is a count and not an inference. */
    var puts = 0
        private set

    override suspend fun put(locator: String, source: ByteSource): StoredBytes {
        puts += 1
        val stored = inner.put(locator, source)
        return if (reportedSize == null) stored else stored.copy(sizeBytes = reportedSize)
    }

    override suspend fun open(locator: String): InputStream? = inner.open(locator)
    override suspend fun exists(locator: String): Boolean = inner.exists(locator)

    override suspend fun delete(locator: String) {
        deleteAttempts += 1
        failDeleteWith?.let { throw it() }
        inner.delete(locator)
    }
}

/** An always-ready [AttachmentStorage] over one given store. */
private class OneStore(private val store: AttachmentStore) : AttachmentStorage {
    override fun state(): StoreState = StoreState.Ready("Attachments", "com.example.provider")
    override fun store(): AttachmentStore = store
}
