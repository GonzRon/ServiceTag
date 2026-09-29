package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.storedBytesOf
import com.loosecannon.servicetag.core.model.AssetId
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
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Decides what merging one archive into this install would do, and **writes nothing** (1.1.0, #46;
 * semantics from #44).
 *
 * Four steps and no more: decode — which refuses a corrupt or future-format file before anything
 * else happens — ask whether there is an attachment folder at all, hash and size whatever that
 * folder holds for the locators the archive names, and read the twenty canonical tables in one
 * `uow.read` so the planner sees a single consistent point in time rather than twenty. The decision
 * itself is `mergePlanOf`, a pure function.
 *
 * The same twenty-two collaborators, in the same order, as [ImportBackupReplace] — because the two are
 * the two halves of the same question, and a reader comparing them should have nothing to subtract.
 * #74's [categories] is read like the rest: the planner decides the archive's category rows against
 * it and plans the rows its accepted assets need; and so are #79's [serviceCases] and [caseEntries], and
 * #72's [loans], #77's [transfers] and #86's [successions].
 */
class BuildBackupMergePlan(
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
    /** #74 — the owner's own categories, the fifteenth table the snapshot reads. */
    private val categories: CategoryRepository,
    /** #79 — the case headers and their timelines (format 12). */
    private val serviceCases: ServiceCaseRepository,
    private val caseEntries: ServiceCaseEntryRepository,
    /** #72 — the loans (format 13). */
    private val loans: AssetLoanRepository,
    /** #77 — the transfer records (format 14). */
    private val transfers: TransferRecordRepository,
    /** #86 — the successions (format 15). */
    private val successions: AssetSuccessionRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
) {
    suspend fun run(bytes: ByteArray): MergePlan = run(BackupCodec.decode(bytes), emptyMap(), emptySet())

    /**
     * #77 (C14, C15) — a Transfer Pack's plan. [incoming] is the pack's own documents (locator → sha256 and size),
     * overlaid **only on locators absent from the folder**, so bytes the folder already holds are never masked — a
     * locator holding other bytes stays `ATTACHMENT_BYTES_DIFFER`. [returning] are the assets the pack brings back:
     * the plan runs on the snapshot without their stale local graph ([ReturnScope]). Both empty is [run] above.
     */
    suspend fun run(backup: Backup, incoming: Map<String, StoredBytes>, returning: Set<AssetId>): MergePlan {
        // Both store questions before the transaction: `store()` resolves a preference and a grant,
        // and `open` is a document-provider round trip. Neither belongs inside a Room transaction.
        // And `store()` is asked exactly once, so "is there a folder?" and "what is in it?" cannot
        // answer about two different states of the store.
        val store = storage.store()
        val configured = store != null
        val stored = storedBytesOf(backup, store)
        val overlay = incoming.filterKeys { locator -> locator !in stored && store?.exists(locator) == false }
        val snapshot = uow.read {
            mergeSnapshotOf(
                assets, groups, tags, links, definitions, profiles, schedules, closures,
                events, attachments, references, seasonActivations, conditions, healthSubjects,
                categories, serviceCases, caseEntries, loans, transfers, successions, stored + overlay, configured,
            )
        }
        return mergePlanOf(backup, ReturnScope.of(snapshot, returning).snapshot, returning)
    }
}
