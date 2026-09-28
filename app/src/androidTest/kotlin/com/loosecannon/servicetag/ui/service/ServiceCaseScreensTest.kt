package com.loosecannon.servicetag.ui.service

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for a state to settle. */
private const val WAIT_MS = 10_000L

/**
 * #79 (C21, C22; §3 row 49): the case editor and the case screen on a real Compose tree, over the app's
 * own graph. The editor's fields, chips and refusals as ratified; the update sheet and the timeline it
 * appends to, with no way to edit or remove an entry; and each linked event's documents drawn through
 * that event's own section — a case owns none (R79-2).
 *
 * Every control below a text field is scrolled to before it is tapped (the standing scroll rule).
 * Emulator only (`emulator-5554`) — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ServiceCaseScreensTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun freshInstall() = clearInstall()

    private val today: String get() = app.graph.today.localDate().toString()

    private fun heater(): AssetId = runBlocking {
        val until = app.graph.today.localDate().plusDays(365).toString()
        app.graph.createAsset.run(AssetCommand(name = "Example Heater", warrantyExpiresOn = until, currency = "EUR")).id
    }

    private fun event(assetId: AssetId, kind: EventKind, title: String): EventId = runBlocking {
        app.graph.logEvent.run(EventCommand(assetId, null, kind, title, today, null, "UTC", "", emptyMap(), emptyList())).id
    }

    private fun openCase(assetId: AssetId, incident: EventId, resolution: EventId? = null): String = runBlocking {
        app.graph.openServiceCase.run(
            assetId,
            ServiceCaseCommand(
                title = "Heater claim", type = CaseType.WARRANTY_SERVICE, openedOn = today,
                coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = resolution, caseRef = "RMA-0001",
            ),
            incident,
        ).id.value
    }

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    /**
     * C21: P79-19 over the ratified fields and chips, the type and coverage chosen from the suggestion,
     * P79-36 under Coverage; then P79-52, P79-62 and P79-48 each under its own field, and nothing saved.
     */
    @Test fun theEditorsFieldsChipsAndRefusals() {
        val id = heater()
        val incident = event(id, EventKind.INCIDENT, "Will not heat")
        rule.setContent {
            ServiceTagTheme {
                ServiceCaseEditScreen(
                    graph = app.graph, assetId = id.value, caseId = null, incidentId = incident.value,
                    onDone = {}, onBack = {},
                )
            }
        }
        rule.awaitText(SAVE_CASE)

        rule.onNodeWithText(NEW_SERVICE_CASE).assertIsDisplayed()
        listOf(
            CASE_TITLE, CASE_TYPE, WARRANTY_SERVICE, REPAIR, OTHER_SERVICE, OPENED_ON, SERVICE_PROVIDER,
            PHONE_OR_CONTACT, CASE_OR_RMA_NUMBER, COVERAGE, COVERAGE_IN_WARRANTY, COVERAGE_OUT_OF_WARRANTY,
            COVERAGE_UNKNOWN, PARTLY_COVERED, SUGGESTED_FROM_THE_WARRANTY_DATE, OUTBOUND_TRACKING, RETURN_TRACKING,
            COST, "Currency", "Notes", SAVE_CASE,
        ).forEach { rule.onAllNodesWithText(it).onFirst().performScrollTo().assertIsDisplayed() }
        rule.onAllNodesWithText(CARRIER).assertCountEquals(2)
        rule.onNodeWithText(WARRANTY_SERVICE).performScrollTo().assertIsSelected()
        rule.onNodeWithText(REPAIR).assertIsNotSelected()
        rule.onNodeWithText(COVERAGE_IN_WARRANTY).performScrollTo().assertIsSelected()
        check(top(COVERAGE) < top(SUGGESTED_FROM_THE_WARRANTY_DATE)) { "P79-36 is under Coverage" }
        field("Will not heat").performScrollTo().assertIsDisplayed()

        // A chip is a choice: Repair and Partly covered, chosen.
        rule.onNodeWithText(REPAIR).performScrollTo().performClick()
        rule.onNodeWithText(REPAIR).assertIsSelected()
        rule.onNodeWithText(PARTLY_COVERED).performScrollTo().performClick()
        rule.onNodeWithText(PARTLY_COVERED).assertIsSelected()
        rule.onNodeWithText(COVERAGE_IN_WARRANTY).assertIsNotSelected()

        field(CASE_TITLE).performScrollTo().performTextReplacement("")
        field(COST).performScrollTo().performTextReplacement("12.345")
        rule.onNodeWithText(SAVE_CASE).performScrollTo().performClick()
        rule.awaitText("Enter a cost like 123.45")

        field(COST).performScrollTo().performTextReplacement("12.34")
        field("Currency").performScrollTo().performTextReplacement("")
        rule.onNodeWithText(SAVE_CASE).performScrollTo().performClick()
        rule.awaitText(A_COST_NEEDS_A_CURRENCY)

        field("Currency").performScrollTo().performTextReplacement("EUR")
        rule.onNodeWithText(SAVE_CASE).performScrollTo().performClick()
        rule.awaitText(GIVE_THE_CASE_A_TITLE)
        rule.onNodeWithText(GIVE_THE_CASE_A_TITLE).performScrollTo().assertIsDisplayed()
        assertEquals(0, runBlocking { app.graph.serviceCases.forAsset(id).size })
    }

    /**
     * C22: P79-54 opens its sheet — Date today, Time, P79-55, the six status chips with none chosen —
     * whose P79-56 is held until a note or a status is given. A status update moves Status, lands in
     * the Timeline and closes the sheet; a CLOSED one adds Closed on. No entry offers an edit or a
     * delete (R79-8).
     */
    @Test fun addUpdateSheetAndTimeline() {
        val id = heater()
        val caseId = openCase(id, event(id, EventKind.INCIDENT, "Will not heat"))
        rule.setContent {
            ServiceTagTheme {
                ServiceCaseScreen(graph = app.graph, caseId = caseId, onEdit = { _, _ -> }, onOpenEvent = {}, onBack = {}, onOpenSettings = {})
            }
        }
        rule.awaitText("Heater claim")
        rule.onNodeWithText(SERVICE_CASE).assertIsDisplayed()
        rule.onNodeWithText("Edit").assertIsDisplayed()
        rule.onNodeWithText("RMA-0001").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(STATUS_OPEN).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(CLOSED_ON).assertCountEquals(0)

        rule.onNodeWithText(ADD_UPDATE).performScrollTo().performClick()
        rule.awaitText(SAVE_UPDATE)
        field("Date").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText(today)).assertIsDisplayed()
        field("Time").assertIsDisplayed()
        field(WHAT_HAPPENED).assertIsDisplayed()
        listOf(STATUS_SENT_OUT, STATUS_AT_THE_SERVICE_CENTER, STATUS_RETURNED, STATUS_CLOSED, STATUS_CANCELLED).forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsNotSelected()
        }
        rule.onNodeWithText(SAVE_UPDATE).performScrollTo().assertIsNotEnabled()

        field(WHAT_HAPPENED).performTextReplacement("Shipped to the service center")
        rule.onNodeWithText(STATUS_SENT_OUT).performScrollTo().performClick()
        rule.onNodeWithText(STATUS_SENT_OUT).assertIsSelected()
        rule.onNodeWithText(SAVE_UPDATE).performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SAVE_UPDATE).fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithText("Shipped to the service center").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(STATUS_SENT_OUT).assertCountEquals(2)
        check(top(TIMELINE.uppercase()) < top("Shipped to the service center")) { "the entry is in the Timeline" }

        rule.onNodeWithText(ADD_UPDATE).performScrollTo().performClick()
        rule.awaitText(SAVE_UPDATE)
        rule.onNodeWithText(STATUS_CLOSED).performScrollTo().performClick()
        rule.onNodeWithText(SAVE_UPDATE).performScrollTo().performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(CLOSED_ON).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(CLOSED_ON).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(STATUS_CLOSED).assertCountEquals(2)

        rule.onAllNodesWithText("Delete").assertCountEquals(0)
        rule.onAllNodesWithText("Remove").assertCountEquals(0)
        assertEquals(2, runBlocking { app.graph.serviceCaseEntries.forCase(ServiceCaseId(caseId)).size })
    }

    /**
     * R79-2 (AC 15): the Incident's claim form and the repair record's invoice are each drawn under its
     * own link row, through that event's own attachments section — one file each, owned by its event,
     * and no file anywhere owned by the case.
     */
    @Test fun theLinkedEventsDocumentsAreDrawnThroughTheirOwnSection() {
        useFileBackedTree()
        val id = heater()
        val incident = event(id, EventKind.INCIDENT, "Will not heat")
        val repair = event(id, EventKind.MAINTENANCE, "Element replaced")
        val claim = attach(AttachmentOwner.OfEvent(incident), "Claim form.pdf")
        val invoice = attach(AttachmentOwner.OfEvent(repair), "Repair invoice.pdf")
        val caseId = openCase(id, incident, resolution = repair)
        rule.setContent {
            ServiceTagTheme {
                ServiceCaseScreen(graph = app.graph, caseId = caseId, onEdit = { _, _ -> }, onOpenEvent = {}, onBack = {}, onOpenSettings = {})
            }
        }
        rule.awaitText("Claim form.pdf")
        rule.awaitText("Repair invoice.pdf")

        rule.onAllNodesWithText("Claim form.pdf").assertCountEquals(1)
        rule.onAllNodesWithText("Repair invoice.pdf").assertCountEquals(1)
        rule.onNodeWithText("Claim form.pdf").performScrollTo()
        check(top(INCIDENT_LINK) < top("Claim form.pdf")) { "the claim form is under the Incident row" }
        rule.onNodeWithText("Repair invoice.pdf").performScrollTo()
        check(top(REPAIR_RECORD) < top("Repair invoice.pdf")) { "the invoice is under the Repair record row" }
        check(top("Claim form.pdf") < top(REPAIR_RECORD)) { "each file under its own row" }
        rule.onNodeWithText("Remove").performScrollTo().assertIsDisplayed()

        val rows = runBlocking { app.graph.attachments.all() }
        assertEquals(setOf(claim, invoice), rows.map { it.id.value }.toSet())
        assertEquals(
            mapOf(claim to AttachmentOwner.OfEvent(incident), invoice to AttachmentOwner.OfEvent(repair)),
            rows.associate { it.id.value to it.owner },
        )
    }

    private fun attach(owner: AttachmentOwner, name: String): String = runBlocking {
        val added = app.graph.addAttachment.run(
            owner,
            AddAttachmentCommand(displayName = name, mimeType = "application/pdf", sizeBytes = 4L, capturedOn = today),
            ByteSource { "%PDF".byteInputStream() },
        )
        check(added is AttachmentResult.Ok) { "the file is added: $added" }
        added.value.id.value
    }
}

/** The attachment seam pointed at a file-backed tree, as `AssetDetailKeyDocumentsTest` points it. */
private fun useFileBackedTree(): File {
    val context: Context = ApplicationProvider.getApplicationContext()
    val root = File(context.getExternalFilesDir(null), "service-case-documents-proof").also {
        it.deleteRecursively()
        it.mkdirs()
    }
    val graph = app.graph
    graph.attachmentRootResolver = { _ -> DocumentTreeRoot(DocumentFile.fromFile(root), context.contentResolver) }
    graph.attachmentGrantCheck = { true }
    graph.prefs.attachmentTreeUri = "file://" + root.absolutePath
    return root
}
