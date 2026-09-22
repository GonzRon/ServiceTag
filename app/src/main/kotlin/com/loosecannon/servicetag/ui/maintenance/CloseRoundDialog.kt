package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The RATIFIED action label (master plan §17). */
const val CLOSE_THIS_ROUND = "Close this round"

/**
 * The RATIFIED confirmation body (owner, 2026-09-22; master plan §17.1b), verbatim.
 *
 * It is the sentence that keeps the action from reading as "mark everything done": the outstanding
 * members are **not** being recorded as serviced, and that is the whole difference between a closure
 * and the false history D-8 exists to avoid.
 */
const val CLOSE_THIS_ROUND_CONFIRMATION =
    "Close this round? The members not marked done will not be recorded as serviced."

/**
 * The dates a closure may carry: the round's **open date, clamped to today**, through today.
 *
 * The same range `CloseRound` enforces, computed the same way, so the picker cannot offer a value
 * the use case would refuse (invariant 78). The clamp matters in a negative UTC offset, where the
 * round's open date — derived at UTC, for the engine's purity — can be tomorrow's date on the
 * evening it opened; an unclamped floor would then leave the range empty and refuse every value,
 * the default included.
 */
internal fun closureDateRange(openOn: LocalDate, today: LocalDate): ClosedRange<LocalDate> =
    minOf(openOn, today)..today

/** Whether [date] is a date this closure may carry. A pure predicate, so a JVM test asserts it. */
internal fun isClosureDateSelectable(date: LocalDate, openOn: LocalDate, today: LocalDate): Boolean =
    date in closureDateRange(openOn, today)

/**
 * "Close this round": the confirmation, then the bounded date.
 *
 * Two things this dialog must get right, both because the row it writes can **never** be amended.
 * It says what the action does **not** do — no member is recorded as serviced, and confirming
 * writes no member event at all — and the date is **picked, never typed**, from a calendar that
 * offers the round's open date through today and nothing else. The day before the open date and
 * tomorrow are unreachable rather than refused, which is the difference between a bound and a
 * validation message.
 *
 * There is no edit and no delete anywhere above a closure, here or on the detail screen: the
 * closure history is read-only (invariant 43).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseRoundDialog(
    openOn: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // The default is today, which is also the range's upper bound.
    var closedOn by remember { mutableStateOf(today) }
    var picking by remember { mutableStateOf(false) }
    val range = closureDateRange(openOn, today)

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(CLOSE_THIS_ROUND_CONFIRMATION)
                MaintenanceField(
                    value = closedOn.toString(),
                    onValueChange = {},
                    label = "Date",
                    // Read-only on purpose: the calendar is the only way to change it, so a date
                    // outside the range is unreachable and not merely marked.
                    readOnly = true,
                    mono = true,
                    trailingIcon = {
                        IconButton(onClick = { picking = true }) {
                            Icon(ServiceTagIcons.CalendarMonth, contentDescription = "Pick Date")
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = closedOn in range,
                onClick = { onConfirm(closedOn.toString()) },
            ) { Text(CLOSE_THIS_ROUND) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = utcMillis(today),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcDate(utcTimeMillis) in range

                override fun isSelectableYear(year: Int): Boolean =
                    year >= range.start.year && year <= range.endInclusive.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { closedOn = utcDate(it) }
                        picking = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = picker)
        }
    }
}

/** UTC both ways, as the shipped calendar does: the day tapped is the day written. */
private fun utcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
