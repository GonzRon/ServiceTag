package com.loosecannon.servicetag.nfc

import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UuidGenerator
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomTagRepository
import com.loosecannon.servicetag.data.room.RoomUnitOfWork
import com.loosecannon.servicetag.data.room.inMemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Phase 1B use cases against the real schema: unique (format,key) lookup, FK targets, cleanup. */
class TagUseCasesRoomTest {

    /**
     * This file builds its repositories by hand rather than through `FakeGraph`, so it builds the
     * codec the same way the app does: from the Gradle-owned BuildConfig fields (C9).
     */
    private val ndefCodec = NdefCodec(
        TagIdentity(
            externalDomain = BuildConfig.NDEF_EXTERNAL_DOMAIN,
            typeName = BuildConfig.NDEF_TYPE_NAME,
            aarPackage = BuildConfig.NDEF_AAR_PACKAGE,
        ),
    )

    @Test
    fun provisionWriteScanResolvesThroughRoom() = runTest {
        val db = inMemoryDb()
        try {
            val assets = RoomAssetRepository(db.assetDao())
            val tags = RoomTagRepository(db.nfcTagDao())
            val uow = RoomUnitOfWork(db)
            // two clocks, so `lastScannedAt` cannot pass by coinciding with the write time
            val provision = ProvisionTag(tags, assets, uow, UuidGenerator, Clock { 42L })
            val resolve = ResolveTag(tags, assets, uow, Clock { 43L })

            uow.write { assets.upsert(Asset(AssetId("a1"), "Hot tub", createdAt = 1L, updatedAt = 1L)) }
            val row = provision.begin(TagTarget.AssetTarget(AssetId("a1")), "lid")
            val onTag = ndefCodec.decode(ndefCodec.encodeV1(row.id))   // what the phone will read back
            provision.complete(row.id, "04aabbcc")

            val r = resolve.run(onTag)
            assertTrue(r.toString(), r is Resolution.OpenAsset)
            r as Resolution.OpenAsset
            assertEquals("Hot tub", r.asset.name)
            assertEquals(42L, r.tag.writtenAt)
            assertEquals(43L, r.tag.lastScannedAt)
            assertEquals("04aabbcc", r.tag.physicalUid)
        } finally {
            db.close()
        }
    }

    @Test
    fun abandonRemovesOnlyTheUnwrittenRow() = runTest {
        val db = inMemoryDb()
        try {
            val assets = RoomAssetRepository(db.assetDao())
            val tags = RoomTagRepository(db.nfcTagDao())
            val uow = RoomUnitOfWork(db)
            val provision = ProvisionTag(tags, assets, uow, UuidGenerator, Clock { 1L })
            val spare = provision.begin(TagTarget.None, null)
            val written = provision.complete(provision.begin(TagTarget.None, null).id, null)
            provision.abandon(spare.id)
            provision.abandon(written.id)
            assertNull(tags.get(spare.id))
            assertEquals(written, tags.get(written.id))
        } finally {
            db.close()
        }
    }

    /**
     * #71 (plan C1): the Room adapter's `observeAll` is live — one collection sees the provisioned
     * row, its verified write, a status change and a delete, as the Assets list's tagged set must.
     * Room answers on its own threads here, so each wait is real time, bounded, and names what it
     * waited for.
     */
    @Test
    fun observeAllEmitsOnEveryWrite() = runTest {
        val db = inMemoryDb()
        try {
            val assets = RoomAssetRepository(db.assetDao())
            val tags = RoomTagRepository(db.nfcTagDao())
            val uow = RoomUnitOfWork(db)
            val provision = ProvisionTag(tags, assets, uow, UuidGenerator, Clock { 42L })
            val latest = MutableStateFlow<List<TagBinding>?>(null)
            backgroundScope.launch { tags.observeAll().collect { latest.value = it } }
            latest.await("the empty table") { it == emptyList<TagBinding>() }

            uow.write { assets.upsert(Asset(AssetId("a1"), "Hot tub", createdAt = 1L, updatedAt = 1L)) }
            val row = provision.begin(TagTarget.AssetTarget(AssetId("a1")), "lid")
            latest.await("the provisioned row, unwritten") { rows -> rows?.map { it.writtenAt } == listOf<Long?>(null) }

            provision.complete(row.id, "04aabbcc")
            latest.await("the verified write") { rows -> rows?.map { it.writtenAt } == listOf<Long?>(42L) }

            tags.upsert(tags.get(row.id)!!.copy(status = TagStatus.LOST))
            latest.await("a status change") { rows -> rows?.map { it.status } == listOf(TagStatus.LOST) }

            tags.delete(row.id)
            latest.await("a delete") { it == emptyList<TagBinding>() }
        } finally {
            db.close()
        }
    }

    private suspend fun <T> StateFlow<T>.await(what: String, until: (T) -> Boolean) {
        val seen = withContext(Dispatchers.Default) { withTimeoutOrNull(WAIT_MS) { first(until) } }
        if (seen == null) fail("$what: never emitted; the last emission was $value")
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
