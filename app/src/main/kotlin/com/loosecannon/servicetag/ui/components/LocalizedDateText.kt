package com.loosecannon.servicetag.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.loosecannon.servicetag.l10n.dateFieldText
import com.loosecannon.servicetag.l10n.dateFieldValue
import com.loosecannon.servicetag.l10n.monthDayFieldText
import com.loosecannon.servicetag.l10n.monthDayFieldValue
import com.loosecannon.servicetag.l10n.shownFieldText

/** What a date field draws, and what it does with a keystroke: hands its form the canonical value. */
class LocalizedFieldText(val text: String, val onTyped: (String) -> Unit)

/**
 * #102 (PR #106 review): a calendar-date field over a form that holds ISO. The owner reads and types the locale's
 * own order ([dateFieldText]); the form receives the ISO day once the text is one, or the text as typed while it is
 * not, so the form's own rule still refuses it. The calendar writes ISO straight into the form, and the field redraws.
 */
@Composable
fun localizedDateText(value: String, onValueChange: (String) -> Unit): LocalizedFieldText =
    localizedFieldText(value, onValueChange, ::dateFieldValue, ::dateFieldText)

/** As [localizedDateText], for a season edge whose form holds `MM-DD` ([monthDayFieldText]). */
@Composable
fun localizedMonthDayText(value: String, onValueChange: (String) -> Unit): LocalizedFieldText =
    localizedFieldText(value, onValueChange, ::monthDayFieldValue, ::monthDayFieldText)

@Composable
private fun localizedFieldText(
    value: String,
    onValueChange: (String) -> Unit,
    read: (String) -> String,
    drawn: (String) -> String,
): LocalizedFieldText {
    var typed by remember { mutableStateOf<String?>(null) }
    return LocalizedFieldText(shownFieldText(typed, value, read, drawn)) { text ->
        typed = text
        onValueChange(read(text))
    }
}
