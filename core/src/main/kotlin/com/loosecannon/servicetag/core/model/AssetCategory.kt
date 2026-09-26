package com.loosecannon.servicetag.core.model

/**
 * One of the owner's own Asset categories (#74, C1): the durable half of the catalog, beside the
 * compiled built-ins, which are never rows. A row is written only by a successful Asset save (the
 * promotion), a rename, or the one-time backfill of `MIGRATION_8_9`, and it outlives the last Asset
 * that used it.
 *
 * [key] is the identity — `CategoryKey.of(display)`, persisted as the primary key, so the same
 * category typed on two phones is the same row. [display] is the spelling the owner sees and the
 * spelling every Asset of this key stores.
 */
data class AssetCategory(
    val key: String,
    val display: String,
    val createdAt: Long,
    val updatedAt: Long,
)
