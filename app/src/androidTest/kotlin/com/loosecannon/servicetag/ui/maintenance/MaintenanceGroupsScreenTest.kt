package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
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
 * #103 (1.7.1): the Maintenance groups list on its own pushed screen. What only a device shows is that
 * the rows and the create row reach the semantics tree under the ratified words, that a group row's tap
 * reports **its id** (never its name, invariant 7), and that the create row calls its seam — the two
 * assertions `MaintenanceShellTest` made while the list was drawn inline on the tab, moved with it. The
 * rows' order and the archived mark are proven on the JVM (`MaintenanceGroupsViewModelTest`).
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceGroupsScreenTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private fun draw(record: MutableList<String>) {
        rule.setContent {
            ServiceTagTheme {
                MaintenanceGroupsScreen(
                    graph = app.graph,
                    onBack = { record += "back" },
                    onOpenGroup = { record += "group:$it" },
                    onNewGroup = { record += "new-group" },
                )
            }
        }
    }

    /** A group row opens its group by id; the create row, drawn after the rows, calls its own seam. */
    @Test fun aGroupRowOpensItsGroupAndTheCreateRowCallsItsSeam() {
        runBlocking {
            app.graph.groups.upsert(
                MaintenanceGroup(
                    id = GroupId("p103-north-run"),
                    name = "North run",
                    description = "",
                    archivedAt = null,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    members = emptyList(),
                ),
            )
        }
        val record = mutableListOf<String>()
        draw(record)

        rule.awaitText(GROUPS_SECTION)
        rule.awaitText("North run")
        rule.onNode(hasText("North run") and hasClickAction()).performClick()
        rule.onAllNodesWithText(MAINTENANCE_GROUP).assertCountEquals(1)
        rule.onNodeWithText(MAINTENANCE_GROUP).assertIsDisplayed().performClick()

        rule.runOnIdle {
            check(record == listOf("group:p103-north-run", "new-group")) { "unexpected navigation: $record" }
        }
    }

    /** With no group at all the create row is still there: the only in-app way to make the first one (C5). */
    @Test fun aPhoneWithNoGroupsStillOffersTheCreateRow() {
        val record = mutableListOf<String>()
        draw(record)

        rule.awaitText(GROUPS_SECTION)
        rule.awaitText(MAINTENANCE_GROUP)
        rule.onNodeWithText(MAINTENANCE_GROUP).performClick()
        rule.runOnIdle { check(record == listOf("new-group")) { "unexpected navigation: $record" } }
    }
}
