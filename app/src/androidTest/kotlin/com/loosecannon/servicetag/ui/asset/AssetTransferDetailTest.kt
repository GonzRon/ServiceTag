package com.loosecannon.servicetag.ui.asset

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #77 (C19, C21a, R77-4; row 37) — the asset detail on a real Compose tree over the app's own graph: a transferred-out
 * asset draws the P77-32 block and P77-31 on its plate, and **no node** for any write it would otherwise offer — the
 * action grid but Backup, every section action, and the overflow but Delete; an ordinary archived asset keeps every
 * action (AC 11). The state's REDs are row 32's, on the JVM; this class proves node absence, which no JVM test in
 * this build can observe.
 *
 * Emulator only (`emulator-5554`) — the suite wipes app data. The transfer records this class appends are its own and
 * are removed after each case (`clearInstall` predates them).
 */
@RunWith(AndroidJUnit4::class)
class AssetTransferDetailTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() {
        clearInstall()
        runBlocking { app.graph.transferRecords.deleteAll() }
    }

    @After fun noRecordsLeft() {
        runBlocking { app.graph.transferRecords.deleteAll() }
    }

    private fun detail(id: String) {
        rule.setContent {
            ServiceTagTheme {
                AssetDetailScreen(
                    graph = app.graph,
                    assetId = id,
                    onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                    onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                    onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                    onOpenSchedule = {}, onOpenGroup = {},
                )
            }
        }
    }

    private fun create(name: String): String = runBlocking { app.graph.createAsset.run(AssetCommand(name = name)).id.value }

    private fun gone(text: String) = rule.onAllNodesWithText(text).assertCountEquals(0)

    @Test fun aHeldAssetDrawsTheBlockAndBadgeAndNoWriteAction() {
        val id = create("Example Water Heater")
        runBlocking {
            app.graph.archiveAsset.run(AssetId(id))
            app.graph.transferRecords.append(
                TransferRecord(
                    id = "out-device-1", assetId = AssetId(id), kind = TransferKind.OUT,
                    packId = "0f1e2d3c-4b5a-4968-8776-655443322110", lineage = emptyList(), at = 1_790_510_400_000L,
                    packSha256 = "ab".repeat(32), nameSnapshot = "Example Water Heater", note = "Keys are in the drawer",
                ),
            )
        }
        detail(id)
        rule.awaitText("TRANSFERRED OUT")

        rule.onNodeWithText("TRANSFERRED").assertIsDisplayed()
        gone("ARCHIVED")
        rule.onNodeWithText("Transfer Pack 0f1e2d3c").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Keys are in the drawer").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Withdraw transfer record").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Backup").performScrollTo().assertIsDisplayed()
        listOf(
            "Write tag", "Edit", "Readings & actions", "Set up from template", "Change condition", "Log incident",
            "Lend out", "+ Add child asset", "Add file", "Take photo", "Add link",
        ).forEach(::gone)
        rule.onAllNodesWithContentDescription("Schedules").assertCountEquals(0)

        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        listOf("Archive", "Unarchive", "Retire", "Unretire", "Transfer assets").forEach(::gone)
    }

    @Test fun anArchivedAssetKeepsItsActions() {
        val id = create("Example Ladder")
        runBlocking { app.graph.archiveAsset.run(AssetId(id)) }
        detail(id)
        rule.awaitText("ARCHIVED")

        gone("TRANSFERRED OUT")
        gone("TRANSFERRED")
        listOf("Write tag", "Edit", "Readings & actions", "+ Add child asset").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }

        rule.onNodeWithContentDescription("More").performClick()
        listOf("Unarchive", "Retire", "Transfer assets", "Delete").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }
}
