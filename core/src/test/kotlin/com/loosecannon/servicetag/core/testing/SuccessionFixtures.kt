package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession

/** #86 (B1) — one succession with fictional defaults: `a1` replaced by `a2` on 2026-09-20. */
fun successionOf(
    id: String,
    predecessor: String = "a1",
    successor: String = "a2",
    replacedOn: String = "2026-09-20",
    createdAt: Long = 1_758_900_000_000L,
) = AssetSuccession(id, AssetId(predecessor), AssetId(successor), replacedOn, createdAt)
