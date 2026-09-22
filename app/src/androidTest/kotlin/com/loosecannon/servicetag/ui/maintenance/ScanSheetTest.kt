package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scan completion sheet on a real Compose tree: it comes up for a seeded due schedule, an
 * explicit "Complete selected" writes the event, "Not now" writes nothing, and "Open asset" reaches
 * the ordinary detail.
 *
 * What only a real tree shows: that the ratified words are actually drawn, that "Complete selected"
 * is **disabled until something is selected** — the sheet cannot complete anything on open, which
 * is #19's navigation-only contract at the pixel level — and that the two ways out are reachable.
 * The tag-resolution rows are unit tests, because **the emulator has no NFC**: the resolution is
 * injected by opening the sheet on a seeded asset and its seeded tag, which is exactly what a
 * `Resolution.OpenAsset` with actionable work leads to.
 *
 * The seeded schedule carries a genuinely older `created_at`: the D-27 pin's floor is
 * `max(anchorOn, createdOn)`, so a schedule made through `saveSchedule` is never overdue on a fresh
 * store and an overdue row has to be seeded (carry-forward (c)).
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ScanSheetTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private fun dayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun seeded(
        id: String,
        target: ScheduleTarget,
        title: String,
        anchorOn: LocalDate,
        createdOn: LocalDate,
    ) = MaintenanceSchedule(
        id = ScheduleId(id),
        target = target,
        title = title,
        description = "",
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = anchorOn.toString(),
        leadDays = 0,
        meterDefinitionId = null,
        meterInterval = null,
        anchorMeter = null,
        meterLead = null,
        seasonBehavior = SeasonBehavior.IGNORE,
        seasonReentry = null,
        seasonReentryOffsetDays = null,
        completionMode = CompletionMode.QUICK,
        profileId = null,
        remindersEnabled = true,
        status = ScheduleStatus.ACTIVE,
        postponedDueOn = null,
        createdAt = dayMillis(createdOn),
        updatedAt = dayMillis(createdOn),
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    /** One overdue `QUICK` schedule on one Asset, with one labelled tag on it. */
    private fun seedDueWork(graph: AppGraph): Pair<String, String> = runBlocking {
        val today = LocalDate.now()
        val mower = graph.createAsset.run(AssetCommand(name = "Ride-on mower", category = "Yard"))
        graph.schedules.upsert(
            seeded(
                id = "b09-due",
                target = ScheduleTarget.AssetTarget(mower.id),
                title = "Blade sharpen",
                anchorOn = today.minusMonths(6),
                createdOn = today.minusMonths(6),
            ),
        )
        graph.recomputeSchedules.forSchedule(ScheduleId("b09-due"))
        val tagId = "55555555-5555-4555-8555-555555555555"
        graph.tags.upsert(
            TagBinding(
                TagId(tagId),
                PayloadFormat.V1,
                tagId,
                TagTarget.AssetTarget(mower.id),
                TagStatus.ACTIVE,
                label = "Deck plate",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        mower.id.value to tagId
    }

    private fun sheetFor(graph: AppGraph, assetId: String, tagId: String?): MutableList<String> {
        val record = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme {
                MaintenanceSheet(
                    graph = graph,
                    assetId = assetId,
                    tagId = tagId,
                    onOpenAsset = { record += "asset:$it" },
                    onReviewSchedule = { record += "review:$it" },
                    onLogForm = { asset, profile -> record += "form:$asset:$profile" },
                    onDismiss = { record += "dismiss" },
                )
            }
        }
        return record
    }

    /**
     * The sheet comes up titled **"Maintenance"**, names the Asset and the scan point, lists the due
     * work with its ratified word, and offers its three ways forward — with **"Complete selected"**
     * disabled, because nothing is selected and a scan never completes anything by itself.
     */
    @Test fun theSheetOpensForDueWorkAndCompletesNothingByItself() {
        val graph = app.graph
        val (assetId, tagId) = seedDueWork(graph)
        val record = sheetFor(graph, assetId, tagId)

        rule.awaitText("Blade sharpen")
        rule.onNodeWithText("Maintenance").assertIsDisplayed()
        rule.onNodeWithText("Ride-on mower").assertIsDisplayed()
        rule.onNodeWithText("Tag placement").assertIsDisplayed()
        rule.onNodeWithText("Deck plate").assertIsDisplayed()
        rule.onNodeWithText("OVERDUE").assertIsDisplayed()
        // Quick versus form, before any selection.
        rule.onNodeWithText("One tap").assertIsDisplayed()
        rule.onNodeWithText("Review maintenance").assertIsDisplayed()
        rule.onNodeWithText("Open asset").assertIsDisplayed()
        rule.onNodeWithText("Not now").assertIsDisplayed()
        rule.onNodeWithText("Complete selected").assertIsNotEnabled()
        // "Close this round" is not on this sheet at all (spec §1.2 offers it in the editor only).
        rule.onAllNodesWithText("Close this round").assertCountEquals(0)

        assertEquals(emptyList<String>(), record)
        assertEquals(0, runBlocking { graph.events.all().size })
    }

    /**
     * **"Complete selected"** on a `QUICK` item: select it, answer the ratified
     * **"When was this done?"**, and exactly one event exists and the row is gone.
     */
    @Test fun completeSelectedWritesOneEventThroughTheCanonicalAffordance() {
        val graph = app.graph
        val (assetId, tagId) = seedDueWork(graph)
        sheetFor(graph, assetId, tagId)

        rule.awaitText("Blade sharpen")
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("Complete selected").performClick()

        // The one completion affordance, in the one set of words.
        rule.awaitText("When was this done?")
        rule.onNodeWithText("Save").performClick()

        rule.waitUntil(TIMEOUT_MS) { runBlocking { graph.events.all().size } == 1 }
        val event = runBlocking { graph.events.all().single() }
        assertEquals("b09-due", event.scheduleId?.value)
        assertEquals(LocalDate.now().toString(), event.occurredOn)
        // The occurrence is no longer offered, so the row has left the sheet.
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText("Blade sharpen").fetchSemanticsNodes().isEmpty()
        }
    }

    /** **"Not now"** leaves, and writes nothing at all (#50 AC 6). */
    @Test fun notNowWritesNothing() {
        val graph = app.graph
        val (assetId, tagId) = seedDueWork(graph)
        val record = sheetFor(graph, assetId, tagId)

        rule.awaitText("Blade sharpen")
        rule.onNodeWithText("Not now").performClick()
        rule.waitUntil(TIMEOUT_MS) { record.isNotEmpty() }

        assertEquals(listOf("dismiss"), record)
        runBlocking {
            assertEquals(0, graph.events.all().size)
            assertEquals(0, graph.closures.all().size)
            assertNull(graph.scheduleLocalDelivery.get(ScheduleId("b09-due"))?.snoozedUntilAt)
            assertNull(graph.schedules.get(ScheduleId("b09-due"))?.postponedDueOn)
        }
    }

    /** **"Open asset"** always reaches the ordinary detail, with no mutation (#50 AC 7). */
    @Test fun openAssetReachesTheOrdinaryDetail() {
        val graph = app.graph
        val (assetId, tagId) = seedDueWork(graph)
        val record = sheetFor(graph, assetId, tagId)

        rule.awaitText("Blade sharpen")
        rule.onNodeWithText("Open asset").performClick()
        rule.waitUntil(TIMEOUT_MS) { record.isNotEmpty() }

        assertEquals(listOf("asset:$assetId"), record)
        assertEquals(0, runBlocking { graph.events.all().size })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
