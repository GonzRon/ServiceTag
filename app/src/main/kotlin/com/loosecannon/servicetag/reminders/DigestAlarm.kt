package com.loosecannon.servicetag.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.prefs.AppPrefs
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The one daily alarm, as a seam.
 *
 * [armed] is a question the health screen's `DIGEST_ALARM_MISSING` finding asks and whose repair
 * calls [arm]; [arm] is idempotent, so the repair, the backstop and every platform event can all
 * call it without anyone counting. There is deliberately no "arm for this instant" — the instant is
 * derived from the owner's hour and `T`, and a caller that could choose one could arm the past.
 */
interface DigestAlarm {
    /** Whether an alarm is pending right now. */
    fun armed(): Boolean

    /** Arms (or replaces) the alarm for the next digest instant. */
    fun arm()

    /** Cancels it. Nothing in the shipped app calls this; the connected proof and a test do. */
    fun cancel()
}

/**
 * The next digest instant, in epoch milliseconds: `ZonedDateTime.of(T_next, hour:00, zone)`
 * (spec 5.2, D-6).
 *
 * Pure, and the reason the two DST cases are assertable without a device. `ZonedDateTime.of` is
 * the whole of the D-6 resolution rule: a local time that does not exist on a spring-forward day is
 * moved **forward** by the gap, and an ambiguous one on a fall-back day takes the **earlier**
 * offset. Neither is decided here — reimplementing either with a `LocalDateTime` and a fixed offset
 * is what throws on one day a year and silently shifts an hour on another.
 *
 * "Next" excludes now: at the hour exactly the answer is tomorrow's, because an alarm armed for the
 * instant that just fired is an alarm that fires twice.
 */
internal fun nextDigestAt(nowMillis: Long, today: LocalDate, hour: Int, zone: ZoneId): Long {
    val time = LocalTime.of(hour, 0)
    val todaysInstant = ZonedDateTime.of(today, time, zone).toInstant().toEpochMilli()
    return if (todaysInstant > nowMillis) {
        todaysInstant
    } else {
        ZonedDateTime.of(today.plusDays(1), time, zone).toInstant().toEpochMilli()
    }
}

/**
 * The Android-backed [DigestAlarm]: one inexact `setAndAllowWhileIdle(RTC_WAKEUP)` and nothing
 * else.
 *
 * **No exact-alarm API appears anywhere in this app** (invariant 52, ledger A12): "due today" is a
 * date, `SCHEDULE_EXACT_ALARM` is denied by default on API 34+ and `USE_EXACT_ALARM` is restricted
 * to alarm and calendar apps, so an exact alarm is both unnecessary and unavailable. The cost is
 * that the fire may be minutes or hours late and, on a zone or DST change day, may happen twice or
 * not at all; the 12 h backstop is what makes that a delay rather than a silence.
 *
 * The `PendingIntent` is `FLAG_IMMUTABLE` on every path (invariant 54) and carries no extras: the
 * digest run reads its own state, so there is nothing for a sender to fill in and nothing the
 * platform needs to mutate. [armed] uses `FLAG_NO_CREATE`, which is the only way to ask the
 * platform whether an alarm is pending without creating one in the asking.
 *
 * The device zone is read through [zoneOf] rather than captured, because a zone change must move the
 * next instant and a field initialised once in `AppGraph` would not.
 */
class AndroidDigestAlarm(
    private val context: Context,
    private val prefs: AppPrefs,
    private val today: Today,
    private val clock: Clock,
    private val zoneOf: () -> ZoneId = { ZoneId.systemDefault() },
) : DigestAlarm {

    override fun armed(): Boolean = pendingIntent(PendingIntent.FLAG_NO_CREATE) != null

    override fun arm() {
        val manager = alarmManager() ?: return
        val pending = pendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = nextDigestAt(
            nowMillis = clock.nowMillis(),
            today = today.localDate(),
            hour = prefs.digestHour,
            zone = zoneOf(),
        )
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    override fun cancel() {
        val pending = pendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager()?.cancel(pending)
        // Cancelling the alarm leaves the PendingIntent itself in the system's registry, so
        // `armed()` would keep answering true for an alarm that will never fire.
        pending.cancel()
    }

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST_CODE,
        Intent(context.applicationContext, DigestReceiver::class.java).setAction(ACTION_DIGEST),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    internal companion object {
        /**
         * One request code for the one alarm. A second value here would be a second alarm the
         * health finding could not see.
         */
        const val REQUEST_CODE = 2101

        /**
         * An action on an otherwise explicit intent: it is what makes the intent readable in a
         * `dumpsys alarm` line during the connected proof, and it takes part in the
         * `filterEquals` match `FLAG_NO_CREATE` uses to recognise the pending intent again.
         */
        const val ACTION_DIGEST = "com.loosecannon.servicetag.reminders.DIGEST"
    }
}
