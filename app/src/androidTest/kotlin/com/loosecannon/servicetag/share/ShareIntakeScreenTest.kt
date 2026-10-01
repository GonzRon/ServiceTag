package com.loosecannon.servicetag.share

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The intake screen, rendered directly from a [ShareIntakeState] — it needs no `AppGraph`, no Room
 * and no intent, exactly as `AssetTagsSectionTest` renders `TagsSection` rather than standing up
 * the whole app. What is proved here is what the person sees in each state: which ratified
 * sentence, which buttons, and whether Save is offered at all.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntakeScreenTest {

    @get:Rule val rule = createComposeRule()

    private val mower = AssetChoice("asset-1", "Cub Cadet XT1")

    private var saved = 0
    private var cancelled = 0
    private var confirmed = 0
    private val roles = mutableListOf<DocumentRole?>()

    private fun form(
        path: IntakePath = IntakePath.LINK,
        received: String = "https://example-mower.invalid/xt1/manual.pdf",
        chosen: String? = mower.id,
        name: String = "OEM parts lookup",
        storeReady: Boolean = true,
        message: String? = null,
        confirming: String? = null,
    ) = ShareIntakeState(
        loading = false,
        path = path,
        received = received,
        assets = listOf(mower),
        chosen = chosen,
        name = name,
        kind = AttachmentKind.DOCUMENT,
        storeReady = storeReady,
        message = message,
        confirming = confirming,
    )

    private fun show(state: ShareIntakeState) = show(mutableStateOf(state))

    /** The state is held, so a case can move the one composition from one share to another. */
    private fun show(state: MutableState<ShareIntakeState>) {
        rule.setContent {
            ServiceTagTheme {
                ShareIntakeScreen(
                    state = state.value,
                    onChoose = {},
                    onName = {},
                    onDescribe = {},
                    onKind = {},
                    onRole = { roles += it },
                    onSave = { saved += 1 },
                    onConfirm = { confirmed += 1 },
                    onDismissConfirmation = {},
                    onCancel = { cancelled += 1 },
                )
            }
        }
        rule.waitForIdle()
    }

    /** `SectionHeader` renders its title uppercased, so the section labels read as shouted. */
    @Test fun theScreenDrawsTheRatifiedLabelsAndNothingElse() {
        show(form())

        rule.onNodeWithText("Save to ServiceTag").assertIsDisplayed()
        rule.onNodeWithText("RECEIVED").assertIsDisplayed()
        rule.onNodeWithText("ATTACH TO").assertIsDisplayed()
        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onNodeWithText("Cub Cadet XT1").assertIsDisplayed()
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Description (optional)").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        // The 2.6 wording the owner refused, and the one #43's mock used.
        rule.onAllNodesWithText("Share to ServiceTag").assertCountEquals(0)
    }

    @Test fun withNoAssetsTheOnlyThingOfferedIsClose() {
        show(
            ShareIntakeState(
                loading = false,
                deadEnd = "Add an asset in ServiceTag first, then share this again.",
            ),
        )

        rule.onNodeWithText("Add an asset in ServiceTag first, then share this again.")
            .assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)

        rule.onNodeWithText("Close").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }

    @Test fun aRefusedStreamGetsItsOwnSentenceAndNotTheReadFailureOne() {
        show(
            ShareIntakeState(
                loading = false,
                deadEnd = "That file cannot be accepted from the app that shared it.",
            ),
        )

        rule.onNodeWithText("That file cannot be accepted from the app that shared it.")
            .assertIsDisplayed()
        rule.onAllNodesWithText("Could not read what was shared").assertCountEquals(0)
        rule.onAllNodesWithText("Save").assertCountEquals(0)
    }

    /** D-20: the byte path is gated by the folder and says so; Save is drawn and disabled. */
    @Test fun aByteShareWithNoFolderShowsTheSentenceAndCannotSave() {
        show(form(path = IntakePath.BYTES, received = "manual.pdf", storeReady = false))

        rule.onNodeWithText(
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)

        rule.onNodeWithText("Save").performClick()
        assertEquals("a disabled Save cannot fire", 0, saved)
    }

    /** The same phone, the same missing folder: a reference needs none, and nothing is said. */
    @Test fun aUriShareOnTheSamePhoneSaysNothingAboutStorage() {
        show(form(path = IntakePath.LINK, storeReady = false))

        rule.onAllNodesWithText(
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
        ).assertCountEquals(0)
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun theTypeControlIsDrawnOnBytesAndNeverOnALink() {
        show(form(path = IntakePath.BYTES, received = "manual.pdf"))

        rule.onNodeWithText("TYPE").assertIsDisplayed()
        // The seven shipped labels, reused and not re-spelled.
        listOf("Photo", "Label photo", "Receipt", "Manual", "Warranty", "Document", "Other")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    /**
     * #67, C7 (R67-9), amended by #91 (R91-4): a byte share draws the Role control — the header and
     * the four chips from their one home, "No role" chosen until the person picks — and a pick
     * reaches `onRole`. The same composition moved onto a web link draws it too, on "No role" and
     * with no Type control; moved onto a link that takes no role (`linkTakesRole` false, the
     * default) it draws none of it.
     */
    @Test fun theRoleControlIsDrawnOnBytesAndOnAWebLinkAndNeverOnANoteLink() {
        val state = mutableStateOf(form(path = IntakePath.BYTES, received = "manual.pdf"))
        show(state)

        rule.onNodeWithText("ROLE").performScrollTo().assertIsDisplayed()
        ROLE_CHIPS.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("No role").assertIsSelected()
        rule.onNodeWithText("Service manual").performScrollTo().performClick()
        assertEquals(listOf<DocumentRole?>(DocumentRole.SERVICE_MANUAL), roles)

        state.value = form(path = IntakePath.LINK).copy(linkTakesRole = true)
        rule.waitForIdle()
        rule.onAllNodesWithText("TYPE").assertCountEquals(0)
        rule.onNodeWithText("ROLE").performScrollTo().assertIsDisplayed()
        ROLE_CHIPS.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("No role").assertIsSelected()

        state.value = form(path = IntakePath.LINK, received = "joplin://x-callback-url/openNote?id=example")
        rule.waitForIdle()
        rule.onAllNodesWithText("ROLE").assertCountEquals(0)
        ROLE_CHIPS.forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test fun aLinkShareHasNoTypeControl() {
        show(form(path = IntakePath.LINK))

        rule.onAllNodesWithText("TYPE").assertCountEquals(0)
        rule.onAllNodesWithText("Manual").assertCountEquals(0)
    }

    /**
     * Disabling Save *is* the intake behaviour, so neither blank-name sentence is ever drawn here:
     * they belong to the use-case layer, which still answers the picker, camera, API and MCP paths.
     */
    @Test fun aBlankNameDisablesSaveAndDrawsNoSentence() {
        show(form(name = "   "))

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onAllNodesWithText("Give the reference a name").assertCountEquals(0)
        rule.onAllNodesWithText("Give the file a name").assertCountEquals(0)
    }

    @Test fun noAssetChosenDisablesSave() {
        show(form(chosen = null))

        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun anUnknownSchemeIsAskedAboutByNameBeforeItIsSaved() {
        show(form(received = "zotero://select/items/0", confirming = "zotero"))

        rule.onNodeWithText("Save this link?").assertIsDisplayed()
        rule.onNodeWithText(
            "ServiceTag does not recognise \"zotero\" links. It will be saved as written and " +
                "opened with whatever app claims it.",
        ).assertIsDisplayed()

        // Two "Save" nodes now: the form's and the dialog's. The dialog's is the last one drawn.
        val saves = rule.onAllNodesWithText("Save")
        saves.assertCountEquals(2)
        saves[1].performClick()

        assertEquals(1, confirmed)
        assertEquals("the confirmation never goes through the ordinary Save", 0, saved)
    }

    @Test fun proseIsOfferedAsANoteAndNeverAsALink() {
        show(
            form(
                path = IntakePath.NOTE,
                received = "Replaced the drive belt, took an hour",
                name = "Replaced the drive belt, took an hour",
                message = "That is not a link.",
            ),
        )

        rule.onNodeWithText("That is not a link.").assertIsDisplayed()
        rule.onNodeWithText("Save as a note").assertIsEnabled()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onNodeWithText("Cancel").assertIsDisplayed()

        rule.onNodeWithText("Save as a note").performClick()
        assertEquals(1, saved)
    }

    @Test fun theSavedLineNamesTheAssetItWentTo() {
        show(form().copy(saved = "Saved to Cub Cadet XT1"))

        rule.onNodeWithText("Saved to Cub Cadet XT1").assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)
    }
}

/** The four Role chips (P67-6, P67-2/3/4), reused from their one home and never re-spelled. */
private val ROLE_CHIPS = listOf("No role", "Purchase invoice or receipt", "User manual", "Service manual")
