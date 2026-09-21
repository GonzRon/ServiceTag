package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.ports.StoredBytes
import java.security.MessageDigest

/**
 * The seven canonical tables, **in the order a merge must write them**: every reference a row makes
 * points at a table declared before it (assets first, attachment rows last, when every owner is
 * in). The ordinal is also the first key conflicts are sorted by, which is what makes a report
 * deterministic.
 */
enum class MergeTable { ASSETS, DEFINITIONS, PROFILES, LINKS, TAGS, EVENTS, ATTACHMENTS }

/**
 * What the plan decided about one incoming row.
 *
 * There is deliberately **no `UPDATE`** (#44): an id that is already here either matches, and is a
 * no-op, or differs, and is a conflict for the owner to resolve. So the only write a 1.1.0 merge
 * can make is an insert, and no pre-existing row can be modified by one.
 *
 * `SKIPPED` is the plan declining to write a row it could otherwise have written — in 1.1.0, an
 * attachment row whose bytes are not on this phone, or one on a phone with no attachment folder.
 */
enum class MergeVerdict { INSERT, IDENTICAL, CONFLICT, SKIPPED }

/**
 * A stable code per outcome kind, so a client branches on the code and never on prose. Every
 * `CONFLICT` and every `SKIPPED` carries one; `INSERT` carries [NONE], and so does an `IDENTICAL`
 * that matched on its own id.
 */
enum class MergeReason {
    NONE,

    /** The id is already here and some backup-format field differs. Never resolved automatically. */
    CONTENT_DIFFERS,

    /** A local tag holds this payload identity and binds a different asset (#44 case 3). */
    PAYLOAD_BOUND_TO_ANOTHER_ASSET,

    /** A local tag holds this payload identity, binds the same asset, and differs elsewhere. */
    PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG,

    /** A local tag under a different row id already is this tag, field for field (#44 case 1). */
    PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG,

    /** Two rows claim one payload identity — `nfc_tag(payload_format, payload_key)` is unique. */
    PAYLOAD_DUPLICATED_IN_ARCHIVE,

    /** `measurement_definition(asset_id, key)` is unique and something else holds this one. */
    DEFINITION_KEY_TAKEN,

    /** `asset_event(source, source_ref)` is unique and something else holds this one. */
    EVENT_SOURCE_REF_TAKEN,

    /**
     * `attachment(storage_provider, storage_locator)` is unique and another row claims it.
     *
     * A **planner guard** in 1.1.0: the codec requires a locator whose shape embeds the row's own
     * id (`BackupCodec.kt:392`, `model/Attachment.kt:78`–`80`), so through the API a locator
     * collision implies an id collision and is answered as `CONTENT_DIFFERS` or `IDENTICAL` first.
     * Reachable from a hand-built archive, and live the moment slice B remaps owners.
     */
    ATTACHMENT_LOCATOR_TAKEN,

    /**
     * `profile_field(profile_id, definition_id)` is unique and this profile offers one reading
     * twice — the fifth unique index, which `BackupCodec.decode` does not check.
     *
     * The one reason whose [MergeDecision.detail] is **not** a row id: it is the definition id the
     * pair names, because in the only live case the holder is the incoming profile itself and its
     * own id would say nothing.
     */
    PROFILE_FIELD_DEFINITION_TAKEN,

    /**
     * An aggregate child row's own primary key is already held: `profile_field.id`,
     * `profile_consumable.id`, `measurement.id` or `consumable_usage.id`. Those ids are durable
     * identity (`core/model/Journal.kt:28`–`30`) and `decode` only checks them within one file.
     */
    CHILD_ROW_ID_TAKEN,

    /** A reference this row makes resolves to nothing local and to nothing this plan inserts. */
    OWNER_NOT_AVAILABLE,

    /** The parent it names sits under it once local and incoming rows are put together. */
    PARENT_CYCLE,

    /** The bytes this attachment row names are not in this phone's attachment folder. */
    ATTACHMENT_BYTES_ABSENT,

    /**
     * Bytes *are* at this locator and they are not the row's: the size or the sha256 differs. A
     * diverged file is a diverged overlap, so it is a conflict and not a skip (#44 acceptance 12).
     */
    ATTACHMENT_BYTES_DIFFER,

    /** This phone has no attachment folder, so no attachment row's bytes could be looked for. */
    ATTACHMENT_STORE_NOT_CONFIGURED,
}

/** A review hint (#44: "review hints only, never automatic identity"). It never blocks an apply. */
enum class MergeHint { SAME_MANUFACTURER_MODEL_SERIAL }

/**
 * One row's outcome. [detail] carries what it collided with — a local row's id, a taken key, a
 * child row's id, a locator — and **never** a display field. It is the *holder's* row id for every
 * "taken" reason but one: [MergeReason.PROFILE_FIELD_DEFINITION_TAKEN] reports the definition id of
 * the pair, for the reason that reason's own doc gives.
 */
data class MergeDecision(
    val table: MergeTable,
    val id: String,
    val verdict: MergeVerdict,
    val reason: MergeReason = MergeReason.NONE,
    val detail: String = "",
)

/** Two asset rows that look like the same physical thing. Reported; never acted on. */
data class DuplicateCandidate(
    val incomingAssetId: String,
    val localAssetId: String,
    val hint: MergeHint,
)

/** One table's outcome, counted. */
data class MergeTally(val insert: Int, val identical: Int, val conflict: Int, val skipped: Int)

