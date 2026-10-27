package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.model.AssetId
import java.time.LocalDate

/**
 * #124 (C41) — the day an asset's opening service made it ready for its season, as `IN_SERVICE_AFTER_OPENING_SERVICE`
 * counts from it: #114's own answer for today ([Readiness.Ready.serviceOn]), or null while the asset is not ready.
 * Asked with the stores as the caller sees them: inside its write transaction for the recompute, and with no
 * transaction at all for the health read model's read. Its one implementation is [SeasonReadinessReader]; no other code
 * decides whether an opening service qualifies.
 */
fun interface OpeningServiceReader {
    suspend fun openingServiceOn(assetId: AssetId): LocalDate?

    companion object {
        /** No feed: every schedule that counts from the opening service waits. Shipped constructions and tests only. */
        val NONE: OpeningServiceReader = OpeningServiceReader { null }
    }
}
