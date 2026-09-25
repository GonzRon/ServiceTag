package com.loosecannon.servicetag.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable data class StatusColor(val foreground: Color, val container: Color)

/** Operational meaning lives here, never in Material roles and never in raw colours at call sites (D12 §5, §15). */
@Immutable
data class ServiceTagSemanticColors(
    val maintenanceOkay: StatusColor, val dueSoon: StatusColor, val due: StatusColor, val overdue: StatusColor,
    val seasonInactive: StatusColor, val paused: StatusColor,
    val measurementLow: StatusColor, val measurementInRange: StatusColor, val measurementHigh: StatusColor,
    val measurementNoTarget: StatusColor,
    val reminderHealthy: StatusColor, val reminderFailure: StatusColor,
    val syncProblem: StatusColor, val destructiveAction: StatusColor,
    /**
     * 1.4 condition and health (spec §10.6, O-2): operational and nominal in the cool-blue family,
     * degraded and warning in the amber family — DEGRADED on a token of its own that is neither
     * Due's nor Due soon's — down and critical in the error family, not recorded and not tracked
     * neutral. Colour only reinforces: the word, the glyph and the position carry each state alone.
     */
    val conditionOperational: StatusColor, val conditionDegraded: StatusColor,
    val conditionDown: StatusColor, val conditionNotRecorded: StatusColor,
    val healthNominal: StatusColor, val healthWarning: StatusColor,
    val healthCritical: StatusColor, val healthNotTracked: StatusColor,
)

val ServiceTagLightSemanticColors = ServiceTagSemanticColors(
    maintenanceOkay = StatusColor(Color(0xFF245B78), Color(0xFFDCEBF3)),
    dueSoon = StatusColor(Color(0xFF7A4B0A), Color(0xFFF6E5C3)),
    // G1 correction b; container nudged 0xF3C89A -> 0xF3CA9D (4% toward WarmIvory) to clear 4.5:1 (4.49 -> 4.56)
    due = StatusColor(Color(0xFF8C4700), Color(0xFFF3CA9D)),
    overdue = StatusColor(Color(0xFF8C2E2A), Color(0xFFF8DAD6)),
    seasonInactive = StatusColor(Color(0xFF586269), Color(0xFFE6E8E8)),
    paused = StatusColor(Color(0xFF5B4D6F), Color(0xFFE8E3EF)),
    measurementLow = StatusColor(Color(0xFF4F5F9A), Color(0xFFE2E5F6)),
    measurementInRange = StatusColor(Color(0xFF245B78), Color(0xFFDCEBF3)),
    measurementHigh = StatusColor(Color(0xFF8C4700), Color(0xFFF7D3AD)),
    measurementNoTarget = StatusColor(Color(0xFF444B50), Color(0xFFE6E5DF)),  // G1 correction f
    reminderHealthy = StatusColor(Color(0xFF245B78), Color(0xFFDCEBF3)),
    reminderFailure = StatusColor(Color(0xFF8C2E2A), Color(0xFFF8DAD6)),
    syncProblem = StatusColor(Color(0xFF684682), Color(0xFFE9DFF2)),
    destructiveAction = StatusColor(Color(0xFFA43D36), Color(0xFFF8DAD6)),
    conditionOperational = StatusColor(Color(0xFF245B78), Color(0xFFDCEBF3)),
    // Its own ochre, so a DEGRADED badge never reads as Due (orange) or Due soon (tan).
    conditionDegraded = StatusColor(Color(0xFF6B5200), Color(0xFFEFE3A6)),
    conditionDown = StatusColor(Color(0xFF8C2E2A), Color(0xFFF8DAD6)),
    conditionNotRecorded = StatusColor(Color(0xFF444B50), Color(0xFFE6E5DF)),
    healthNominal = StatusColor(Color(0xFF245B78), Color(0xFFDCEBF3)),
    healthWarning = StatusColor(Color(0xFF7A4B0A), Color(0xFFF6E5C3)),
    healthCritical = StatusColor(Color(0xFF8C2E2A), Color(0xFFF8DAD6)),
    healthNotTracked = StatusColor(Color(0xFF444B50), Color(0xFFE6E5DF)),
)

val ServiceTagDarkSemanticColors = ServiceTagSemanticColors(
    maintenanceOkay = StatusColor(Color(0xFF91BED6), Color(0xFF17384B)),
    dueSoon = StatusColor(Color(0xFFE4B45F), Color(0xFF4A320D)),
    due = StatusColor(Color(0xFFF0A15D), Color(0xFF573015)),
    overdue = StatusColor(Color(0xFFF2B8B5), Color(0xFF4E1C1A)),
    seasonInactive = StatusColor(Color(0xFFB1B8BC), Color(0xFF2A3136)),
    paused = StatusColor(Color(0xFFC4B4D3), Color(0xFF342C3B)),
    measurementLow = StatusColor(Color(0xFFB5C1F0), Color(0xFF2A3152)),
    measurementInRange = StatusColor(Color(0xFF91BED6), Color(0xFF17384B)),
    measurementHigh = StatusColor(Color(0xFFF0A15D), Color(0xFF573015)),
    measurementNoTarget = StatusColor(Color(0xFFD9E0E4), Color(0xFF202A32)),
    reminderHealthy = StatusColor(Color(0xFF91BED6), Color(0xFF17384B)),
    reminderFailure = StatusColor(Color(0xFFF2B8B5), Color(0xFF4E1C1A)),
    syncProblem = StatusColor(Color(0xFFCFB3E5), Color(0xFF3B2C46)),
    destructiveAction = StatusColor(Color(0xFFF2B8B5), Color(0xFF4E1C1A)),
    conditionOperational = StatusColor(Color(0xFF91BED6), Color(0xFF17384B)),
    conditionDegraded = StatusColor(Color(0xFFE3CB6A), Color(0xFF3A3208)),
    conditionDown = StatusColor(Color(0xFFF2B8B5), Color(0xFF4E1C1A)),
    conditionNotRecorded = StatusColor(Color(0xFFD9E0E4), Color(0xFF202A32)),
    healthNominal = StatusColor(Color(0xFF91BED6), Color(0xFF17384B)),
    healthWarning = StatusColor(Color(0xFFE4B45F), Color(0xFF4A320D)),
    healthCritical = StatusColor(Color(0xFFF2B8B5), Color(0xFF4E1C1A)),
    healthNotTracked = StatusColor(Color(0xFFD9E0E4), Color(0xFF202A32)),
)

val LocalServiceTagSemanticColors = staticCompositionLocalOf { ServiceTagLightSemanticColors }
