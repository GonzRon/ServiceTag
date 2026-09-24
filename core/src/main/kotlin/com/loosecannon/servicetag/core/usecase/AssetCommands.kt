package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.isCode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import com.loosecannon.servicetag.core.model.Season as SeasonWindow

/**
 * Everything an asset form can say, in one value (spec §4). Create and update take the same
 * command so the two paths cannot drift: a field the editor learns to fill in is validated and
 * stored identically whether the row is new or not. What is *not* here is what the person does
 * not type — id, `createdAt`, `status`, `retiredOn`, `templateKey` — so an edit can never
 * resurrect an archived asset or quietly un-retire one (that stays deliberate, on [ArchiveAsset]
 * and [RetireAsset]).
 */
data class AssetCommand(
    val name: String,
    val category: String = "",
    val description: String = "",
    val notes: String = "",
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
    val parentAssetId: AssetId? = null,
    /**
     * The 1.3 season pair, kept as a **compatibility input** (spec §3.2, §9.3). A create reads it as
     * the season: a window is CALENDAR, none is YEAR_ROUND. An edit reads it only when it differs from
     * the stored pair, and then through the season-mode rules — so a MANUAL asset's null pair
     * round-trips untouched, and a different pair on a MANUAL asset is refused
     * ([LegacyWriteCannotRepresent]). The break and the mode proper have their own commands.
     */
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
)

/** One thing wrong with an [AssetCommand]. A form marks a field per problem. */
sealed interface AssetProblem {
    data object NameRequired : AssetProblem
    data object BadCurrency : AssetProblem
    data object CurrencyRequired : AssetProblem
    data object NegativePrice : AssetProblem
    data class BadDate(val field: String) : AssetProblem
    data class Season(val p: SeasonWindow.Problem) : AssetProblem
    data object UnknownParent : AssetProblem
}

/**
 * Every problem at once, thrown once, so a form marks all the bad fields on a single submit
 * instead of making the user re-submit to find the next one.
 */
class AssetValidation(val problems: List<AssetProblem>) :
    IllegalArgumentException("asset rejected: ${problems.joinToString()}")

/** The parent chosen for this asset is the asset itself, or sits under it (spec §5). */
class AssetCycle(val assetId: AssetId, val parentId: AssetId) :
    IllegalStateException("asset ${assetId.value} cannot be parented under ${parentId.value}")

/** A parent is not deletable while it still has children; they are named so a dialog can list them. */
class AssetHasChildren(val assetId: AssetId, val children: List<AssetId>) :
    IllegalStateException("asset ${assetId.value} still has ${children.size} child asset(s)")

/**
 * The single gate both [CreateAsset] and [UpdateAsset] pass through. Returns the trimmed command
 * to store — blank text is `""` and a blank nullable field is `null`, so "cleared" and "never
 * filled in" are the same row — or throws:
 *
 * - [AssetValidation] with every collected problem, one per bad field;
 * - [AssetCycle] when [id] is an existing asset and [AssetCommand.parentAssetId] is itself or a
 *   descendant. A brand-new asset ([id] null) cannot cycle: nothing points at it yet.
 */
fun validateAsset(cmd: AssetCommand, existing: Collection<Asset>, id: AssetId?): AssetCommand {
    val clean = cmd.trimmed()
    val problems = mutableListOf<AssetProblem>()

    if (clean.name.isEmpty()) problems += AssetProblem.NameRequired

    val price = clean.purchasePriceMinor
    val currency = clean.currency
    if (currency != null && !Money.isCode(currency)) {
        problems += AssetProblem.BadCurrency
    } else if (price != null && currency != null && Money.fractionDigits(currency) == null) {
        problems += AssetProblem.BadCurrency        // shaped right but no currency table knows it
    }
    if (price != null && currency == null) problems += AssetProblem.CurrencyRequired
    if (price != null && price < 0) problems += AssetProblem.NegativePrice

    for ((field, value) in listOf(
        "purchaseOn" to clean.purchaseOn,
        "inServiceOn" to clean.inServiceOn,
        "warrantyExpiresOn" to clean.warrantyExpiresOn,
    )) {
        if (value != null && !isIsoDate(value)) problems += AssetProblem.BadDate(field)
    }

    SeasonWindow.validate(clean.seasonStartMmdd, clean.seasonEndMmdd)
        .forEach { problems += AssetProblem.Season(it) }

    val parent = clean.parentAssetId
    if (parent != null && existing.none { it.id == parent }) problems += AssetProblem.UnknownParent

    if (problems.isNotEmpty()) throw AssetValidation(problems)

    if (parent != null && id != null && AssetTree.wouldCycle(existing, id, parent)) {
        throw AssetCycle(id, parent)
    }
    return clean
}

/** True when [value] is an ISO calendar date — `LocalDate.parse` is the whole rule. */
internal fun isIsoDate(value: String): Boolean = try {
    LocalDate.parse(value)
    true
} catch (e: DateTimeParseException) {
    false
}

/** Text trimmed; a nullable field left blank becomes `null`. */
private fun AssetCommand.trimmed(): AssetCommand = copy(
    name = name.trim(),
    category = category.trim(),
    description = description.trim(),
    notes = notes.trim(),
    manufacturer = manufacturer.trim(),
    model = model.trim(),
    serialNumber = serialNumber.trim(),
    purchaseOn = purchaseOn.blankToNull(),
    inServiceOn = inServiceOn.blankToNull(),
    currency = currency.blankToNull(),
    vendor = vendor.trim(),
    location = location.trim(),
    warrantyExpiresOn = warrantyExpiresOn.blankToNull(),
    warrantyNotes = warrantyNotes.trim(),
    seasonStartMmdd = seasonStartMmdd.blankToNull(),
    seasonEndMmdd = seasonEndMmdd.blankToNull(),
)

/** Blank text is absent: the one rule every command's trimming uses. */
internal fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The season mode the legacy pair names on its own (spec §3.2; inv. 88): a window is a CALENDAR
 * season, no window is YEAR_ROUND. Only ever applied to a validated command, whose pair is both or
 * neither.
 */
internal fun AssetCommand.legacyPairMode(): SeasonMode =
    if (seasonStartMmdd != null && seasonEndMmdd != null) SeasonMode.CALENDAR else SeasonMode.YEAR_ROUND

/**
 * Lays an already-validated command over a stored row. Identity and lifecycle come from the row,
 * never from the form: `id`, `createdAt`, `status`, `retiredOn` and `templateKey` survive, and so does
 * every 1.4-only field — the season mode, the break and the health policy are never reset here. The
 * pair is laid over as sent; which mode it means is [CreateAsset]'s and [UpdateAsset]'s decision.
 */
internal fun Asset.applying(cmd: AssetCommand, now: Long): Asset = copy(
    name = cmd.name,
    category = cmd.category,
    description = cmd.description,
    notes = cmd.notes,
    manufacturer = cmd.manufacturer,
    model = cmd.model,
    serialNumber = cmd.serialNumber,
    purchaseOn = cmd.purchaseOn,
    inServiceOn = cmd.inServiceOn,
    purchasePriceMinor = cmd.purchasePriceMinor,
    currency = cmd.currency,
    vendor = cmd.vendor,
    location = cmd.location,
    warrantyExpiresOn = cmd.warrantyExpiresOn,
    warrantyNotes = cmd.warrantyNotes,
    parentAssetId = cmd.parentAssetId,
    seasonStartMmdd = cmd.seasonStartMmdd,
    seasonEndMmdd = cmd.seasonEndMmdd,
    updatedAt = now,
)
