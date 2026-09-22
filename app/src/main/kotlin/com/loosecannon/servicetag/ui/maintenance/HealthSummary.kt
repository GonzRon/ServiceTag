package com.loosecannon.servicetag.ui.maintenance

/**
 * How bad the worst reminder-health finding is, ordered so that "≥ WARN" is an ordinal comparison.
 *
 * B04 declares the same three-member enum on the reminder port (`core/.../core/reminders/
 * ReminderPort.kt`) and B10's findings carry that one. This brief does not reach into the `core`
 * module and does not wait for B04 or B10, so the badge's threshold is expressed over this copy and B10 — which
 * consumes both — maps its findings' severity onto it at its own seam. Same three words, same
 * order; if the two are ever collapsed, this is the one to delete.
 */
enum class Severity { INFO, WARN, ERROR }

/**
 * The one question the dashboard's health badge asks, and the whole of what this brief knows about
 * reminder health.
 *
 * Declared here and **implemented by B10** (decision 28), so the badge can land and be tested
 * against a fake before the seven findings exist. The dependency points this way on purpose: B10
 * consumes this interface rather than B08 importing B10, which is what lets the shell — and with it
 * B10's own placement — ship first.
 *
 * Null means "nothing found", which is not the same as `INFO`: a phone with no findings and a phone
 * with one informational finding both leave the badge off, but only the second has something to
 * show on the Reminders screen.
 */
interface HealthSummary {
    suspend fun worstSeverity(): Severity?
}

/**
 * The badge threshold of #5 and D3 §7.3 ("a badge on Home appears when any finding has severity
 * ≥ WARN"), in one place so the dashboard and the shell cannot disagree about it. An `INFO`-only
 * set is deliberately below the line: `REMINDERS_GLOBALLY_OFF` is the owner's own choice and a
 * badge for it would be a nag that never clears.
 */
fun Severity?.showsBadge(): Boolean = this != null && ordinal >= Severity.WARN.ordinal

/** No findings at all — the default every surface holds until B10 supplies a real check. */
object NoHealthFindings : HealthSummary {
    override suspend fun worstSeverity(): Severity? = null
}
