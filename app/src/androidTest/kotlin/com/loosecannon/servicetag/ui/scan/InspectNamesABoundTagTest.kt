package com.loosecannon.servicetag.ui.scan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.nfc.ReaderMode
import com.loosecannon.servicetag.ui.nfc.ReaderModeControl
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for a delivery, a resolve or a navigation to settle. */
private const val SETTLE_MILLIS = 5_000L

/** An invented canonical UUID, the same one `PreSplitLinkTagSheetTest` uses. Never a real tag's id. */
private const val TAG_KEY = "123e4567-e89b-12d3-a456-426614174000"

/**
 * 2.7.1 (#41) — a deliberate inspect inspects, and the ambient tap still opens.
 *
 * **How a real answer is produced without NFC.** `ScanViewModel`'s primary constructor already
 * takes a `TagIo` (`ScanViewModels.kt:87`); what has no seam is the path from `ScanScreen`, which
 * is handed only an `AppGraph`. So the model is built here with a `TagIo` that reads back the very
 * v1 record the seeded row is bound to, and seeded into the `ViewModelStore` this test provides
 * under the key `ScanScreen` resolves with — `viewModel(key = "scan")` returns the stored instance
 * rather than calling its initializer. `ScanViewModels.kt` is not touched and no seam is added to
 * production code. The records come from the production `NdefCodec`, so they are the bytes a real
 * tag would carry, and `ResolveTag` really does find the row and return `OpenAsset`.
 *
 * **Why the screen is composed directly.** Inside `ServiceTagRoot` the nav entry decorator owns
 * `LocalViewModelStoreOwner`, so the seeded store cannot reach the screen and no answer can be put
 * on screen at all. What the real back stack does with the navigation is covered by
 * `ReaderModeHoldTest`; what back does with a showing answer, by `InspectBackDismissesTheAnswerTest`.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class InspectNamesABoundTagTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val control = CountingControl()
    private val readerMode = ReaderMode { _ -> control }

    /** Every asset id the host was asked to navigate to, in order. */
    private val opened = mutableListOf<String>()

    @Before fun freshInstall() = clearInstall()

    @Test fun aBoundTagIsNamedAndOpensOnlyWhenTheOwnerTapsIt() {
        val graph = app.graph
        val tubId = runBlocking {
            val tub = graph.createAsset.run("Hot tub", "Water")
            graph.bindTag.run(PayloadFormat.V1, TAG_KEY, TagTarget.AssetTarget(tub.id))
            tub.id.value
        }

        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        ViewModelProvider.create(
            owner,
            viewModelFactory {
                initializer { ScanViewModel(graph.resolveTag, ReadsABoundTag(graph.ndefCodec), graph.ndefCodec) }
            },
        )["scan", ScanViewModel::class.java]

        rule.setContent {
            ServiceTagTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ScanScreen(
                        graph = graph,
                        readerMode = readerMode,
                        onOpenAsset = { id -> opened += id },
                        onNewAsset = { error("a bound tag never makes an asset") },
                        onWriteTag = { error("a bound tag never writes a tag") },
                        onBack = {},
                    )
                }
            }
        }

        rule.awaitText("READY TO SCAN")
        rule.waitUntil(SETTLE_MILLIS) { readerMode.sinkCount == 1 }

        rule.runOnIdle { readerMode.deliver(Bound) }

        // The answer names the asset and offers to open it. Nothing has navigated.
        //
        // `sentence = result.asset.name` is the same in both arms, so "Hot tub" alone would pass
        // either way: the two assertions that actually tell the arms apart are the ratified eyebrow
        // — `ServiceTag tag`, rendered uppercase by `NfcSheet` (`TagResultSheet.kt:282`) — and the
        // absence of the other arm's `Opening asset…` status line.
        rule.awaitText("Hot tub")
        rule.onNodeWithText("SERVICETAG TAG").assertIsDisplayed()
        rule.onAllNodesWithText("Opening asset…").assertCountEquals(0)
        rule.onNodeWithText("Open asset").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("READY TO SCAN").assertIsDisplayed()
        assertEquals("an inspect must not navigate on its own", emptyList<String>(), opened)
        // Which is the whole point: the screen is still on top and still reading. The stop count is
        // the house form (`InspectBackDismissesTheAnswerTest.kt:124`) and satisfies the ratified
        // "zero reader-mode stops" literally — but note what it is not: `ReaderMode.hold()` is the
        // only caller of `ReaderModeControl.stop()` (`ui/nfc/ReaderMode.kt:86`–`91`) and the nav
        // shell is what calls `hold`, and the shell is not composed here. The real hold across a
        // navigation is `ReaderModeHoldTest`'s; this line is a guard, not the proof.
        assertEquals("the screen is still the sink", 1, readerMode.sinkCount)
        assertEquals("and nothing in this composition handed NFC back", 0, control.stops)

        // The owner's tap is what opens it, and it opens the asset the tag is bound to.
        rule.onNodeWithText("Open asset").performClick()
        rule.waitUntil(SETTLE_MILLIS) { opened.isNotEmpty() }
        assertEquals(listOf(tubId), opened)
    }

    /**
     * The ambient path, pinned. With `inspecting` left at its default the bound-tag branch still
     * navigates by itself, with no tap: `ServiceTagRoot`'s `Route.TagResult` entry — where the
     * `NfcDispatchActivity` trampoline lands — is exactly this call, so "tap a tag anywhere and the
     * phone opens the right place" is unchanged by #41.
     */
    @Test fun theAmbientSheetStillOpensABoundTagWithoutATap() {
        val graph = app.graph
        val tubId = runBlocking {
            val tub = graph.createAsset.run("Hot tub", "Water")
            graph.bindTag.run(PayloadFormat.V1, TAG_KEY, TagTarget.AssetTarget(tub.id))
            tub.id.value
        }

        rule.setContent {
            ServiceTagTheme {
                TagResultSheet(
                    graph = graph,
                    format = PayloadFormat.V1.name,
                    key = TAG_KEY,
                    onDismiss = {},
                    onWriteTag = { error("a bound tag never writes a tag") },
                    onOpenAsset = { id -> opened += id },
                    onNewAsset = { error("a bound tag never makes an asset") },
                )
            }
        }

        rule.waitUntil(SETTLE_MILLIS) { opened.isNotEmpty() }
        assertEquals(listOf(tubId), opened)
        // No action was offered, because none was needed.
        rule.onAllNodesWithText("Open asset").assertCountEquals(0)
        // This tag carries no placement, so no "Tag placement" caption either (#49 AC 3).
        rule.onAllNodesWithText("Tag placement").assertCountEquals(0)
    }

    /**
     * #49 AC 3, review fix round 1 finding 4: spec §2.7 says "the scan result surfaces the
     * placement label when present" without naming a branch, so the ambient path — which is what
     * the ordinary tap-a-tag-anywhere trampoline actually lands on — shows it too, not only the
     * deliberate-inspect branch `InspectNamesABoundTagTest.aBoundTagIsNamedAndOpensOnlyWhenTheOwnerTapsIt`
     * already covers.
     */
    @Test fun theAmbientSheetShowsThePlacementWhenTheTagHasOne() {
        val graph = app.graph
        val tubId = runBlocking {
            val tub = graph.createAsset.run("Hot tub", "Water")
            graph.bindTag.run(PayloadFormat.V1, TAG_KEY, TagTarget.AssetTarget(tub.id), "Poolside shed")
            tub.id.value
        }

        rule.setContent {
            ServiceTagTheme {
                TagResultSheet(
                    graph = graph,
                    format = PayloadFormat.V1.name,
                    key = TAG_KEY,
                    onDismiss = {},
                    onWriteTag = { error("a bound tag never writes a tag") },
                    onOpenAsset = { id -> opened += id },
                    onNewAsset = { error("a bound tag never makes an asset") },
                )
            }
        }

        rule.waitUntil(SETTLE_MILLIS) { opened.isNotEmpty() }
        assertEquals(listOf(tubId), opened)
        rule.onNodeWithText("Tag placement").assertIsDisplayed()
        rule.onNodeWithText("Poolside shed").assertIsDisplayed()
    }

    /** A handle the test can build; [ReadsABoundTag] only echoes its `uid` back. */
    private object Bound : TagHandle {
        override val uid: String = "04a1"
    }

    /**
     * Reads back the one v1 record [TAG_KEY] is written as, through the production codec, so
     * `ScanViewModel` classifies `TagPayload.V1` and `ResolveTag` finds the seeded row and returns
     * `Resolution.OpenAsset`. The three write members are unreachable from the inspect screen.
     */
    private class ReadsABoundTag(private val codec: NdefCodec) : TagIo {
        override fun inspect(tag: TagHandle): TagInspection = TagInspection(
            uid = tag.uid,
            read = TagRead.Readable(codec.encodeV1(TagId(TAG_KEY))),
            maxSize = 492,
            writable = true,
            needsFormat = false,
            canLock = true,
        )

        override fun format(tag: TagHandle): WriteResult = error("the inspect screen never writes")
        override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult =
            error("the inspect screen never writes")
        override fun lock(tag: TagHandle, expected: List<NdefRecordData>): Boolean =
            error("the inspect screen never locks")
    }

    private class CountingControl : ReaderModeControl {
        @Volatile var starts = 0
        @Volatile var stops = 0
        override val available: Boolean = true
        override val enabled: Boolean = true
        override fun start() { starts++ }
        override fun stop() { stops++ }
    }
}
