package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.backup.AssetSuccessionDto
import com.loosecannon.servicetag.core.backup.EventProfileDto
import com.loosecannon.servicetag.core.backup.MaintenanceGroupDto
import com.loosecannon.servicetag.core.backup.MaintenanceScheduleDto
import com.loosecannon.servicetag.core.backup.MeasurementDefinitionDto
import com.loosecannon.servicetag.core.backup.NfcTagDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ReplaceDraft
import com.loosecannon.servicetag.core.usecase.ReplaceOffer
import com.loosecannon.servicetag.core.usecase.ReplacePlan
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * #92 (C19–C23; R92-1, R92-2) — the three replace rows' bodies. Every answer reuses the archive's own row DTOs, as
 * every `/v1` answer does; the request is #86's `ReplaceDraft` with the predecessor taken from the path.
 */

/** C19: `ReplaceAsset.offer`, key by key. Nothing in it is ticked, and there is no `scheduleStartOn` default. */
@Serializable
internal data class ReplaceOfferResponse(
    val eligible: Boolean,
    val held: Boolean,
    val replacedBy: AssetSuccessionDto?,
    val predecessor: AssetDto,
    /** A schedule has a time rule iff `timeInterval` is non-null; ticking one needs `scheduleStartOn`. */
    val schedules: List<ScheduleRowResponse>,
    val groups: List<MaintenanceGroupDto>,
    val setupOffered: Boolean,
    val seasonOffered: Boolean,
    val notesOffered: Boolean,
    /** The ACTIVE bindings, each selectable only by its own id (R92-2). */
    val tags: List<NfcTagDto>,
    val parentChoiceIds: List<String>,
    val prefill: ReplacePrefill,
    val childNames: List<String>,
    val openLoan: Boolean,
)

@Serializable
internal data class ReplacePrefill(
    val name: String,
    val category: String,
    val location: String,
    val parentAssetId: String?,
)

internal fun ReplaceOffer.toResponse() = ReplaceOfferResponse(
    eligible = eligible,
    held = held,
    replacedBy = replacedBy?.toDto(),
    predecessor = predecessor.toDto(),
    schedules = schedules.map { it.rowResponse() },
    groups = groups.map { it.toDto() },
    setupOffered = setupOffered,
    seasonOffered = seasonOffered,
    notesOffered = notesOffered,
    tags = tags.map { it.toDto() },
    parentChoiceIds = parentChoices.map { it.id.value },
    prefill = ReplacePrefill(
        name = prefill.name,
        category = prefill.category,
        location = prefill.location,
        parentAssetId = prefill.parentAssetId?.value,
    ),
    childNames = childNames,
    openLoan = openLoan,
)

/**
 * C20: the new asset's fields — the asset command's keys **minus** `description`, `notes`, `templateKey` and the
 * season pair, which the use case fills or ignores. Sending one is the strict decoder's 400, never a silent drop.
 */
@Serializable
internal data class ReplaceSuccessorRequest(
    val name: String,
    val category: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val purchaseOn: String? = null,
    val inServiceOn: String? = null,
    val purchasePriceMinor: Long? = null,
    val currency: String? = null,
    val vendor: String = "",
    val location: String = "",
    val warrantyExpiresOn: String? = null,
    val warrantyNotes: String = "",
    val parentAssetId: String? = null,
)

/**
 * C20: the draft both POSTs take. Every flag false and every set empty unless the caller names it (R86-9); tags only
 * by their binding ids (R92-2: no "all", label or pattern form). [sourcesDigest] is `replace`'s alone.
 */
@Serializable
internal data class ReplaceDraftRequest(
    val retiredOn: String? = null,
    val successor: ReplaceSuccessorRequest,
    val carrySeason: Boolean = false,
    val carrySetup: Boolean = false,
    val carryNotes: Boolean = false,
    /** `IN_SEASON` or `OUT_OF_SEASON`. */
    val manualPhase: String? = null,
    val scheduleIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    /** Never defaulted here: the caller sends one explicitly (R86-10). */
    val scheduleStartOn: String? = null,
    val movedTagIds: List<String> = emptyList(),
    val sourcesDigest: String? = null,
)

