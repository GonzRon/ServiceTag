package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord

/** #77 (B2a) — one transfer record with fictional defaults: an OUT of `a1` in pack `pack-q`. */
fun transferOf(
    id: String,
    assetId: String = "a1",
    kind: TransferKind = TransferKind.OUT,
    packId: String = "pack-q",
    lineage: List<String> = emptyList(),
    at: Long = 1_758_960_000_000L,
    packSha256: String = "ab".repeat(32),
    nameSnapshot: String = "Example Water Heater",
    note: String = "",
) = TransferRecord(id, AssetId(assetId), kind, packId, lineage, at, packSha256, nameSnapshot, note)
