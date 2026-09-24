package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.ports.StoredBytes
import java.security.MessageDigest

/**
 * The fourteen canonical tables, **in the order a merge must write them**: every reference a row makes
 * points at a table declared before it (assets first, attachment rows last, when every owner is
 * in). The ordinal is also the first key conflicts are sorted by, which is what makes a report
 * deterministic.
 *
 * The three 1.2 members sit in dependency position rather than at the end. A group's members
 * reference assets, so `GROUPS` follows `ASSETS`; a schedule references an asset or a group, a
 * meter definition and a profile, so `SCHEDULES` follows all four; a closure references only a
 * schedule, so `CLOSURES` follows it; an event may reference a schedule, and `EVENTS` already sat
 * late enough to keep its place.
 *
 * 1.3's [REFERENCES] follows [ATTACHMENTS], and any position after [ASSETS] would have been
 * correct: a reference points only at an asset, so that is the smaller diff and never the weaker
 * order.
 *
 * 1.4's three follow [REFERENCES], each in dependency position: a season activation and a condition
 * point only at an asset, and a health subject at an asset and — when it has one — a schedule, both
 * decided long before. Their other links (an event, a baseline profile, the asset's primary subject)
 * are soft and are never owners, so nothing here waits on them.
 */
enum class MergeTable {
    ASSETS, GROUPS, DEFINITIONS, PROFILES, SCHEDULES, CLOSURES, LINKS, TAGS, EVENTS, ATTACHMENTS,
    REFERENCES, SEASON_ACTIVATIONS, CONDITIONS, HEALTH_SUBJECTS,
}

