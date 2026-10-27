package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.ports.Clock
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * #113/#114 (C31, K14) — the local-midnight tick: sleeps until the next local midnight, runs its pass, and re-arms,
 * for as long as the process lives. No receiver and no alarm: it carries a time-only change — a calendar window's end,
 * a boundary going from `Fresh` to `Unverified` (C9a) — while the app is open, and the next start or resume carries it
 * otherwise. [sleep] is `delay` in the app; a test scripts it.
 */
class MidnightTick(
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Never returns; cancelled with its scope. */
    suspend fun run(onMidnight: suspend () -> Unit): Nothing {
        while (true) {
            sleep(millisUntilNextLocalMidnight(clock.nowMillis(), zone()))
            onMidnight()
        }
    }
}

/** The wait from [nowMillis] to the start of the next local day in [zone] (a DST gap included); at least 1 ms. */
fun millisUntilNextLocalMidnight(nowMillis: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val next = now.toLocalDate().plusDays(1).atStartOfDay(zone)
    return Duration.between(now, next).toMillis().coerceAtLeast(1)
}
