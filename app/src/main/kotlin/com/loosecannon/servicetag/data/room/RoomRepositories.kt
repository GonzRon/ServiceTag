package com.loosecannon.servicetag.data.room

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.data.room.dao.AssetDao
import com.loosecannon.servicetag.data.room.dao.AttachmentDao
import com.loosecannon.servicetag.data.room.dao.ExternalLinkDao
import com.loosecannon.servicetag.data.room.dao.NfcTagDao
import com.loosecannon.servicetag.data.room.entities.NfcTagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * D4 §3 requires `CHECK(NOT (asset_id IS NOT NULL AND link_id IS NOT NULL))`. Room has no CHECK
 * annotation, so the rule is enforced here, on the way into the table. The domain's `TagTarget`
 * already makes both-at-once unrepresentable; this guards rows built any other way.
 */
fun NfcTagEntity.requireAtMostOneTarget(): NfcTagEntity = apply {
    require(assetId == null || linkId == null) {
        "nfc_tag '$id' carries both asset_id ('$assetId') and link_id ('$linkId'); at most one target is allowed"
    }
}

class RoomAssetRepository(private val dao: AssetDao) : AssetRepository {
    override suspend fun upsert(asset: Asset) = dao.upsert(asset.toEntity())
    override suspend fun get(id: AssetId): Asset? = dao.byId(id.value)?.toDomain()
    override suspend fun all(): List<Asset> = dao.all().map { it.toDomain() }
    override suspend fun delete(id: AssetId) = dao.delete(id.value)

    /**
     * The single wipe strategy (spec §10). `parent_asset_id` is a RESTRICT self-foreign-key, so a
     * wipe has to delete children before their parents; the order comes from the core authority —
     * `parentsFirst` reversed *is* children-first — rather than from ad hoc SQL that would have to
     * re-derive the hierarchy in a `WHERE` clause and would still not control row order within a
     * statement. Every full wipe lands here: the replace import, the debug Wipe, the
     * instrumentation's `clearInstall`.
     *
     * `deleteAllInOrder` is a `@Transaction` DAO method, so the deletes commit or roll back
     * together even if a caller opens no transaction of its own; the callers that exist today all
     * run inside `UnitOfWork.write`, and Room nests that.
     */
    override suspend fun deleteAll() {
        val childrenFirst = AssetTree.parentsFirst(dao.all().map { it.toDomain() }).asReversed()
        dao.deleteAllInOrder(childrenFirst.map { it.id.value })
    }

    override fun observeAll(): Flow<List<Asset>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
}

class RoomTagRepository(private val dao: NfcTagDao) : TagRepository {
    override suspend fun upsert(tag: TagBinding) = dao.upsert(tag.toEntity().requireAtMostOneTarget())
    override suspend fun get(id: TagId): TagBinding? = dao.byId(id.value)?.toDomain()

    override suspend fun findByPayload(format: PayloadFormat, key: String): TagBinding? =
        dao.byPayload(format.name, key)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<TagBinding> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun all(): List<TagBinding> = dao.all().map { it.toDomain() }
    override suspend fun delete(id: TagId) = dao.delete(id.value)
    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForAsset(assetId: AssetId): Flow<List<TagBinding>> =
        dao.observeForAsset(assetId.value).map { list -> list.map { it.toDomain() } }

    override fun observeAll(): Flow<List<TagBinding>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
}

/**
 * The tombstone adapter (2.6): exactly the four members `LinkRepository` still declares, so the
 * only way above the DAO to reach `external_link` is the backup path. The display queries this
 * class used to expose live on [ExternalLinkDao] and nowhere else.
 */
class RoomLinkRepository(private val dao: ExternalLinkDao) : LinkRepository {
    override suspend fun upsert(link: ExternalLink) = dao.upsert(link.toEntity())
    override suspend fun get(id: LinkId): ExternalLink? = dao.byId(id.value)?.toDomain()
    override suspend fun all(): List<ExternalLink> = dao.all().map { it.toDomain() }
    override suspend fun deleteAll() = dao.deleteAll()
}

/**
 * The `attachment` table's side of [AttachmentRepository]. Metadata only: the bytes are the
 * store's, never Room's, and `storage_locator` is the only thing here that knows where they are.
 * Every write goes through [requireExactlyOneOwner], so a row with both owners or neither is
 * refused before SQLite ever sees it (spec §11.5).
 */
class RoomAttachmentRepository(private val dao: AttachmentDao) : AttachmentRepository {
    override suspend fun upsert(a: Attachment) = dao.upsert(a.toEntity().requireExactlyOneOwner())
    override suspend fun get(id: AttachmentId): Attachment? = dao.byId(id.value)?.toDomain()

    override suspend fun forOwner(owner: AttachmentOwner): List<Attachment> = when (owner) {
        is AttachmentOwner.OfAsset -> dao.forAsset(owner.assetId.value)
        is AttachmentOwner.OfEvent -> dao.forEvent(owner.eventId.value)
        is AttachmentOwner.OfSupplyItem -> dao.forSupplyItem(owner.supplyId.value)
        is AttachmentOwner.OfInstalledComponent -> dao.forInstalledComponent(owner.componentId.value)
    }.map { it.toDomain() }

    override suspend fun forAsset(assetId: AssetId): List<Attachment> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun all(): List<Attachment> = dao.all().map { it.toDomain() }
    override suspend fun delete(id: AttachmentId) = dao.delete(id.value)
    override suspend fun deleteAll() = dao.deleteAll()
    override suspend fun count(): Int = dao.count()

    override fun observeForOwner(owner: AttachmentOwner): Flow<List<Attachment>> = when (owner) {
        is AttachmentOwner.OfAsset -> dao.observeForAsset(owner.assetId.value)
        is AttachmentOwner.OfEvent -> dao.observeForEvent(owner.eventId.value)
        is AttachmentOwner.OfSupplyItem -> dao.observeForSupplyItem(owner.supplyId.value)
        is AttachmentOwner.OfInstalledComponent -> dao.observeForInstalledComponent(owner.componentId.value)
    }.map { list -> list.map { it.toDomain() } }
}

class RoomUnitOfWork(private val db: AppDatabase) : UnitOfWork {
    override suspend fun <T> write(block: suspend () -> T): T = db.withWriteTransaction { block() }
    override suspend fun <T> read(block: suspend () -> T): T = db.withReadTransaction { block() }
}
