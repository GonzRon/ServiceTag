package com.loosecannon.servicetag.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.loosecannon.servicetag.R

/**
 * The glyphs D12 §5/§8 asks for that `material-icons-core` does not ship. Screens name the icon,
 * never the drawable id; the tint stays the caller's decision.
 */
object ServiceTagIcons {
    val Contactless: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_contactless)
    val NfcTag: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_nfc_tag)
    val History: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_history)
    val Description: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_description)
    val Schedule: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_schedule)
    val Event: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_event)
    val CalendarMonth: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_calendar_month)
    val ArrowUpward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_upward)
    val ArrowDownward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_downward)
    val PauseCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pause_circle)
    val NotificationsActive: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_notifications_active)
    val NotificationsOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_notifications_off)
    val CloudOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cloud_off)
    val DeleteForever: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_delete_forever)
    val Speed: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_speed)
    val Backup: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_backup)
    val Photo: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_photo)
    val AttachFile: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_attach_file)

    /** The DEFERRED status glyph (1.4 spec §10.6): the break is holding the work until a set day. */
    val HourglassTop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_hourglass_top)

    /** An equals sign: the kind glyph on a reading that is computed rather than entered. */
    val Equal: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_equal)

    // 1.4 condition, health and season glyphs (spec §10.6's proposed icons); see [StateGlyph].
    val TaskAlt: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_task_alt)
    val TrendingDown: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_trending_down)
    val Block: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_block)
    val RadioButtonUnchecked: ImageVector @Composable get() =
        ImageVector.vectorResource(R.drawable.ic_radio_button_unchecked)
    val SignalCellularAlt: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_signal_cellular_alt)
    val SignalCellularAlt2Bar: ImageVector @Composable get() =
        ImageVector.vectorResource(R.drawable.ic_signal_cellular_alt_2_bar)
    val SignalCellularAlt1Bar: ImageVector @Composable get() =
        ImageVector.vectorResource(R.drawable.ic_signal_cellular_alt_1_bar)
    val SignalCellularNodata: ImageVector @Composable get() =
        ImageVector.vectorResource(R.drawable.ic_signal_cellular_nodata)
    val EventAvailable: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_event_available)
}

/**
 * The glyph half of the 1.4 condition, health and season states (spec §10.6), **named** rather than
 * resolved — the `StatusGlyph` precedent — so "no two states share a glyph" is a fact a JVM test can
 * assert. Colour is only reinforcement: with the palette gone, the word, this glyph and the position
 * each still tell every state apart. [icon] resolves a member to its vector.
 */
enum class StateGlyph {
    /** OPERATIONAL. */
    TASK_ALT,

    /** DEGRADED. */
    TRENDING_DOWN,

    /** DOWN. */
    BLOCK,

    /** Condition not recorded. */
    RADIO_BUTTON_UNCHECKED,

    /** NOMINAL: three bars. */
    SIGNAL_3_BARS,

    /** WARNING: two bars. */
    SIGNAL_2_BARS,

    /** CRITICAL: one bar. */
    SIGNAL_1_BAR,

    /** NOT TRACKED. */
    SIGNAL_NO_DATA,

    /** IN SEASON. */
    EVENT_AVAILABLE,
    ;

    val icon: ImageVector
        @Composable get() = when (this) {
            TASK_ALT -> ServiceTagIcons.TaskAlt
            TRENDING_DOWN -> ServiceTagIcons.TrendingDown
            BLOCK -> ServiceTagIcons.Block
            RADIO_BUTTON_UNCHECKED -> ServiceTagIcons.RadioButtonUnchecked
            SIGNAL_3_BARS -> ServiceTagIcons.SignalCellularAlt
            SIGNAL_2_BARS -> ServiceTagIcons.SignalCellularAlt2Bar
            SIGNAL_1_BAR -> ServiceTagIcons.SignalCellularAlt1Bar
            SIGNAL_NO_DATA -> ServiceTagIcons.SignalCellularNodata
            EVENT_AVAILABLE -> ServiceTagIcons.EventAvailable
        }
}
