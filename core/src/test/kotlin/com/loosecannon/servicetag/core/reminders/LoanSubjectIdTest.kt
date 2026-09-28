package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoanId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #72 (C9, C12): a loan subject's id is `<assetId>/<loanId>`, split at the **last** `/`. The asset
 * half is what "Open" aims at with no read; the loan half is what keeps a returned loan's stamp out
 * of an identical re-lend. A loan id never holds a `/` (minted ids, and a restore refuses one), so
 * an asset id may hold any number and still round-trip.
 */
class LoanSubjectIdTest {

    @Test
    fun anAssetAndALoanRoundTrip() {
        val id = LoanSubjectId.of(AssetId("8f14e45f-ceea-467a-9575-3f1c2e5d6a7b"), AssetLoanId("c9f0f895-fb98-4b91-99f5-1d5a2e7c0b3e"))

        assertEquals("8f14e45f-ceea-467a-9575-3f1c2e5d6a7b/c9f0f895-fb98-4b91-99f5-1d5a2e7c0b3e", id)
        assertEquals(AssetId("8f14e45f-ceea-467a-9575-3f1c2e5d6a7b"), LoanSubjectId.assetOf(id))
        assertEquals(AssetLoanId("c9f0f895-fb98-4b91-99f5-1d5a2e7c0b3e"), LoanSubjectId.loanOf(id))
    }

    /** An imported asset id may hold `/`; the split at the last one still gives both halves back. */
    @Test
    fun anAssetIdHoldingASlashRoundTrips() {
        listOf("a/b", "shed/bench/drill", "/leading").forEach { asset ->
            val id = LoanSubjectId.of(AssetId(asset), AssetLoanId("l1"))

            assertEquals(asset, LoanSubjectId.assetOf(id)?.value, id)
            assertEquals("l1", LoanSubjectId.loanOf(id)?.value, id)
        }
    }

    /** No `/`, or nothing on either side of the last one, is no loan subject: both halves are null. */
    @Test
    fun noSlashOrAnEmptySideIsNull() {
        listOf("a1", "", "/", "a1/", "/l1", "a/b/").forEach { id ->
            assertNull(LoanSubjectId.assetOf(id), id)
            assertNull(LoanSubjectId.loanOf(id), id)
        }
    }
}