/** The phone form's own mapping (`ReplaceAssetViewModel.draftOf`): the path's predecessor, the caller's values. */
internal fun ReplaceDraftRequest.toDraft(predecessorId: AssetId) = ReplaceDraft(
    predecessorId = predecessorId,
    retiredOn = retiredOn,
    successor = AssetCommand(
        name = successor.name,
        category = successor.category,
        manufacturer = successor.manufacturer,
        model = successor.model,
        serialNumber = successor.serialNumber,
        purchaseOn = successor.purchaseOn,
        inServiceOn = successor.inServiceOn,
        purchasePriceMinor = successor.purchasePriceMinor,
        currency = successor.currency,
        vendor = successor.vendor,
        location = successor.location,
        warrantyExpiresOn = successor.warrantyExpiresOn,
        warrantyNotes = successor.warrantyNotes,
        parentAssetId = successor.parentAssetId?.let(::AssetId),
    ),
    carrySeason = carrySeason,
    manualPhase = manualPhase?.let { enumOr400<SeasonPhase>(it, "manualPhase") },
    carrySetup = carrySetup,
    carryNotes = carryNotes,
    scheduleIds = scheduleIds.map(::ScheduleId).toSet(),
    scheduleStartOn = scheduleStartOn,
    groupIds = groupIds.map(::GroupId).toSet(),
    movedTagIds = movedTagIds.map(::TagId).toSet(),
)

/** C21: the review. Problems are data here; nothing is written. */
@Serializable
internal data class ReplacePlanResponse(
    val eligible: Boolean,
    /** Null, `asset_transferred_out` or `ASSET_ALREADY_REPLACED`: the refusal `replace` would answer first. */
    val blockedBy: String?,
    val replacedOn: String,
    val problems: List<ReplaceProblemDto>,
    val sourcesDigest: String,
)

@Serializable
internal data class ReplaceProblemDto(val code: String, val field: String?, val problem: String)

/** C22: the new asset as stored, and the archive's succession row naming both. */
@Serializable
internal data class ReplaceResponse(val successor: AssetDto, val succession: AssetSuccessionDto)

/**
 * C23's one input: the canonical draft, then `replacedOn`, then `ReplaceSources` in its own order as archive rows —
 * exactly what `ReplaceAsset.run` compares (`draft != reviewed.draft`, `fresh.sources != reviewed.sources`,
 * `fresh.replacedOn != reviewed.replacedOn`).
 */
@Serializable
internal data class ReplaceDigestInput(
    val draft: ReplaceDraftRequest,
    val replacedOn: String,
    val predecessor: AssetDto,
    val successorRow: AssetSuccessionDto?,
    val schedules: List<MaintenanceScheduleDto>,
    val groups: List<MaintenanceGroupDto>,
    val tags: List<NfcTagDto>,
    val definitions: List<MeasurementDefinitionDto>,
    val profiles: List<EventProfileDto>,
)

/**
 * C23: lowercase-hex SHA-256 of [ApiJson] over [ReplaceDigestInput]. The draft is canonical: the digest itself left
 * out, and each id list sorted and distinct, as the draft's sets are. One function for the plan and the apply.
 */
internal fun sourcesDigest(draft: ReplaceDraftRequest, plan: ReplacePlan): String {
    val sources = plan.sources
    val input = ReplaceDigestInput(
        draft = draft.copy(
            scheduleIds = draft.scheduleIds.distinct().sorted(),
            groupIds = draft.groupIds.distinct().sorted(),
            movedTagIds = draft.movedTagIds.distinct().sorted(),
            sourcesDigest = null,
        ),
        replacedOn = plan.replacedOn,
        predecessor = sources.predecessor.toDto(),
        successorRow = sources.successorRow?.toDto(),
        schedules = sources.schedules.map { it.toDto() },
        groups = sources.groups.map { it.toDto() },
        tags = sources.tags.map { it.toDto() },
        definitions = sources.definitions.map { it.toDto() },
        profiles = sources.profiles.map { it.toDto() },
    )
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(ApiJson.encodeToString(ReplaceDigestInput.serializer(), input).toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
