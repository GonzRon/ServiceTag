package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncState
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * #16 (C3, C24; R16-9) — `GET /v1/assets/{id}/season-sync`: the path's asset, this installation's one Home Assistant
 * connection as its non-secret settings, and the asset's binding or `null`. Read only. Nothing here holds the
 * connection's address, the home Wi-Fi's name or the token: the types have no field for any of them, so no mapping
 * can put one on the wire. The effective season is `GET …/season`'s phase and is not repeated.
 */
@Serializable
internal data class SeasonSyncResponse(
    val assetId: String,
    val connection: SeasonSyncConnectionDto,
    val binding: SeasonSyncBindingDto?,
)

/**
 * The connection as C3 shows it. [configured] is whether one exists; [needsToken] whether it exists and its token is
 * not on this phone (`SecretStore.has`, never the token). The five keys after them are present exactly when
 * [configured] — absent, not `null`, when it is not: the cadence, where the token may be sent from, whether the
 * home-network mode also checks from background work, whether Android grants what that needs, and whether a home
 * network is set — never which one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SeasonSyncConnectionDto(
    val configured: Boolean,
    val needsToken: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cadence: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val networkEligibility: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val backgroundChecks: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val backgroundAllowed: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val homeNetworkSet: Boolean? = null,
) {
    companion object {
        /** No connection on this phone: nothing to configure and no token to need. */
        val NOT_CONFIGURED = SeasonSyncConnectionDto(configured = false, needsToken = false)
    }
}

/**
 * One binding. [state] is derived when it is read (C17) and stored nowhere. The three times are three fields:
 * [lastSuccessAt], the fetch time of the last valid answer, which a failure never moves; [lastAttemptAt], any outcome;
 * and the error's and the applied change's own `at`. Times are epoch milliseconds.
 */
@Serializable
internal data class SeasonSyncBindingDto(
    val entityId: String,
    val mode: String,
    val enabled: Boolean,
    val state: String,
    val observation: SeasonSyncObservationDto?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastError: SeasonSyncErrorDto?,
    val lastApplied: SeasonSyncAppliedDto?,
)

/** The last valid answer: `ON` or `OFF`, and Home Assistant's change text verbatim, information only. */
@Serializable
internal data class SeasonSyncObservationDto(val state: String, val haLastChanged: String?)

/** The latest "no new decision": its `SyncErrorKind` name, an HTTP status or Home Assistant's own state, and when. */
@Serializable
internal data class SeasonSyncErrorDto(val kind: String, val detail: String?, val at: Long)

/**
 * The last change the binding made (R16-11): `START` or `END`, the day it is dated, when it was applied, and its
 * [source] (C33): `HOME_ASSISTANT`, or `FORCED_IN` / `FORCED_OUT` when the owner's forced season applied it.
 */
@Serializable
internal data class SeasonSyncAppliedDto(val action: String, val occurredOn: String, val at: Long, val source: String)

/** The settings of a configured connection; [hasToken] is `SecretStore.has`, [backgroundAllowed] Android's grant. */
internal fun HaConnection.toSeasonSyncDto(hasToken: Boolean, backgroundAllowed: Boolean) = SeasonSyncConnectionDto(
    configured = true,
    needsToken = !hasToken,
    cadence = cadence.name,
    networkEligibility = networkEligibility.name,
    backgroundChecks = backgroundChecks.name,
    backgroundAllowed = backgroundAllowed,
    homeNetworkSet = homeNetworkSsid?.isNotBlank() == true,
)

/** The binding at [state]; each status object is written whole by its one writer, so it is shown whole or not. */
internal fun SeasonSyncBinding.toDto(state: SeasonSyncState) = SeasonSyncBindingDto(
    entityId = entityId,
    mode = mode.name,
    enabled = enabled,
    state = state.name,
    observation = observedState?.let { SeasonSyncObservationDto(it.name, observedChangedAt) },
    lastSuccessAt = lastSuccessAt,
    lastAttemptAt = lastAttemptAt,
    lastError = errorKind?.let { kind -> errorAt?.let { at -> SeasonSyncErrorDto(kind.name, errorDetail, at) } },
    lastApplied = appliedAction?.let { action ->
        appliedOn?.let { on ->
            appliedAt?.let { at ->
                lastAppliedSource?.let { source -> SeasonSyncAppliedDto(action.name, on, at, source.name) }
            }
        }
    },
)
