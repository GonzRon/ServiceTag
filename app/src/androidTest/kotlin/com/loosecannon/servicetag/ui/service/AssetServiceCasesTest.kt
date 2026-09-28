package com.loosecannon.servicetag.ui.service

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.NO_CHANGE
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nav.ServiceTagRoot
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long a navigation and its effects are given to settle. */
private const val SETTLE_MILLIS = 10_000L

/**
 * #79 (C20, R79-3; §3 row 46): "New service case" on the asset detail, on the real back stack.
 *
 * **Why `ServiceTagRoot`.** What is under test is where P79-19 goes: with a current failure's Incident
 * the case editor opens on it; with none, a new INCIDENT entry opens first and its save — after
 * Workflow B's answer — is *replaced* by the editor on the Incident it just logged. Only the shell's
 * back stack can show a replacement, so the whole root is composed, opened on the asset by its own
 * deep-link flow, and driven through the Compose test APIs alone.
 *
 * Emulator only (`emulator-5554`) — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetServiceCasesTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val snackbars = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    @Before fun freshInstall() = clearInstall()

    /** Example Heater, in warranty for a year yet, priced in euros. */
    private fun heater(): AssetId = runBlocking {
        val until = app.graph.today.localDate().plusDays(365).toString()
        app.graph.createAsset.run(
            AssetCommand(name = "Example Heater", warrantyExpiresOn = until, purchasePriceMinor = 12_000, currency = "EUR"),
        ).id
    }

    private fun openOn(assetId: AssetId) {
        rule.setContent {
            ServiceTagTheme { ServiceTagRoot(graph = app.graph, deepLinks = deepLinks, snackbars = snackbars) }
        }
        rule.awaitText("ServiceTag")
        deepLinks.tryEmit(Route.AssetDetail(assetId.value))
        rule.awaitText("Example Heater")
    }

    private fun tapNewServiceCase() {
        rule.onNodeWithText(SERVICE_CASES.uppercase()).performScrollTo()
        rule.onNodeWithText(NEW_SERVICE_CASE).performScrollTo().performClick()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /**
     * DOWN with an Incident for this failure: P79-19 opens the editor on it — P79-19 the title, the
     * Incident's title in Title, the suggestion from its date — and Save case opens the new case over
     * the asset, whose section then lists it with "1 open service case".
     */
    @Test fun newServiceCaseWithACurrentIncidentOpensTheEditorOnIt() {
        val id = heater()
        val today = app.graph.today.localDate().toString()
        val incident = runBlocking {
            app.graph.recordConditionWithIncident.run(
                id,
                "cond-down",
                ConditionCommand(OperationalCondition.DOWN, occurredOn = today, tzId = "UTC"),
                EventCommand(id, null, EventKind.INCIDENT, "Will not heat", today, null, "UTC", "", emptyMap(), emptyList()),
            ).incident!!
        }
        openOn(id)
        rule.onNodeWithText(NO_SERVICE_CASES_YET).performScrollTo().assertIsDisplayed()

        tapNewServiceCase()
        rule.awaitText(SAVE_CASE)
        // The detail's own "New service case" leaves with the detail; the editor's title stays.
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(NEW_SERVICE_CASE).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText(NEW_SERVICE_CASE).assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Will not heat")).assertIsDisplayed()
        rule.onNodeWithText(SUGGESTED_FROM_THE_WARRANTY_DATE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SAVE_CASE).performScrollTo().performClick()

        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(TIMELINE.uppercase()).fetchSemanticsNodes().isNotEmpty() }
        val case = runBlocking { app.graph.serviceCases.forAsset(id).single() }
        assertEquals(incident.id, case.incidentEventId)
        assertEquals(CaseStatus.OPEN, case.status)
        assertEquals(CaseCoverage.IN_WARRANTY, case.coverage)
        assertEquals("EUR", case.currency)

        back()
        rule.awaitText(ONE_OPEN_SERVICE_CASE)
        rule.onNodeWithText("Open · In warranty").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(SAVE_CASE).assertCountEquals(0)
    }

    /**
     * C23 through the production graph: the Incident's own detail offers P79-20 in its menu, after
     * Edit; it opens the editor on that Incident, whose save opens the case over the entry. Back on the
     * entry, the delete confirm now adds P79-60 after "Its readings go with it." — the graph's
     * `CaseLinks` read — and "Cancel" deletes nothing.
     */
    @Test fun theIncidentDetailStartsACaseAndItsDeleteConfirmNamesTheLink() {
        val id = heater()
        val today = app.graph.today.localDate().toString()
        val incident = runBlocking {
            app.graph.logEvent.run(EventCommand(id, null, EventKind.INCIDENT, "Will not heat", today, null, "UTC", "", emptyMap(), emptyList()))
        }
        rule.setContent {
            ServiceTagTheme { ServiceTagRoot(graph = app.graph, deepLinks = deepLinks, snackbars = snackbars) }
        }
        rule.awaitText("ServiceTag")
        deepLinks.tryEmit(Route.EventDetail(incident.id.value))
        rule.awaitText("WILL NOT HEAT")

        rule.onNodeWithContentDescription("More").performClick()
        rule.awaitText(START_SERVICE_CASE)
        check(rule.onNodeWithText("Edit").getUnclippedBoundsInRoot().top < rule.onNodeWithText(START_SERVICE_CASE).getUnclippedBoundsInRoot().top) {
            "P79-20 follows Edit"
        }
        rule.onNodeWithText(START_SERVICE_CASE).performClick()
        rule.awaitText(SAVE_CASE)
        rule.onNode(hasSetTextAction() and hasText("Will not heat")).assertIsDisplayed()
        rule.onNodeWithText(SAVE_CASE).performScrollTo().performClick()
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(TIMELINE.uppercase()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(incident.id, runBlocking { app.graph.serviceCases.forAsset(id).single().incidentEventId })

        back()
        rule.awaitText("WILL NOT HEAT")
        rule.onNodeWithContentDescription("More").performClick()
        rule.awaitText("Delete")
        rule.onNodeWithText("Delete").performClick()
        rule.awaitText("Delete this entry?")
        rule.onNodeWithText("Its readings go with it.").assertIsDisplayed()
        rule.onNodeWithText(A_SERVICE_CASE_LINKS_THIS_ENTRY).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals("Cancel deletes nothing", incident.id, runBlocking { app.graph.events.get(incident.id)?.id })
    }

    /**
     * No current Incident: P79-19 opens a new INCIDENT entry first. Its save — after Workflow B's "No
     * change" — hands over to the editor on the Incident it logged, which *replaces* the entry: one
     * back press from the editor returns to the asset.
     */
    @Test fun withoutOneItOpensAnIncidentEntryFirst() {
        val id = heater()
        openOn(id)

        tapNewServiceCase()
        rule.awaitText("Entry")
        rule.onAllNodesWithText(SAVE_CASE).assertCountEquals(0)
        rule.onNode(hasSetTextAction() and hasText("Entry")).performTextReplacement("Trips the breaker")
        rule.onAllNodesWithText("Save").onFirst().performClick()
        rule.awaitText(NO_CHANGE)
        rule.onNodeWithText(NO_CHANGE).performClick()

        rule.awaitText(SAVE_CASE)
        // The entry is gone — replaced, not stacked — before the editor's field is read.
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Entry").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Trips the breaker")).assertIsDisplayed()
        val incident = runBlocking { app.graph.events.forAsset(id).single() }
        assertEquals(EventKind.INCIDENT, incident.kind)
        assertEquals("the Incident alone is written until Save case", 0, runBlocking { app.graph.serviceCases.forAsset(id).size })

        back()
        rule.awaitText("Example Heater")
        rule.onAllNodesWithText(SAVE_CASE).assertCountEquals(0)
        rule.onAllNodesWithText("Entry").assertCountEquals(0)
        rule.onNodeWithText(NO_SERVICE_CASES_YET).performScrollTo().assertIsDisplayed()
    }
}
