package com.loosecannon.servicetag.ui.scan

import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.nav.Route
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The scanner's failure wording, and what a pre-split link tag does. `viewModelScope` dispatches on
 * `Dispatchers.Main`, so the main dispatcher is a test one for the length of each case and the same
 * dispatcher carries the tag read — and Room's query context, which is what makes
 * `advanceUntilIdle()` a real settle rather than a hope (`TagWriteControllerTest`'s KDoc says the
 * same thing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        graph = FakeGraph(queryContext = dispatcher)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    /**
     * E2 — a read that throws says one thing the user can act on. The exception's class name used
     * to be interpolated into the sentence; it is diagnosis, so it goes to the log instead.
     */
    @Test fun aTagThatCannotBeReadSaysOneSentenceWithNoClassNameInIt() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock),
            ThrowingTagIo,
            graph.ndefCodec,
            dispatcher,
        )

        viewModel.onTag(FakeHandle)
        advanceUntilIdle()

        assertEquals(
            "Couldn't read that tag. Hold it still and try again.",
            viewModel.state.value.problem,
        )
        assertFalse("the scanner is not left reading", viewModel.state.value.reading)
    }

    /**
     * 2.6 — a link-bound tag is handed to the result sheet as an ordinary (format, key) pair and
     * nothing else happens. `ScanEvent` has no launch member any more, so "never launches" is a
     * compile-time fact; what this pins is that the tag is not swallowed, not turned into an asset
     * and not reported as unreadable.
     */
    @Test fun aLinkBoundTagIsHandedToTheSheetAndNothingElse() = runTest(dispatcher) {
        val key = "123e4567-e89b-12d3-a456-426614174000"
        graph.uow.write {
            graph.links.upsert(ExternalLink(LinkId("l1"), null, LinkKind.JOPLIN, "note", "joplin://x", 1L, null, 1L))
            graph.tags.upsert(
                TagBinding(TagId(key), PayloadFormat.V1, key, TagTarget.LinkTarget(LinkId("l1")), TagStatus.ACTIVE, createdAt = 1L, updatedAt = 1L),
            )
        }
        val viewModel = ScanViewModel(
            ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock),
            ReadableTagIo(graph.ndefCodec.encodeV1(TagId(key))),
            graph.ndefCodec,
            dispatcher,
        )
        val seen = mutableListOf<ScanEvent>()
        val job = launch { viewModel.events.collect { seen += it } }
        viewModel.onTag(FakeHandle)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf<ScanEvent>(ScanEvent.Show(Route.TagResult("V1", key))), seen)
        assertNull("nothing is reported as a read problem", viewModel.state.value.problem)
    }

    private object FakeHandle : TagHandle {
        override val uid: String = "04a1"
    }

    /** The one operation this case needs: a read that fails the way a tag leaving the field fails. */
    private object ThrowingTagIo : TagIo {
        override fun inspect(tag: TagHandle): TagInspection? = throw IOException("tag left the field")
        override fun format(tag: TagHandle): WriteResult = error("not used")
        override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult = error("not used")
        override fun lock(tag: TagHandle, expected: List<NdefRecordData>): Boolean = error("not used")
    }

    /**
     * A tag whose NDEF is exactly the records handed in: the second half of the case above.
     * `ScanViewModel` reads nothing but `inspection.read`, so the capacity fields are the
     * ordinary-writable-tag values `TagWriteControllerTest`'s fake uses.
     */
    private class ReadableTagIo(private val records: List<NdefRecordData>) : TagIo {
        override fun inspect(tag: TagHandle): TagInspection = TagInspection(
            "04a1",
            TagRead.Readable(records),
            maxSize = 137,
            writable = true,
            needsFormat = false,
            canLock = true,
        )
        override fun format(tag: TagHandle): WriteResult = error("not used")
        override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult = error("not used")
        override fun lock(tag: TagHandle, expected: List<NdefRecordData>): Boolean = error("not used")
    }
}
