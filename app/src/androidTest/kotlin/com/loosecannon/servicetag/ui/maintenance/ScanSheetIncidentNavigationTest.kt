package com.loosecannon.servicetag.ui.maintenance

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nav.ServiceTagRoot
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #82 (C6, C11) — the scan sheet's two ways to an Incident through the **real** root: `ServiceTagRoot`
 * composed as `SeasonReconciliationNavigationTest` composes it, the sheet reached by emitting its route
 * on `deepLinks` (a scan's resolution pushes the same route). What only the root proves is its two
 * sheet lines: "Log incident" pushes a new INCIDENT entry, and "Log incident details" pushes the
 * combined entry carrying the held condition. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ScanSheetIncidentNavigationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val snackbars = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    @Before fun freshInstall() = clearInstall()

    @Test fun bothIncidentActionsOpenTheirEntriesThroughTheRoot() {
        val graph = app.graph
        val pack = runBlocking {
            val id = graph.createAsset.run(AssetCommand(name = "Battery pack", category = "Power")).id
            val on = LocalDate.now().minusDays(1).toString()
            graph.recordCondition.run(
                id,
                ConditionCommand(condition = OperationalCondition.DOWN, occurredOn = on, tzId = ZoneId.systemDefault().id),
            )
            id
        }
        rule.setContent { ServiceTagTheme { ServiceTagRoot(graph = graph, deepLinks = deepLinks, snackbars = snackbars) } }
        rule.awaitText("ServiceTag")
        deepLinks.tryEmit(Route.MaintenanceSheet(pack.value))

        // "Log incident": a new, profile-less INCIDENT entry over the sheet, preset "Incident".
        rule.awaitText("Log incident")
        rule.onNodeWithText("Log incident").performScrollTo().performClick()
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText("Entry")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Entry")).assert(hasText("Incident"))
        rule.onAllNodesWithText("Save").onFirst().performClick()
        // Back on the sheet, which reads its flag again: the failure has its Incident now.
        rule.awaitText("Change condition")
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText("Log incident").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(EventKind.INCIDENT), runBlocking { graph.events.all().map { it.kind } })
        assertEquals(1, runBlocking { graph.conditions.all().size })

        // "Log incident details", from a held DOWN: the combined entry, carrying the held condition.
        rule.onNodeWithText("Change condition").performScrollTo().performClick()
        rule.awaitText("Save condition")
        rule.onNodeWithText("Down").performClick()
        rule.onNodeWithText("Save condition").performScrollTo().performClick()
        rule.awaitText("Log incident details?")
        rule.onNodeWithText("Log incident details").performClick()
        rule.awaitText("Saving also records Battery pack as DOWN.")
        assertEquals("the hand-over wrote nothing", 1, runBlocking { graph.conditions.all().size })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
