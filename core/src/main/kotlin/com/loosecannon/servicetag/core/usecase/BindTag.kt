package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Binds the tag that carries ([format], [key]) to [target]. Works for a tag this phone has never
 * seen (a v1 tag from before a wipe, or another phone's tag) and for a known row, which is
 * retargeted and re-activated in place — its history and label survive.
 */
class BindTag(
    private val tags: TagRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    // [format] is kept, not folded away to V1: `payload_format` is a persisted discriminator and a second format is planned.
    suspend fun run(format: PayloadFormat, key: String, target: TagTarget, label: String? = null): TagBinding {
        require(target != TagTarget.None) { "bind needs an asset" }
        NdefCodec.requireCanonicalUuid(TagId(key))
        return uow.write { bindInTransaction(format, key, target, label) }
    }

    /**
     * #86 (C15): the bind itself — a known row retargeted and re-activated in place, its id, payload, label,
     * `physicalUid`, `writtenAt` and `lastScannedAt` kept — inside the caller's transaction, so `ReplaceAsset`
     * moves a tag in its own one write. Nothing here writes to a tag.
     */
    internal suspend fun bindInTransaction(
        format: PayloadFormat,
        key: String,
        target: TagTarget,
        label: String?,
    ): TagBinding {
        requireTargetExists(target, assets)
        val now = clock.nowMillis()
        val existing = tags.findByPayload(format, key)
        val bound = existing?.copy(target = target, status = TagStatus.ACTIVE, label = label ?: existing.label, updatedAt = now)
            ?: TagBinding(
                id = TagId(key),
                payloadFormat = format,
                payloadKey = key,
                target = target,
                status = TagStatus.ACTIVE,
                label = label,
                createdAt = now,
                updatedAt = now,
            )
        tags.upsert(bound)
        return bound
    }
}
