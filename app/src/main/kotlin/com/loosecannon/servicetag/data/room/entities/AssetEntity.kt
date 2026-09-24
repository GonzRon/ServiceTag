package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v4 (spec §4, §5). The identity block is unchanged; everything after `updated_at` is the
 * 2B-2 asset record: what the thing is, what it cost, where it lives, when it left service, and
 * which asset it hangs off.
 *
 * The text fields are non-null Kotlin `String`s — `TEXT NOT NULL`, with `''` for "not filled in",
 * so "cleared" and "never typed" are the same row, matching the domain's `""` defaults. Dates are
 * ISO strings, `purchase_price_minor` is a count of the currency's minor unit (`core.model.Money`
 * owns the conversion), and the season bounds are `MM-DD`.
 *
 * `parent_asset_id` is a self-referencing foreign key with **RESTRICT**: a parent cannot be
 * deleted while a child still points at it. That is deliberate — losing a sub-assembly to a
 * cascade nobody pictured is worse than a refusal, so `DeleteAsset` refuses and names the
 * children, and a full wipe walks the tree children-first (`RoomAssetRepository.deleteAll`).
 *
 * Schema v8 appends five columns, in the order `MIGRATION_7_8` adds them: the season mode, the
 * maintenance break's two `MM-DD` bounds, the health aggregation and the primary health subject. The
 * two NOT NULL ones carry a `defaultValue` equal to the migration's `DEFAULT`, or an upgraded and a
 * fresh database would be two schemas for one version. `health_primary_subject_id` is a **soft
 * link** with no foreign key (inv. 109).
 */
@Entity(
    tableName = "asset",
    indices = [Index("status"), Index("name"), Index("parent_asset_id")],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val notes: String,
    val status: String,
    @ColumnInfo(name = "template_key") val templateKey: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val manufacturer: String = "",
    val model: String = "",
    @ColumnInfo(name = "serial_number") val serialNumber: String = "",
    @ColumnInfo(name = "purchase_on") val purchaseOn: String? = null,
    @ColumnInfo(name = "in_service_on") val inServiceOn: String? = null,
    @ColumnInfo(name = "purchase_price_minor") val purchasePriceMinor: Long? = null,
    val currency: String? = null,
    val vendor: String = "",
    val location: String = "",
    @ColumnInfo(name = "warranty_expires_on") val warrantyExpiresOn: String? = null,
    @ColumnInfo(name = "warranty_notes") val warrantyNotes: String = "",
    @ColumnInfo(name = "retired_on") val retiredOn: String? = null,
    @ColumnInfo(name = "parent_asset_id") val parentAssetId: String? = null,
    @ColumnInfo(name = "season_start_mmdd") val seasonStartMmdd: String? = null,
    @ColumnInfo(name = "season_end_mmdd") val seasonEndMmdd: String? = null,
    @ColumnInfo(name = "season_mode", defaultValue = "YEAR_ROUND") val seasonMode: String = "YEAR_ROUND",
    @ColumnInfo(name = "blackout_start_mmdd") val blackoutStartMmdd: String? = null,
    @ColumnInfo(name = "blackout_end_mmdd") val blackoutEndMmdd: String? = null,
    @ColumnInfo(name = "health_aggregation", defaultValue = "WORST") val healthAggregation: String = "WORST",
    @ColumnInfo(name = "health_primary_subject_id") val healthPrimarySubjectId: String? = null,
)
