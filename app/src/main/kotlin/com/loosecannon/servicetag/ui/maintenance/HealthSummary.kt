package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one question the dashboard's health badge asks, and the whole of what this brief knows about
 * reminder health.
 *
 * Declared here and **implemented by B10** (decision 28), so the badge can land and be tested
 * against a fake before the seven findings exist. The dependency points this way on purpose: B10
 * consumes this interface rather than B08 importing B10, which is what lets the shell — and with it
 * B10's own placement — ship first. [ReminderHealthSeverity] is B04's, on the reminder port: one three-member
 * enum in the repository, not two.
 *
 * Null means "nothing found", which is not the same as `INFO`: a phone with no findings and a phone
 * with one informational finding both leave the badge off, but only the second has something to
 * show on the Reminders screen.
 *
 * **The implementation must cache.** Master plan decision 32 fixes where the check runs — app
 * launch, the backstop worker and the Health screen — and **not** per dashboard emission, because
 * the real check reads the standby bucket. Both surfaces here ask this only when the store or an
 * explicit refresh moves, never on a keystroke; an implementation that did platform work inside
 * `worstSeverity()` on every call would put decision 32 out of reach whatever its callers do.
 */
interface HealthSummary {
    suspend fun worstSeverity(): ReminderHealthSeverity?

    /**
     * A **change signal**, because the cache above makes this interface pull-only and both badge
     * surfaces read it inside a store-driven `combine` — so without one, nothing re-reads it when a
     * check lands (B10 fix round 1, S3). Two failures came of that, and the second is the worse:
     * a repair the backstop worker applied left the badge lit until the next launch, and the launch
     * check races the dashboard's first emission, so on a **cold launch** on a phone with a ≥ WARN
     * condition the badge could be absent for the whole session. §11.1 states the contract as a fact
     * about findings — "a health badge appears when any finding is ≥ WARN" — not about a refresh
     * point.
     *
     * It is a **counter and not the severity itself** on purpose: the value every surface acts on
     * stays [worstSeverity]'s, so a summary that answers only that question is still a complete
     * implementation and nothing has two sources for one answer. The default never ticks, which is
     * the honest signal from a summary that never changes.
     */
    val changes: StateFlow<Int> get() = NEVER_CHANGES
}

/** For a summary whose answer cannot move — the default above, and [NoHealthFindings]. */
private val NEVER_CHANGES: StateFlow<Int> = MutableStateFlow(0).asStateFlow()

/**
 * The badge threshold of #5 and D3 §7.3 ("a badge on Home appears when any finding has severity
 * ≥ WARN"), in one place so the dashboard and the shell cannot disagree about it. An `INFO`-only
 * set is deliberately below the line: `REMINDERS_GLOBALLY_OFF` is the owner's own choice and a
 * badge for it would be a nag that never clears.
 */
fun ReminderHealthSeverity?.showsBadge(): Boolean = this != null && ordinal >= ReminderHealthSeverity.WARN.ordinal

/**
 * The word the health badge carries, in **one** place so the dashboard and the Maintenance
 * destination cannot drift apart if it is re-ratified.
 *
 * It is D12 §5's own label for the scheduler-failure state
 * (`12-visual-design-apollo-service-binder.md:274-296`, the `notifications_off` row), and strings
 * D12 carries are pre-ratified with it (spec §9.1) — §17 lists no separate badge label. Recorded as
 * a finding for the owner all the same: the badge fires for any finding at or above WARN, including
 * `NO_DATA` and `APP_RESTRICTED`, which this word overstates.
 */
const val REMINDER_FAILED = "REMINDER FAILED"

/**
 * No findings at all.
 *
 * It was the default every surface held until B10 landed the real check; now that `AppGraph` wires
 * `ReminderHealth`, it is a **test default** — the answer a suite wants when the badge is not what
 * it is asserting. Its [changes] never ticks, which is correct: nothing about it can move.
 */
object NoHealthFindings : HealthSummary {
    override suspend fun worstSeverity(): ReminderHealthSeverity? = null
}
