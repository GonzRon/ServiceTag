package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.SetWarrantyReminder
import com.loosecannon.servicetag.di.AppGraph

/**
 * #79's two `/v1` endpoints (C12; R79-18), reached from the router as `handlers.warranty.*`: the
 * asset's warranty, derived for today, and its reminder lead.
 *
 * The rule is 1.2's: the write calls exactly one use case, `SetWarrantyReminder`, and the read reads
 * the asset row. The status is derived with the [Today] port — the same date the asset detail draws
 * it for — and never with a clock.
 *
 * **Neither route runs a reminder sweep**, like every other `/v1` write: a lead set or cleared here
 * settles at the phone's next digest or its 12-hour backstop (R79-15), and the warning itself — a
 * device-local post and its device-local stamp — has no route at all.
 */
internal class WarrantyHandlers(
    private val assets: AssetRepository,
    private val setWarrantyReminder: SetWarrantyReminder,
    private val today: Today,
) {
    constructor(graph: AppGraph) : this(graph.assets, graph.setWarrantyReminder, graph.today)

    /** The warranty for today, computed at read time; nothing is written. */
    suspend fun getWarranty(assetId: String): ApiResponse {
        val asset = assets.get(AssetId(assetId)) ?: throw NoSuchAsset(AssetId(assetId))
        return ok(WarrantyResponse.serializer(), WarrantyResponse(asset.warrantyOn(today.localDate())))
    }

    /**
     * Sets or clears the lead: 422 `warranty_reminder_validation` for a lead under one day or one on
     * an asset without a warranty date, and the stored lead writes nothing. Answers the asset as stored
     * and its warranty now.
     */
    suspend fun setWarrantyReminder(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(WarrantyReminderRequest.serializer())
        val saved = setWarrantyReminder.run(AssetId(assetId), body.toCommand())
        return ok(
            AssetWarrantyResponse.serializer(),
            AssetWarrantyResponse(saved.toDto(), saved.warrantyOn(today.localDate())),
        )
    }
}
