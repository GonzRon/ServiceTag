package com.loosecannon.servicetag.ui.condition

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
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

    @get:Rule val rule = createComposeRule()

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
}
