package com.loosecannon.servicetag.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x relative-luminance contrast, so the D12 numbers are checked rather than trusted. */
class ContrastTest {
    private fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a); val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
    private fun assertAtLeast(min: Double, fg: Color, bg: Color, what: String) {
        val r = contrast(fg, bg)
        assertTrue("$what contrast %.2f:1 < $min:1".format(r), r >= min)
    }
    private fun checkScheme(name: String, s: ColorScheme) {
        assertAtLeast(4.5, s.onPrimary, s.primary, "$name primary")
        assertAtLeast(4.5, s.onSecondaryContainer, s.secondaryContainer, "$name secondaryContainer")
        assertAtLeast(4.5, s.onTertiaryContainer, s.tertiaryContainer, "$name tertiaryContainer")
        assertAtLeast(4.5, s.onErrorContainer, s.errorContainer, "$name errorContainer")
        assertAtLeast(4.5, s.onBackground, s.background, "$name background")
        assertAtLeast(4.5, s.onSurface, s.surface, "$name surface")
        assertAtLeast(4.5, s.onSurfaceVariant, s.surface, "$name onSurfaceVariant on surface")
        assertAtLeast(4.5, s.onSurface, s.surfaceContainerLow, "$name surfaceContainerLow")
        assertAtLeast(3.0, s.outline, s.surface, "$name outline (graphic)")
    }
    private fun checkSemantic(name: String, c: ServiceTagSemanticColors) {
        listOf(
            "ok" to c.maintenanceOkay, "dueSoon" to c.dueSoon, "due" to c.due, "overdue" to c.overdue,
            "seasonInactive" to c.seasonInactive, "paused" to c.paused, "low" to c.measurementLow,
            "inRange" to c.measurementInRange, "high" to c.measurementHigh, "noTarget" to c.measurementNoTarget,
            "reminderHealthy" to c.reminderHealthy, "reminderFailure" to c.reminderFailure,
            "sync" to c.syncProblem, "destructive" to c.destructiveAction,
        ).forEach { (label, sc) -> assertAtLeast(4.5, sc.foreground, sc.container, "$name semantic $label") }
    }

    /** The eight 1.4 condition and health tokens (spec §10.6, O-2), under the same threshold. */
    private fun checkConditionAndHealth(name: String, c: ServiceTagSemanticColors) {
        listOf(
            "conditionOperational" to c.conditionOperational, "conditionDegraded" to c.conditionDegraded,
            "conditionDown" to c.conditionDown, "conditionNotRecorded" to c.conditionNotRecorded,
            "healthNominal" to c.healthNominal, "healthWarning" to c.healthWarning,
            "healthCritical" to c.healthCritical, "healthNotTracked" to c.healthNotTracked,
        ).forEach { (label, sc) -> assertAtLeast(4.5, sc.foreground, sc.container, "$name semantic $label") }
    }

    @Test fun lightSchemePairsMeetAA() { checkScheme("light", LightColorScheme) }
    @Test fun darkSchemePairsMeetAA() { checkScheme("dark", DarkColorScheme) }
    @Test fun lightSemanticPairsMeetAA() { checkSemantic("light", ServiceTagLightSemanticColors) }
    @Test fun darkSemanticPairsMeetAA() { checkSemantic("dark", ServiceTagDarkSemanticColors) }
    @Test fun lightConditionAndHealthPairsMeetAA() { checkConditionAndHealth("light", ServiceTagLightSemanticColors) }
    @Test fun darkConditionAndHealthPairsMeetAA() { checkConditionAndHealth("dark", ServiceTagDarkSemanticColors) }
    @Test fun darkMetadataRecedesFromBodyText() {
        // G1 correction h: onSurfaceVariant must be visibly darker than onSurface in dark mode
        assertTrue(luminance(DarkColorScheme.onSurfaceVariant) < luminance(DarkColorScheme.onSurface) * 0.75)
    }
    @Test fun okIsNotGreen() {
        val ok = ServiceTagLightSemanticColors.maintenanceOkay.foreground
        assertTrue("OK foreground must be a cool blue, not green", ok.blue > ok.green && ok.green > ok.red)
    }
}
