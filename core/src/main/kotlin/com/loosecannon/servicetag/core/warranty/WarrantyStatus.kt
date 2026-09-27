package com.loosecannon.servicetag.core.warranty

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Where an asset stands against its stored warranty date (#79, C1; AC 2). Derived, never stored:
 * no column, no backup field and no API backup row carries it, so every reader derives it from the
 * stored `warrantyExpiresOn` and the `Today` it is handed.
 */
enum class WarrantyStatus { IN_WARRANTY, OUT_OF_WARRANTY, NOT_RECORDED }

/**
 * The status on [today]. No date, or one that does not parse, is [WarrantyStatus.NOT_RECORDED]; the
 * expiry day itself is still [WarrantyStatus.IN_WARRANTY], and the day after it is not. Pure: it
 * reads no clock, so a caller passes the date its own `Today` port gives it.
 */
fun warrantyStatusOf(expiresOn: String?, today: LocalDate): WarrantyStatus {
    val expiry = expiresOn?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    } ?: return WarrantyStatus.NOT_RECORDED
    return if (!today.isAfter(expiry)) WarrantyStatus.IN_WARRANTY else WarrantyStatus.OUT_OF_WARRANTY
}
