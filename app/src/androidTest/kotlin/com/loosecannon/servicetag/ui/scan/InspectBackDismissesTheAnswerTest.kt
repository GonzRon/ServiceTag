package com.loosecannon.servicetag.ui.scan

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.nfc.ReaderMode
import com.loosecannon.servicetag.ui.nfc.ReaderModeControl
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for a delivery, a sheet or a back press to settle. */
private const val SETTLE_MILLIS = 5_000L

/**
 * 2.7 (W1) — with a read's answer showing, back dismisses the answer and leaves the user on the
 * inspect screen, which is what one back press did at 2.6 when the answer was its own nav entry.
 *
 * **How an answer is produced without NFC.** `ScanViewModel`'s primary constructor already takes a
 * `TagIo`; what has no seam is the path from `ScanScreen`, which is handed only an `AppGraph`. So
 * the model is built here with a `TagIo` that reads nothing and seeded into the `ViewModelStore`
 * this test provides, under the key `ScanScreen` resolves with — `viewModel(key = "scan")` returns
 * the stored instance rather than calling its initializer. `ScanViewModels.kt` is not touched and no
 * seam is added to production code; an unreadable tag is a real answer (the "not a ServiceTag tag"
 * sheet), and any answer is enough for a case about back.
 *
 * **Why the screen is composed directly.** Inside `ServiceTagRoot` the nav entry decorator owns
 * `LocalViewModelStoreOwner`, so the seeded store cannot reach the screen and no answer can be put
 * on screen at all. The cost is that there is no real back stack here: `NavDisplay(onBack = …)` is
 * stood in for by a `BackHandler` composed *before* the screen, which puts the two callbacks in the
 * dispatcher in the same order the shell and its entry content put them — the shell's first, the
 * screen's second and therefore first to be offered the press. What the real back stack does with
 * a press the screen does *not* take is covered in `ReaderModeHoldTest`.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class InspectBackDismissesTheAnswerTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val control = CountingControl()
    private val readerMode = ReaderMode { _ -> control }

    /** Presses the screen declines. Stands in for `NavDisplay(onBack = { backStack.remove… })`. */
    private var hostBacks = 0

    /** The app bar's own arrow, which a system back press must never reach. */
    private var screenBacks = 0

    @Before fun freshInstall() = clearInstall()

    @Test fun backDismissesTheAnswerAndStaysOnTheInspectScreen() {
        val graph = app.graph
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        ViewModelProvider.create(
            owner,
            viewModelFactory { initializer { ScanViewModel(graph.resolveTag, ReadsNothing, graph.ndefCodec) } },
        )["scan", ScanViewModel::class.java]

        rule.setContent {
            ServiceTagTheme {
                BackHandler(enabled = true) { hostBacks++ }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ScanScreen(
                        graph = graph,
                        readerMode = readerMode,
                        onOpenAsset = { error("no answer here opens an asset") },
                        onNewAsset = { error("no answer here makes an asset") },
                        onWriteTag = { error("no answer here writes a tag") },
                        onBack = { screenBacks++ },
                    )
                }
            }
        }

        rule.awaitText("READY TO SCAN")
        rule.waitUntil(SETTLE_MILLIS) { readerMode.sinkCount == 1 }

        // Nothing showing: back is not this screen's to take, so the host gets it.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals("with no answer, back falls through", 1, hostBacks)

        // An answer, and the screen still underneath it.
        rule.runOnIdle { readerMode.deliver(Unreadable) }
        rule.awaitText("Cancel")
        rule.onNodeWithText("READY TO SCAN").assertIsDisplayed()

        // The press the release is about.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(SETTLE_MILLIS) {
            rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("READY TO SCAN").assertIsDisplayed()
        assertEquals("the answer went, not the screen", 1, hostBacks)
        assertEquals("and never the app bar's arrow", 0, screenBacks)
        assertEquals("so the screen is still reading", 1, readerMode.sinkCount)
        assertEquals("and never handed NFC back", 0, control.stops)

        // And with the answer gone the handler is inert again, so back belongs to the host.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(2, hostBacks)
    }

    /** Not an `NfcTagHandle`; it never reaches the tag anyway, because [ReadsNothing] does not read. */
    private object Unreadable : TagHandle {
        override val uid: String = "04a1"
    }

    /**
     * Reads nothing, so `ScanViewModel` resolves `TagPayload.Malformed` and the screen shows the
     * "not a ServiceTag tag" answer. The three write members are unreachable from the scan screen.
     */
    private object ReadsNothing : TagIo {
        override fun inspect(tag: TagHandle): TagInspection? = null
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
