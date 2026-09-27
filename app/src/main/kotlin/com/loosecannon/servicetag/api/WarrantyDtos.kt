package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.usecase.WarrantyReminderCommand
import com.loosecannon.servicetag.core.warranty.warrantyStatusOf
import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * #79's shapes on the wire (C12; M1; R79-18).
 *
 * The lead is canonical data, so it rides on the backup row the API already reuses:
 * `AssetDto.warrantyReminderLeadDays`, written by `POST /v1/assets/{id}/warranty-reminder` and by
 * nothing on the asset command. The **status is derived** — from the stored date and today — so it is
 * never a field of `AssetDto` and has a response of its own, as the season and the health do: no
 * archive carries it, no command accepts it, and no table stores it.
 */

/**
 * `POST /v1/assets/{id}/warranty-reminder`: whole days before the warranty date, or `null` for no
 * reminder. The key has no default, as the maintenance break's two have none, so a body that forgot it
 * is a 400 rather than a reminder silently turned off. `docs/api/command-shapes.json` pins it as
 * `warrantyReminder`.
 */
@Serializable
internal data class WarrantyReminderRequest(val leadDays: Int?)

internal fun WarrantyReminderRequest.toCommand() = WarrantyReminderCommand(leadDays)

/**
 * The asset's warranty, derived for the day it is asked. [status] is `IN_WARRANTY` (today on or before
 * [expiresOn]; the expiry day itself is in), `OUT_OF_WARRANTY` (after it) or `NOT_RECORDED` (no date,
 * or one that does not parse). [expiresOn] is the stored `warrantyExpiresOn` and [leadDays] the stored
 * lead, `null` when there is none. Every field is present, `null` when absent.
 */
@Serializable
internal data class WarrantyDto(val status: String, val expiresOn: String?, val leadDays: Int?)

/** `GET /v1/assets/{id}/warranty`. */
@Serializable
internal data class WarrantyResponse(val warranty: WarrantyDto)

/** `POST /v1/assets/{id}/warranty-reminder`: the asset as stored, and its warranty now. */
@Serializable
internal data class AssetWarrantyResponse(val asset: AssetDto, val warranty: WarrantyDto)

/** The one derivation, with the `Today` the handler was handed — never a clock. */
internal fun Asset.warrantyOn(today: LocalDate): WarrantyDto = WarrantyDto(
    status = warrantyStatusOf(warrantyExpiresOn, today).name,
    expiresOn = warrantyExpiresOn,
    leadDays = warrantyReminderLeadDays,
)
