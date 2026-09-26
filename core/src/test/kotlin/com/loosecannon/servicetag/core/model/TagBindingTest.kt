package com.loosecannon.servicetag.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #71's NFC predicate (plan §2 C1, R71-1): an asset carries a ServiceTag tag exactly when some row
 * targets it, is ACTIVE and has been written (`writtenAt`, which only `ProvisionTag.complete` sets).
 * Each row of the table fails on one clause alone, so dropping any one clause turns a row wrong.
 */
class TagBindingTest {

    private val pump = AssetId("pump")
    private val heater = AssetId("heater")

    private fun row(
        id: String,
        target: TagTarget,
        status: TagStatus = TagStatus.ACTIVE,
        writtenAt: Long? = 42L,
    ) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = "key-$id",
        target = target,
        status = status,
        writtenAt = writtenAt,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test fun isWrittenForRequiresTargetActiveAndWritten() {
        val table = listOf(
            "targets the asset, ACTIVE, written" to (row("t1", TagTarget.AssetTarget(pump)) to true),
            "provisioned but never written" to (row("t2", TagTarget.AssetTarget(pump), writtenAt = null) to false),
            "an UNBOUND spare" to (row("t3", TagTarget.None, status = TagStatus.UNBOUND) to false),
            "LOST, still naming the asset, written" to (row("t4", TagTarget.AssetTarget(pump), status = TagStatus.LOST) to false),
            "RETIRED, still naming the asset, written" to (row("t5", TagTarget.AssetTarget(pump), status = TagStatus.RETIRED) to false),
            "another asset's written ACTIVE tag" to (row("t6", TagTarget.AssetTarget(heater)) to false),
        )

        table.forEach { (case, pair) ->
            val (tag, expected) = pair
            assertEquals(expected, tag.isWrittenFor(pump), case)
        }
        assertEquals(true, row("t6", TagTarget.AssetTarget(heater)).isWrittenFor(heater), "it counts for its own asset")
    }
}
