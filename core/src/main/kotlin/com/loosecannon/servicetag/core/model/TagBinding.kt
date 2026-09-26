package com.loosecannon.servicetag.core.model

enum class PayloadFormat { V1 }

enum class TagStatus { ACTIVE, UNBOUND, LOST, RETIRED }

sealed interface TagTarget {
    data class AssetTarget(val assetId: AssetId) : TagTarget
    data class LinkTarget(val linkId: LinkId) : TagTarget
    data object None : TagTarget
}

data class TagBinding(
    val id: TagId,
    val payloadFormat: PayloadFormat,
    val payloadKey: String,
    val target: TagTarget = TagTarget.None,
    val status: TagStatus = TagStatus.ACTIVE,
    val label: String? = null,
    val physicalUid: String? = null,
    val writtenAt: Long? = null,
    val lastScannedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * #71 (plan C1, R71-1): whether this row is a written, active ServiceTag tag for [assetId] — it
 * targets that asset, it is ACTIVE, and `ProvisionTag.complete` stamped [TagBinding.writtenAt] after
 * the verified write. A row provisioned but never written, an UNBOUND spare, and a LOST or RETIRED tag
 * that still names the asset do not count. The one home of the rule; one qualifying row is enough.
 */
fun TagBinding.isWrittenFor(assetId: AssetId): Boolean = target == TagTarget.AssetTarget(assetId) && status == TagStatus.ACTIVE && writtenAt != null
