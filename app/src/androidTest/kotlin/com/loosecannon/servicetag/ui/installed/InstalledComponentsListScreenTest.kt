package com.loosecannon.servicetag.ui.installed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #103 (1.7.1; owner ruling Q2): the cross-asset Installed components list drawn over the real graph. What
 * only a device shows is that the rows and their asset path reach the semantics tree under the ratified
 * words, that a row's tap reports its **asset**, and that an empty store draws the shipped empty line. The
 * rows' order and contents are proven on the JVM (`InstalledComponentsListViewModelTest`).
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class InstalledComponentsListScreenTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private fun draw(record: MutableList<String>) {
        rule.setContent {
            ServiceTagTheme {
                InstalledComponentsListScreen(
                    graph = app.graph,
                    onBack = { record += "back" },
                    onOpenAsset = { record += "asset:$it" },
                )
            }
        }
    }

    /** A fitted component is listed under its asset's name, and tapping it opens that asset. */
    @Test fun aFittedComponentIsListedWithItsAssetAndOpensTheAsset() {
        val graph = app.graph
        val heater: AssetId = runBlocking {
            val asset = graph.createAsset.run(AssetCommand(name = "Example Heater", category = "Heating"))
            graph.installComponent.run(
                InstallComponentCommand(
                    assetId = asset.id, parentId = null, name = "Example Battery Tray", supplyId = null,
                    composition = emptyList(), serialOrLot = "", installedOn = null, notes = "", sortOrder = null,
                ),
            )
            asset.id
        }
        val record = mutableListOf<String>()
        draw(record)

        rule.awaitText(INSTALLED_COMPONENTS_SECTION)
        rule.awaitText("Example Battery Tray")
        rule.onNodeWithText("Example Heater").assertIsDisplayed()
        rule.onAllNodesWithText(NO_INSTALLED_COMPONENTS).assertCountEquals(0)

        rule.onNode(hasText("Example Battery Tray") and hasClickAction()).performClick()
        rule.runOnIdle { check(record == listOf("asset:${heater.value}")) { "the row opens its asset, not $record" } }
    }

    /** Nothing fitted anywhere: the shipped empty line, under the title, and nothing to tap. */
    @Test fun aPhoneWithNothingFittedSaysSo() {
        val record = mutableListOf<String>()
        draw(record)

        rule.awaitText(INSTALLED_COMPONENTS_SECTION)
        rule.awaitText(NO_INSTALLED_COMPONENTS)
        rule.runOnIdle { check(record.isEmpty()) { "unexpected navigation: $record" } }
    }
}
