package com.loosecannon.servicetag.ui.maintenance

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nav.ServiceTagRoot
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #117 — a FORM schedule opened from the app completes **that schedule** through its form, on the **real** root:
 * `ServiceTagRoot` composed as `ScanSheetIncidentNavigationTest` composes it, each way in reached by emitting its
 * route on `deepLinks` (a scan and the notification's **Done** push the same routes) and driven only by semantic taps.
 *
 * What only the root proves is its wiring: the detail's, the scan sheet's and the Maintenance destination's
 * **Log maintenance** picker's `onLogForm` lambdas build the entry route with `formEntry`, so the form's
 * **Record entry** writes the completion (N1, N2, N3, N4); a notification's arrival asks once, so back from the form
 * — saved (N2) or closed with ✕ (N2b) — the detail does not open the form again (§3.3); and back from a form the scan
 * sheet re-reads its list and walks on to the next selected form (N3, N3b; §3.4). An arrival over the same schedule's
 * plain detail leaves two equal detail keys once it is cleared, and the stack still backs out through both to the
 * dashboard (N2c). N4 also proves #120: the picker waits for its rows before it decides there is nothing to log.
 *
 * The last three cases compose the detail screen alone, because what they count is its callbacks: an arrival's end
 * reaches `onArrivalEnded` exactly once after an answer or a cancel, and never after a hand-over to the form.
 *
 * Every form here is committed with its bottom **Record entry**, scrolled to first. A "the form does not come back"
 * check is a bounded look ([SETTLE_MS]) taken once the form has closed: a re-run arrival opens it again within a
 * frame or two of the detail composing.
 *
 * Fixtures are fictional: an asset "Mower", FORM schedules "Blade sharpen" and "Deck clean" anchored two quarters
 * back (so overdue), and one profile with a required reading and a suggested material. Emulator only — the suite
 * wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class FormCompletionNavigationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val snackbars = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    @Before fun freshInstall() = clearInstall()

    /** N1: the schedule's own **Log maintenance** → its form → **Record entry** completes it; the detail says so. */
    @Test fun logMaintenanceOnTheScheduleCompletesItThroughItsForm() {
        val mower = seed(BLADE)
        open(Route.ScheduleDetail(mower.schedule(BLADE)))

        rule.awaitText(LOG_MAINTENANCE_LABEL)
        rule.onNodeWithText(OVERDUE_WORD).assertExists()
        rule.onNodeWithText(NO_SERVICE).assertExists()
        rule.onNodeWithText(LOG_MAINTENANCE_LABEL).performClick()

        recordTheReading()
        awaitFormClosed()
        awaitTheDetailListsTheCompletion()
        rule.onAllNodesWithText(NO_SERVICE).assertCountEquals(0)
        rule.onAllNodesWithText(OVERDUE_WORD).assertCountEquals(0)
        assertEquals(listOf(mower.schedule(BLADE)), linkedSchedules())
    }

    /**
     * N2: the notification's **Done** on a FORM schedule — the form opens by itself, **Record entry** writes one
     * completion, and back on the detail the arrival is not asked again: the form stays closed.
     */
    @Test fun anArrivalOpensTheFormOnceAndItsSaveCompletesTheSchedule() {
        val mower = seed(BLADE)
        open(Route.ScheduleDetail(mower.schedule(BLADE), complete = true))

        recordTheReading()
        awaitFormClosed()
        assertFalse("the arrival asked again: the form opened a second time", formReopens())
        awaitTheDetailListsTheCompletion()
        rule.onAllNodesWithText(OVERDUE_WORD).assertCountEquals(0)
        assertEquals(listOf(mower.schedule(BLADE)), linkedSchedules())
    }

    /**
     * N2c (T1b2 review, O1): the notification's **Done** while the same schedule's plain detail is open. Cleared, the
     * arrival leaves two equal detail keys under the form; its save completes the schedule once, and two backs walk
     * out through both details to the dashboard.
     */
    @Test fun anArrivalOverTheSameSchedulesOpenDetailCompletesAndBacksOut() {
        val mower = seed(BLADE)
        open(Route.ScheduleDetail(mower.schedule(BLADE)))
        rule.awaitText(LOG_MAINTENANCE_LABEL)
        deepLinks.tryEmit(Route.ScheduleDetail(mower.schedule(BLADE), complete = true))

        recordTheReading()
        awaitFormClosed()
        assertFalse("the arrival asked again: the form opened a second time", formReopens())
        awaitTheDetailListsTheCompletion()
        assertEquals(listOf(mower.schedule(BLADE)), linkedSchedules())

        backFromTheDetail()
        awaitTheDetailListsTheCompletion()
        backFromTheDetail()
        rule.waitUntil(conditionDescription = "back on the dashboard", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithText("ServiceTag").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithContentDescription(BACK).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, runBlocking { app.graph.events.all().size })
    }

    /** N2b: the arrival's form closed with ✕ — back on the detail the form stays closed, and nothing is written. */
    @Test fun anArrivalsFormClosedWithoutSavingDoesNotOpenAgain() {
        val mower = seed(BLADE)
        open(Route.ScheduleDetail(mower.schedule(BLADE), complete = true))

        awaitForm()
        rule.onNodeWithContentDescription(CLOSE).performClick()
        awaitFormClosed()
        assertFalse("✕ looped back into the form", formReopens())
        rule.awaitText(LOG_MAINTENANCE_LABEL)
        rule.onNodeWithText(OVERDUE_WORD).assertExists()
        rule.onNodeWithText(NO_SERVICE).assertExists()
        assertEquals(0, runBlocking { app.graph.events.all().size })
    }

    /** N3: the scan sheet's **Complete selected** on a FORM row → its form → **Record entry** → the row has left. */
    @Test fun theScanSheetsFormCompletesItsRowAndTheSheetReReadsItsList() {
        val mower = seed(BLADE)
        open(Route.MaintenanceSheet(mower.id.value))

        rule.awaitText(BLADE)
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText(COMPLETE_SELECTED_LABEL).performClick()

        recordTheReading()
        awaitTheSheetWithout(BLADE)
        assertEquals(listOf(mower.schedule(BLADE)), linkedSchedules())
    }

    /**
     * N3b: two FORM rows selected — the first form's **Record entry** brings the second form up by itself, and its
     * **Record entry** leaves the sheet with neither row.
     */
    @Test fun theScanSheetWalksOnToTheNextSelectedForm() {
        val mower = seed(BLADE, DECK)
        open(Route.MaintenanceSheet(mower.id.value))

        rule.awaitText(BLADE)
        rule.awaitText(DECK)
        rule.onAllNodes(isToggleable()).assertCountEquals(2)
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onAllNodes(isToggleable())[1].performClick()
        rule.onNodeWithText(COMPLETE_SELECTED_LABEL).performClick()

        recordTheReading()
        rule.waitUntil(conditionDescription = "the first form's completion is written", timeoutMillis = TIMEOUT_MS) {
            runBlocking { app.graph.events.all().size } == 1
        }
        // The second form is a fresh one — its reading is empty — and the first has gone.
        rule.waitUntil(conditionDescription = "the second form opens by itself", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithText(RECORD_ENTRY).fetchSemanticsNodes().size == 1 &&
                rule.onAllNodes(hasTestTag(READING_TAG) and hasEmptyValue()).fetchSemanticsNodes().size == 1
        }
        recordTheReading()
        awaitTheSheetWithout(BLADE, DECK)
        assertEquals(setOf(mower.schedule(BLADE), mower.schedule(DECK)), linkedSchedules().toSet())
        assertEquals(2, runBlocking { app.graph.events.all().size })
    }

    /** N4: the Maintenance destination's **Log maintenance** → the FORM row → its form → **Record entry**. */
    @Test fun theMaintenancePickersFormCompletesTheSchedule() {
        val mower = seed(BLADE)
        open(Route.Maintenance)

        // The destination lists the due row, so the picker has work to offer.
        rule.awaitText(BLADE)
        rule.awaitText(LOG_MAINTENANCE_LABEL)
        rule.onNodeWithText(LOG_MAINTENANCE_LABEL).performClick()
        rule.waitUntil(conditionDescription = "the picker opens", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(conditionDescription = "the picker lists the FORM row", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodes(hasText(BLADE) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText(BLADE) and hasAnyAncestor(isDialog())).performClick()

        recordTheReading()
        awaitFormClosed()
        rule.awaitText(LOG_MAINTENANCE_LABEL)
        assertEquals(listOf(mower.schedule(BLADE)), linkedSchedules())
    }

    /** T1b2 review, O2: a QUICK meter arrival answered — one completion, and its end said to the host once. */
    @Test fun anAnsweredArrivalEndsOnce() {
        val trail = detailAlone(seedMeterSchedule())

        rule.awaitText(WHEN_WAS_THIS_DONE)
        rule.onNode(hasSetTextAction() and hasText(LOG_METER_READING)).performTextInput("250")
        rule.onNode(hasText(SAVE) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(conditionDescription = "the answered arrival ends", timeoutMillis = TIMEOUT_MS) {
            rule.runOnIdle { ENDED in trail }
        }
        assertEquals(listOf(ENDED), rule.runOnIdle { trail.toList() })
        assertEquals(1, runBlocking { app.graph.events.all().size })
    }

    /** O2: the same arrival cancelled — nothing written, and its end said to the host once. */
    @Test fun aCancelledArrivalEndsOnce() {
        val trail = detailAlone(seedMeterSchedule())

        rule.awaitText(WHEN_WAS_THIS_DONE)
        rule.onNode(hasText(CANCEL) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(conditionDescription = "the cancelled arrival ends", timeoutMillis = TIMEOUT_MS) {
            rule.runOnIdle { ENDED in trail }
        }
        assertEquals(listOf(ENDED), rule.runOnIdle { trail.toList() })
        assertEquals(0, runBlocking { app.graph.events.all().size })
    }

    /** O2, a pin: a FORM arrival hands over once and says no end — the host clears it as it pushes the form. */
    @Test fun aFormArrivalHandsOverOnceAndSaysNoEnd() {
        val mower = seed(BLADE)
        val trail = detailAlone(mower.schedule(BLADE))

        val handOver = "form:${mower.schedule(BLADE)}"
        rule.waitUntil(conditionDescription = "the arrival hands over", timeoutMillis = TIMEOUT_MS) {
            rule.runOnIdle { handOver in trail }
        }
        assertFalse("a hand-over said an end", rule.comesTrueWithin(SETTLE_MS) { rule.runOnIdle { ENDED in trail } })
        assertEquals(listOf(handOver), rule.runOnIdle { trail.toList() })
        assertEquals(0, runBlocking { app.graph.events.all().size })
    }

    // ------------------------------------------------------------------ driving

    /** The real root, with [route] emitted on `deepLinks` once the dashboard is up. */
    private fun open(route: Route) {
        val graph = app.graph
        rule.setContent { ServiceTagTheme { ServiceTagRoot(graph = graph, deepLinks = deepLinks, snackbars = snackbars) } }
        rule.awaitText("ServiceTag")
        deepLinks.tryEmit(route)
    }

    /**
     * The schedule detail alone, arriving as the notification's **Done** does; the returned trail records each
     * hand-over to a form (`form:<schedule>`) and each arrival end ([ENDED]), in order, on the main thread.
     */
    private fun detailAlone(scheduleId: String): MutableList<String> {
        val graph = app.graph
        val trail = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme {
                ScheduleDetailScreen(
                    graph = graph,
                    scheduleId = scheduleId,
                    onBack = {},
                    onEditRecurrence = {},
                    onLogForm = { form -> trail += "form:${form.scheduleId.value}" },
                    startCompletion = true,
                    onArrivalEnded = { trail += ENDED },
                )
            }
        }
        return trail
    }

    /** The one detail on top's back arrow. */
    private fun backFromTheDetail() {
        rule.waitUntil(conditionDescription = "one detail on top", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithContentDescription(BACK).fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithContentDescription(BACK).performClick()
    }

    /** Exactly one form is up — one **Record entry**, one reading — whatever opened it. */
    private fun awaitForm() {
        rule.waitUntil(conditionDescription = "the form is open", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithText(RECORD_ENTRY).fetchSemanticsNodes().size == 1 &&
                rule.onAllNodesWithTag(READING_TAG).fetchSemanticsNodes().size == 1
        }
    }

    /** The open form's required reading, then its bottom **Record entry**. */
    private fun recordTheReading() {
        awaitForm()
        rule.onNodeWithTag(READING_TAG).performScrollTo().performTextInput("3")
        rule.onNodeWithText(RECORD_ENTRY).performScrollTo().performClick()
    }

    private fun awaitFormClosed() {
        rule.waitUntil(conditionDescription = "the form closes", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithText(RECORD_ENTRY).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Whether a form comes up within [SETTLE_MS] — a bounded look for what must not happen. */
    private fun formReopens(): Boolean =
        rule.comesTrueWithin(SETTLE_MS) { rule.onAllNodesWithText(RECORD_ENTRY).fetchSemanticsNodes().isNotEmpty() }

    /**
     * The schedule detail on top — its back arrow, no form — with its service record listing the completion: the
     * record's row is labelled with the asset in capitals, beside the bar's own "MOWER", and the empty line has gone.
     */
    private fun awaitTheDetailListsTheCompletion() {
        rule.waitUntil(conditionDescription = "the detail lists the completion", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithContentDescription(BACK).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText(RECORD_ENTRY).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText(NO_SERVICE).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText(MOWER_LABEL).fetchSemanticsNodes().size >= 2
        }
    }

    /** The sheet on top again — its **Not now**, no form — with none of [titles] left on it. */
    private fun awaitTheSheetWithout(vararg titles: String) {
        rule.waitUntil(conditionDescription = "the sheet drops ${titles.toList()}", timeoutMillis = TIMEOUT_MS) {
            rule.onAllNodesWithText(NOT_NOW_LABEL).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText(RECORD_ENTRY).fetchSemanticsNodes().isEmpty() &&
                titles.all { rule.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty() }
        }
    }

    /** Every stored event's schedule link, in no particular order; an unlinked entry reads null. */
    private fun linkedSchedules(): List<String?> = runBlocking { app.graph.events.all().map { it.scheduleId?.value } }

    private fun hasEmptyValue(): SemanticsMatcher = SemanticsMatcher("an empty value") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text?.isEmpty() == true
    }

    private fun ComposeTestRule.comesTrueWithin(windowMs: Long, condition: () -> Boolean): Boolean =
        try {
            waitUntil(timeoutMillis = windowMs, condition = condition)
            true
        } catch (_: ComposeTimeoutException) {
            false
        }

    // ------------------------------------------------------------------ fixtures

    private class Seeded(val id: AssetId, private val schedules: Map<String, ScheduleId>) {
        fun schedule(title: String): String = schedules.getValue(title).value
    }

    /**
     * The Mower, its "Blade service" profile — one required reading, one suggested material — and one FORM schedule
     * per title on that profile, FIXED quarterly and anchored two quarters back. The rows carry a genuinely older
     * `created_at`: the D-27 pin's floor is `max(anchorOn, createdOn)`, so a schedule saved today is never overdue.
     */
    private fun seed(vararg titles: String): Seeded = runBlocking {
        val graph = app.graph
        val mower = graph.createAsset.run(AssetCommand(name = MOWER, category = "Yard")).id
        val gap = graph.saveDefinition.run(
            null,
            DefinitionCommand(
                assetId = mower,
                key = "blade_gap",
                label = "Blade gap",
                unit = "mm",
                kind = DefinitionKind.ENTERED,
                valueType = ValueType.NUMBER,
                decimals = 0,
                rangeLow = null,
                rangeHigh = null,
                isMeter = false,
                formula = null,
                sourceA = null,
                sourceB = null,
            ),
        )
        val profile = graph.saveProfile.run(
            null,
            ProfileCommand(
                assetId = mower,
                name = "Blade service",
                eventKind = EventKind.MAINTENANCE,
                defaultTitle = "",
                fields = listOf(ProfileFieldInput(gap.id, required = true)),
                consumables = listOf(ProfileConsumableInput(null, "Grease", 1.0, "tube", supplyId = null)),
            ),
        )
        val back = LocalDate.now().minusMonths(6)
        val ids = titles.associateWith { title ->
            val id = ScheduleId("t1c-" + title.lowercase().replace(' ', '-'))
            graph.schedules.upsert(formSchedule(id, mower, title, profile.id, back))
            graph.recomputeSchedules.forSchedule(id)
            id
        }
        Seeded(mower, ids)
    }

    /**
     * O2's QUICK schedule that counts a meter — "Oil change" on the Mower, every 100 engine hours from 100 and
     * quarterly from two quarters back — so its arrival asks "When was this done?" with the reading it owes.
     */
    private fun seedMeterSchedule(): String = runBlocking {
        val graph = app.graph
        val mower = graph.createAsset.run(AssetCommand(name = MOWER, category = "Yard")).id
        val hours = graph.saveDefinition.run(
            null,
            DefinitionCommand(
                assetId = mower,
                key = "engine_hours",
                label = "Engine hours",
                unit = "h",
                kind = DefinitionKind.ENTERED,
                valueType = ValueType.NUMBER,
                decimals = 0,
                rangeLow = null,
                rangeHigh = null,
                isMeter = true,
                formula = null,
                sourceA = null,
                sourceB = null,
            ),
        )
        val id = ScheduleId("t1c-oil-change")
        graph.schedules.upsert(
            formSchedule(id, mower, "Oil change", profileId = null, back = LocalDate.now().minusMonths(6)).copy(
                completionMode = CompletionMode.QUICK,
                meterDefinitionId = hours.id,
                meterInterval = 100.0,
                anchorMeter = 100.0,
            ),
        )
        graph.recomputeSchedules.forSchedule(id)
        id.value
    }

    private fun formSchedule(id: ScheduleId, assetId: AssetId, title: String, profileId: ProfileId?, back: LocalDate) =
        MaintenanceSchedule(
            id = id,
            target = ScheduleTarget.AssetTarget(assetId),
            title = title,
            description = "",
            timeInterval = 3,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = TimeBasis.FIXED,
            anchorOn = back.toString(),
            leadDays = 0,
            meterDefinitionId = null,
            meterInterval = null,
            anchorMeter = null,
            meterLead = null,
            servicePolicy = ServicePolicy.CONTINUOUS,
            policyOffsetDays = null,
            completionMode = CompletionMode.FORM,
            profileId = profileId,
            remindersEnabled = true,
            status = ScheduleStatus.ACTIVE,
            postponedDueOn = null,
            createdAt = dayMillis(back),
            updatedAt = dayMillis(back),
            ruleChangedAt = dayMillis(back),
            providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
        )

    private fun dayMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** How long a closed form is watched for coming back; a re-run arrival reopens it within a frame or two. */
        const val SETTLE_MS = 3_000L

        const val MOWER = "Mower"

        /** The asset as the detail's bar and its service record's rows draw it. */
        const val MOWER_LABEL = "MOWER"
        const val BLADE = "Blade sharpen"
        const val DECK = "Deck clean"
        const val READING_TAG = "value-blade_gap"

        // The shipped English words the owner taps and reads; none is new.
        const val RECORD_ENTRY = "Record entry"
        const val CLOSE = "Close"
        const val LOG_MAINTENANCE_LABEL = "Log maintenance"
        const val COMPLETE_SELECTED_LABEL = "Complete selected"
        const val NOT_NOW_LABEL = "Not now"
        const val OVERDUE_WORD = "OVERDUE"
        const val NO_SERVICE = "No service recorded yet"
        const val BACK = "Back"
        const val WHEN_WAS_THIS_DONE = "When was this done?"
        const val LOG_METER_READING = "Log meter reading"
        const val SAVE = "Save"
        const val CANCEL = "Cancel"

        /** The trail's mark for one `onArrivalEnded` call. */
        const val ENDED = "ended"
    }
}
