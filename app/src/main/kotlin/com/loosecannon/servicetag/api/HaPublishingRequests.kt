package com.loosecannon.servicetag.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * #113/#114 (C35b, I33) — the publishing controls' request bodies, and nothing else: no class here carries a value, a
 * date to publish or READY, and `ApiJson`'s `ignoreUnknownKeys = false` makes any such key the shipped 400.
 * `docs/api/command-shapes.json` lists each one's keys (`haPublishingPlan`, `haPublishingAction`,
 * `haPublishingAcknowledgement`); Publish now takes an empty object.
 */

/**
 * `POST /v1/assets/{id}/ha-publishing-plan`: the publication [kind], the control it is planned for ([action],
 * `ENABLE` or `CHANGE_TARGET`), the helper to publish to ([entityId]) and, for a date publication whose season Home
 * Assistant drives, the season-ended helper it reads ([boundaryEntityId]). The plan reads both and writes nothing.
 */
@Serializable
internal data class HaPublishingPlanRequest(
    val kind: String,
    val action: String,
    val entityId: String,
    val boundaryEntityId: String? = null,
)

/**
 * `POST /v1/assets/{id}/ha-publishing`: one control on the asset's [kind]. `ENABLE` and `CHANGE_TARGET` commit the plan
 * named by [planId] and need [confirmDestination], the plan's `entityId` exactly — both the caller's, never defaulted.
 * `DISABLE` and `CHECK_HELPER` take neither: no action carries a confirmation it does not check.
 */
@Serializable
internal data class HaPublishingActionRequest(
    val kind: String,
    val action: String,
    val planId: String? = null,
    val confirmDestination: String? = null,
)

/** `POST /v1/assets/{id}/ha-publishing/publish-now`: `{}`, and nothing else — Publish now takes no value. */
@Serializable
internal class HaPublishNowRequest

/**
 * `POST /v1/ha-publishing/decommissions/{id}/acknowledge`: the caller's own attestation that the tombstone's helper was
 * cleared in Home Assistant by hand — [decommissionId], the path's id, and [acknowledgedClearedInHa], the JSON `true`.
 * Both are the caller's: decoded as given (null when absent) so that a missing key, a `false`, a string or a number is
 * the 422 the handler answers, never a value filled here. [acknowledgedClearedInHa] is kept as the JSON element sent,
 * so only the literal `true` passes.
 */
@Serializable
internal data class HaDecommissionAcknowledgement(
    val decommissionId: String? = null,
    val acknowledgedClearedInHa: JsonElement? = null,
)
