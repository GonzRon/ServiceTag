package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * [InMemoryReferenceRepository] already models the table and its unique index; what a refusal test
 * needs on top of that is a count of the writes that did **not** happen, which `rows` alone cannot
 * show — an upsert followed by a rollback leaves the same empty map as an upsert never attempted.
 */
class RecordingReferenceRepository : InMemoryReferenceRepository() {
    var upserts = 0
        private set
    var deletes = 0
        private set

    override suspend fun upsert(reference: AssetReference) {
        upserts += 1
        super.upsert(reference)
    }

    override suspend fun delete(id: ReferenceId) {
        deletes += 1
        super.delete(id)
    }
}

/**
 * [FakeUnitOfWork] counts the transactions that *committed*; this one counts the ones that were
 * **entered at all**, which is what "a refusal writes nothing" has to mean: a use case that opens a
 * transaction and then decides against writing has already taken the lock.
 */
class RecordingUnitOfWork(vararg stores: Rollbackable) : UnitOfWork {
    private val inner = FakeUnitOfWork(*stores)

    var writesEntered = 0
        private set

    val commits: Int get() = inner.commits

    override suspend fun <T> write(block: suspend () -> T): T {
        writesEntered += 1
        return inner.write(block)
    }

    override suspend fun <T> read(block: suspend () -> T): T = inner.read(block)
}
