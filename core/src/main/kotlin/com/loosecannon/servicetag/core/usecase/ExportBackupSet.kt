package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.ArtifactsPlan
import com.loosecannon.servicetag.core.backup.ArtifactsPlanEntry
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.ports.AssetRepository
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
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

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
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val appVersion: String,
    private val schemaVersion: Int,
) {
    suspend fun run(): BackupSet {
        val backupSetId = ids.newId()
        val createdAt = clock.nowMillis()
        val (data, rows) = uow.read {
            val rows = attachments.all()
            BackupData(
                assets = assets.all().map { it.toDto() },
                nfcTags = tags.all().map { it.toDto() },
                externalLinks = links.all().map { it.toDto() },
                measurementDefinitions = definitions.all().map { it.toDto() },
                eventProfiles = profiles.all().map { it.toDto() },
                assetEvents = events.all().map { it.toDto() },
                attachments = rows.map { it.toDto() },
                // Schema 8's other two tables are deliberately not read here: the first is
                // derived and is rebuilt after any import, the second is device-local delivery
                // bookkeeping. Neither has a port on this use case that could reach it.
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
            ) to rows
        }
        val plan = ArtifactsPlan(
            backupSetId = backupSetId,
            dataFormatVersion = BackupCodec.FORMAT_VERSION,
            createdAt = createdAt,
            entries = rows
                .filter { it.mode == AttachmentMode.MANAGED }
                .sortedBy { it.id.value }
                .map { it.planEntry() },
        )
        return BackupSet(
            data = BackupCodec.encode(data, appVersion, schemaVersion, createdAt, backupSetId),
            plan = plan,
        )
    }

    private fun Attachment.planEntry() = ArtifactsPlanEntry(
        attachmentId = id,
        entryName = ArtifactsCodec.entryName(id, storageLocator),
        locator = storageLocator,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
    )
}