/**
 * The rows to insert. **The field order is the write order**, and each list is ordered within
 * itself — assets parents-first, definitions ENTERED-before-DERIVED. Empty in every field when the
 * plan holds a conflict, so "no partial merge" is a property of this value rather than a discipline
 * the apply has to remember.
 */
data class MergeWrites(
    val assets: List<Asset> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val events: List<AssetEvent> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
)

/**
 * One consistent view of this install, read once.
 *
 * The last two fields are the exception: they come from the attachment *store*, not the database.
 * [storedBytes] maps each locator the archive names — and only those — to the size and sha256 of
 * what is actually there, so the planner can answer #44's *"verify size + SHA-256 before writing"*
 * without opening a file itself. [attachmentStoreConfigured] is false when the owner has picked no
 * folder at all, which is a different fact from "the bytes are missing" and gets its own reason —
 * and it carries **no default**, so a caller cannot forget it and have a configuration state
 * reported as absent bytes, which is the confusion decision 12 exists to prevent.
 */
data class MergeSnapshot(
    val assets: List<Asset> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val events: List<AssetEvent> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val storedBytes: Map<String, StoredBytes> = emptyMap(),
    val attachmentStoreConfigured: Boolean,
)

/**
 * What a plan says — the shape the API and the MCP report, for a plan and for an apply alike.
 *
 * [applicable] is the only thing a client has to read to know whether an apply will do anything:
 * it is true exactly when [conflicts] is empty. [duplicateCandidates] never makes it false, and
 * neither does a `SKIPPED` attachment row.
 */
data class MergeReport(
    val formatVersion: Int,
    val backupSetId: String,
    val applicable: Boolean,
    val assets: MergeTally,
    val definitions: MergeTally,
    val profiles: MergeTally,
    val links: MergeTally,
    val tags: MergeTally,
    val events: MergeTally,
    val attachments: MergeTally,
    /** Deterministic: table order, then id. */
    val conflicts: List<MergeDecision>,
    val duplicateCandidates: List<DuplicateCandidate>,
)

/**
 * A decision about every row in one archive, against one snapshot of this install — and nothing
 * else. Building it writes nothing; [MergeWrites] is what an apply would do, not what it has done.
 *
 * The constructor is `internal` because only [mergePlanOf] may make one: a plan that was not
 * produced by the planner is a plan nobody checked.
 */
class MergePlan internal constructor(
    /** The archive this was planned from, kept so an apply can re-plan against a fresh snapshot. */
    val backup: Backup,
    /** Every row's outcome, sorted by table order then id. */
    val decisions: List<MergeDecision>,
    val writes: MergeWrites,
    val duplicateCandidates: List<DuplicateCandidate>,
) {
    val conflicts: List<MergeDecision> = decisions.filter { it.verdict == MergeVerdict.CONFLICT }

    /** Whether an apply would write the union. One conflict anywhere makes this false. */
    val applicable: Boolean get() = conflicts.isEmpty()

    /**
     * A digest of what this plan *decided*, so an apply can tell "the same answer" from "a
     * different answer" without re-deriving it.
     *
     * It covers the archive's identity — `backupSetId` and the `dataSha256` of its `data.json` —
     * and then every decision's table, id, verdict, reason and detail. So a change to any local row
     * the plan looked at moves the verdict or the reason, and moves this. It deliberately does
     * **not** cover [duplicateCandidates]: a destination change that alters only the review hints
     * is not named stale, which is harmless because hints never affect [applicable] and nothing is
     * written differently for them.
     *
     * It is **not** the safety mechanism: rebuilding the plan inside the apply's transaction is
     * ([com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan]). This is what lets a stale
     * plan be *named* stale instead of quietly producing a different report.
     */
    val fingerprint: String = fingerprintOf(backup, decisions)

    fun tally(table: MergeTable): MergeTally {
        val rows = decisions.filter { it.table == table }
        return MergeTally(
            insert = rows.count { it.verdict == MergeVerdict.INSERT },
            identical = rows.count { it.verdict == MergeVerdict.IDENTICAL },
            conflict = rows.count { it.verdict == MergeVerdict.CONFLICT },
            skipped = rows.count { it.verdict == MergeVerdict.SKIPPED },
        )
    }

    fun report(): MergeReport = MergeReport(
        formatVersion = backup.manifest.formatVersion,
        backupSetId = backup.manifest.backupSetId,
        applicable = applicable,
        assets = tally(MergeTable.ASSETS),
        definitions = tally(MergeTable.DEFINITIONS),
        profiles = tally(MergeTable.PROFILES),
        links = tally(MergeTable.LINKS),
        tags = tally(MergeTable.TAGS),
        events = tally(MergeTable.EVENTS),
        attachments = tally(MergeTable.ATTACHMENTS),
        conflicts = conflicts,
        duplicateCandidates = duplicateCandidates,
    )
}

private fun fingerprintOf(backup: Backup, decisions: List<MergeDecision>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(backup.manifest.backupSetId.toByteArray(Charsets.UTF_8))
    digest.update(backup.manifest.dataSha256.toByteArray(Charsets.UTF_8))
    for (d in decisions) {
        digest.update(
            "${d.table.name}|${d.id}|${d.verdict.name}|${d.reason.name}|${d.detail}\n"
                .toByteArray(Charsets.UTF_8),
        )
    }
    return digest.digest().joinToString("") { b -> "%02x".format(b) }
}
