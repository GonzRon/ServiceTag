package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import java.security.MessageDigest

/**
 * #92 (C13, R92-6, R92-8): the one home of the upload's derived attachment id. The caller's operation key is stored in
 * the row's own id — no table, no schema change — and the id is scoped to this installation and the owner asset:
 *
 * `uuid8(SHA-256(UTF-8("servicetag:attachment-upload:v2\n" + installationId + "\n" + assetId + "\n" + operationKey)))`
 *
 * — the digest's first 16 bytes with the RFC 9562 version-8 and variant bits set, in the lowercase canonical form. One
 * installation derives the same id for one asset and key every time; the development and production phones derive
 * different ones, so a later merge meets two rows, never one id with two contents. The golden vector the MCP's twin
 * reads is `docs/api/attachment-operation-ids.json`.
 */
internal fun attachmentOperationId(installationId: String, assetId: AssetId, operationKey: String): AttachmentId =
    uuid8("$OPERATION_ID_PREFIX\n$installationId\n${assetId.value}\n$operationKey")

/**
 * #69 (C21): the derived id for an upload to any of the three owners a route names. An asset's is the shipped v2 one
 * above, byte for byte. A SupplyItem's and an installed component's take [OWNER_OPERATION_ID_PREFIX] and name the
 * owner's kind before its id:
 *
 * `uuid8(SHA-256(UTF-8(OWNER_OPERATION_ID_PREFIX + "\n" + installationId + "\n" + ownerKind + "\n" + ownerId + "\n" + operationKey)))`
 *
 * with `ownerKind` `supply-item` or `installed-component`, so one id string under two owner kinds never derives one row
 * id. Its golden vectors are the `ownerVectors` of `docs/api/attachment-operation-ids.json`.
 */
internal fun attachmentOperationId(installationId: String, owner: ReferenceOwner, operationKey: String): AttachmentId =
    when (owner) {
        is ReferenceOwner.OfAsset -> attachmentOperationId(installationId, owner.assetId, operationKey)
        is ReferenceOwner.OfSupplyItem ->
            uuid8("$OWNER_OPERATION_ID_PREFIX\n$installationId\nsupply-item\n${owner.supplyId.value}\n$operationKey")
        is ReferenceOwner.OfInstalledComponent -> uuid8(
            "$OWNER_OPERATION_ID_PREFIX\n$installationId\ninstalled-component\n${owner.componentId.value}\n$operationKey",
        )
    }

/** The digest's first 16 bytes with the version-8 and variant bits set, in the lowercase canonical form. */
private fun uuid8(text: String): AttachmentId {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte() // version 8
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // variant 10
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return AttachmentId(
        "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}",
    )
}

/** The derivation's domain separator; a new derivation takes a new version, never this string. */
internal const val OPERATION_ID_PREFIX: String = "servicetag:attachment-upload:v2"

/** #69 (C21): the separator of a SupplyItem's and an installed component's derivation; an asset's stays v2. */
internal const val OWNER_OPERATION_ID_PREFIX: String = "servicetag:attachment-upload:v3"

/** C11: an operation key is 1–128 of the URI-unreserved characters plus `:`. */
private val OPERATION_KEY = Regex("[A-Za-z0-9._~:-]{1,128}")

internal fun isOperationKey(key: String): Boolean = OPERATION_KEY.matches(key)
