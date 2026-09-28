package com.loosecannon.servicetag.core.model

/** #77 (C6; R77-3, R77-12) — what one transfer record says happened to an asset here. */
enum class TransferKind {
    /** The asset left this installation in a Transfer Pack ([TransferRecord.packId]). */
    OUT,

    /** The asset arrived here in a Transfer Pack ([TransferRecord.packId]) — imported, or returned. */
    IN,

    /** An explicit, phone-only withdrawal of the OUT of [TransferRecord.packId] (R77-5). */
    WITHDRAWN,
}

/**
 * #77 (C6; R77-3, R77-12) — one **append-only** fact about an asset's custody: never updated and never
 * deleted (only a Replace restore wipes the table, and reloads it from the archive). [assetId] is a soft id
 * with no foreign key, so the records outlive `DeleteAsset` (R77-4) and are what every later ordinary
 * backup carries in place of a transferred graph.
 *
 * - [packId]: for OUT and IN, the Transfer Pack; for WITHDRAWN, the pack of the OUT it withdraws.
 * - [lineage]: the pack ids the asset travelled in **before** [packId], oldest first; `[]` at its origin.
 * - [packSha256]: the sealed pack file's sha256 (64 lowercase hex).
 * - [nameSnapshot]: the asset's name when the record was made, for a history line that outlives the row.
 * - [note]: the pack's one optional note (R77-15), one line of at most 200 characters.
 */
data class TransferRecord(
    val id: String,
    val assetId: AssetId,
    val kind: TransferKind,
    val packId: String,
    val lineage: List<String>,
    val at: Long,
    val packSha256: String,
    val nameSnapshot: String,
    val note: String,
)

// The rules, pure and in one place (C6; R77-12, R77-B2a-MJ1). Every rule is **per asset**: one pack carries
// several assets, and a record speaks only for its own. An OUT(q) is open unless an IN here lists q in its
// **lineage** — the pack ids the asset travelled in before the IN's own pack, so it came back — or an IN here
// **is** q — the recipient's "q arrived here", which, where the sender's OUT(q) meets it (a merge between the two
// ends of the transfer), cancels the departure for custody — or a WITHDRAWN here names q. An IN(p) is current
// unless an OUT here lists p in its lineage: the asset arrived in p and has left again since.

/**
 * Whether [record] closes [out] — the one closing rule (R77-B2a-MJ1). For the same asset: an IN whose **lineage**
 * lists the OUT's pack (the asset came back), or an IN **of** the OUT's pack (the recipient's arrival: on the
 * recipient, a merged OUT(q) must never hold the asset it actually has; on the sender, M1 refuses that IN loudly
 * as one that would close an OUT open here); or a WITHDRAWN naming it. A return, though, is lineage-only:
 * [returnsHere] never counts a same-pack IN.
 */
fun closes(record: TransferRecord, out: TransferRecord): Boolean =
    out.kind == TransferKind.OUT && record.assetId == out.assetId && when (record.kind) {
        TransferKind.IN -> out.packId in record.lineage || record.packId == out.packId
        TransferKind.WITHDRAWN -> record.packId == out.packId
        TransferKind.OUT -> false
    }

private fun TransferRecord.closedBy(records: List<TransferRecord>): Boolean = records.any { closes(it, this) }

/** The open OUTs among [records]: the ones no IN of its asset lists in its lineage and no WITHDRAWN names. */
fun openOuts(records: List<TransferRecord>): List<TransferRecord> =
    records.filter { it.kind == TransferKind.OUT && !it.closedBy(records) }

/** The assets this installation has transferred out and not had back: every asset with an open OUT (C6). */
fun heldIds(records: List<TransferRecord>): Set<AssetId> = openOuts(records).mapTo(LinkedHashSet()) { it.assetId }

/**
 * The pack ids [asset] travelled in to reach this installation, the current IN's included; `[]` if none (C6):
 * the current IN's lineage and then its own pack. With several current INs — two installations imported
 * different packs of one asset and merged — the latest `at` wins, then the larger id (rm-12).
 */
fun lineageFor(records: List<TransferRecord>, asset: AssetId): List<String> {
    val current = records
        .filter { r ->
            r.kind == TransferKind.IN && r.assetId == asset &&
                records.none { it.kind == TransferKind.OUT && it.assetId == asset && r.packId in it.lineage }
        }
        .maxWithOrNull(compareBy<TransferRecord>({ it.at }, { it.id }))
        ?: return emptyList()
    return current.lineage + current.packId
}

/**
 * Whether a pack whose [lineage] for [asset] is this one brings it back here (R77-13, C15, rm-8): it names an
 * OUT of [asset] here that no IN has closed — an open OUT, or one withdrawn here, so a mistaken withdrawal
 * never strands a legitimate return. An OUT an IN already closed is history: naming it again is stale. The
 * acceptance is **lineage-only**: an IN of the OUT's own pack (the recipient's arrival) is never a return.
 */
fun returnsHere(records: List<TransferRecord>, asset: AssetId, lineage: List<String>): Boolean =
    records.any { out ->
        out.kind == TransferKind.OUT && out.assetId == asset && out.packId in lineage &&
            records.none { it.kind == TransferKind.IN && closes(it, out) }
    }

/** A pack's short id, as its file name and every sentence show it (P77-34, P77-54, P77-63): its first 8 characters. */
fun shortPackId(packId: String): String = packId.take(SHORT_PACK_ID)

private const val SHORT_PACK_ID = 8
