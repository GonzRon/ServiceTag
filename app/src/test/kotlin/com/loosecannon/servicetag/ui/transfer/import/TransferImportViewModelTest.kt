package com.loosecannon.servicetag.ui.transfer.`import`

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.usecase.TransferImportOutcome
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.backup.NoAttachmentFolder
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.HEATER
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.MANUAL_AT
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.entriesOf
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.seal
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.seedHeater
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.zipOf
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #77 (B3; C16, row 26) — the import screen's state machine, over a Room-backed [FakeGraph] and the production
 * import: reading → preview (P77-39–42, the counts, P77-66, P77-46) → P77-43 / P77-44 + P77-45 / P77-67 / Import →
 * P77-50, or a reader refusal (P77-47–49), the door's folder sentence, or P77-52. Import runs once, Cancel writes
 * nothing, and every end deletes the copy. The packs are made by the production creation on a second graph.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferImportViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var sender: FakeGraph
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        sender = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        sender.close()
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun copyOf(bytes: ByteArray): File = graph.transferPackInbox.copyIn(ByteSource { bytes.inputStream() })

    private var sweeps = 0
    private val sweep = ReminderReconcile { sweeps += 1 }

    private fun model(
        copy: File?,
        on: FakeGraph = graph,
        sentence: String = NoAttachmentFolder().message,
        reconcile: ReminderReconcile = sweep,
    ) = TransferImportViewModel(
        on.importTransferPack, on.transferPackInbox, copy, sentence, reconcile, zone = ZoneOffset.UTC,
        io = StandardTestDispatcher(scheduler),
    )

    private suspend fun heaterPack(note: String = "Example handover note"): AppPack {
        seedHeater(sender)
        return seal(sender, HEATER, note = note)
    }

    @Test
    fun aPackPreviewsItsLinesAndOffersImport() = runTest(scheduler) {
        sender.now = 1_790_467_200_000L // 27 Sep 2026, 00:00 UTC
        val model = model(copyOf(heaterPack().bytes))
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(TransferImportPhase.PREVIEW, state.phase)
        assertEquals("Note: Example handover note", state.note)
        assertEquals("Created 27 Sep 2026", state.created)
        assertEquals(listOf("1 asset", "1 NFC tag", "1 document or photo"), state.contains)
        assertEquals(emptyList<String>(), state.outcome)
        assertTrue(state.importEnabled)
    }

    @Test
    fun importSaysP77_50AndTheRowsArrive() = runTest(scheduler) {
        val copy = copyOf(heaterPack().bytes)
        val model = model(copy)
        advanceUntilIdle()

        model.import()
        advanceUntilIdle()

        assertEquals(TransferImportPhase.DONE, model.state.value.phase)
        assertEquals("Transfer Pack imported: 1 asset, 1 NFC tag, 1 document or photo", model.state.value.done)
        assertEquals("Example Water Heater", graph.assets.get(AssetId(HEATER))?.name)
        assertEquals(listOf(TransferKind.IN), graph.transferRecords.all().map { it.kind })
        assertTrue(MANUAL_AT in graph.attachmentStorage.store.files)
    }

    /**
     * #84 C3 (77-1, D-3): the Backup door hands P77-50 to the Backup screen exactly once — nothing before DONE, the
     * line once at DONE, then nothing; nothing after a refusal. The hand-off is not Cancel's exit.
     */
    @Test
    fun theDoneLineIsHandedOverOnce() = runTest(scheduler) {
        val model = model(copyOf(heaterPack().bytes))
        advanceUntilIdle()
        assertEquals(TransferImportPhase.PREVIEW, model.state.value.phase)
        assertNull("nothing before DONE", model.handOff())

        model.import()
        advanceUntilIdle()
        assertEquals(TransferImportPhase.DONE, model.state.value.phase)
        assertEquals("Transfer Pack imported: 1 asset, 1 NFC tag, 1 document or photo", model.state.value.done)
        assertEquals(model.state.value.done, model.handOff())
        assertNull("once", model.handOff())
        assertFalse("not Cancel's exit", model.state.value.finished)
        model.cancel()
        assertFalse("a back after the hand-off does not pop again", model.state.value.finished)

        val refused = model(copyOf(zipOf(listOf("readme.txt" to "Example".toByteArray()))))
        advanceUntilIdle()
        assertEquals(TransferImportPhase.REFUSED, refused.state.value.phase)
        assertNull("nothing after a refusal", refused.handOff())
    }

    @Test
    fun aCancelAtDoneBeforeTheHandOffHandsNothingOver() = runTest(scheduler) {
        val model = model(copyOf(heaterPack().bytes))
        advanceUntilIdle()
        model.import()
        advanceUntilIdle()
        assertEquals(TransferImportPhase.DONE, model.state.value.phase)
        model.cancel()
        assertTrue("cancel's exit", model.state.value.finished)
        assertNull("no second pop through the hand-off", model.handOff())
    }

    @Test
    fun theOutcomesSayTheirSentences() = runTest(scheduler) {
        val pack = heaterPack()
        // P77-43: already here.
        val first = model(copyOf(pack.bytes)).also { advanceUntilIdle(); it.import(); advanceUntilIdle() }
        assertEquals(TransferImportPhase.DONE, first.state.value.phase)
        val again = model(copyOf(pack.bytes)).also { advanceUntilIdle() }
        assertEquals(listOf("This Transfer Pack is already on this phone."), again.state.value.outcome)
        // #102: the screen styles the line by the use case's outcome, never by comparing its words.
        assertEquals(TransferImportOutcome.ALREADY_HERE, again.state.value.outcomeKind)
        assertFalse(again.state.value.importEnabled)

        // P77-44 and P77-45: the heater's tag is bound here to another asset.
        val other = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        other.assets.upsert(com.loosecannon.servicetag.core.model.Asset(id = AssetId("r1"), name = "Sample Pump", createdAt = 1, updatedAt = 1))
        other.tags.upsert(
            com.loosecannon.servicetag.core.model.TagBinding(
                id = com.loosecannon.servicetag.core.model.TagId("rt1"),
                payloadFormat = com.loosecannon.servicetag.core.model.PayloadFormat.V1, payloadKey = "TEST-0001",
                target = com.loosecannon.servicetag.core.model.TagTarget.AssetTarget(AssetId("r1")), createdAt = 1, updatedAt = 1,
            ),
        )
        val conflicted = model(other.transferPackInbox.copyIn(ByteSource { pack.bytes.inputStream() }), on = other)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "This Transfer Pack conflicts with records on this phone, so nothing was imported.",
                "An NFC tag in this pack is already used for Sample Pump here.",
            ),
            conflicted.state.value.outcome,
        )
        assertEquals(TransferImportOutcome.CONFLICTS, conflicted.state.value.outcomeKind)
        other.close()

        // P77-67: the heater is held here, and this pack does not bring it back.
        val holder = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        holder.assets.upsert(com.loosecannon.servicetag.core.model.Asset(id = AssetId(HEATER), name = "Example Water Heater", createdAt = 1, updatedAt = 1))
        holder.transferRecords.append(outOf(HEATER, "pack-elsewhere"))
        val refused = model(holder.transferPackInbox.copyIn(ByteSource { pack.bytes.inputStream() }), on = holder)
        advanceUntilIdle()
        assertEquals(
            listOf("Example Water Heater was transferred out from this phone, and this Transfer Pack does not bring it back."),
            refused.state.value.outcome,
        )
        assertEquals(TransferImportOutcome.NOT_BROUGHT_BACK, refused.state.value.outcomeKind)
        assertFalse(refused.state.value.importEnabled)
        holder.close()
    }

    /** P77-66 on the Room graph: the sender marks the heater out; the recipient's own pack brings it back. */
    @Test
    fun aReturnSaysComingBackAndReplacesTheStaleGraph() = runTest(scheduler) {
        val q1 = heaterPack()
        sender.markTransferredOut.run(q1.created)
        assertEquals(setOf(AssetId(HEATER)), heldIds(sender.transferRecords.all()))
        model(copyOf(q1.bytes)).also { advanceUntilIdle(); it.import(); advanceUntilIdle() }
        repeat(10) { graph.ids.newId() } // the two graphs count ids alike; the recipient's pack gets its own
        val q2 = seal(graph, HEATER)

        val back = model(sender.transferPackInbox.copyIn(ByteSource { q2.bytes.inputStream() }), on = sender)
        advanceUntilIdle()
        assertEquals(listOf("Coming back: Example Water Heater"), back.state.value.comingBack)
        back.import()
        advanceUntilIdle()

        assertEquals(TransferImportPhase.DONE, back.state.value.phase)
        assertEquals(emptySet<AssetId>(), heldIds(sender.transferRecords.all()))
        assertEquals(
            com.loosecannon.servicetag.core.model.AssetStatus.ACTIVE,
            sender.assets.get(AssetId(HEATER))?.status,
        )
        assertEquals(listOf("t1"), sender.tags.all().map { it.id.value })
    }

    @Test
    fun readerRefusalsSayTheirSentences() = runTest(scheduler) {
        val pack = heaterPack().bytes
        val notAPack = model(copyOf(zipOf(listOf("readme.txt" to "Example".toByteArray()))))
        val damaged = model(copyOf(pack.copyOf(pack.size / 2)))
        val entries = entriesOf(pack)
        val manifest = String(entries.first().second).replace(Regex("\"packFormatVersion\"\\s*:\\s*1"), "\"packFormatVersion\": 2")
        val newer = model(copyOf(zipOf(listOf(entries.first().first to manifest.toByteArray()) + entries.drop(1))))
        advanceUntilIdle()

        assertEquals("This file is not a Transfer Pack.", notAPack.state.value.refusal)
        assertEquals("This Transfer Pack is damaged and cannot be imported.", damaged.state.value.refusal)
        assertEquals("This Transfer Pack needs a newer version of ServiceTag.", newer.state.value.refusal)
        assertTrue(listOf(notAPack, damaged, newer).all { it.state.value.phase == TransferImportPhase.REFUSED })
    }

    @Test
    fun noFolderSaysTheBackupDoorsSentence() = runTest(scheduler) {
        val copy = copyOf(heaterPack().bytes)
        graph.attachmentStorage.state = StoreState.NotConfigured

        val model = model(copy)
        advanceUntilIdle()

        assertEquals("Choose an attachment folder in Settings first", model.state.value.refusal)
    }

    @Test
    fun aDoubleTapImportsOnce() = runTest(scheduler) {
        val model = model(copyOf(heaterPack().bytes))
        advanceUntilIdle()

        model.import()
        model.import()
        advanceUntilIdle()

        assertEquals(TransferImportPhase.DONE, model.state.value.phase)
        assertEquals(1, graph.transferRecords.all().size)
    }

    @Test
    fun cancelWritesNothing() = runTest(scheduler) {
        val copy = copyOf(heaterPack().bytes)
        val model = model(copy)
        advanceUntilIdle()

        model.cancel()
        advanceUntilIdle()

        assertTrue(model.state.value.finished)
        assertEquals(emptyList<Any>(), graph.assets.all())
        assertEquals(emptyList<Any>(), graph.transferRecords.all())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
        assertFalse(copy.exists())
    }

    @Test
    fun everyEndDeletesTheCopy() = runTest(scheduler) {
        val pack = heaterPack().bytes
        val refused = copyOf(zipOf(listOf("readme.txt" to "Example".toByteArray())))
        val imported = copyOf(pack)
        model(refused)
        val importing = model(imported)
        advanceUntilIdle()
        assertFalse("a refusal deletes its copy", refused.exists())
        assertTrue("a preview keeps its copy until Import", imported.exists())

        importing.import()
        advanceUntilIdle()
        assertFalse("an import deletes its copy", imported.exists())

        val nothingToDo = copyOf(pack)
        model(nothingToDo)
        advanceUntilIdle()
        assertFalse("a preview with nothing to import deletes its copy", nothingToDo.exists())
        assertNull(graph.transferPackInbox.find(nothingToDo.name))
    }

    /**
     * R77-IMPORT-SWEEP: a successful import runs exactly one reminder sweep, after the write; a preview, a pack with
     * nothing to import and a reader refusal run none.
     */
    @Test
    fun aSuccessfulImportRunsOneReminderSweep() = runTest(scheduler) {
        val pack = heaterPack().bytes
        val model = model(copyOf(pack))
        advanceUntilIdle()
        assertEquals("nothing before Import", 0, sweeps)

        model.import()
        advanceUntilIdle()

        assertEquals(TransferImportPhase.DONE, model.state.value.phase)
        assertEquals("one sweep, after the write", 1, sweeps)
        model(copyOf(pack)) // already here: nothing to import
        model(copyOf(zipOf(listOf("readme.txt" to "Example".toByteArray())))) // not a pack
        advanceUntilIdle()
        assertEquals(1, sweeps)
    }

    /** R77-IMPORT-SWEEP: the sweep is a post-write step, not a condition of success — one that fails leaves P77-50. */
    @Test
    fun aFailedSweepNeverFailsACommittedImport() = runTest(scheduler) {
        val failing = ReminderReconcile {
            sweeps += 1
            throw IllegalStateException("the sweep failed")
        }
        val model = model(copyOf(heaterPack().bytes), reconcile = failing)
        advanceUntilIdle()

        model.import()
        advanceUntilIdle()

        assertEquals("the sweep ran", 1, sweeps)
        assertEquals(TransferImportPhase.DONE, model.state.value.phase)
        assertEquals("Transfer Pack imported: 1 asset, 1 NFC tag, 1 document or photo", model.state.value.done)
        assertNull(model.state.value.refusal)
        assertEquals(listOf(TransferKind.IN), graph.transferRecords.all().map { it.kind })
    }

    private fun outOf(asset: String, pack: String) = TransferRecord(
        id = "out-$asset", assetId = AssetId(asset), kind = TransferKind.OUT, packId = pack, lineage = emptyList(),
        at = 1_758_960_000_000L, packSha256 = "ab".repeat(32), nameSnapshot = "Example Water Heater", note = "",
    )
}
