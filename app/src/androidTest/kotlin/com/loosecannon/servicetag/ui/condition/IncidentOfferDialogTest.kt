package com.loosecannon.servicetag.ui.condition

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #82's three answers on a real Compose tree, the dialog drawn alone (§3 row 19): P82-6 over S53,
 * then P82-7, P82-8 and P82-9 — each reaching its own callback — and all three disabled once an
 * answer is being recorded. The rules are `OffersTest`'s and `EventEntryViewModelTest`'s; what is
 * here is that the ratified words are really drawn and each tap really reaches its answer.
 */
@RunWith(AndroidJUnit4::class)
class IncidentOfferDialogTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val incident = AssetEvent(
        id = EventId("e-seized"), assetId = AssetId("pump"), kind = EventKind.INCIDENT, title = "Pump seized",
        profileId = null, occurredOn = "2026-04-15", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = 0L, updatedAt = 0L,
        measurements = emptyList(), consumables = emptyList(),
    )

    private val answers = listOf("Mark down", "Mark degraded", "No change")

    @Test fun titleBodyAndThreeAnswers() {
        rule.setContent {
            ServiceTagTheme {
                IncidentOfferDialog(ImpairmentOfferPrompt(incident, "Pump"), onMarkDown = {}, onMarkDegraded = {}, onNoChange = {})
            }
        }

        rule.onNodeWithText("Did this affect whether the asset can be used?").assertIsDisplayed()
        rule.onNodeWithText("You logged Pump seized.").assertIsDisplayed()
        answers.forEach { rule.onNodeWithText(it).assertIsDisplayed().assertIsEnabled() }
    }

    @Test fun eachAnswerReachesItsCallbackAndDisablesTheOthers() {
        var offer by mutableStateOf(ImpairmentOfferPrompt(incident, "Pump"))
        val trail = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme {
                IncidentOfferDialog(
                    offer,
                    // As the entry does: the answer and `accepting` are set before the write.
                    onMarkDown = {
                        trail += "down"
                        offer = offer.copy(accepting = true, chosen = OperationalCondition.DOWN)
                    },
                    onMarkDegraded = {
                        trail += "degraded"
                        offer = offer.copy(accepting = true, chosen = OperationalCondition.DEGRADED)
                    },
                    onNoChange = { trail += "no change" },
                )
            }
        }

        for ((label, answer) in listOf("Mark down" to "down", "Mark degraded" to "degraded")) {
            offer = ImpairmentOfferPrompt(incident, "Pump")
            rule.waitForIdle()
            rule.onNodeWithText(label).performClick()
            rule.waitForIdle()
            assertEquals(answer, trail.last())
            answers.forEach { rule.onNodeWithText(it).assertIsNotEnabled() }
        }
        offer = ImpairmentOfferPrompt(incident, "Pump")
        rule.waitForIdle()
        rule.onNodeWithText("No change").performClick()
        rule.waitForIdle()

        assertEquals(listOf("down", "degraded", "no change"), trail)
    }

    /**
     * #82 branch review m1: on a 360 dp phone at font scale 2.0 the answers wrap instead of squeezing —
     * each is drawn on one line and nothing of it is clipped. The dialog is a window of its own that
     * takes its density from the view that opened it, so the phone is a `ComposeView` whose context
     * overrides the configuration, as `AssetDetailKeyDocumentsTest`'s 360 dp case does; the first check
     * proves the text really drew at double size. "Not clipped" is the label's bounds clipped by its
     * ancestors equal to its own: the layout result's overflow flag is not used, because a wrap-content
     * label's semantics result is laid out at the full width and always reports a width overflow.
     */
    @Test fun theAnswersWrapRatherThanSqueezeOnA360dpPhoneAtDoubleTextSize() {
        rule.runOnUiThread {
            val activity = rule.activity
            val metrics = activity.resources.displayMetrics
            val phone = Configuration(activity.resources.configuration).apply {
                fontScale = 2f
                densityDpi = metrics.widthPixels * 160 / 360
                screenWidthDp = 360
                screenHeightDp = metrics.heightPixels * 360 / metrics.widthPixels
            }
            val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(phone) }
            activity.setContentView(
                ComposeView(context).apply {
                    setContent {
                        ServiceTagTheme {
                            IncidentOfferDialog(
                                ImpairmentOfferPrompt(incident, "Pump"),
                                onMarkDown = {},
                                onMarkDegraded = {},
                                onNoChange = {},
                            )
                        }
                    }
                },
            )
        }
        rule.awaitText("Mark down")
        val label = rule.onNodeWithText("No change", useUnmergedTree = true).getUnclippedBoundsInRoot()
        check(label.bottom - label.top > 24.dp) { "the dialog draws double-size text: No change is ${label.bottom - label.top} tall" }

        answers.forEach { answer ->
            rule.onNodeWithText(answer).assertIsDisplayed()
            val text = rule.onNodeWithText(answer, useUnmergedTree = true)
            assertEquals("$answer is drawn on one line", 1, text.textLayout().lineCount)
            assertEquals("$answer is not clipped", text.getUnclippedBoundsInRoot(), text.getBoundsInRoot())
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }
}
