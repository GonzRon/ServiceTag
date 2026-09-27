package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.warranty.WarrantyStatus
import com.loosecannon.servicetag.core.warranty.warrantyStatusOf
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.health.inService
import java.time.LocalDate

// #79 (Part A), the asset screens' warranty words, RATIFIED verbatim (R79-21). Each literal has this
// one home; the reused words ("Warranty", "Expires on", "Warranty notes", "Remind me N days early",
// "Enter the number of days.") are drawn through their shipped homes.

/** P79-1, the Warranty section's badge while the date has not passed (the expiry day included). */
const val IN_WARRANTY_WORD = "IN WARRANTY"

/** P79-2, the same badge once the date has passed. */
const val OUT_OF_WARRANTY_WORD = "OUT OF WARRANTY"

/** P79-3, the Warranty section with no date recorded. */
const val WARRANTY_NOT_RECORDED = "Warranty not recorded"

/** P79-4, "Expires <date>", `<date>` in the shipped `d MMM uuuu`. */
fun warrantyExpiresLine(date: String): String = "Expires $date"

/** P79-5, "Expired <date>". */
fun warrantyExpiredLine(date: String): String = "Expired $date"

/** P79-7, the reminder line at a lead of one day. */
const val REMINDER_ONE_DAY_BEFORE = "Reminder: 1 day before"

/** P79-6 for any other lead, P79-7 for one day. */
fun warrantyReminderLine(days: Int): String =
    if (days == 1) REMINDER_ONE_DAY_BEFORE else "Reminder: $days days before"

/** P79-8, the lead field's supporting text. */
const val LEAVE_BLANK_FOR_NO_REMINDER = "Leave blank for no reminder."

/** P79-9, the lead field's refusal when there is no warranty date to count back from. */
const val ADD_THE_WARRANTY_DATE_FIRST = "Add the date the warranty expires first."

/**
 * The Warranty section's facts (#79, C10): the derived [status], the stored date, lead and notes,
 * and whether the asset is in service. Never stored; derived from the asset row and `Today`, so the
 * composition decides nothing.
 */
data class WarrantyFacts(
    val status: WarrantyStatus = WarrantyStatus.NOT_RECORDED,
    val expiresOn: String? = null,
    val leadDays: Int? = null,
    val notes: String = "",
    val inService: Boolean = true,
) {
    /** P79-1 or P79-2; null with no date (or one that does not parse), where P79-3 is drawn instead. */
    val badge: String?
        get() = when (status) {
            WarrantyStatus.IN_WARRANTY -> IN_WARRANTY_WORD
            WarrantyStatus.OUT_OF_WARRANTY -> OUT_OF_WARRANTY_WORD
            WarrantyStatus.NOT_RECORDED -> null
        }

    /** P79-4 or P79-5 under the badge, the date in the shipped display shape; null beside P79-3. */
    val dateLine: String?
        get() {
            val shown = expiresOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let(::displayDate)
            return when (status) {
                WarrantyStatus.IN_WARRANTY -> shown?.let(::warrantyExpiresLine)
                WarrantyStatus.OUT_OF_WARRANTY -> shown?.let(::warrantyExpiredLine)
                WarrantyStatus.NOT_RECORDED -> null
            }
        }

    /**
     * P79-6 or P79-7, only where a warning can be delivered (m9): in warranty, with a lead, on an asset
     * in service. A retired or archived asset keeps its lead, and says nothing it cannot keep.
     */
    val reminderLine: String?
        get() = leadDays?.takeIf { status == WarrantyStatus.IN_WARRANTY && inService }?.let(::warrantyReminderLine)
}

/**
 * The section's facts for [asset] on [today], the `Today` port's date — never the clock (K7). In
 * service is the shipped rule every surface reads: active and not retired.
 */
fun warrantyFactsOf(asset: Asset, today: LocalDate): WarrantyFacts = WarrantyFacts(
    status = warrantyStatusOf(asset.warrantyExpiresOn, today),
    expiresOn = asset.warrantyExpiresOn,
    leadDays = asset.warrantyReminderLeadDays,
    notes = asset.warrantyNotes,
    inService = asset.inService,
)
