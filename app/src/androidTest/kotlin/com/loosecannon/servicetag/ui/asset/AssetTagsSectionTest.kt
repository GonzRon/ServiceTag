package com.loosecannon.servicetag.ui.asset

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #49 AC 6 and AC 4, at the widget level. `TagsSection` needs no `AppGraph` and no Room — it is a
 * pure function of the tag list and the edit callback — so this renders it directly with three
 * seeded tags, exactly as `WriteTagScreenConsentWordingTest` renders `WriteStatus` directly rather
 * than standing up the whole app.
 *
 * The inline "Tag placement" edit records only `(id, newLabel)` through its callback: it never
 * reaches a `TagWriteController`, an `NdefCodec` or a `ProvisionTag` (the review gate's fourth
 * grep proves the production `ui/asset` package carries none of those symbols at all — this test
 * proves the edit affordance itself never offers more than the callback's narrow signature, i.e.
 * the payload and the binding are out of this screen's reach by construction).
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class AssetTagsSectionTest {

    @get:Rule val rule = createComposeRule()

    private fun tag(id: String, label: String?, status: TagStatus = TagStatus.ACTIVE) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = id,
        target = TagTarget.AssetTarget(AssetId("asset-1")),
        status = status,
        label = label,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val active = tag(TAG_A, "Indoor head")
    private val lost = tag(TAG_B, "Outdoor head", TagStatus.LOST)
    private val retired = tag(TAG_C, null, TagStatus.RETIRED)

    private fun setContent(onEditLabel: (TagId, String?) -> Unit) {
        rule.setContent {
            ServiceTagTheme {
                TagsSection(tags = listOf(active, lost, retired), onEditLabel = onEditLabel)
            }
        }
        rule.waitForIdle()
    }

    @Test fun everyTagIsListedWithItsLabelAndStatus() {
        setContent(onEditLabel = { _, _ -> })

        rule.onNodeWithText("Indoor head").assertIsDisplayed()
        rule.onNodeWithText("Outdoor head").assertIsDisplayed()
        // `StatusBadge` renders its label uppercased (`ui/components/StatusBadge.kt`'s
        // `label.uppercase()`), so the rendered text is "LOST" / "RETIRED", not "Lost" / "Retired".
        rule.onNodeWithText("LOST").assertIsDisplayed()
        rule.onNodeWithText("RETIRED").assertIsDisplayed()
        // The active tag needs no badge at all (only a non-ACTIVE status earns one).
        rule.onAllNodesWithText("ACTIVE").assertCountEquals(0)
        // The retired tag has no label: only the two labelled rows carry a "Tag placement" caption.
        rule.onAllNodesWithText("Tag placement").assertCountEquals(2)
    }

    @Test fun editingARowRecordsOnlyItsIdAndTheNewLabelAndLeavesEverythingElseUntouched() {
        var recorded: Pair<TagId, String?>? = null
        setContent(onEditLabel = { id, label -> recorded = id to label })

        rule.onNodeWithText("Indoor head").performClick()
        rule.onNodeWithText("Save").assertIsDisplayed()   // the dialog is up
        rule.onNode(hasSetTextAction()).performTextReplacement("Garage, north wall")
        rule.onNodeWithText("Save").performClick()

        assertEquals(TAG_A, recorded?.first?.value)
        assertEquals("Garage, north wall", recorded?.second)
    }

    @Test fun cancellingAnEditRecordsNothing() {
        var calls = 0
        setContent(onEditLabel = { _, _ -> calls++ })

        rule.onNodeWithText("Outdoor head").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Somewhere else")
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(0, calls)
        // The row still shows its original placement: cancelling wrote nothing.
        rule.onNodeWithText("Outdoor head").assertIsDisplayed()
    }

    private companion object {
        const val TAG_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val TAG_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val TAG_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    }
}
