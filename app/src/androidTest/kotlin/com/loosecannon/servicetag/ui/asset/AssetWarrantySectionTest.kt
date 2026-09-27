package com.loosecannon.servicetag.ui.asset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.WarrantyReminderCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.CONDITION_TITLE
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #79 (C10, R79-17; §3 row 28): the asset detail's Warranty section on a real Compose tree, over the
 * app's own graph and its `Today`. IN WARRANTY with its date and reminder line, OUT OF WARRANTY with
 * its date, and "Warranty not recorded"; the section sits after DETAILS and before Condition, carries
 * the warranty notes, and DETAILS no longer says " (expired)".
 *
 * Emulator only (`emulator-5554`) — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetWarrantySectionTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    /** The detail screen on [initial]; the returned setter switches it to another asset. */
    private fun detail(initial: String): (String) -> Unit {
        var shown by mutableStateOf(initial)
        rule.setContent {
            ServiceTagTheme {
                AssetDetailScreen(
                    graph = app.graph,
                    assetId = shown,
                    onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                    onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                    onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                    onOpenSchedule = {}, onOpenGroup = {},
                )
            }
        }
        return { shown = it }
    }

    private fun create(cmd: AssetCommand): String = runBlocking { app.graph.createAsset.run(cmd).id.value }

    private fun shows(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test fun inOutAndNotRecorded() {
        val today = app.graph.today.localDate()
        val later = today.plusDays(400)
        val earlier = today.minusDays(10)
        val inId = create(AssetCommand(name = "Example Heater", warrantyExpiresOn = later.toString()))
        runBlocking {
            app.graph.setWarrantyReminder.run(AssetId(inId), WarrantyReminderCommand(30))
        }
        val outId = create(AssetCommand(name = "Example Pump", warrantyExpiresOn = earlier.toString()))
        val bareId = create(AssetCommand(name = "Example Fan"))

        val show = detail(inId)
        rule.awaitText(IN_WARRANTY_WORD)
        shows("WARRANTY")
        shows(IN_WARRANTY_WORD)
        shows("Expires ${displayDate(later)}")
        shows("Reminder: 30 days before")
        check(top("WARRANTY") < top(IN_WARRANTY_WORD)) { "the badge is under its header" }
        check(top(IN_WARRANTY_WORD) < top("Expires ${displayDate(later)}")) { "the date is under the badge" }
        check(top("Reminder: 30 days before") < top(CONDITION_TITLE)) { "the section is above Condition" }
        rule.onAllNodesWithText(OUT_OF_WARRANTY_WORD).assertCountEquals(0)
        rule.onAllNodesWithText(WARRANTY_NOT_RECORDED).assertCountEquals(0)

        show(outId)
        rule.awaitText(OUT_OF_WARRANTY_WORD)
        shows("Expired ${displayDate(earlier)}")
        rule.onAllNodesWithText(IN_WARRANTY_WORD).assertCountEquals(0)
        rule.onAllNodes(hasText("Reminder:", substring = true)).assertCountEquals(0)

        show(bareId)
        rule.awaitText(WARRANTY_NOT_RECORDED)
        shows("WARRANTY")
        shows(WARRANTY_NOT_RECORDED)
        rule.onAllNodesWithText(IN_WARRANTY_WORD).assertCountEquals(0)
        rule.onAllNodesWithText(OUT_OF_WARRANTY_WORD).assertCountEquals(0)
    }

    /** R79-17: the date and the notes left DETAILS for the section, and " (expired)" went with them. */
    @Test fun theExpiredSuffixIsGone() {
        val earlier = app.graph.today.localDate().minusDays(30)
        val id = create(
            AssetCommand(
                name = "Example Heater",
                vendor = "Example Supply",
                warrantyExpiresOn = earlier.toString(),
                warrantyNotes = "Parts only",
            ),
        )

        detail(id)
        rule.awaitText(OUT_OF_WARRANTY_WORD)
        shows("DETAILS")
        shows("Example Supply")
        shows("Expired ${displayDate(earlier)}")
        shows("Parts only")
        rule.onAllNodes(hasText("(expired)", substring = true)).assertCountEquals(0)
        rule.onAllNodesWithText("WARRANTY").assertCountEquals(1)
        rule.onAllNodesWithText("WARRANTY NOTES").assertCountEquals(1)
        check(top("DETAILS") < top("WARRANTY")) { "the section follows DETAILS" }
        check(top("WARRANTY") < top("WARRANTY NOTES")) { "the notes are in the section, not in DETAILS" }
        check(top("Parts only") < top(CONDITION_TITLE)) { "and the section ends before Condition" }
    }
}
