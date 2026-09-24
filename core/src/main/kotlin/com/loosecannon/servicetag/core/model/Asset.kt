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
)

val Asset.isRetired: Boolean get() = retiredOn != null
