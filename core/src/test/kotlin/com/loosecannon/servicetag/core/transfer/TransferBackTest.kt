package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import kotlin.coroutines.cancellation.CancellationException
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.usecase.PackAsset
import com.loosecannon.servicetag.core.usecase.TransferImportOutcome
import com.loosecannon.servicetag.core.usecase.TransferImportResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #77 (B3; C15, row 24; R77-12, R77-25, R77-B3-RETURN) — an explicit transfer back. The sender ([HEATER] and its
 * anode, marked out in `pack-q1`) takes back a pack whose lineage names that OUT: its IN is appended first, the
 * stale local graph is replaced by the pack's in the same write, the sender-local returned loan and the 2.6 link
 * with its tag stay as history, and bytes of removed documents the pack does not name are swept. A stale or
 * foreign pack for a held asset is refused (P77-67), and so is a return that would leave another OUT of the asset
 * open. Every name is fictional.
 */
class TransferBackTest {

    /** The sender, with the heater marked out in `pack-q1`, and that pack's bytes. */
    private suspend fun senderAfterTheTransfer(): Pair<TransferInstall, SealedPack> {
        val s = TransferInstall("set-sender").also { TransferFixtures.seed(it.raw) }
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        return s to q1
    }

    /** The recipient of [pack], after it serviced the heater: renamed it, logged a note, dropped a reference and a document. */
    private suspend fun recipientOf(pack: SealedPack, setId: String = "set-recipient"): TransferInstall =
        TransferInstall(setId).also { r ->
            assertIs<TransferImportResult.Imported>(r.import(pack.bytes))
            val heater = r.raw.assets.get(AssetId(HEATER))!!
            r.assets.upsert(heater.copy(name = "Example Water Heater, serviced", updatedAt = IMPORT_NOW + 1))
            r.events.upsert(completionOf("e9", "2026-09-01", null, assetId = HEATER).copy(scheduleId = null))
            r.references.delete(ReferenceId("r1"))
            r.attachments.delete(AttachmentId("at1"))
            r.raw.storage.store.files.remove("assets/h1/at1.pdf")
        }

    @Test
    fun aPackWhoseLineageNamesMyOpenOutReturnsTheAsset() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        val r = recipientOf(q1)
        val q2 = r.pack("pack-q2", HEATER)
        assertEquals(listOf("pack-q1"), q2.created.lineage[HEATER])
        val loan = s.raw.loans.rows.getValue("l1")
        val link = s.raw.links.rows.getValue("L1")
        val linkTag = s.raw.tags.rows.getValue("t4")
        val appended = mutableListOf<TransferKind>()
        s.raw.transfers.onAppend = { appended += it.kind }

