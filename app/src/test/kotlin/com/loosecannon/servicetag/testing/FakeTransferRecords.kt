package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * #77 — the transfer records in a list, for an `:app` test that builds a subject builder or a projection over its
 * own doubles rather than [FakeGraph]'s Room tables. Empty unless a test appends; `heldIds` is core's own rule.
 */
class FakeTransferRecords : TransferRecordRepository {
    private val rows = MutableStateFlow<List<TransferRecord>>(emptyList())

    override suspend fun append(record: TransferRecord) {
        check(rows.value.none { it.id == record.id }) { "record ${record.id} already exists" }
        rows.value = rows.value + record
    }

    override suspend fun all(): List<TransferRecord> = rows.value
    override suspend fun forAsset(assetId: AssetId): List<TransferRecord> = rows.value.filter { it.assetId == assetId }
    override suspend fun heldIds(): Set<AssetId> = heldIds(rows.value)
    override fun observeHeldIds(): Flow<Set<AssetId>> = rows.map { heldIds(it) }
    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
