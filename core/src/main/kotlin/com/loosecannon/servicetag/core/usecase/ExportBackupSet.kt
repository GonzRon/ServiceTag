package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.ArtifactsPlan
import com.loosecannon.servicetag.core.backup.ArtifactsPlanEntry
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.TransferredGraphEntangled
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.transfer.TransferRetention

/** The data archive's bytes, and the plan for the second archive that goes with it. */
data class BackupSet(val data: ByteArray, val plan: ArtifactsPlan) {
    // A ByteArray in a data class: equals/hashCode are identity, which is what callers want here
    // (nobody compares two backup sets) but is worth saying out loud.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * One read transaction over every canonical table, a set id minted once, and two outputs: the
 * data archive's bytes (small, held whole, as before) and a plan the app streams into the
 * artifacts archive. `:core` never opens a store here — it does not know where the bytes are.
 *
 * With zero attachments the plan is empty, and the app still writes the artifacts archive: a set
 * is always two files (spec §7.3).
 *
 * **#77 (C9): never a transferred graph.** The archive is `TransferGraph.retain` of the snapshot with the
 * assets the records hold — their rows gone, every record kept — and the artifacts plan is built from that
 * retained data's MANAGED rows only (MJ-1). An archived asset that is not held leaves as it always did.
 *
 * **#86 (C5, C6): a succession naming a held asset at either end never leaves** — `retain` drops it, as it drops a
 * loan, and never answers `Entangled` for one — so the archive still decodes on the next restore.
 */
class ExportBackupSet(
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
    /** #74 — the owner's own categories (format 9). The compiled built-ins are never rows. */
    private val categories: CategoryRepository,
    /** #79 — the service case aggregate (format 12): the headers, then their timelines. */
    private val serviceCases: ServiceCaseRepository,
    private val caseEntries: ServiceCaseEntryRepository,
    /** #72 — the loans (format 13): open and returned alike, the contact link beside the name. */
    private val loans: AssetLoanRepository,
    /** #77 — the transfer records (format 14): every record, and never the graph of an asset they hold. */
    private val transfers: TransferRecordRepository,
    /** #86 — the successions (format 15): every row but those naming an asset held here (`retain` drops them). */
    private val successions: AssetSuccessionRepository,
    /** #15 — the SupplyItems with their specifications, and their applicability (format 18). */
    private val supplyItems: SupplyItemRepository,
    private val assetSupplies: AssetSupplyRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val appVersion: String,
    private val schemaVersion: Int,
) {
    private val repos = BackupRepositories(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments, references,
        seasonActivations, conditions, healthSubjects, categories, serviceCases, caseEntries, loans, transfers,
        successions, supplyItems, assetSupplies,
    )

    /**
     * @throws TransferredGraphEntangled when a row that stays names a row of a held asset (P77-58): no byte
     *   exists yet, and none is made.
     */
    suspend fun run(): BackupSet {
        val backupSetId = ids.newId()
        val createdAt = clock.nowMillis()
        val data = uow.read { readSnapshot(repos) }
        // #77 (C9; AC 11–13): never the graph of an asset held here — only its records. What stays must
        // still decode, so a staying row naming a held row stops the export before any byte exists.
        val held = heldIds(data.transferRecords.map { it.toDomain() })
        val kept = when (val retention = TransferGraph.retain(data, held)) {
            is TransferRetention.Retained -> retention.data
            is TransferRetention.Entangled -> throw TransferredGraphEntangled(retention.refs)
        }
        return BackupSet(
            data = BackupCodec.encode(kept, appVersion, schemaVersion, createdAt, backupSetId),
            // MJ-1: the plan from what is exported, so the two archives never name different documents.
            plan = artifactsPlanOf(kept, backupSetId, createdAt),
        )
    }
}

/**
 * The twenty-two canonical stores an archive is read from, in `ExportBackupSet`'s order — one value to hand
 * [readSnapshot] instead of twenty-two ports (#77, mn-8; #86 adds the successions, #15 the SupplyItems and their
 * applicability).
 */
class BackupRepositories(
    val assets: AssetRepository,
    val groups: GroupRepository,
    val tags: TagRepository,
    val links: LinkRepository,
    val definitions: DefinitionRepository,
    val profiles: ProfileRepository,
    val schedules: ScheduleRepository,
    val closures: ClosureRepository,
    val events: EventRepository,
    val attachments: AttachmentRepository,
    val references: ReferenceRepository,
    val seasonActivations: SeasonActivationRepository,
    val conditions: ConditionRepository,
    val healthSubjects: HealthSubjectRepository,
    val categories: CategoryRepository,
    val serviceCases: ServiceCaseRepository,
    val caseEntries: ServiceCaseEntryRepository,
    val loans: AssetLoanRepository,
    /** #77 — the transfer records (format 14). */
    val transfers: TransferRecordRepository,
    /** #86 — the successions (format 15). */
    val successions: AssetSuccessionRepository,
    /** #15 — the SupplyItems and their applicability (format 18). */
    val supplyItems: SupplyItemRepository,
    val assetSupplies: AssetSupplyRepository,
)

/**
 * Every canonical table as the archive names it (#77, C5: extracted from `ExportBackupSet` unchanged).
 * It only reads, and it opens no transaction: each caller wraps it in its own — a read for the export and
 * for creating a Transfer Pack, the write for marking one (a read nested in a write is illegal).
 */
suspend fun readSnapshot(repos: BackupRepositories): BackupData = with(repos) {
    BackupData(
        assets = assets.all().map { it.toDto() },
        nfcTags = tags.all().map { it.toDto() },
        externalLinks = links.all().map { it.toDto() },
        measurementDefinitions = definitions.all().map { it.toDto() },
        eventProfiles = profiles.all().map { it.toDto() },
        assetEvents = events.all().map { it.toDto() },
        attachments = attachments.all().map { it.toDto() },
        // Schema 8's other two tables are deliberately not read here: the first is
        // derived and is rebuilt after any import, the second is device-local delivery
        // bookkeeping. Neither has a port here that could reach it.
        maintenanceGroups = groups.all().map { it.toDto() },
        maintenanceSchedules = schedules.all().map { it.toDto() },
        occurrenceClosures = closures.all().map { it.toDto() },
        assetReferences = references.all().map { it.toDto() },
        // Format 8: two fact tables and the subjects' configuration. No health value is
        // read here, because none is stored (inv. 111).
        seasonActivations = seasonActivations.all().map { it.toDto() },
        assetConditions = conditions.all().map { it.toDto() },
        healthSubjects = healthSubjects.all().map { it.toDto() },
        // Format 9: every row, used or not — a category outlives the last Asset using it.
        assetCategories = categories.all().map { it.toDto() },
        // Format 12: every case header, then every timeline entry as its own row.
        serviceCases = serviceCases.all().map { it.toDto() },
        serviceCaseEntries = caseEntries.all().map { it.toDto() },
        // Format 13: every loan, the returned history included.
        assetLoans = loans.all().map { it.toDto() },
        // Format 14: every transfer record, OUT, IN and WITHDRAWN alike.
        transferRecords = transfers.all().map { it.toDto() },
        // Format 15: every succession; the export's `retain` drops a row naming a held asset (#86, C6).
        assetSuccessions = successions.all().map { it.toDto() },
        // Format 18: every SupplyItem, archived included, with its specifications, and every applicability row;
        // the export passes both lists to `retain`, which decides what a backup set carries (C10, C13).
        supplyItems = supplyItems.all().map { it.toDto() },
        assetSupplies = assetSupplies.all().map { it.toDto() },
    )
}

/**
 * The artifacts archive's plan for [data]: its MANAGED rows, sorted by id, built from the rows the data
 * archive carries — so the two archives of a set can never name different documents (#77, MJ-1).
 */
fun artifactsPlanOf(data: BackupData, backupSetId: String, createdAt: Long): ArtifactsPlan = ArtifactsPlan(
    backupSetId = backupSetId,
    dataFormatVersion = BackupCodec.FORMAT_VERSION,
    createdAt = createdAt,
    entries = data.attachments
        .filter { it.mode == AttachmentMode.MANAGED.name }
        .sortedBy { it.id }
        .map { it.planEntry() },
)

private fun AttachmentDto.planEntry() = ArtifactsPlanEntry(
    attachmentId = AttachmentId(id),
    entryName = ArtifactsCodec.entryName(AttachmentId(id), storageLocator),
    locator = storageLocator,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
)
