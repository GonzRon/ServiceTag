package com.loosecannon.servicetag.ui

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.components.StateGlyph
import com.loosecannon.servicetag.ui.condition.conditionColors
import com.loosecannon.servicetag.ui.condition.conditionGlyph
import com.loosecannon.servicetag.ui.condition.conditionWord
import com.loosecannon.servicetag.ui.health.bandWord
import com.loosecannon.servicetag.ui.health.healthGlyph
import com.loosecannon.servicetag.ui.maintenance.statusGlyph
import com.loosecannon.servicetag.ui.maintenance.statusLabel
import com.loosecannon.servicetag.ui.theme.ServiceTagDarkSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagLightSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagSemanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * D12's separation with the palette removed (spec §10.6, O-2; master plan §13.4): colour only
 * reinforces, so the **word** alone and the **glyph** alone must each tell every 1.4 state apart —
 * and DEGRADED keeps a token of its own, so even the colour it does carry never says "due".
 */
class PresentationGrayscaleTest {

    /** One state as grayscale leaves it: its word and the name of its glyph. */
    private data class Seen(val state: String, val word: String, val glyph: String)

    private val states = listOf(
        Seen("operational", conditionWord(OperationalCondition.OPERATIONAL), conditionGlyph(OperationalCondition.OPERATIONAL).name),
        Seen("degraded", conditionWord(OperationalCondition.DEGRADED), conditionGlyph(OperationalCondition.DEGRADED).name),
        Seen("down", conditionWord(OperationalCondition.DOWN), conditionGlyph(OperationalCondition.DOWN).name),
        Seen("not recorded", conditionWord(null), conditionGlyph(null).name),
        Seen("nominal", bandWord(HealthBand.NOMINAL), healthGlyph(HealthBand.NOMINAL).name),
        Seen("warning", bandWord(HealthBand.WARNING), healthGlyph(HealthBand.WARNING).name),
        Seen("critical", bandWord(HealthBand.CRITICAL), healthGlyph(HealthBand.CRITICAL).name),
        Seen("not tracked", bandWord(null), healthGlyph(null).name),
        // DEFERRED is B02's status word and the shipped hourglass glyph.
        Seen("deferred", statusLabel(DueStatus.DEFERRED), statusGlyph(DueStatus.DEFERRED).name),
        // IN SEASON's word is S39, drawn by B14; its glyph is one of this brief's nine.
        Seen("in season", "IN SEASON", StateGlyph.EVENT_AVAILABLE.name),
    )

    @Test fun noTwoStatesShareAWordAndAnIcon() {
        val byWord = states.groupBy { it.word }.filterValues { it.size > 1 }
        assertEquals("states told apart by word alone", emptyMap<String, List<Seen>>(), byWord)
        val byGlyph = states.groupBy { it.glyph }.filterValues { it.size > 1 }
        assertEquals("states told apart by glyph alone", emptyMap<String, List<Seen>>(), byGlyph)
    }

    @Test fun degradedHasItsOwnTokenDistinctFromDueAndDueSoon() {
        fun check(theme: String, colors: ServiceTagSemanticColors) {
            val degraded = conditionColors(OperationalCondition.DEGRADED, colors)
            assertEquals("$theme: the DEGRADED token is conditionDegraded", colors.conditionDegraded, degraded)
            listOf("due" to colors.due, "dueSoon" to colors.dueSoon).forEach { (name, token) ->
                assertNotEquals("$theme: DEGRADED foreground is not $name's", token.foreground, degraded.foreground)
                assertNotEquals("$theme: DEGRADED container is not $name's", token.container, degraded.container)
            }
        }
        check("light", ServiceTagLightSemanticColors)
        check("dark", ServiceTagDarkSemanticColors)
    }
}
