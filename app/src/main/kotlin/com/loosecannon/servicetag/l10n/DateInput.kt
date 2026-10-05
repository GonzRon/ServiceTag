package com.loosecannon.servicetag.l10n

import com.loosecannon.servicetag.R
import java.time.DateTimeException
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * #102 (PR #106 review) — the date fields' presentation boundary. A calendar date is stored, validated and sent to
 * `:core` as ISO (`2026-03-01`) and a season edge as `MM-DD` (`03-01`); neither shape is what an owner reads or types.
 * A field draws its value in the rendering locale's own numeric order — "03/01/2026" in US English, "01.03.2026" in
 * German, "2026/03/01" in Japanese, the order Android's own date input uses — and reads what is typed back into the
 * canonical value. The view models and the use cases never see anything else.
 *
 * The order and separator are the locale's short date pattern's, region included, so British English reads
 * day-first; the year always has four digits. ISO typed into a full-date field is read too, since a four-digit
 * first group is unambiguous. Text that is not a date is handed on as typed, so the form refuses it by its own rule.
 */
private enum class DatePart { DAY, MONTH, YEAR }

private class DateShape(val order: List<DatePart>, val separator: String)

private val ISO_DAY = Regex("""\d{4}-\d{2}-\d{2}""")
private val MONTH_DAY = Regex("""(\d{2})-(\d{2})""")
private val DIGITS = Regex("""\d+""")

/** Only separators between the numbers: what the locale draws, and what a keyboard offers in its place. */
private const val SEPARATORS = "./-年月日 "

/** A year in which every `MM-DD` exists, so 29 February is a real season edge. */
private const val ANY_LEAP_YEAR = 2000

private fun shape(): DateShape {
    val pattern = DateTimeFormatterBuilder
        .getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, AppText.current.locale)
        .replace(Regex("'[^']*'"), "")
    val order = pattern.mapNotNull {
        when (it) {
            'd' -> DatePart.DAY
            'M', 'L' -> DatePart.MONTH
            'y', 'u' -> DatePart.YEAR
            else -> null
        }
    }.distinct()
    if (order.size != 3) return DateShape(listOf(DatePart.YEAR, DatePart.MONTH, DatePart.DAY), "-")
    val separator = Regex("""[dMLyu]+([^dMLyu]+)[dMLyu]""").find(pattern)?.groupValues?.get(1)?.trim().orEmpty()
    return DateShape(order, separator.ifEmpty { "/" })
}

private fun DateShape.draw(date: LocalDate, parts: List<DatePart> = order): String = parts.joinToString(separator) {
    when (it) {
        DatePart.DAY -> date.dayOfMonth.toString().padStart(2, '0')
        DatePart.MONTH -> date.monthValue.toString().padStart(2, '0')
        DatePart.YEAR -> date.year.toString().padStart(4, '0')
    }
}

/** [typed]'s numbers in [parts]' order, or null when it holds anything but those numbers and separators. */
private fun numbers(typed: String, parts: List<DatePart>): Map<DatePart, String>? {
    val text = typed.trim()
    if (text.isEmpty() || DIGITS.replace(text, "").any { it !in SEPARATORS }) return null
    val groups = DIGITS.findAll(text).map { it.value }.toList()
    if (groups.size != parts.size) return null
    val byPart = parts.zip(groups).toMap()
    val wellFormed = byPart.all { (part, digits) -> if (part == DatePart.YEAR) digits.length == 4 else digits.length <= 2 }
    return byPart.takeIf { wellFormed }
}

private fun dateOf(year: Int, month: Int, day: Int): LocalDate? =
    try { LocalDate.of(year, month, day) } catch (e: DateTimeException) { null }

/** The day an owner typed, in the locale's order or as ISO; null when it is not a real calendar date. */
fun parseFieldDate(typed: String): LocalDate? {
    val text = typed.trim()
    if (ISO_DAY.matches(text)) return runCatching { LocalDate.parse(text) }.getOrNull()
    val parts = numbers(text, shape().order) ?: return null
    return dateOf(parts.getValue(DatePart.YEAR).toInt(), parts.getValue(DatePart.MONTH).toInt(), parts.getValue(DatePart.DAY).toInt())
}

/** A stored ISO day as its field shows it ("03/01/2026" in US English); anything else is shown as it is. */
fun dateFieldText(isoDay: String): String {
    val day = isoDay.trim().takeIf { ISO_DAY.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return day?.let { shape().draw(it) } ?: isoDay
}

/** What a date field hands its form for [typed]: the ISO day when it is one, otherwise the text as typed. */
fun dateFieldValue(typed: String): String = parseFieldDate(typed)?.toString() ?: typed

/** The field's placeholder and the refusal's example: the locale's order in the language's letters ("MM/DD/YYYY"). */
fun datePlaceholder(): String = placeholder(shape().order)

/** A stored `MM-DD` as its field shows it, in the locale's order without the year ("03/01", "01.03"). */
fun monthDayFieldText(monthDay: String): String {
    val match = MONTH_DAY.matchEntire(monthDay.trim()) ?: return monthDay
    val day = dateOf(ANY_LEAP_YEAR, match.groupValues[1].toInt(), match.groupValues[2].toInt()) ?: return monthDay
    return shape().let { it.draw(day, it.order - DatePart.YEAR) }
}

/** What a month-day field hands its form for [typed]: `MM-DD` when it is a real month and day, else the text as typed. */
fun monthDayFieldValue(typed: String): String {
    val parts = numbers(typed, shape().order - DatePart.YEAR) ?: return typed
    val day = dateOf(ANY_LEAP_YEAR, parts.getValue(DatePart.MONTH).toInt(), parts.getValue(DatePart.DAY).toInt()) ?: return typed
    return canonicalMonthDay(day)
}

/** A month-day field's placeholder ("MM/DD"). */
fun monthDayPlaceholder(): String = placeholder(shape().order - DatePart.YEAR)

/** The stored `MM-DD` of [date]'s month and day, which the season commands read. */
fun canonicalMonthDay(date: LocalDate): String =
    String.format(Locale.ROOT, "%02d-%02d", date.monthValue, date.dayOfMonth)

/**
 * The text a field shows while the owner edits it: what they [typed] while it still reads as the form's [value], so
 * a half-typed "01.0" or an unpadded "1.3.2026" is never redrawn under the cursor, and otherwise [value] [drawn]
 * fresh — a calendar pick, a pre-fill, a reset.
 */
fun shownFieldText(typed: String?, value: String, read: (String) -> String, drawn: (String) -> String): String =
    typed?.takeIf { read(it) == value } ?: drawn(value)

private fun placeholder(parts: List<DatePart>): String {
    val letters = AppText.current.string(R.string.format_date_input_letters)
    val shape = shape()
    return parts.joinToString(shape.separator) {
        when (it) {
            DatePart.DAY -> letters[0].toString().repeat(2)
            DatePart.MONTH -> letters[1].toString().repeat(2)
            DatePart.YEAR -> letters[2].toString().repeat(4)
        }
    }
}
