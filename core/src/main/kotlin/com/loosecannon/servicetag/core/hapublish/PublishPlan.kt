package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.AssetId
import java.security.MessageDigest

/** #113/#114 (C25a) — the two controls a plan is made for: the ones that commit a fresh validation. */
enum class PublishAction { ENABLE, CHANGE_TARGET }

/**
 * #113/#114 (C25a, I34) — a plan's identity: lowercase hex SHA-256 over canonical lines, the merge fingerprint's
 * precedent. It covers the asset, the kind, the action, the target, the boundary helper, the connection's id **and**
 * its `configGeneration` (Forget and a new connection start the counter again), the binding's revision (0 when none)
 * and each helper's **shape** — its domain, its options sorted, its date and time flags — never its state: a value
 * Home Assistant changed is repaired by publication, never a stale plan.
 */
fun planIdOf(
    assetId: AssetId,
    kind: PublishKind,
    action: PublishAction,
    target: String,
    boundary: String?,
    connectionId: String,
    configGeneration: Long,
    bindingRevision: Long,
    targetShape: RemoteEntity,
    boundaryShape: RemoteEntity?,
): String {
    val lines = listOf(
        "asset|${assetId.value}",
        "kind|${kind.name}",
        "action|${action.name}",
        "target|$target",
        "boundary|${boundary ?: "-"}",
        "connection|$connectionId|$configGeneration",
        "binding|$bindingRevision",
        "targetShape|${shapeOf(targetShape)}",
        "boundaryShape|${boundaryShape?.let(::shapeOf) ?: "-"}",
    )
    val digest = MessageDigest.getInstance("SHA-256")
    lines.forEach { digest.update("$it\n".toByteArray(Charsets.UTF_8)) }
    return digest.digest().joinToString("") { b -> "%02x".format(b) }
}

/** `<domain>|<options sorted, or ->|<hasDate>|<hasTime>`; each option length-prefixed, so no option forges a line. */
private fun shapeOf(remote: RemoteEntity): String {
    val domain = remote.entityId.substringBefore('.', missingDelimiterValue = "")
    val options = remote.options?.sorted()?.joinToString(",") { "${it.length}:$it" } ?: "-"
    return "$domain|$options|${remote.hasDate}|${remote.hasTime}"
}
