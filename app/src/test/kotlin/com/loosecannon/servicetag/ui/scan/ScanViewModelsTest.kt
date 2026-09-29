package com.loosecannon.servicetag.ui.scan

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.nav.Route
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #77 (C20, R77-11; row 34) — a scan of a transferred-out asset's tag on this phone: the trampoline's pair routes to
 * the sheet like any row we hold, and the sheet says P77-36 over P77-37 — **never** the maintenance sheet, whose
 * offer is not even asked — and the scan leaves the tag unstamped. Fictional names and keys only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelsTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    @Test fun aTransferredOutAssetsTagNeverOpensTheSheet() = runTest(scheduler) {
        graph.assets.upsert(Asset(AssetId("h1"), "Example Water Heater", createdAt = 1L, updatedAt = 1L))
        graph.tags.upsert(
            TagBinding(TagId(KEY), PayloadFormat.V1, KEY, TagTarget.AssetTarget(AssetId("h1")), createdAt = 1L, updatedAt = 1L),
        )
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId("h1"), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_790_510_400_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Water Heater", note = "",
            ),
        )
        val resolve = ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock, graph.transferRecords)
        val model = TagResultViewModel(
            resolve,
            BindTag(graph.tags, graph.assets, graph.uow, graph.clock),
            graph.assets,
            { error("the maintenance sheet is never asked about a transferred-out asset") },
            PayloadFormat.V1.name,
            KEY,
            graph.transferRecords,
            zone = { ZoneOffset.UTC },
        )
        advanceUntilIdle()

        val state = model.state.value
        assertTrue("state was $state", state is TagResult.TransferredOut)
        assertEquals(
            "Example Water Heater was handed over on " + displayDate(LocalDate.of(2026, 9, 27)) +
                ". This phone no longer maintains it.",
            (state as TagResult.TransferredOut).handedOver,
        )
        assertNull("the scan leaves the tag unstamped", graph.tags.get(TagId(KEY))!!.lastScannedAt)

        // The trampoline's hand-off: the pair of a row we hold, which re-resolves to this sheet.
        val route = resolve.run(TagPayload.V1(TagId(KEY))).asTagResult()
        assertEquals(Route.TagResult(TagResultWire.wordFor(PayloadFormat.V1), KEY), route)
    }

    private companion object {
        const val KEY = "33333333-3333-4333-8333-333333333333"
    }
}
