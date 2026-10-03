package com.loosecannon.servicetag.core.seasonsync

/**
 * #16 (C5) — what one read of one Home Assistant entity came to. Only [Observed] is a decision, and only for
 * exactly `on` or `off`; every other answer, and every failure before an answer, is [NoDecision] with its kind,
 * which the phone draws as a status (I3).
 */
sealed interface HaReadOutcome {
    /**
     * HA reported [state]. [haLastChanged] is HA's `last_changed` text, bounded (C5 rule 4) and kept as text:
     * information only, never parsed into a date for any decision (R16-3).
     */
    data class Observed(val state: HaSwitchState, val haLastChanged: String?) : HaReadOutcome

    /** No new decision. [detail] is an HTTP status or HA's reported state, bounded; never a URL or a token. */
    data class NoDecision(val kind: SyncErrorKind, val detail: String?) : HaReadOutcome
}
