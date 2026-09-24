package com.loosecannon.servicetag.share

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.share.TestSender.Command
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * The external boundary of share intake (#62, planning policy "Testing hierarchy", layer 4):
 * **Android delivered a share from another UID to the exported activity.** Each case asks
 * `:share-test-sender` — its own package, its own UID — to fire a real `ACTION_SEND`, waits for
 * the intake to resume and reads what it drew through Compose semantics. Nothing is chosen,
 * typed, pressed or saved.
 *
 * **Three cases, and that is the cap.** Each names the boundary it alone demonstrates; everything
 * the screen does after delivery is proved in process by `ShareIntakeScreenTest`,
 * `ShareIntakeViewModelTest`, `SharedItemReaderTest` and `SharedItemLiftTest`.
 *
 * **The tripwire is the timeout.** A case gets 20 s end to end, set-up and tear-down included; a
 * proof that needs longer is a scripted journey, and it fails on time alone.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class ShareBoundaryTest {

    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(20)

    @get:Rule(order = 1) val rule = createEmptyComposeRule()

    private val graph get() = ApplicationProvider.getApplicationContext<ServiceTagApp>().graph

    private var mower: AssetId? = null
    private var intake: ShareIntakeActivity? = null

    /** One asset, so the intake draws its form and not the no-assets dead end. */
    @Before fun seedOneAsset() {
        mower = runBlocking { graph.createAsset.run(name = "Mower").id }
    }

    @After fun finishTheIntakeAndRemoveTheAsset() {
        intake?.let(TestSender::finish)
        mower?.let { runBlocking { graph.deleteAsset.run(it) } }
    }

    /** Boundary: `ACTION_SEND` + `EXTRA_TEXT` from a foreign UID reaches the exported activity. */
    @Test fun anExternalTextShareFromAnotherUidReachesTheIntake() {
        intake = TestSender.share(Command.SEND_TEXT, text = FIXTURE_URI)
        awaitTheReadToLand()

        rule.onNodeWithText(TITLE).assertIsDisplayed()
        rule.onNodeWithText(RECEIVED).assertIsDisplayed()
        rule.onNode(hasTextExactly(FIXTURE_URI, includeEditableText = false)).assertIsDisplayed()
        DEAD_ENDS.forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    /**
     * Boundary: a `content://` URI from another package's `FileProvider`, carrying a real temporary
     * read grant, is readable by the intake — the provider's own name for it arrives, and the byte
     * form (the only one with a Type control) is drawn.
     */
    @Test fun anExternalStreamWithAGenuineGrantReachesTheByteForm() {
        intake = TestSender.share(Command.SEND_FILE)
        awaitTheReadToLand()

        rule.onNodeWithText(TITLE).assertIsDisplayed()
        rule.onNodeWithText(RECEIVED).assertIsDisplayed()
        // The Name field is pre-filled with the same name, so only the drawn line is matched.
        rule.onNode(hasTextExactly(FIXTURE_FILE, includeEditableText = false)).assertIsDisplayed()
        rule.onNodeWithText(TYPE).assertIsDisplayed()
        rule.onAllNodesWithText(UNREADABLE).assertCountEquals(0)
    }

    /**
     * Boundary: the same foreign URI **without** a grant. The platform refuses the read; the app
     * survives it and answers with the ratified read-failure dead end, and the activity is alive.
     *
     * **One granted share goes first, and it is load-bearing.** Under API 30+ package visibility
     * ServiceTag cannot see the sender's package until the sender has granted it a URI. Before
     * that, the resolver reports "Failed to find provider info", `query` answers null without
     * throwing, and the intake draws a byte form with an empty Received line instead of refusing
     * (recorded in `docs/release-proofs.md`). After it, the provider is found and the platform
     * denies the ungranted read — the refusal this case exists to prove. Doing it here, rather than
     * relying on the grant case having run first, keeps the case independent of method order.
     */
    @Test fun anExternalStreamWithoutAGrantIsRefusedNotCrashed() {
        TestSender.finish(TestSender.share(Command.SEND_FILE))
        val refused = TestSender.share(Command.SEND_BAD_GRANT).also { intake = it }
        awaitTheReadToLand()

        rule.onNodeWithText(UNREADABLE).assertIsDisplayed()
        rule.onNodeWithText(CLOSE).assertIsDisplayed()
        rule.onAllNodesWithText(RECEIVED).assertCountEquals(0)
        assertTrue("the intake must still be resumed after the refusal", TestSender.isAlive(refused))
    }

    /**
     * The intent is read off the main thread, so the first frame is the title alone. Every loaded
     * state draws either the Received section or Close; waiting for one of them waits for the read.
     */
    private fun awaitTheReadToLand() {
        rule.waitUntil(LOAD_TIMEOUT_MS) {
            rule.onAllNodesWithText(RECEIVED).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText(CLOSE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L

        const val FIXTURE_URI = "https://example-mower.invalid/xt1/manual.pdf"
        const val FIXTURE_FILE = "mower-manual.pdf"

        // What the screen draws, verbatim. `SectionHeader` uppercases its title.
        const val TITLE = "Save to ServiceTag"
        const val RECEIVED = "RECEIVED"
        const val TYPE = "TYPE"
        const val CLOSE = "Close"
        const val UNREADABLE = "Could not read what was shared"

        /** Every sentence the intake shows in place of a form. */
        val DEAD_ENDS = listOf(
            "Add an asset in ServiceTag first, then share this again.",
            "That file cannot be accepted from the app that shared it.",
            UNREADABLE,
            "That link is too long to save.",
            "ServiceTag will not save that kind of link.",
        )
    }
}
