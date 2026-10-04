package com.loosecannon.servicetag.l10n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.loosecannon.servicetag.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.ServiceLoader

/**
 * #102 — the app's words live in Android string resources: `res/values/` is the English source, and each
 * `values-<locale>/` a language pack. Composables read them with `stringResource`; everything else that renders
 * text for the owner — a view model's state, a notification, a read model's line — reads them through
 * [localized], [localizedPlural] and [localizedDate].
 *
 * Localization is presentation only. Nothing drawn through here is stored, backed up, sent over the Developer API
 * or written to a tag: those carry language-neutral codes (enum names, ids, ISO dates), and the owner's own text —
 * names, notes, serial numbers — is passed in as an argument and shown exactly as entered.
 *
 * Text is read when it is drawn, never cached: a top-level or companion `val` holding a rendered string would keep
 * the language it was first read in, so every catalog entry is a getter or a function.
 */
interface LocalizedText {
    /** The language the words are being rendered in: the first of the app's (or the device's) locales. */
    val locale: Locale

    fun string(@StringRes id: Int): String

    fun format(@StringRes id: Int, args: Array<out Any?>): String

    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

/**
 * Where [localized] reads from. [ServiceTagApp] installs the Android resources first thing in `onCreate`, before
 * any receiver, worker or activity can run. A process with nothing installed — a JVM unit test — takes the first
 * [LocalizedText] on the classpath's `META-INF/services`, which the unit tests provide over `res/values/`.
 */
object AppText {
    @Volatile
    private var installed: LocalizedText? = null

    private val discovered: LocalizedText by lazy {
        ServiceLoader.load(LocalizedText::class.java).firstOrNull()
            ?: error("no LocalizedText installed: ServiceTagApp.onCreate installs the Android resources")
    }

    fun install(text: LocalizedText) {
        installed = text
    }

    val current: LocalizedText get() = installed ?: discovered
}

/** The string [id], formatted with [args] when there are any — `getString`'s rules, `%%` included. */
fun localized(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) AppText.current.string(id) else AppText.current.format(id, args)

/**
 * The plural [id] for [count] under the rendering language's rules. [args] are the format arguments, the count
 * itself included where the text shows it: every quantity form says the number through a placeholder, never as a
 * literal "1", because `one` also covers 21 in Russian and 0 in French.
 */
fun localizedPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
    AppText.current.plural(id, count, args)

/** A [Long] count: plural rules only look at it through [Int], and no count the app shows comes near the limit. */
fun localizedPlural(@PluralsRes id: Int, count: Long, vararg args: Any?): String =
    AppText.current.plural(id, count.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(), args)

/**
 * The display date: English keeps the shipped `d MMM uuuu` ("1 Mar 2026"), and each language pack gives its own
 * pattern in `format_date_display`, so the order, the month names and the separators are the language's own.
 * Storage, file names and the API keep ISO dates and never come through here.
 */
fun localizedDate(date: LocalDate): String {
    val text = AppText.current
    return date.format(DateTimeFormatter.ofPattern(text.string(R.string.format_date_display), text.locale))
}

/** A day and month without the year, [localizedDate]'s companion ("1 Mar" in English). */
fun localizedMonthDay(date: LocalDate): String {
    val text = AppText.current
    return date.format(DateTimeFormatter.ofPattern(text.string(R.string.format_date_month_day), text.locale))
}

/**
 * Owner-entered or app-made names joined into one run ("A, B, C" in English): the separator belongs to the language
 * (Chinese and Japanese use "、"). The names themselves are never changed.
 */
fun localizedList(items: Iterable<String>): String =
    items.joinToString(AppText.current.string(R.string.format_list_separator))