/**
 * What the plan decided about one incoming row.
 *
 * There is deliberately **no `UPDATE`** (#44): an id that is already here either matches, and is a
 * no-op, or differs, and is a conflict for the owner to resolve. So the only write a 1.1.0 merge
 * can make is an insert, and no pre-existing row can be modified by one.
 *
 * `SKIPPED` is the plan declining to write a row it could otherwise have written. There are two
 * cases. An **attachment** row whose bytes are not on this phone, or one on a phone with no
 * attachment folder (1.1.0). And a **reference** row whose `(asset_id, uri)` is already held here
 * by a row under a different id that differs from it (1.3.0, D-18 C): with no `UPDATE` verdict the
 * incoming name and description could never be adopted, so a conflict there could only refuse the
 * whole archive, and a skip leaves the local row exactly as it was. And a **health subject** that
 * would drive a schedule a local non-archived subject under a different id already drives (1.4),
 * for the same reason.
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

    /**
     * `maintenance_group_member(group_id, asset_id, added_at)` is unique and something else holds
     * this window.
     *
     * An **archive-internal planner guard**, like [PROFILE_FIELD_DEFINITION_TAKEN]: `group_id` is
     * part of the key, so a collision with a destination row implies a collision on the group's own
     * id, which the `local != null` arms answer first. What is left is an archive disagreeing with
     * itself — one group carrying the same `(asset, addedAt)` twice, or two of its groups filed
     * under one id. Two phones that genuinely disagree about a group's membership disagree about
     * the **group row's content**, because members are child rows of the group aggregate, and that
     * surfaces as [CONTENT_DIFFERS] on `GROUPS` and never as this code.
     */
    GROUP_MEMBER_WINDOW_TAKEN,

    /**
     * Two membership rows claim an open window — `removed_at IS NULL` — for one `(group, asset)`.
     *
     * This guards a **use-case rule** rather than an index: a partial unique index is not
     * expressible in Room, so the planner is the only place it can be checked before a write. An
     * archive-internal guard for [GROUP_MEMBER_WINDOW_TAKEN]'s reason.
     */
    GROUP_MEMBER_ALREADY_OPEN,

    /**
     * A schedule row names **both** an asset and a group, or neither. There is no SQL `CHECK` — Room
     * cannot declare one — so the shape is a rule the readers enforce, and a row that breaks it
     * describes a schedule the domain cannot hold.
     */
    SCHEDULE_TARGET_INVALID,

    /**
     * A local closure under a different row id holds this `(schedule_id, occurrence_on)` and
     * differs — a different `closed_on`, say. Two devices closed the same round on different dates,
     * which is a disagreement for a human and never resolved by a timestamp.
     */
    CLOSURE_DIVERGED,

    /** Two rows of one archive claim one `(schedule_id, occurrence_on)`, which is unique. */
    CLOSURE_DUPLICATED_IN_ARCHIVE,

    /**
     * A local closure under a different row id already **is** this closure, field for field. It
     * rides on an `IDENTICAL`: two devices that closed the same round on the same day merge
     * cleanly, and matching on the row id alone would make this an `INSERT` that the unique index
     * then refuses at apply time.
     */
    CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW,

    /**
     * `asset_event(schedule_id, occurrence_on, asset_id)` is unique and another event holds it: two
     * devices recorded the same member's completion of the same occurrence under different event
     * ids. NULLs are distinct in SQLite, so the check is skipped — and the index inert — for every
     * event that is not a completion.
     */
    SCHEDULE_OCCURRENCE_TAKEN,

    /** Two rows of one archive claim one `asset_reference(asset_id, uri)`, which is unique. */
    REFERENCE_DUPLICATED_IN_ARCHIVE,

    /**
     * A local reference under a different row id already **is** this reference, field for field. It
     * rides on an `IDENTICAL`, exactly as [CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW] does, because
     * matching on the row id alone would make this an `INSERT` that the unique index then refuses
     * at apply time.
     *
     * "Field for field" includes `createdAt` and `updatedAt`, which two phones that each typed the
     * link themselves agree about never. So **this arm is reachable in practice only from a copied
     * or replayed archive** — a reviewer must not read it as the live two-phone case. The live
     * two-phone case is [REFERENCE_HELD_BY_A_LOCAL_ROW].
     */
    REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW,

    /**
     * A local reference under a different row id holds this `(asset_id, uri)` and differs — a
     * different name or description, or simply different timestamps. It rides on a **`SKIPPED`**,
     * and that is deliberate (D-18 C, spec §5).
     *
     * There is no `UPDATE` verdict anywhere in this planner, so the incoming `display_name` and
     * `description` could never be adopted under any rule. A `CONFLICT` here could therefore not
     * produce a better merged state than a skip: it could only refuse the **entire** archive, and
     * there is no conflict UI to resolve it with. `applicable` is `conflicts.isEmpty()`, so a skip
     * cannot block an apply, and the local row is left exactly as it was.
     *
     * This is the codebase's first *declining* second identity, and the departure is on the merits:
     * [PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG] guards an NFC payload and [CLOSURE_DIVERGED] an
     * immutable closed round, each of which a human must arbitrate, whereas two descriptions of one
     * URL are not a disagreement about anything. Two costs, accepted: the skip is visible only as a
     * count, and the same-id/different-content arm still blocks as [CONTENT_DIFFERS].
     */
    REFERENCE_HELD_BY_A_LOCAL_ROW,

    /**
     * 1.4. A local, non-archived health subject under a different row id already drives the
     * schedule this incoming non-archived subject names. It rides on a **`SKIPPED`**, for
     * [REFERENCE_HELD_BY_A_LOCAL_ROW]'s reason (D-18): a schedule drives at most one non-archived
     * subject (inv. 120), there is no `UPDATE` verdict that could hand the schedule over, and a
     * conflict could only refuse the whole archive. [MergeDecision.detail] is the local subject's id.
     *
     * An **archived** subject claims no schedule, on either side, so it never reaches this arm.
     */
    HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW,

    /**
     * 1.4. Two non-archived subjects of one archive claim one schedule, which inv. 120 forbids. A
     * `CONFLICT`: the archive disagrees with itself, and no single row of it can be chosen.
     * [MergeDecision.detail] is the id of the archive row that claimed the schedule first.
     */
    HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE,
}

