package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.schedule.SeasonPhase

/**
 * #16 (C6) — the season a binding wants, pure. [SyncMode.FORCE_IN] and [SyncMode.FORCE_OUT] answer their phase
 * whatever HA said (C-5: re-asserted on every run). [SyncMode.FOLLOW] answers only from [fresh], the outcome **of
 * this request**: `on` is in season, `off` is out, and [HaReadOutcome.NoDecision] or no result at all is null — no
 * decision. A stored observation is never an input, so returning to FOLLOW waits for a fresh read (AC6, I6).
 */
fun desiredPhase(mode: SyncMode, fresh: HaReadOutcome?): SeasonPhase? = when (mode) {
    SyncMode.FORCE_IN -> SeasonPhase.IN_SEASON
    SyncMode.FORCE_OUT -> SeasonPhase.OUT_OF_SEASON
    SyncMode.FOLLOW -> when (fresh) {
        is HaReadOutcome.Observed -> when (fresh.state) {
            HaSwitchState.ON -> SeasonPhase.IN_SEASON
            HaSwitchState.OFF -> SeasonPhase.OUT_OF_SEASON
        }
        is HaReadOutcome.NoDecision -> null
        null -> null
    }
}
