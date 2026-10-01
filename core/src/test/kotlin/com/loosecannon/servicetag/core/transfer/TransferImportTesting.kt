package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.lineageFor
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.BackupRepositories
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.CreateTransferPack
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.core.usecase.CreatedPack
import com.loosecannon.servicetag.core.usecase.ImportTransferPack
import com.loosecannon.servicetag.core.usecase.MarkTransferredOut
import com.loosecannon.servicetag.core.usecase.MarkTransferredOutResult
import com.loosecannon.servicetag.core.usecase.TransferImportPreview
import com.loosecannon.servicetag.core.usecase.TransferImportResult
import com.loosecannon.servicetag.core.usecase.readSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.assertIs

/** A sealed Transfer Pack: the file's bytes, and what the ready screen hands marking. */
internal class SealedPack(val bytes: ByteArray, val created: CreatedPack)

/**
 * #77 (B3) — one installation as `AppGraph` builds it for a Transfer Pack: every asset-owned port behind the
 * write guard (C12), the merge plan and apply over the guarded ports, and the import over them. The raw in-memory
 * stores reproduce the schema's cascades from `asset` and `maintenance_group` (the Room tests prove the real ones),
 * so a return's scoped replace leaves exactly what the database would.
 */
internal class TransferInstall(
    setId: String = "set-install",
    private val now: Long = IMPORT_NOW,
    /** The folder the merge and the import see; a test wraps the raw one to rig a failure. */
    storageOf: (AttachmentStorage) -> AttachmentStorage = { it },
) {
    val raw = BackupInstall(setId)
    val storage: AttachmentStorage = storageOf(raw.storage)
    private val guard = HeldWriteGuard(
        raw.transfers, raw.events, raw.definitions, raw.profiles, raw.groups, raw.schedules, raw.serviceCases, raw.links,
    )
    val assets = guard.assets(raw.assets)
    val tags = guard.tags(raw.tags)
    val definitions = guard.definitions(raw.definitions)
    val profiles = guard.profiles(raw.profiles)
    val events = guard.events(raw.events)
    val groups = guard.groups(raw.groups)
    val schedules = guard.schedules(raw.schedules)
    val closures = guard.closures(raw.closures)
    val attachments = guard.attachments(raw.attachments)
    val references = guard.references(raw.references)
    val activations = guard.activations(raw.activations)
    val conditions = guard.conditions(raw.conditions)
    val subjects = guard.subjects(raw.subjects)
    val cases = guard.cases(raw.serviceCases)
    val entries = guard.entries(raw.caseEntries)
    val loans = guard.loans(raw.loans)
    /** #86 (C6): the guarded port for every consumer; the merge apply takes `raw.successions` (MJ-2), as `AppGraph`. */
    val successions = guard.successions(raw.successions)
    var rebuilds = 0

    val repos = BackupRepositories(
        assets, groups, tags, raw.links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, raw.categories, cases, entries, loans, raw.transfers, successions,
        raw.supplyItems, raw.assetSupplies,
    )
    val build = BuildBackupMergePlan(
        assets, groups, tags, raw.links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, raw.categories, cases, entries, loans, raw.transfers,
        successions, raw.supplyItems, raw.assetSupplies, storage, raw.uow,
    )
    val apply = ApplyBackupMergePlan(
        assets, groups, tags, raw.links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, raw.categories, cases, entries, loans, raw.transfers,
        raw.successions, raw.supplyItems, raw.assetSupplies, storage, raw.uow,
        rebuildAll = { rebuilds += 1 },
    )
    val importer = ImportTransferPack(build, apply, raw.transfers, assets, tags, storage, raw.uow, Clock { now })

    private var recordIds = 0

    init {
        reproduceRoomCascades(raw)
    }

    /** Creates a pack of [roots] here, with this installation's lineage, and seals it with its documents' bytes. */
    suspend fun pack(packId: String, vararg roots: String, note: String = ""): SealedPack {
        val creation = CreateTransferPack(
            repos, raw.uow, IdGenerator { packId }, Clock { now - 1_000 }, appVersion = "1.4.1", schemaVersion = 14,
            lineageOf = { id -> lineageFor(raw.transfers.all(), id) },
        )
        val draft = assertIs<CreateTransferPackResult.Created>(creation.run(roots.map(::AssetId), note)).draft
        val artifacts = ByteArrayOutputStream().also { out ->
            ArtifactsCodec.write(out, draft.plan) { locator -> raw.storage.store.files[locator]?.let(::ByteArrayInputStream) }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        val written = TransferPackCodec.write(out, draft) { ByteArrayInputStream(artifacts) }
        return SealedPack(out.toByteArray(), CreatedPack.of(draft, written.packSha256))
    }

    /** Marks [pack]'s assets transferred out here (C8). */
    suspend fun mark(pack: SealedPack): MarkTransferredOutResult.Marked = assertIs(
        MarkTransferredOut(repos, raw.uow, IdGenerator { "rec-$setIdTag-${recordIds++}" }, Clock { now }, onLifecycleChanged = {})
            .run(pack.created),
    )

    private val setIdTag = setId

    suspend fun preview(bytes: ByteArray): TransferImportPreview = importer.preview { ByteArrayInputStream(bytes) }

    suspend fun ready(bytes: ByteArray): TransferImportPreview.Ready = assertIs(preview(bytes))

    /** Preview then Import, the file opened afresh for each, as the screen does. */
    suspend fun import(bytes: ByteArray, open: () -> InputStream = { ByteArrayInputStream(bytes) }): TransferImportResult =
        importer.import(ready(bytes), open)

    /** Every canonical row here, as an archive names it. */
    suspend fun data(): BackupData = raw.uow.read { readSnapshot(repos) }
}

internal const val IMPORT_NOW = 1_759_000_000_000L

/**
 * The schema's cascades (`14.json`), reproduced on the raw stores: from `asset`, its definitions, profiles, events
 * (and their documents), documents, schedules (and their closures and subjects), references, activations,
 * conditions, subjects, 2.6 links and membership rows, a tag's `asset_id` and `link_id` SET NULL — the cases and
 * loans [BackupInstall] already registers; from `maintenance_group`, its schedules and theirs.
 */
private fun reproduceRoomCascades(raw: BackupInstall) {
    fun dropSchedules(doomed: Set<String>) {
        raw.schedules.rows.keys.removeAll(doomed)
        raw.closures.rows.values.removeIf { it.scheduleId.value in doomed }
        raw.subjects.rows.values.removeIf { it.scheduleId?.value in doomed }
    }
    raw.assets.cascadesTo { id ->
        raw.definitions.rows.values.removeIf { it.assetId == id }
        raw.profiles.rows.values.removeIf { it.assetId == id }
        val events = raw.events.rows.values.filter { it.assetId == id }.map { it.id }.toSet()
        raw.events.rows.values.removeIf { it.id in events }
        raw.attachments.rows.values.removeIf { a ->
            when (val owner = a.owner) {
                is AttachmentOwner.OfAsset -> owner.assetId == id
                is AttachmentOwner.OfEvent -> owner.eventId in events
            }
        }
        dropSchedules(
            raw.schedules.rows.values.filter { (it.target as? ScheduleTarget.AssetTarget)?.assetId == id }
                .map { it.id.value }.toSet(),
        )
        raw.references.rows.values.removeIf { it.assetId == id }
        raw.activations.rows.values.removeIf { it.assetId == id }
        raw.conditions.rows.values.removeIf { it.assetId == id }
        raw.subjects.rows.values.removeIf { it.assetId == id }
        val links = raw.links.rows.values.filter { it.assetId == id }.map { it.id }.toSet()
        raw.links.rows.values.removeIf { it.id in links }
        raw.tags.rows.replaceAll { _, tag ->
            when (val target = tag.target) {
                is TagTarget.AssetTarget -> if (target.assetId == id) tag.copy(target = TagTarget.None) else tag
                is TagTarget.LinkTarget -> if (target.linkId in links) tag.copy(target = TagTarget.None) else tag
                TagTarget.None -> tag
            }
        }
        raw.groups.rows.replaceAll { _, group -> group.copy(members = group.members.filterNot { it.assetId == id }) }
    }
    raw.groups.cascadesTo { id ->
        dropSchedules(
            raw.schedules.rows.values.filter { (it.target as? ScheduleTarget.GroupTarget)?.groupId == id }
                .map { it.id.value }.toSet(),
        )
    }
}
