package com.loosecannon.servicetag.ui.scan

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.loosecannon.nfc.tagcore.NdefEnvelope
import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.OverwriteReasons
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.usecase.OverwriteSubject
import com.loosecannon.servicetag.core.usecase.OverwriteSubjects
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.data.room.AppDatabase
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The write flow's decision logic on the nfc-tag-core seam, on the JVM: the library's [TagIo]
 * stands in for the tag, and the Room-backed [FakeGraph] supplies the real `ProvisionTag`, so what
 * a test asserts about the row is what the database actually holds.
 *
 * One dispatcher carries the controller's scope, its io hop AND Room's query context, so provision,
 * inspect, write and complete all land on the single test scheduler and `advanceUntilIdle()` is a
 * real settle rather than a hope (`inMemoryDb()` puts Room on `Dispatchers.Default`, which would
 * leave the row write racing the assertion).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagWriteControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var graph: FakeGraph
    private lateinit var provision: CountingTags
    private lateinit var io: FakeTagIo
    private lateinit var controller: TagWriteController

    @Before fun setUp() {
        graph = FakeGraph(
            db = Room.inMemoryDatabaseBuilder<AppDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(dispatcher)
                .build(),
        )
        provision = CountingTags(graph.tags)
        io = FakeTagIo()
    }

    @After fun tearDown() = graph.close()

    /**
     * Both scopes are children of `backgroundScope`, never a detached `CoroutineScope`: a throw
     * that escapes `onTag`'s `scope.launch` then fails the test instead of being swallowed by a
     * scope nobody is watching. Still the one dispatcher, so `advanceUntilIdle()` still settles
     * provision, inspect, write and complete together.
     */
    private fun TestScope.controller(
        target: TagTarget = TagTarget.None,
        label: String? = null,
        resolveTag: ResolveTag = ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock),
    ): TagWriteController {
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        return TagWriteController(
            provisionTag = ProvisionTag(provision, graph.assets, graph.uow, graph.ids, graph.clock),
            appScope = scope,
            io = io,
            codec = graph.ndefCodec,
            target = target,
            label = label,
            scope = scope,
            resolveTag = resolveTag,
            ioDispatcher = dispatcher,
        )
    }

    /** The one row the controller provisions on its first writable tap. */
    private suspend fun theRow() = graph.tags.all().single()

    // --- the cases that came over from the 1B controller, on the library's shapes ---------------

    @Test fun emptyTagIsWrittenWithoutAsking() = runTest(dispatcher) {
        controller = controller()
        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = false)

        controller.onTag(handle); advanceUntilIdle()

        assertIs<WriteState.Written>(controller.state.value)
        assertEquals(1, io.writeAttempts)
        assertEquals("the bytes that reach the seam are the ones the codec planned", intended, io.lastWriteRecords)
        assertNotNull("an empty tag must not raise a question", theRow().writtenAt)
    }

    // --- #49, review fix round 1 finding 1: the placement the screen types must reach the row ---

    /** A placement typed via `setLabel` before the tap is carried into the row `begin` provisions. */
    @Test fun aPlacementSetBeforeTheTapIsCarriedIntoTheProvisionedRow() = runTest(dispatcher) {
        controller = controller()
        controller.setLabel("Indoor head")
        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = false)

        controller.onTag(handle); advanceUntilIdle()

        assertEquals("Indoor head", theRow().label)
    }

    /** `ProvisionTag`'s own blank-to-null normalisation is what a blank placement relies on. */
    @Test fun aBlankPlacementLeavesTheRowsLabelNull() = runTest(dispatcher) {
        controller = controller()
        controller.setLabel("   ")
        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = false)

        controller.onTag(handle); advanceUntilIdle()

        assertNull(theRow().label)
    }

    /** Finding 2's fix: the field must stop accepting input once the row is provisioned. */
    @Test fun placementLocksTheInstantTheRowIsProvisioned() = runTest(dispatcher) {
        controller = controller()
        assertEquals(false, controller.placementLocked.value)

        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(handle); advanceUntilIdle()

        assertEquals(true, controller.placementLocked.value)
    }

    @Test fun foreignContentAsksFirstAndKeepItWritesNothing() = runTest(dispatcher) {
        controller = controller()
        io.inspection = foreign137

        controller.onTag(handle); advanceUntilIdle()

        val asked = controller.state.value as WriteState.Confirm
        assertTrue("the question names what is on the tag: ${asked.subject.line}", "foreign" in asked.subject.line)
        assertEquals(0, io.writeAttempts)

        controller.keepIt(); advanceUntilIdle()
        assertEquals(WriteState.Idle("Not written. The tag was left as it was."), controller.state.value)
        assertEquals(0, io.writeAttempts)
        // The row stays provisioned for the next tap; it is only abandoned when the screen closes.
        assertNull(theRow().writtenAt)
    }

    @Test fun confirmedOverwriteIsHonouredOnTheNextTapWithoutAskingAgain() = runTest(dispatcher) {
        controller = controller()
        io.inspection = foreign137
        controller.onTag(handle); advanceUntilIdle()
        assertIs<WriteState.Confirm>(controller.state.value)

        controller.confirmOverwrite(); advanceUntilIdle()

        // Same tag, same content, a fresh handle: the consent carries over and nothing is asked.
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(FakeHandle(uid = handle.uid)); advanceUntilIdle()

        assertIs<WriteState.Written>(controller.state.value)
        assertEquals("a confirmed overwrite asks once and writes once", 1, io.writeAttempts)
    }

    @Test fun readBackMismatchDoesNotCompleteTheRow() = runTest(dispatcher) {
        controller = controller()
        io.inspection = writable137
        io.writeResult = WriteResult.VerifyMismatch(emptyList())

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(
            WriteState.Error("Read-back differs from what was written. Nothing recorded — try again."),
            controller.state.value,
        )
        assertNull("nothing may be recorded for a tag that read back differently", theRow().writtenAt)
    }

    /** The old format path's two claims on the new route: the uid is recorded, nothing is locked blind. */
    @Test fun formatThenWriteRecordsTheUidAndNeverLocksBlind() = runTest(dispatcher) {
        controller = controller()
        controller.setLock(true)
        io.inspection = formatable

        controller.onTag(handle); advanceUntilIdle()
        assertEquals("a formatted tag is not written on the same tap", 0, io.writeAttempts)
        assertEquals("a formatted tag is never locked blind", 0, io.lockCalls)

        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = true)
        controller.onTag(handle); advanceUntilIdle()

        val done = controller.state.value as WriteState.Written
        assertTrue(done.locked)
        assertEquals("the lock rides the write; the standalone lock is never called", 0, io.lockCalls)
        assertEquals(1, io.writeAttempts)
        assertEquals(handle.uid, theRow().physicalUid)
    }

    @Test fun abandonDeletesOnlyAnUnwrittenRow() = runTest(dispatcher) {
        controller = controller()
        // A tap that provisions a row and writes nothing: the question is still up.
        io.inspection = foreign137
        controller.onTag(handle); advanceUntilIdle()
        assertIs<WriteState.Confirm>(controller.state.value)
        assertEquals(1, graph.tags.all().size)

        controller.abandonIfUnwritten()?.join()
        assertEquals("a row whose tag was never written leaves nothing behind", 0, graph.tags.all().size)

        val second = controller()
        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        second.onTag(handle); advanceUntilIdle()
        assertIs<WriteState.Written>(second.state.value)

        second.abandonIfUnwritten()?.join()
        assertEquals("a written tag's row survives the screen closing", 1, graph.tags.all().size)
    }

    /** Correction 2's other half: a tag that is not NDEF at all creates no product state either. */
    @Test fun aTagThatIsNotNdefAtAllIsRefusedAndProvisionsNothing() = runTest(dispatcher) {
        controller = controller()
        io.inspection = null

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(
            WriteState.Error("This tag does not support NDEF. Use an NTAG213/215/216 or similar."),
            controller.state.value,
        )
        assertEquals(0, provision.begun)
        assertEquals(0, graph.tags.all().size)
    }

    // --- Phase G, the rulings the seam made expressible -----------------------------------------

    /** R1/R2 — a formatable tag: tap one formats and writes nothing; tap two writes. */
    @Test fun aFormatableTagIsFormattedOnTapOneAndWrittenOnTapTwo() = runTest(dispatcher) {
        controller = controller()
        io.inspection = TagInspection("04a1", TagRead.Readable(emptyList()), maxSize = -1, writable = true, needsFormat = true, canLock = true)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(1, io.formatCount); assertEquals(0, io.writeAttempts)
        assertEquals(WriteState.Idle("Formatted. Lift the tag off and hold it again to write."), controller.state.value)
        assertEquals(0, provision.begun)                       // a format-only tap creates no row (correction 2)
        io.inspection = TagInspection("04a1", TagRead.Readable(emptyList()), maxSize = 137, writable = true, needsFormat = false, canLock = true)
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(1, io.writeAttempts); assertIs<WriteState.Written>(controller.state.value); assertEquals(1, provision.begun); assertTrue(provision.completed)
    }

    /** E1 — a failed format is one sentence; the library's reason is the log's business, not the user's. */
    @Test fun aFailedFormatIsOneSentenceWithoutTheLibraryReason() = runTest(dispatcher) {
        controller = controller()
        io.inspection = TagInspection("04a1", TagRead.Readable(emptyList()), maxSize = -1, writable = true, needsFormat = true, canLock = true)
        io.formatResult = WriteResult.Failed("format threw", cause = IOException("lost"), attempted = true)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(WriteState.Error("Could not format the tag. Hold it still and try again."), controller.state.value)
        assertEquals(0, io.writeAttempts)
        assertEquals(0, provision.begun)
    }

    /** C1 — unreadable NDEF is never treated as an empty tag. */
    @Test fun anUnreadableTagAsksBeforeItIsOverwritten() = runTest(dispatcher) {
        controller = controller()
        io.inspection = TagInspection("04a1", TagRead.Unreadable("NDEF on tag could not be parsed", null), maxSize = 137, writable = true, needsFormat = false, canLock = true)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(
            WriteState.Confirm(OverwriteSubject("The tag already holds unreadable NDEF content (NDEF on tag could not be parsed).", null)),
            controller.state.value,
        )
        assertEquals(0, io.writeAttempts)
    }

    /** Invariant 7 — the exact message against the measured capacity, before any consent question. */
    @Test fun aTagTooSmallForTheMessageIsRefusedWithoutWriting() = runTest(dispatcher) {
        controller = controller()
        io.inspection = TagInspection("04a1", TagRead.Readable(emptyList()), maxSize = 94, writable = true, needsFormat = false, canLock = true)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(WriteState.Error("Tag too small: it holds 94 bytes, the message needs 95."), controller.state.value)
        assertEquals(0, io.writeAttempts)
    }

    /** I1 — attempted says which sentence, never the reason text. */
    @Test fun aRefusedWriteAndAnIndeterminateWriteAreWordedDifferently() = runTest(dispatcher) {
        controller = controller()
        io.inspection = writable137
        io.writeResult = WriteResult.Failed("tag still needs formatting", attempted = false)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(WriteState.Error("Nothing was written (tag still needs formatting). Hold the tag still and try again."), controller.state.value)
        io.writeResult = WriteResult.Failed("tag left the field", cause = IOException("lost"), attempted = true)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(WriteState.Error("The write may not have finished (tag left the field). Lift the tag off and hold it to the phone again."), controller.state.value)
    }

    /** G-6 — the lock rides on the write; the standalone lock is never called. */
    @Test fun lockIsAppliedByTheWriteItself() = runTest(dispatcher) {
        controller = controller()
        controller.setLock(true)
        io.inspection = writable137
        io.writeResult = WriteResult.Written(intended, 95, locked = true)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(true, io.lastWriteLock); assertEquals(null, io.lastLockExpected)
        assertEquals(WriteState.Written(rowId, locked = true), controller.state.value)
    }

    /** Invariant 10 — consent is recorded, never written through the sheet's (stale) handle. */
    @Test fun confirmingRecordsConsentAndPerformsNoTagIo() = runTest(dispatcher) {
        controller = controller()
        io.inspection = holdingOtherTag                      // Readable(records of another ServiceTag id), writable, 137
        controller.onTag(handle); advanceUntilIdle()
        assertIs<WriteState.Confirm>(controller.state.value)
        controller.confirmOverwrite(); advanceUntilIdle()
        assertEquals(0, io.writeAttempts)
        assertEquals(WriteState.Idle("Overwrite confirmed. Hold the same tag to the phone again to write."), controller.state.value)
    }

    /** The fresh tap carrying the SAME content consumes the consent and writes through its own handle. */
    @Test fun theNextTapWithTheSameContentWritesThroughTheFreshHandle() = runTest(dispatcher) {
        controller = controller()
        io.inspection = holdingOtherTag
        controller.onTag(handle); advanceUntilIdle(); controller.confirmOverwrite(); advanceUntilIdle()
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        val fresh = FakeHandle(uid = "04a1-second-discovery")
        controller.onTag(fresh); advanceUntilIdle()
        assertEquals(1, io.writeAttempts); assertEquals(fresh, io.lastWriteHandle)
        assertIs<WriteState.Written>(controller.state.value)
    }

    /** A fresh tap carrying DIFFERENT content discards the consent and asks again. */
    @Test fun theNextTapWithDifferentContentAsksAgain() = runTest(dispatcher) {
        controller = controller()
        io.inspection = holdingOtherTag
        controller.onTag(handle); advanceUntilIdle(); controller.confirmOverwrite(); advanceUntilIdle()
        io.inspection = holdingAThirdTag                     // a different ServiceTag id
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(0, io.writeAttempts)
        assertIs<WriteState.Confirm>(controller.state.value)
        // and that second question, once confirmed, is honoured on the next matching tap
        controller.confirmOverwrite(); advanceUntilIdle()
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(1, io.writeAttempts)
    }

    /** R4 — a read failure is one fixed sentence and the next tap is still handled. */
    @Test fun aTagThatCannotBeReadIsOneSentenceAndTheNextTapStillWorks() = runTest(dispatcher) {
        controller = controller()
        io.inspectFailure = IOException("lost")
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(WriteState.Error("Could not read the tag. Hold it still and try again."), controller.state.value)
        io.inspectFailure = null; io.inspection = writable137; io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(handle); advanceUntilIdle()
        assertEquals(2, io.inspectCount); assertIs<WriteState.Written>(controller.state.value)
    }

    // --- #70: the question names what the tag already identifies --------------------------------

    @Test fun aTagBoundToAnAssetIsNamedInTheQuestion() = runTest(dispatcher) {
        seedKnownRow()
        controller = controller()
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()

        val asked = assertIs<WriteState.Confirm>(controller.state.value)
        assertEquals("This tag currently identifies Pump 3.", asked.subject.line)
        assertEquals("11111111 · v1", asked.subject.identifier)
        assertEquals(identityLine(OTHER_TAG), asked.subject.identifier)
        assertEquals(0, io.writeAttempts)
    }

    @Test fun aPlacementRidesOnTheIdentifierLine() = runTest(dispatcher) {
        seedKnownRow(label = "Pump house")
        controller = controller()
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(
            WriteState.Confirm(OverwriteSubject("This tag currently identifies Pump 3.", "11111111 · v1 · Pump house")),
            controller.state.value,
        )
    }

    @Test fun anUnknownV1TagIsSaidToBeNotInTheRecords() = runTest(dispatcher) {
        controller = controller()
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(
            WriteState.Confirm(OverwriteSubject("This ServiceTag tag is not in this phone's records.", "11111111 · v1")),
            controller.state.value,
        )
        assertEquals(0, io.writeAttempts)
    }

    /** The row still targets an asset that exists; the question must not name it (AC 3). */
    @Test fun aLostTagIsSaidToBeLost() = runTest(dispatcher) {
        seedKnownRow(status = TagStatus.LOST)
        controller = controller()
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()

        val asked = assertIs<WriteState.Confirm>(controller.state.value)
        assertEquals(OverwriteSubject("This tag was marked lost and taken out of service.", "11111111 · v1"), asked.subject)
        assertFalse(ASSET_NAME in asked.subject.line)
    }

    @Test fun aNewerTagKeepsTheNewerAppLine() = runTest(dispatcher) {
        controller = controller()
        io.inspection = readable(NdefEnvelope.encode(graph.tagIdentity, byteArrayOf(2, 0)))

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(
            WriteState.Confirm(OverwriteSubject("The tag already holds a ServiceTag tag written by a newer app (format 2).", null)),
            controller.state.value,
        )
        assertEquals(0, io.writeAttempts)
    }

    /** AC 5: a lookup that throws still asks, and "Keep it" still leaves everything as it was. */
    @Test fun aFailedLookupStillAsksAndKeepItWritesNothing() = runTest(dispatcher) {
        seedKnownRow()
        val seeded = graph.tags.all()
        val lookup = ScriptedLookup(graph.tags).apply { failure = IllegalStateException("store unavailable") }
        controller = controller(resolveTag = lookup.resolver())
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(WriteState.Confirm(OverwriteSubject(COULD_NOT_CHECK, "11111111 · v1")), controller.state.value)
        val whileAsking = graph.tags.all()
        controller.keepIt(); advanceUntilIdle()
        assertEquals(WriteState.Idle("Not written. The tag was left as it was."), controller.state.value)
        assertEquals(0, io.writeAttempts)
        assertEquals(whileAsking, graph.tags.all())
        assertEquals(seeded, graph.tags.all().filter { it.id.value != rowId })
    }

    /** AC 5: the failed lookup's sheet is an ordinary question — confirm, lift, retap, written. */
    @Test fun aFailedLookupStillAsksAndOverwriteWritesOnTheRetap() = runTest(dispatcher) {
        seedKnownRow()
        val lookup = ScriptedLookup(graph.tags).apply { failure = IllegalStateException("store unavailable") }
        controller = controller(resolveTag = lookup.resolver())
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()
        assertEquals(WriteState.Confirm(OverwriteSubject(COULD_NOT_CHECK, "11111111 · v1")), controller.state.value)

        controller.confirmOverwrite(); advanceUntilIdle()
        assertEquals(WriteState.Idle("Overwrite confirmed. Hold the same tag to the phone again to write."), controller.state.value)
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(FakeHandle(uid = handle.uid)); advanceUntilIdle()

        assertIs<WriteState.Written>(controller.state.value)
        assertEquals("a confirmed overwrite asks once and writes once", 1, io.writeAttempts)
        assertEquals("the retap consumes consent without asking again", 1, lookup.lookups)
    }

    /** C7: a cancelled lookup shows no sheet, writes nothing, leaves no consent and frees the tap. */
    @Test fun aCancelledLookupAsksNothingAndReleasesTheTap() = runTest(dispatcher) {
        seedKnownRow()
        val lookup = ScriptedLookup(graph.tags).apply { failure = CancellationException("the lookup was cancelled") }
        controller = controller(resolveTag = lookup.resolver())
        io.inspection = holdingOtherTag

        controller.onTag(handle); advanceUntilIdle()

        assertEquals(nothingYet, controller.state.value)
        assertEquals(0, io.writeAttempts)
        controller.confirmOverwrite(); controller.keepIt(); advanceUntilIdle()
        assertEquals("no question was recorded, so there is nothing to answer", nothingYet, controller.state.value)

        lookup.failure = null
        controller.onTag(handle); advanceUntilIdle()
        assertEquals("the next tap is handled", 2, io.inspectCount)
        assertEquals(WriteState.Confirm(OverwriteSubject("This tag currently identifies Pump 3.", "11111111 · v1")), controller.state.value)
        assertEquals(0, io.writeAttempts)
    }

    /** C2/C7: until the question is on screen there is nothing to answer, and the tap keeps `busy`. */
    @Test fun answeringWhileTheLookupRunsIsInert() = runTest(dispatcher) {
        seedKnownRow()
        val gate = CompletableDeferred<Unit>()
        val lookup = ScriptedLookup(graph.tags).apply { this.gate = gate }
        controller = controller(resolveTag = lookup.resolver())
        io.inspection = holdingOtherTag

        controller.onTag(handle); runCurrent()
        assertEquals("the lookup is under way", 1, lookup.lookups)
        assertEquals(nothingYet, controller.state.value)

        controller.keepIt(); controller.confirmOverwrite(); runCurrent()
        assertEquals("answering before the question is shown does nothing", nothingYet, controller.state.value)
        controller.onTag(FakeHandle(uid = "04a1-second-discovery")); runCurrent()
        assertEquals("the tap in flight still owns busy", 1, io.inspectCount)

        gate.complete(Unit); runCurrent()
        assertEquals(WriteState.Confirm(OverwriteSubject("This tag currently identifies Pump 3.", "11111111 · v1")), controller.state.value)

        controller.keepIt(); runCurrent()
        assertEquals(WriteState.Idle("Not written. The tag was left as it was."), controller.state.value)
        assertEquals(0, io.writeAttempts)
    }

    /** R70-6: a lookup that never answers is given two seconds, then the honest line asks anyway. */
    @Test fun aHangingLookupIsBoundedToTheHonestLine() = runTest(dispatcher) {
        seedKnownRow()
        val lookup = ScriptedLookup(graph.tags).apply { gate = CompletableDeferred() }   // never completed
        controller = controller(resolveTag = lookup.resolver())
        io.inspection = holdingOtherTag

        controller.onTag(handle); runCurrent()
        advanceTimeBy(2.seconds - 1.milliseconds); runCurrent()
        assertEquals("inside the bound the question waits for its words", nothingYet, controller.state.value)

        advanceTimeBy(2.milliseconds); runCurrent()
        assertEquals(WriteState.Confirm(OverwriteSubject(COULD_NOT_CHECK, "11111111 · v1")), controller.state.value)
        controller.keepIt(); runCurrent()
        assertEquals(WriteState.Idle("Not written. The tag was left as it was."), controller.state.value)
        assertEquals(0, io.writeAttempts)
    }

    /** C6: asking — then keeping, then overwriting on the retap — leaves every other row as it was. */
    @Test fun askingWritesNothingToTheStore() = runTest(dispatcher) {
        seedKnownRow(label = "Pump house")
        val before = graph.tags.all()
        assertNull(before.single().lastScannedAt)
        controller = controller()
        io.inspection = holdingOtherTag
        suspend fun others() = graph.tags.all().filter { it.id.value != rowId }

        controller.onTag(handle); advanceUntilIdle()
        assertIs<WriteState.Confirm>(controller.state.value)
        assertEquals(before, others())

        controller.keepIt(); advanceUntilIdle()
        assertEquals(before, others())

        controller.onTag(handle); advanceUntilIdle()
        controller.confirmOverwrite(); advanceUntilIdle()
        io.writeResult = WriteResult.Written(intended, 95, locked = false)
        controller.onTag(FakeHandle(uid = handle.uid)); advanceUntilIdle()
        assertIs<WriteState.Written>(controller.state.value)
        assertEquals(before, others())
    }

    /** The write flow's words for these states are the inspect sheet's words, not a second copy that drifts. */
    @Test fun theOverwriteWordsMatchTheScanWords() {
        assertEquals(PRE_SPLIT_LINK_SENTENCE, OverwriteSubjects.PRE_SPLIT_LINK)
        // The inspect sheet's literal, itself pinned on the emulator by NfcIdentityDeviceProofTest.
        assertEquals("This ServiceTag tag is not in this phone's records.", OverwriteSubjects.NOT_IN_RECORDS)

        val unlabelled = TagBinding(TagId(OTHER_TAG), PayloadFormat.V1, OTHER_TAG, TagTarget.None, TagStatus.UNBOUND, createdAt = 1L, updatedAt = 1L)
        val asked = OverwriteReasons.decide(TagPayload.V1(TagId(OTHER_TAG)), TagId(rowId)) as OverwriteDecision.Confirm
        val subject = OverwriteSubjects.of(asked, Resolution.Unbound(unlabelled))
        assertEquals(identityLine(OTHER_TAG), subject.identifier)
        assertEquals(unlabelled.identityLine(), subject.identifier)
    }

    // --- the fixture ---------------------------------------------------------------------------

    /** What the screen shows before any tap has been answered. */
    private val nothingYet = WriteState.Idle("Hold a blank or reusable tag to the back of the phone.")

    /** An asset this phone knows and a row for [OTHER_TAG], as `BindTag` would have left them. */
    private suspend fun seedKnownRow(status: TagStatus = TagStatus.ACTIVE, label: String? = null) {
        graph.assets.upsert(Asset(AssetId(ASSET_ID), ASSET_NAME, createdAt = 1L, updatedAt = 1L))
        graph.tags.upsert(
            TagBinding(
                TagId(OTHER_TAG), PayloadFormat.V1, OTHER_TAG, TagTarget.AssetTarget(AssetId(ASSET_ID)), status,
                label = label, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    /**
     * The real Room store, except that the payload lookup can be made to throw or to wait on a
     * gate. Only [ResolveTag] is given this; `ProvisionTag` keeps its own repository.
     */
    private inner class ScriptedLookup(private val delegate: TagRepository) : TagRepository by delegate {
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var lookups = 0

        override suspend fun findByPayload(format: PayloadFormat, key: String): TagBinding? {
            lookups++
            failure?.let { throw it }
            gate?.await()
            return delegate.findByPayload(format, key)
        }

        fun resolver() = ResolveTag(this, graph.assets, graph.uow, graph.clock)
    }

    /** Two distinct handles can be told apart: the consent rule is about which one does the write. */
    private class FakeHandle(override val uid: String?) : TagHandle

    private val handle = FakeHandle(uid = "04a7912c")

    /** The id `FakeGraph`'s counting generator mints for the first row a controller provisions. */
    private val rowId = "00000000-0000-4000-8000-000000000001"

    private val intended: List<NdefRecordData> get() = graph.ndefCodec.encodeV1(TagId(rowId))

    private val writable137 = TagInspection(
        "04a1", TagRead.Readable(emptyList()),
        maxSize = 137, writable = true, needsFormat = false, canLock = true,
    )

    private val formatable = TagInspection(
        "04a1", TagRead.Readable(emptyList()),
        maxSize = -1, writable = true, needsFormat = true, canLock = true,
    )

    private val foreign137: TagInspection get() = readable(
        listOf(NdefRecordData(1, "T".toByteArray(Charsets.US_ASCII), byteArrayOf(0x01))),
    )

    private val holdingOtherTag: TagInspection get() = readable(graph.ndefCodec.encodeV1(TagId(OTHER_TAG)))
    private val holdingAThirdTag: TagInspection get() = readable(graph.ndefCodec.encodeV1(TagId(THIRD_TAG)))

    private fun readable(records: List<NdefRecordData>) = TagInspection(
        "04a1", TagRead.Readable(records),
        maxSize = 137, writable = true, needsFormat = false, canLock = true,
    )

    /**
     * The row side, counted. `ProvisionTag` is final, so the count is taken where it lands: an
     * upsert with no `writtenAt` is a row being begun, one that carries a `writtenAt` is the
     * verified write claiming it.
     */
    private class CountingTags(private val delegate: TagRepository) : TagRepository by delegate {
        var begun = 0
        var completed = false

        override suspend fun upsert(tag: TagBinding) {
            if (tag.writtenAt == null) begun++ else completed = true
            delegate.upsert(tag)
        }
    }

    /** The library's four-operation seam, scripted. Every call is counted; nothing is inferred. */
    private class FakeTagIo : TagIo {
        var inspection: TagInspection? = null
        var inspectFailure: Throwable? = null
        var writeResult: WriteResult = WriteResult.Failed("no write scripted", attempted = false)
        var formatResult: WriteResult = WriteResult.Formatted

        var inspectCount = 0
        var formatCount = 0
        var writeAttempts = 0
        var lockCalls = 0

        var lastWriteHandle: TagHandle? = null
        var lastWriteRecords: List<NdefRecordData>? = null
        var lastWriteLock: Boolean? = null
        var lastLockExpected: List<NdefRecordData>? = null

        override fun inspect(tag: TagHandle): TagInspection? {
            inspectCount++
            inspectFailure?.let { throw it }
            return inspection
        }

        override fun format(tag: TagHandle): WriteResult {
            formatCount++
            return formatResult
        }

        override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult {
            writeAttempts++
            lastWriteHandle = tag
            lastWriteRecords = records
            lastWriteLock = lock
            return writeResult
        }

        override fun lock(tag: TagHandle, expected: List<NdefRecordData>): Boolean {
            lockCalls++
            lastLockExpected = expected
            return true
        }
    }

    private companion object {
        const val OTHER_TAG = "11111111-1111-4111-8111-111111111111"
        const val THIRD_TAG = "22222222-2222-4222-8222-222222222222"
        const val ASSET_ID = "a1"
        const val ASSET_NAME = "Pump 3"

        /** P70-6, written out: the failure paths must say exactly this. */
        const val COULD_NOT_CHECK = "This is a ServiceTag tag, but its record could not be checked just now."
    }
}
