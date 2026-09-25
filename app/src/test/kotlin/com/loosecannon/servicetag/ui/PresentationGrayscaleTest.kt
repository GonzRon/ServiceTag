package com.loosecannon.servicetag.ui

import androidx.compose.ui.graphics.Color
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        // The due statuses DEGRADED must never be read as (O-2), by their shipped words and glyphs.
        Seen("due soon", statusLabel(DueStatus.DUE_SOON), statusGlyph(DueStatus.DUE_SOON).name),
        Seen("due", statusLabel(DueStatus.DUE), statusGlyph(DueStatus.DUE).name),
        Seen("overdue", statusLabel(DueStatus.OVERDUE), statusGlyph(DueStatus.OVERDUE).name),
    )

    @Test fun noTwoStatesShareAWordAndAnIcon() {
        val byWord = states.groupBy { it.word }.filterValues { it.size > 1 }
        assertEquals("states told apart by word alone", emptyMap<String, List<Seen>>(), byWord)
        val byGlyph = states.groupBy { it.glyph }.filterValues { it.size > 1 }
        assertEquals("states told apart by glyph alone", emptyMap<String, List<Seen>>(), byGlyph)
    }

    /**
     * DEGRADED's token is its own, and **perceptibly** so: its foreground and its container each sit at
     * least [MIN_DELTA_E] (CIEDE2000) from Due's and Due soon's, in both themes — a copy one bit off
     * would be a different value and the same colour.
     */
    @Test fun degradedHasItsOwnTokenDistinctFromDueAndDueSoon() {
        fun check(theme: String, colors: ServiceTagSemanticColors) {
            val degraded = conditionColors(OperationalCondition.DEGRADED, colors)
            assertEquals("$theme: the DEGRADED token is conditionDegraded", colors.conditionDegraded, degraded)
            listOf("due" to colors.due, "dueSoon" to colors.dueSoon).forEach { (name, token) ->
                assertNotEquals("$theme: DEGRADED foreground is not $name's", token.foreground, degraded.foreground)
                assertNotEquals("$theme: DEGRADED container is not $name's", token.container, degraded.container)
                val foreground = deltaE2000(token.foreground, degraded.foreground)
                val container = deltaE2000(token.container, degraded.container)
                assertTrue("$theme: DEGRADED foreground ΔE %.1f from $name's".format(foreground), foreground >= MIN_DELTA_E)
                assertTrue("$theme: DEGRADED container ΔE %.1f from $name's".format(container), container >= MIN_DELTA_E)
            }
        }
        check("light", ServiceTagLightSemanticColors)
        check("dark", ServiceTagDarkSemanticColors)
    }

    // ---------------------------------------------------------------- CIEDE2000 over sRGB (D65)

    private fun lab(c: Color): DoubleArray {
        fun linear(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(c.red)
        val g = linear(c.green)
        val b = linear(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16) / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun deltaE2000(one: Color, two: Color): Double {
        val (l1, a1, b1) = lab(one)
        val (l2, a2, b2) = lab(two)
        val cBar = (hypot(a1, b1) + hypot(a2, b2)) / 2
        val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1)
        val c2p = hypot(a2p, b2)
        fun hue(b: Double, ap: Double): Double {
            if (b == 0.0 && ap == 0.0) return 0.0
            val h = Math.toDegrees(atan2(b, ap))
            return if (h < 0) h + 360 else h
        }
        val h1p = hue(b1, a1p)
        val h2p = hue(b2, a2p)
        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            h2p - h1p > 180 -> h2p - h1p - 360
            h2p - h1p < -180 -> h2p - h1p + 360
            else -> h2p - h1p
        }
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lBarP = (l1 + l2) / 2
        val cBarP = (c1p + c2p) / 2
        val hBarP = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        fun cosDeg(d: Double) = cos(Math.toRadians(d))
        val t = 1 - 0.17 * cosDeg(hBarP - 30) + 0.24 * cosDeg(2 * hBarP) + 0.32 * cosDeg(3 * hBarP + 6) -
            0.20 * cosDeg(4 * hBarP - 63)
        val dTheta = 30 * exp(-((hBarP - 275) / 25).pow(2))
        val rc = 2 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lBarP - 50).pow(2) / sqrt(20 + (lBarP - 50).pow(2))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh))
    }

    private companion object {
        /** A difference plainly visible side by side; the shipped tokens measure 7.4 to 19.6. */
        const val MIN_DELTA_E = 5.0
    }
}