        val ready = s.ready(q2.bytes)
        assertEquals(TransferImportOutcome.READY, ready.outcome)
        assertEquals(listOf(PackAsset(AssetId(ANODE), "Example Anode Rod"), PackAsset(AssetId(HEATER), "Example Water Heater, serviced")), ready.returning)
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q2.bytes.inputStream() })

        assertEquals(listOf(TransferKind.IN, TransferKind.IN), appended, "the INs are the only records appended")
        assertEquals(emptySet(), heldIds(s.raw.transfers.all()), "no longer held")
        val here = s.data()
        val sent = r.data()
        assertEquals(sent.assets.filter { it.id in setOf(HEATER, ANODE) }, here.assets.filter { it.id in setOf(HEATER, ANODE) })
        assertEquals(sent.assetEvents.filter { it.assetId == HEATER }, here.assetEvents.filter { it.assetId == HEATER })
        assertTrue(here.assetReferences.none { it.id == "r1" }, "the stale reference was replaced away")
        assertTrue(here.attachments.none { it.id == "at1" }, "the stale document row was replaced away")
        val heater = s.raw.assets.get(AssetId(HEATER))!!
        assertEquals(AssetStatus.ACTIVE, heater.status)
        assertTrue(heater.maintainedHere(heldIds(s.raw.transfers.all())), "quiescence lifted")
        assertEquals(loan, s.raw.loans.rows["l1"], "R77-25 (a): the returned loan stays as history")
        assertEquals(link, s.raw.links.rows["L1"], "the 2.6 tombstone stays")
        assertEquals(linkTag, s.raw.tags.rows["t4"], "and its tag")
        assertTrue(here.assets.any { it.id == "x1" }, "an unrelated asset stays")
    }

    @Test
    fun aMultiHopReturnIsAccepted() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        val r = recipientOf(q1)
        val q2 = r.pack("pack-q2", HEATER)
        r.mark(q2)
        val t = TransferInstall("set-third")
        assertIs<TransferImportResult.Imported>(t.import(q2.bytes))
        val q3 = t.pack("pack-q3", HEATER)
        assertEquals(listOf("pack-q1", "pack-q2"), q3.created.lineage[HEATER])

        val ready = s.ready(q3.bytes)

        assertEquals(TransferImportOutcome.READY, ready.outcome)
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE)), ready.returning.map { it.id }.toSet())
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q3.bytes.inputStream() })
        assertEquals(emptySet(), heldIds(s.raw.transfers.all()))
    }

    @Test
    fun aReturningGroupIsReplaced() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        val r = recipientOf(q1)
        val group = r.raw.groups.get(GroupId(GROUP))!!
        r.groups.upsert(group.copy(name = "Example Flush Round, yearly", updatedAt = IMPORT_NOW + 2))
        val q2 = r.pack("pack-q2", HEATER)

        assertIs<TransferImportResult.Imported>(s.import(q2.bytes))

        assertEquals(r.raw.groups.get(GroupId(GROUP)), s.raw.groups.get(GroupId(GROUP)))
        assertEquals(r.data().maintenanceSchedules.filter { it.groupId == GROUP }.toSet(), s.data().maintenanceSchedules.filter { it.groupId == GROUP }.toSet())
        assertEquals(r.data().occurrenceClosures.toSet(), s.data().occurrenceClosures.filter { it.scheduleId != "s2" }.toSet())
    }

    @Test
    fun removedRowsBytesAreSwept() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        val r = recipientOf(q1)
        val q2 = r.pack("pack-q2", HEATER)
        assertTrue("assets/h1/at1.pdf" in s.raw.storage.store.files)

        assertIs<TransferImportResult.Imported>(s.import(q2.bytes))

        assertTrue("assets/h1/at1.pdf" !in s.raw.storage.store.files, "the removed document's bytes were swept")
        assertTrue("events/e1/at2.jpg" in s.raw.storage.store.files, "the pack's own document stays")
        assertTrue("assets/x1/at3.pdf" in s.raw.storage.store.files, "an unrelated document stays")
    }

    /** A pack this phone made before the one it marked: its lineage names no OUT here. */
    @Test
    fun myOwnOldPackIsRefused() = runTest {
        val s = TransferInstall("set-sender").also { TransferFixtures.seed(it.raw) }
        val q0 = s.pack("pack-q0", HEATER)
        s.mark(s.pack("pack-q1", HEATER))
        val before = s.data()
        val records = s.raw.transfers.all()

        val ready = s.ready(q0.bytes)

        assertEquals(TransferImportOutcome.NOT_BROUGHT_BACK, ready.outcome)
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE)), ready.refused.map { it.id }.toSet())
        assertEquals(false, ready.importable)
        assertEquals(before, s.data())
        assertEquals(records, s.raw.transfers.all())
    }

    /** Another installation's heater under the same id, never descended from this phone's OUT. */
    @Test
    fun aForeignPackForAHeldAssetIsRefused() = runTest {
        val (s, _) = senderAfterTheTransfer()
        val f = TransferInstall("set-foreign").also { TransferFixtures.seed(it.raw) }
        val foreign = f.pack("pack-f1", HEATER)

        val ready = s.ready(foreign.bytes)

        assertEquals(TransferImportOutcome.NOT_BROUGHT_BACK, ready.outcome)
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE)), ready.refused.map { it.id }.toSet())
        assertEquals(emptyList(), ready.returning)
    }

    /** §14 row 24, the rm-8 recovery (R77-5): a withdrawn OUT the lineage names still brings the asset back. */
    @Test
    fun aPackWhoseLineageNamesAWithdrawnOutStillReturns() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        s.raw.transfers.append(transferOf("w-h1", HEATER, TransferKind.WITHDRAWN, packId = "pack-q1", at = IMPORT_NOW))
        s.raw.transfers.append(transferOf("w-h2", ANODE, TransferKind.WITHDRAWN, packId = "pack-q1", at = IMPORT_NOW))
        assertEquals(emptySet(), heldIds(s.raw.transfers.all()))
        val r = recipientOf(q1)
        val q2 = r.pack("pack-q2", HEATER)

        val ready = s.ready(q2.bytes)

        assertEquals(TransferImportOutcome.READY, ready.outcome)
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE)), ready.returning.map { it.id }.toSet())
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q2.bytes.inputStream() })
        assertEquals("Example Water Heater, serviced", s.raw.assets.get(AssetId(HEATER))!!.name)
    }

    /**
     * The controller's ruling (R77-B3-RETURN): the lineage names a withdrawn OUT, but another OUT of the heater
     * (a double mark's leftover) stays open — so the heater is not brought back (P77-67) and nothing is written.
     */
    @Test
    fun aReturnWhileAnotherOutOfTheAssetStaysOpenIsRefused() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        s.raw.transfers.append(transferOf("w-h1", HEATER, TransferKind.WITHDRAWN, packId = "pack-q1", at = IMPORT_NOW))
        s.raw.transfers.append(transferOf("o-h1", HEATER, TransferKind.OUT, packId = "pack-q9", at = IMPORT_NOW))
        val r = recipientOf(q1)
        val q2 = r.pack("pack-q2", HEATER)
        val before = s.data()
        val records = s.raw.transfers.all()
        val files = LinkedHashMap(s.raw.storage.store.files)

        val ready = s.ready(q2.bytes)

        assertEquals(TransferImportOutcome.NOT_BROUGHT_BACK, ready.outcome)
        assertEquals(listOf(AssetId(HEATER)), ready.refused.map { it.id })
        assertEquals(false, ready.importable)
        assertEquals(before, s.data())
        assertEquals(records, s.raw.transfers.all())
        assertEquals(files, s.raw.storage.store.files)
        assertNull(s.raw.transfers.all().firstOrNull { it.kind == TransferKind.IN })
    }

    // --- fix round 1: MJ-1, mn-1, mn-2, mn-3 --------------------------------------------------------------------

    /**
     * MJ-1: the owner presses back once the return has committed — the removed documents' sweep is where the
     * cancellation lands. The committed rows stay, and so do the bytes this import staged for them.
     */
    @Test
    fun aCancellationAfterTheCommitKeepsTheStagedBytes() = runTest {
        val sender = TransferInstall("set-sender", storageOf = { raw -> cancellingOn(raw, "assets/h1/at1.pdf") })
            .also { TransferFixtures.seed(it.raw) }
        val q1 = sender.pack("pack-q1", HEATER)
        sender.mark(q1)
        val r = recipientOf(q1)
        val leaflet = "Example heater leaflet".toByteArray()
        r.attachments.upsert(
            Attachment(
                id = AttachmentId("at9"), owner = AttachmentOwner.OfAsset(AssetId(HEATER)), kind = AttachmentKind.DOCUMENT,
                displayName = "at9 file", mimeType = "application/pdf", sizeBytes = leaflet.size.toLong(),
                sha256 = InMemoryAttachmentStore.sha256Hex(leaflet), storageLocator = "assets/h1/at9.pdf",
                capturedOn = null, createdAt = IMPORT_NOW + 3, updatedAt = IMPORT_NOW + 3,
            ),
        )
        r.raw.storage.store.files["assets/h1/at9.pdf"] = leaflet
        val q2 = r.pack("pack-q2", HEATER)
        val ready = sender.ready(q2.bytes)

        val outcome = runCatching { sender.importer.import(ready) { q2.bytes.inputStream() } }

        outcome.exceptionOrNull()?.let { assertIs<CancellationException>(it) }
        assertEquals(emptySet(), heldIds(sender.raw.transfers.all()), "the return committed")
        assertTrue(sender.data().attachments.any { it.id == "at9" }, "its document row committed")
        assertTrue(leaflet.contentEquals(sender.raw.storage.store.files["assets/h1/at9.pdf"]), "and its staged bytes stay")
    }

    /**
     * mn-1/mn-2 (a): the recipient deleted the pump its round also covered, so the pack brings the heater back with a
     * group the phone still shares with the held pump. The preview refuses (P77-52); nothing is staged or written.
     */
    @Test
    fun aReturnWhoseGroupNamesAnotherHeldAssetWritesNothing() = runTest {
        val s = TransferInstall("set-sender").also { TransferFixtures.seed(it.raw) }
        s.raw.assets.upsert(plainAssetOf("p1", "Sample Pump"))
        s.raw.groups.upsert(
            groupOf("G2", "Example Pump Round", members = listOf(Triple(HEATER, "2026-01-01", null), Triple("p1", "2026-01-01", null))),
        )
        val q1 = s.pack("pack-q1", HEATER, "p1")
        s.mark(q1)
        val r = recipientOf(q1)
        r.raw.assets.delete(AssetId("p1"))
        val q2 = r.pack("pack-q2", HEATER)
        val before = s.data()
        val records = s.raw.transfers.all()
        val files = LinkedHashMap(s.raw.storage.store.files)

        val refusal = runCatching { s.preview(q2.bytes) }.exceptionOrNull()

        assertIs<IllegalStateException>(refusal, "the preview refuses: ${refusal ?: "it offered a plan"}")
        assertEquals(before, s.data())
        assertEquals(records, s.raw.transfers.all())
        assertEquals(files, s.raw.storage.store.files)
    }

    /** mn-2 (b): a held component the pack does not carry still points at the returning parent — refused, nothing written. */
    @Test
    fun aHeldChildOfAReturningParentWritesNothing() = runTest {
        val s = TransferInstall("set-sender")
        s.raw.assets.upsert(plainAssetOf("p1", "Sample Pump"))
        s.raw.assets.upsert(plainAssetOf("c1", "Sample Pump Motor").copy(parentAssetId = AssetId("p1")))
        val q1 = s.pack("pack-q1", "p1")
        s.mark(q1)
        val r = TransferInstall("set-recipient")
        assertIs<TransferImportResult.Imported>(r.import(q1.bytes))
        val motor = r.raw.assets.get(AssetId("c1"))!!
        r.assets.upsert(motor.copy(parentAssetId = null, updatedAt = IMPORT_NOW + 1))
        val q2 = r.pack("pack-q2", "p1")
        assertEquals(listOf("p1"), q2.created.assetIds.map { it.value })
        val before = s.data()
        val records = s.raw.transfers.all()
        val files = LinkedHashMap(s.raw.storage.store.files)

        val refusal = runCatching { s.preview(q2.bytes) }.exceptionOrNull()

        assertIs<IllegalStateException>(refusal, "the preview refuses: ${refusal ?: "it offered a plan"}")
        assertEquals(before, s.data())
        assertEquals(records, s.raw.transfers.all())
        assertEquals(files, s.raw.storage.store.files)
    }

    /** mn-3 (R77-B2a-MJ1): the very pack this phone marked out is its own departure, never a return. */
    @Test
    fun mySentPackItselfIsNotAReturn() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        val before = s.data()
        val records = s.raw.transfers.all()

        val ready = s.ready(q1.bytes)

        assertEquals(TransferImportOutcome.NOT_BROUGHT_BACK, ready.outcome)
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE)), ready.refused.map { it.id }.toSet())
        assertEquals(emptyList(), ready.returning)
        assertEquals(before, s.data())
        assertEquals(records, s.raw.transfers.all())
    }

    /**
     * mn-3: out in q1, back in q2 — so q1's OUT is closed here and history. A pack whose lineage is `[q1]` then brings
     * nothing back: after the return it is only what is already here, and once the heater is out again in q3 it is
     * refused (P77-67).
     */
    @Test
    fun aPackNamingAnOutAlreadyClosedHereIsRefused() = runTest {
        val (s, q1) = senderAfterTheTransfer()
        val r = recipientOf(q1)
        val q2 = r.pack("pack-q2", HEATER)
        assertEquals(listOf("pack-q1"), q2.created.lineage[HEATER])
        assertIs<TransferImportResult.Imported>(s.import(q2.bytes))

        val closed = s.ready(q2.bytes)
        assertEquals(emptyList(), closed.returning, "a closed OUT is history, not a return")
        assertEquals(TransferImportOutcome.ALREADY_HERE, closed.outcome)

        s.mark(s.pack("pack-q3", HEATER))
        val before = s.data()
        val records = s.raw.transfers.all()

        val stale = s.ready(q2.bytes)

        assertEquals(TransferImportOutcome.NOT_BROUGHT_BACK, stale.outcome)
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE)), stale.refused.map { it.id }.toSet())
        assertEquals(before, s.data())
        assertEquals(records, s.raw.transfers.all())
    }

    /** The folder [raw] hands out, whose store throws a cancellation when asked to delete [locator]. */
    private fun cancellingOn(raw: AttachmentStorage, locator: String): AttachmentStorage = object : AttachmentStorage {
        override fun state() = raw.state()
        override fun store(): AttachmentStore? = raw.store()?.let { inner ->
            object : AttachmentStore by inner {
                override suspend fun delete(locator2: String) {
                    if (locator2 == locator) throw CancellationException("the owner pressed back after the commit")
                    inner.delete(locator2)
                }
            }
        }
    }

    // --- #86 (C6, Hazard 2; R86-16): the local successions come back with the asset ------------------------

    /**
     * The return deletes the returning assets' rows and relies on the schema's cascades, which take every succession
     * naming one — here a returning predecessor (heater → compressor) and a returning successor (opener → anode). The
     * return writes them back unchanged after the pack's assets, as it keeps the returned loan; the pack never
     * carried one (SENDER_ONLY), so there is no second copy to collide with.
     */
    @Test
    fun aReturnKeepsTheLocalSuccessionRows() = runTest {
        val s = TransferInstall("set-sender").also { TransferFixtures.seed(it.raw) }
        val rows = listOf(
            successionOf("s1", predecessor = HEATER, successor = TransferFixtures.COMPRESSOR),
            successionOf("s2", predecessor = TransferFixtures.OPENER, successor = ANODE, replacedOn = "2025-04-01"),
        )
        rows.forEach { s.successions.append(it) }
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        assertEquals(rows, s.raw.successions.all(), "marking touches no succession")
        val q2 = recipientOf(q1).pack("pack-q2", HEATER)

        assertIs<TransferImportResult.Imported>(s.import(q2.bytes))

        assertEquals(emptySet(), heldIds(s.raw.transfers.all()), "the heater and its anode are back")
        assertEquals(rows, s.raw.successions.all(), "both rows are back, unchanged")
    }

    /**
     * MJ-2: A → B, where A returns and B left in another pack and is still held. The row existed before the
     * transaction, so the return writes it back through the unguarded store — I8 governs new rows only — and the
     * return applies. Through the guarded port it would be refused as `AssetTransferredOut` and roll back.
     */
    @Test
    fun aReturnKeepsASuccessionWhoseOtherEndIsStillHeld() = runTest {
        val s = TransferInstall("set-sender").also { TransferFixtures.seed(it.raw) }
        val row = successionOf("s1", predecessor = HEATER, successor = TransferFixtures.OPENER)
        s.successions.append(row)
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        s.mark(s.pack("pack-o1", TransferFixtures.OPENER))
        assertEquals(setOf(AssetId(HEATER), AssetId(ANODE), AssetId(TransferFixtures.OPENER)), heldIds(s.raw.transfers.all()))
        val q2 = recipientOf(q1).pack("pack-q2", HEATER)

        assertIs<TransferImportResult.Imported>(s.import(q2.bytes))

        assertEquals(setOf(AssetId(TransferFixtures.OPENER)), heldIds(s.raw.transfers.all()), "the opener is still held")
        assertEquals(listOf(row), s.raw.successions.all(), "the row is back")
    }
}