/** A review hint (#44: "review hints only, never automatic identity"). It never blocks an apply. */
enum class MergeHint { SAME_MANUFACTURER_MODEL_SERIAL }

/**
 * One row's outcome. [detail] carries what it collided with — a local row's id, a taken key, a
 * child row's id, a locator — and **never** a display field. It is the *holder's* row id for every
 * "taken" reason but three, each of which reports the colliding **key** instead:
 * [MergeReason.PROFILE_FIELD_DEFINITION_TAKEN] reports the definition id of its pair, and
 * [MergeReason.GROUP_MEMBER_WINDOW_TAKEN] and [MergeReason.GROUP_MEMBER_ALREADY_OPEN] report the
 * asset id of theirs. All three share one reason, which their own docs give: the key contains the
 * parent's own id, so in the only reachable case the holder *is* the incoming row and its own id
 * would say nothing.
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
    val groups: List<MaintenanceGroup> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val schedules: List<MaintenanceSchedule> = emptyList(),
    val closures: List<OccurrenceClosure> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val events: List<AssetEvent> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val references: List<AssetReference> = emptyList(),
    val seasonActivations: List<SeasonActivation> = emptyList(),
    val conditions: List<AssetCondition> = emptyList(),
    val healthSubjects: List<HealthSubject> = emptyList(),
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
    val groups: List<MaintenanceGroup> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val schedules: List<MaintenanceSchedule> = emptyList(),
    val closures: List<OccurrenceClosure> = emptyList(),
    val events: List<AssetEvent> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val references: List<AssetReference> = emptyList(),
    val seasonActivations: List<SeasonActivation> = emptyList(),
    val conditions: List<AssetCondition> = emptyList(),
    val healthSubjects: List<HealthSubject> = emptyList(),
    val storedBytes: Map<String, StoredBytes> = emptyMap(),
    val attachmentStoreConfigured: Boolean,
)

/**
 * What a plan says — the shape the API and the MCP report, for a plan and for an apply alike.
 *
 * [applicable] is the only thing a client has to read to know whether an apply will do anything:
 * it is true exactly when [conflicts] is empty. [duplicateCandidates] never makes it false, and
 * neither does a `SKIPPED` row — an attachment whose bytes are absent, a reference whose
 * `(asset_id, uri)` a diverged local row already holds (D-18 C), or a health subject whose schedule
 * a local subject already drives.
 */
data class MergeReport(
    val formatVersion: Int,
    val backupSetId: String,
    val applicable: Boolean,
    val assets: MergeTally,
    val groups: MergeTally,
    val definitions: MergeTally,
    val profiles: MergeTally,
    val schedules: MergeTally,
    val closures: MergeTally,
    val links: MergeTally,
    val tags: MergeTally,
    val events: MergeTally,
    val attachments: MergeTally,
    val references: MergeTally,
    val seasonActivations: MergeTally,
    val conditions: MergeTally,
    val healthSubjects: MergeTally,
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
        groups = tally(MergeTable.GROUPS),
        definitions = tally(MergeTable.DEFINITIONS),
        profiles = tally(MergeTable.PROFILES),
        schedules = tally(MergeTable.SCHEDULES),
        closures = tally(MergeTable.CLOSURES),
        links = tally(MergeTable.LINKS),
        tags = tally(MergeTable.TAGS),
        events = tally(MergeTable.EVENTS),
        attachments = tally(MergeTable.ATTACHMENTS),
        references = tally(MergeTable.REFERENCES),
        seasonActivations = tally(MergeTable.SEASON_ACTIVATIONS),
        conditions = tally(MergeTable.CONDITIONS),
        healthSubjects = tally(MergeTable.HEALTH_SUBJECTS),
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
