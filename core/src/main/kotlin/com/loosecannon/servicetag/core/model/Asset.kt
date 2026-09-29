package com.loosecannon.servicetag.core.model

enum class AssetStatus { ACTIVE, ARCHIVED }

data class Asset(
    val id: AssetId,
    val name: String,
    val description: String = "",
    val category: String = "",
    val notes: String = "",
    val status: AssetStatus = AssetStatus.ACTIVE,
    val templateKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
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
    val retiredOn: String? = null,
    val parentAssetId: AssetId? = null,
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
    /** CALENDAR exactly when both `MM-DD` bounds above are set (inv. 88, a use-case rule). */
    val seasonMode: SeasonMode = SeasonMode.YEAR_ROUND,
    /** The maintenance break, `MM-DD`: both set or both null. */
    val blackoutStartMmdd: String? = null,
    val blackoutEndMmdd: String? = null,
    val healthAggregation: HealthAggregation = HealthAggregation.WORST,
    /** A soft link to the subject `TRACK_ONE` reads; no foreign key. */
    val healthPrimarySubjectId: HealthSubjectId? = null,
    /**
     * #79 (C2; R79-11, R79-12): warn this many whole days before [warrantyExpiresOn], or null for no
     * reminder. It has its own command and is never part of the asset form's command, so a full
     * replace of the form cannot clear it; it is non-null only while [warrantyExpiresOn] is.
     */
    val warrantyReminderLeadDays: Int? = null,
)

val Asset.isRetired: Boolean get() = retiredOn != null

/**
 * Whether this asset is **in service** on its own lifecycle: ACTIVE and not retired (D-16, decision 27). The one
 * home of that predicate; it stays argument-free, and custody sits beside it in [maintainedHere].
 */
val Asset.isInService: Boolean get() = status == AssetStatus.ACTIVE && !isRetired

/**
 * #77 (C11, R77-20): whether this installation still **maintains** this asset — in service, and not transferred
 * out from here ([held] is the transfer records' `heldIds`). Every reminder subject, due, attention, dashboard and
 * health projection asks this, so a held asset is quiesced by an explicit record rule and never by ARCHIVED alone:
 * a held asset that reads ACTIVE (merged transfer history, R77-17) is still not maintained here.
 */
fun Asset.maintainedHere(held: Set<AssetId>): Boolean = isInService && id !in held
