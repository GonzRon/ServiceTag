package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.TransferredOutInArchive
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.journal.AssetRow
import com.loosecannon.servicetag.core.journal.CategoryBackfill
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.returnsHere
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

data class ImportReport(
    val formatVersion: Int,
    val assets: Int,
    val tags: Int,
    val links: Int,
    val definitions: Int,
    val profiles: Int,
    val events: Int,
    val attachments: Int,
    /** What a later `RestoreArtifacts` must match; `""` for a format ≤4 file. */
    val lastRestoredBackupSetId: String,
)

/**
 * Replace import: wipe and load in one transaction. A decode failure, a newer format, or a failed
 * insert leaves the previous data exactly as it was — the decode happens before the transaction
 * opens, and everything after it rolls back together.
 *
 * The bytes of the attachment rows this replaces are swept *after* the commit, best effort: a
 * store that will not co-operate leaves an orphaned file for 4B, never a half-undone import.
 *
 * **The post-restore rebuild is total, for [ApplyBackupMergePlan]'s reasons.** Every canonical row
 * is replaced here, so every schedule's derived due state describes data that is gone: the wipe
 * takes `schedule_state` with it through the CASCADE, and [rebuildAll] fills it again from the
 * history the file brought, inside this transaction, after the last insert. Derived state is
 * rebuilt after **any** import, and a restore is the import that replaces the most.
 *
 * **The categories (#74, C12).** The archive's own category rows go in **first** — any filed under a
 * built-in's key dropped, never refused (a built-in added by a later release must not make an older
 * archive unrestorable) — and then **every** restored asset is promoted in this same transaction by
 * [CategoryBackfill.plan], the one chooser: a built-in's key takes the label, a key the archive's
 * rows hold takes that row's display, and any other key gets a new row spelled as its oldest asset
 * spells it. Each asset is written in that canonical spelling **with its own `updatedAt`** — a restore
 * is not an owner's edit. So a format-9 archive whose rows are complete adds nothing, and a format
 * ≤8 archive, or one whose assets name a category it does not carry, gets the rows it needs: a
 * restore always leaves every non-blank asset category in the catalog.
 *
 * **The transfer records (#77, C9; R77-13).** Wiped by name and reloaded from the archive, last. Before the
 * wipe, in the same write, the archive is compared with this phone's open OUTs: one carrying the graph of an
 * asset held here is refused with [TransferredOutInArchive] — nothing wiped — unless its own records hold an
 * IN whose lineage names this phone's OUT of that asset (open, or withdrawn by mistake: rm-8).
 */
