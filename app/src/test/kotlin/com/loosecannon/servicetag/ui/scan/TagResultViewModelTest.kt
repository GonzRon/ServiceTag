package com.loosecannon.servicetag.ui.scan

import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2.6 — the result sheet's answer for a tag written before the split. The row stays, the link row
 * stays, and the only thing that happens is one sentence. The sentence is asserted verbatim here
 * because it is the release's single new user-facing string; `PreSplitLinkTagSheetTest` proves the
 * sheet actually draws it.
 *
 * One dispatcher carries the test body, `Dispatchers.Main` and Room's query context, which is what
 * makes `advanceUntilIdle()` a real settle here rather than a hope (`TagWriteControllerTest`'s
 * KDoc says the same thing): a `StandardTestDispatcher` under Room leaves the resolve queued and
 * the state still `Loading` when the assertion runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagResultViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private lateinit var graph: FakeGraph
    private val key = "123e4567-e89b-12d3-a456-426614174000"

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        graph = FakeGraph(queryContext = dispatcher)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    @Test fun aLinkBoundTagResolvesToThePreSplitStateAndTheRowIsLeftAlone() = runTest(dispatcher) {
        graph.uow.write {
            graph.links.upsert(ExternalLink(LinkId("l1"), null, LinkKind.JOPLIN, "note", "joplin://x", 1L, null, 1L))
            graph.tags.upsert(
                TagBinding(TagId(key), PayloadFormat.V1, key, TagTarget.LinkTarget(LinkId("l1")), TagStatus.ACTIVE, createdAt = 1L, updatedAt = 1L),
            )
        }
        val model = TagResultViewModel(
            ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock),
            BindTag(graph.tags, graph.assets, graph.uow, graph.clock),
            graph.assets,
            // A link-bound tag never reaches the sheet, so the offer is never asked.
            { error("a pre-split link tag is not a Resolution.OpenAsset") },
            PayloadFormat.V1.name,
            key,
        )
        advanceUntilIdle()

        val state = model.state.value
        assertTrue("state was $state", state is TagResult.PreSplitLink)
        assertEquals(TagTarget.LinkTarget(LinkId("l1")), (state as TagResult.PreSplitLink).tag.target)
        assertEquals(1, graph.links.all().size)
    }

    @Test fun theSentenceIsTheOwnersWords() {
        assertEquals(
            "This tag points at a note link from before the product split. " +
                "ServiceTag no longer opens links; NoteTag does.",
            PRE_SPLIT_LINK_SENTENCE,
        )
    }
}