class ImportBackupReplace(
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val schedules: ScheduleRepository,
    private val closures: ClosureRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val references: ReferenceRepository,
    /** The three 1.4 stores — manual season activations, conditions and health subjects. */
    private val seasonActivations: SeasonActivationRepository,
    private val conditions: ConditionRepository,
    private val healthSubjects: HealthSubjectRepository,
    /** #74 — the owner's own categories: wiped, restored first, completed by the promotion. */
    private val categories: CategoryRepository,
    /** #79 — the service case aggregate (format 12): the headers, then their timelines. */
    private val serviceCases: ServiceCaseRepository,
    private val caseEntries: ServiceCaseEntryRepository,
    /** #72 — the loans (format 13): wiped by name, restored after their assets. */
    private val loans: AssetLoanRepository,
    /** #77 — the transfer records (format 14): wiped and reloaded; a held graph is refused first (R77-13). */
    private val transfers: TransferRecordRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    /**
     * Recomputes the derived state of **every** schedule. It runs inside this restore's
     * transaction, after the last insert, exactly once — a seam rather than a direct call for the
     * reason [ApplyBackupMergePlan] states: the derived state is never in the file, and the engine
     * that computes it is not this layer's concern.
     */
    private val rebuildAll: suspend () -> Unit,
) {
    suspend fun run(bytes: ByteArray): ImportReport {
        val backup = BackupCodec.decode(bytes) // outside the transaction: refuse before touching data
        val data = backup.data

        // The categories this restore lands, decided from the file alone — pure, so outside the
        // transaction like the decode. Built-in-keyed rows are dropped; the rest win for their keys.
        val restoredCategories = data.assetCategories.map { it.toDomain() }
            .filter { CategoryCatalog.builtIn(it.key) == null }
        val restoredAssets = data.assets.map { it.toDomain() }
        val promotion = CategoryBackfill.plan(
            restoredAssets.map { AssetRow(it.id, it.category, it.createdAt) },
            existing = restoredCategories,
        )
        // Canonical spellings only; `updatedAt` stays the file's.
        val canonicalAssets = restoredAssets.map { asset ->
            promotion.rewrites[asset.id]?.let { asset.copy(category = it) } ?: asset
        }

        // #77 (R77-13): the archive's own INs, by asset — what may legitimately bring a held asset back.
        val incomingIns = data.transferRecords.map { it.toDomain() }.filter { it.kind == TransferKind.IN }.groupBy { it.assetId }
        val archivedAssetIds = data.assets.map { AssetId(it.id) }.toSet()

        val orphaned = uow.write {
            // R77-13, before anything is wiped and in the same write: an archive carrying the graph of an asset
            // this phone holds as transferred out is refused — a stale pre-transfer backup never silently brings
            // it back — unless the archive's own records carry an IN whose lineage names this phone's OUT of it
            // (C15's closing rule, rm-8 included): the Replace restore from the installation it returned to.
            val local = transfers.all()
            val resurrected = heldIds(local)
                .filter { it in archivedAssetIds }
                .filterNot { asset -> incomingIns[asset].orEmpty().any { returnsHere(local, asset, it.lineage) } }
                .sortedBy { it.value }
            if (resurrected.isNotEmpty()) throw TransferredOutInArchive(resurrected)

            // The bytes of everything about to be replaced, read before the wipe.
            val doomed = attachments.all().map { it.storageLocator }

            // delete in the order that clears references before the rows they point at.
            // The closure table has no delete of its own — the row is immutable — so it is
            // cleared the only way it ever leaves: the CASCADE from the schedule row, which
            // `schedules.deleteAll()` below takes with it, along with the provider rows and the two
            // unexported tables. Groups follow, taking their members.
            attachments.deleteAll()
            events.deleteAll()
            schedules.deleteAll()
            groups.deleteAll()
            profiles.deleteAll()
            definitions.deleteAll()
            tags.deleteAll()
            links.deleteAll()
            // References point only at assets, so they clear just before them.
            references.deleteAll()
            // #79's case aggregate would go with its assets by the CASCADE too; it is wiped by name, the
            // timeline before the headers it points at, so no reader of this list has to know that.
            caseEntries.deleteAll()
            serviceCases.deleteAll()
            // #72's loans likewise: the CASCADE would take them with their assets, and they are wiped by
            // name so that no reader of this list has to know it.
            loans.deleteAll()
            // #77's records have no foreign key, so nothing would take them: wiped by name, reloaded last.
            transfers.deleteAll()
            // The three 1.4 tables need no line: activations and conditions point only at an
            // asset and subjects at an asset or a schedule, all ON DELETE CASCADE, and neither fact
            // table has a delete of its own because its rows are immutable.
            assets.deleteAll()
            // Nothing points at a category and a category points at nothing, so its place in the
            // wipe is free; it goes with the assets it classified.
            categories.deleteAll()

            // insert in reference order so foreign keys are satisfied at every step. Assets go
            // in parents-first order (AssetTree.parentsFirst) regardless of the file's own list
            // order, so a self-referencing parent_asset_id FK resolves at insert time even for a
            // shuffled file. Within measurementDefinitions, ENTERED rows go first and DERIVED
            // rows after, so a DERIVED definition's source_a_id/source_b_id foreign keys
            // (schema v3) resolve at insert time regardless of the file's own id ordering.
            // The archive's categories first (#74, C12), then the assets in their canonical
            // spellings, then the rows the promotion adds for the keys the archive did not carry.
            restoredCategories.forEach { categories.upsert(it) }
            AssetTree.parentsFirst(canonicalAssets).forEach { assets.upsert(it) }
            promotion.newRows.forEach { categories.upsert(it) }
            // Straight after the assets: `asset_id` is a reference's only foreign key, so this is
            // the earliest point at which every one of them resolves.
            data.assetReferences.forEach { references.upsert(it.toDomain()) }
            // Groups before schedules, and both before events: a group's members name assets, a
            // schedule names an asset or a group plus a meter definition and a profile, a closure
            // names a schedule, and an event may name one too. This is `MergeTable`'s order.
            data.maintenanceGroups.forEach { groups.upsert(it.toDomain()) }
            val (entered, derived) = data.measurementDefinitions.partition { it.kind == DefinitionKind.ENTERED.name }
            entered.forEach { definitions.upsert(it.toDomain()) }
            derived.forEach { definitions.upsert(it.toDomain()) }
            data.eventProfiles.forEach { profiles.upsert(it.toDomain()) }
            data.maintenanceSchedules.forEach { schedules.upsert(it.toDomain()) }
            data.occurrenceClosures.forEach { closures.insert(it.toDomain()) }
            // After assets and schedules, the two things they point at. `eventId` is a soft link,
            // so the facts need no event to be in first.
            data.seasonActivations.forEach { seasonActivations.insert(it.toDomain()) }
            data.assetConditions.forEach { conditions.insert(it.toDomain()) }
            data.healthSubjects.forEach { healthSubjects.upsert(it.toDomain()) }
            data.externalLinks.forEach { links.upsert(it.toDomain()) }
            data.nfcTags.forEach { tags.upsert(it.toDomain()) }
            data.assetEvents.forEach { events.upsert(it.toDomain()) }
            // #79: the case headers after their assets — and after the events, which they name only
            // softly — and each timeline after its case.
            data.serviceCases.forEach { serviceCases.upsert(it.toDomain()) }
            data.serviceCaseEntries.forEach { caseEntries.insert(it.toDomain()) }
            // #72: the loans after their assets. The graph check held the file to one open loan per
            // asset, so the schema's unique index has nothing to refuse here.
            data.assetLoans.forEach { loans.upsert(it.toDomain()) }
            // Attachment rows go last: every owner, asset or event, is already in.
            data.attachments.forEach { attachments.upsert(it.toDomain()) }
            // #77: the transfer records after every row, as a merge appends them — the archive's own records,
            // which the codec proved hold none of the assets it lands.
            data.transferRecords.forEach { transfers.append(it.toDomain()) }

            // After every write, inside the same transaction, once.
            rebuildAll()

            doomed - data.attachments.map { it.storageLocator }.toSet()
        }

        // After the commit, best effort: a file the store will not delete is an orphan for 4B.
        storage.sweepBytes(orphaned)

        return ImportReport(
            formatVersion = backup.manifest.formatVersion,
            assets = data.assets.size,
            tags = data.nfcTags.size,
            links = data.externalLinks.size,
            definitions = data.measurementDefinitions.size,
            profiles = data.eventProfiles.size,
            events = data.assetEvents.size,
            attachments = data.attachments.size,
            lastRestoredBackupSetId = backup.manifest.backupSetId,
        )
    }
}
