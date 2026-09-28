package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.closes
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.openOuts
import com.loosecannon.servicetag.core.model.shortPackId
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.OwnerLookup
import com.loosecannon.servicetag.core.transfer.OwnerRef
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.transfer.TransferOwnership
import com.loosecannon.servicetag.core.transfer.TransferRetention
import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.backup.AssetEventDto
import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.EventProfileDto
import com.loosecannon.servicetag.core.backup.MaintenanceGroupDto
import com.loosecannon.servicetag.core.backup.MaintenanceScheduleDto
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.journal.AssetRow
import com.loosecannon.servicetag.core.journal.CategoryBackfill
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStore
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
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.ports.TagRepository
import java.io.IOException
import java.security.MessageDigest

/**
 * The merge planner (1.1.0, #46; semantics from #44): a **pure function** of one decoded archive
 * and one snapshot of this install, returning what a merge would do and writing nothing.
 *
 * ### How it decides
 *
 * Table by table, in [MergeTable] order, so that every reference a row makes points at a table
 * already decided — except #74's categories, decided before everything (see below). For each
 * incoming row:
 *
 * 1. **Its own id.** Absent here → `INSERT`. Present with identical content → `IDENTICAL`. Present
 *    with any difference → `CONFLICT` / `CONTENT_DIFFERS`.
 * 2. **Its second identity**, where the table has one, and *independently of the row id* — #44's
 *    second identity rule. A local tag holding the same `(payloadFormat, payloadKey)` is the same
 *    logical tag, a local closure holding the same `(scheduleId, occurrenceOn)` is the same closed
 *    round, and a local reference holding the same `(assetId, uri)` is the same link: equivalent
 *    field for field → `IDENTICAL`; bound elsewhere or diverged → `CONFLICT`, **except for a
 *    reference, which is `SKIPPED` instead** (D-18 C — see
 *    [MergeReason.REFERENCE_HELD_BY_A_LOCAL_ROW] for why a conflict there could only refuse the
 *    whole archive). A non-archived health subject's schedule is its second identity too, and it
 *    declines the same way (1.4). Nothing is coalesced and nothing is remapped; that is #44's later
 *    slice.
 * 3. **Every uniqueness constraint the schema has** — the unique indices *and* the aggregate
 *    child-row primary keys — each checked against the destination and against the archive's own
 *    accepted rows, because a constraint does not care which side a duplicate came from. Three of
 *    the 1.2 keys contain their parent's own id, which makes their destination arm unreachable
 *    through the API and their live case an archive that disagrees with itself; each says so at its
 *    reason.
 * 3a. **A schedule's target shape**, which no SQL `CHECK` can carry: both sides set, or neither, is
 *    `SCHEDULE_TARGET_INVALID`. It is read off the DTO, because the domain cannot hold such a row.
 * 4. **Its references.** Every non-null one must resolve to a row that is either already here or
 *    an `INSERT` in this same plan; otherwise `OWNER_NOT_AVAILABLE`, which is a conflict and not an
 *    orphan. Plus, for an asset, the parent-tree cycle guard over local and incoming rows together.
 * 5. **Its bytes**, for attachment rows only: no folder → `SKIPPED`
 *    `ATTACHMENT_STORE_NOT_CONFIGURED`; nothing at the locator → `SKIPPED`
 *    `ATTACHMENT_BYTES_ABSENT`; bytes whose size or sha256 is not the row's → `CONFLICT`
 *    `ATTACHMENT_BYTES_DIFFER`.
 *
 * ### What it never does
 *
 * It never writes a row, never removes one, never chooses a winner by any single field, never
 * treats a name or a physical UID as identity, and never produces a partial write set: one conflict
 * anywhere empties [MergePlan.writes]. **One amendment (#74):** a category's identity *is* its
 * normalised name — `CategoryKey.of(display)` is the row's primary key on every phone (R74-2) — so
 * the categories table is the one place a name is identity, by design; no other table's is.
 *
 * **Canonical content is every backup-format field**, `createdAt` and the last-modified stamp
 * included, compared as `incoming == local.toDto()` in every pass and in the same direction. There
 * are four normalisations. The aggregate tables' child lists are read in `(sortOrder, id)`
 * order: `sortOrder` is the order the format writes them in (`BackupCodec.kt:85`–`96`) and the id
 * makes the key total, because the format does not promise `sortOrder` is unique within a parent.
 * And (#74, C13) an asset's `category` is read through `CategoryKey.of` on **both** sides, so a
 * pre-upgrade export's `appliance` against this phone's canonical `Appliance` is `IDENTICAL`: the
 * two are one classification, and a spelling the catalog canonicalised is not a disagreement.
 * And the third (#67, R67-12 option B): an archive older than format 10 has its attachments compared
 * **without the document role**, and — when the row here carries one — **without the last-modified
 * stamp** that giving it moved (`UpdateAttachment` stamps every save): it cannot speak about roles, so
 * a role given on this phone since that export keeps the row `IDENTICAL` and every pre-#67 export
 * keeps re-planning `IDENTICAL`. Every other field still counts, so a rename or a re-kind here is still
 * a `CONFLICT`, and a row here with no role compares its stamp as before. A format-10 archive compares
 * the role and the stamp like any field — the same role is `IDENTICAL`; a different role, a role
 * against none here, or none against one here is `CONFLICT` / `CONTENT_DIFFERS` — and there is no
 * update path: a role travels by merge only on a row the destination does not have.
 * And the fourth (#79, R79-11b, the same shape): an archive older than format 11 has its assets
 * compared **without the warranty reminder's lead**, and — when the row here carries one — **without
 * the last-modified stamp** that setting it moved (`SetWarrantyReminder` stamps every change), so a
 * lead set on this phone since that export keeps the asset `IDENTICAL` and every pre-#79 export keeps
 * re-planning `IDENTICAL`. Every other field still counts — a rename or another warranty date here is
 * still a `CONFLICT` — and a row here with no lead compares its stamp as before. A format-11 archive
 * compares the lead and the stamp like any field, with no update path.
 *
 * ### Categories (#74, C13)
 *
 * Decided **first**, listed last ([MergeTable.CATEGORIES] is appended to keep the pinned ordinals),
 * written first ([MergeWrites.categories]). An incoming row: a built-in's key → `SKIPPED`
 * `CATEGORY_IS_BUILT_IN`; its key held here with the same display → `IDENTICAL` (key and display
 * only — `createdAt` and `updatedAt` are never compared); held here under another display, or by an
 * earlier row of the same archive → `SKIPPED` `CATEGORY_KEY_HELD`, the local spelling winning; else
 * `INSERT`. Never a `CONFLICT`. Then, after the assets, **the promotions are planned**: every
 * accepted asset's key goes through `CategoryBackfill.plan` — the one chooser the migration and the
 * replace use — with this install's rows and the accepted archive rows winning their keys; a key
 * no built-in, local row or accepted row holds becomes a synthesised `INSERT` decision and a
 * [MergeWrites.categories] row, and every accepted asset is written in its canonical spelling with
 * the archive's `updatedAt`. The report, the tallies and the fingerprint all see the promotion, and
 * the apply writes [MergeWrites] and nothing else. Two costs of key-as-identity are accepted
 * (R74-2): an archive made before a new-key rename re-inserts the old key as an unused row, and a
 * deleted category comes back from an archive that holds it.
 *
 * ### Service cases (#79, C19)
 *
 * Decided last, in [MergeTable] order: a case after the assets it needs, each entry after the cases.
 * A case is an aggregate root compared by its header — IDENTICAL, CONFLICT `CONTENT_DIFFERS`,
 * `OWNER_NOT_AVAILABLE` for its asset, or INSERT, and never an UPDATE — and its Incident and repair
 * links are soft, never owners. Its entries are append-only: one held here is IDENTICAL or a CONFLICT,
 * one the phone lacks is an INSERT whose case must be here or accepted by this plan. No entry is ever
 * updated or deleted by a merge.
 *
 * ### Loans (#72, C7)
 *
 * Decided after the cases, in [MergeTable] order. A loan is its own row, identity by its id, compared on
 * every field: IDENTICAL, or CONFLICT `CONTENT_DIFFERS` — there is **no UPDATE**, so a return, a re-date,
 * a mode change, a relink or a note made on one phone after the other received the loan conflicts on
 * re-merge (R72-18). One the phone lacks is `OWNER_NOT_AVAILABLE` when its asset is neither here nor
 * inserted by this plan; `ASSET_ALREADY_LENT` when it is open and its asset holds a **different open loan
 * here**; else INSERT. Only the local side is asked about open loans: the codec's graph check already
 * holds the archive to one open loan per asset.
 *
 * ### Not total, and only for a hand-built [Backup]
 *
 * This propagates `IllegalStateException` from [AssetTree.parentsFirst] on a cyclic asset set,
 * `BackupCorrupt` from a `toDomain()` that cannot name a value, and an NPE from the attachment
 * pass's `eventId!!` if a row names neither owner. `BackupCodec.decode` refuses all three before a
 * plan exists, so no caller of `BuildBackupMergePlan` can reach them — only a test constructing a
 * `Backup` directly can. See Task 1 decision 10 for what the API maps them to.
 */
internal fun mergePlanOf(backup: Backup, snapshot: MergeSnapshot): MergePlan {
    val data = backup.data
    val decisions = mutableListOf<MergeDecision>()

    val localAssets = snapshot.assets.associateBy { it.id.value }
    val localGroups = snapshot.groups.associateBy { it.id.value }
    val localSchedules = snapshot.schedules.associateBy { it.id.value }
    val localClosures = snapshot.closures.associateBy { it.id }
    val localClosuresByPair = snapshot.closures
        .associateBy { it.scheduleId.value to it.occurrenceOn }
    val localTags = snapshot.tags.associateBy { it.id.value }
    val localTagsByPayload = snapshot.tags.associateBy { it.payloadFormat.name to it.payloadKey }
    val localLinks = snapshot.links.associateBy { it.id.value }
    val localDefinitions = snapshot.definitions.associateBy { it.id.value }
    val localProfiles = snapshot.profiles.associateBy { it.id.value }
    val localEvents = snapshot.events.associateBy { it.id.value }
    val localAttachments = snapshot.attachments.associateBy { it.id.value }
    val localReferences = snapshot.references.associateBy { it.id.value }
    // The reference's **second identity**, id-independent exactly as the closure's pair map is.
    val localReferencesByPair = snapshot.references
        .associateBy { it.assetId.value to it.uri }
    val localActivations = snapshot.seasonActivations.associateBy { it.id }
    val localConditions = snapshot.conditions.associateBy { it.id }
    val localSubjects = snapshot.healthSubjects.associateBy { it.id.value }

    // The claim maps unify the two halves of every uniqueness check: each starts with what this
    // install holds and gains what this plan accepts, so a duplicate inside the archive is caught by
    // the same line that catches a collision with a local row. Value = the id that holds the claim.
    val claimedPayloads = snapshot.tags
        .associateTo(mutableMapOf()) { (it.payloadFormat.name to it.payloadKey) to it.id.value }
    val claimedDefinitionKeys = snapshot.definitions
        .associateTo(mutableMapOf()) { (it.assetId.value to it.key) to it.id.value }
    val claimedSourceRefs = snapshot.events
        .filter { it.sourceRef != null }
        .associateTo(mutableMapOf()) { (it.source.name to it.sourceRef!!) to it.id.value }
    val claimedLocators = snapshot.attachments
        .associateTo(mutableMapOf()) { (it.storageProvider.name to it.storageLocator) to it.id.value }
    // The fifth unique index. Seeded from the destination as defence in depth: the key contains the
    // profile's own id and a merge only inserts new profile ids, so the destination arm is
    // unreachable in 1.1.0 (decision 6) — the live case is one profile listing a reading twice.
    val claimedProfileFieldPairs = snapshot.profiles
        .flatMap { p -> p.fields.map { (p.id.value to it.definitionId.value) to p.id.value } }
        .toMap(mutableMapOf())
    // The four aggregate child-row primary keys. These *are* reachable from the destination: two
    // distinct aggregates, one local and one incoming, can carry the same child id.
    val claimedFieldIds = snapshot.profiles
        .flatMap { p -> p.fields.map { it.id to p.id.value } }.toMap(mutableMapOf())
    val claimedProfileConsumableIds = snapshot.profiles
        .flatMap { p -> p.consumables.map { it.id to p.id.value } }.toMap(mutableMapOf())
    val claimedMeasurementIds = snapshot.events
        .flatMap { e -> e.measurements.map { it.id to e.id.value } }.toMap(mutableMapOf())
    val claimedUsageIds = snapshot.events
        .flatMap { e -> e.consumables.map { it.id to e.id.value } }.toMap(mutableMapOf())
    // The 1.2 constraints. The first three keys all contain their parent's own id, so — exactly as
    // `claimedProfileFieldPairs` already documents — the destination arm is unreachable through the
    // API and is seeded as defence in depth; the live case is an archive disagreeing with itself.
    val claimedMemberWindows = snapshot.groups
        .flatMap { g -> g.members.map { Triple(g.id.value, it.assetId.value, it.addedAt) to g.id.value } }
        .toMap(mutableMapOf())
    val claimedOpenWindows = snapshot.groups
        .flatMap { g -> g.members.filter { it.removedAt == null }.map { (g.id.value to it.assetId.value) to g.id.value } }
        .toMap(mutableMapOf())
    val claimedGroupMemberIds = snapshot.groups
        .flatMap { g -> g.members.map { it.id to g.id.value } }.toMap(mutableMapOf())
    val claimedProviderPairs = snapshot.schedules
        .flatMap { s -> s.providers.map { (s.id.value to it.provider) to s.id.value } }
        .toMap(mutableMapOf())
    // These two are reachable from the destination: a closure's second identity is independent of
    // its row id, and an occurrence key is shared across devices by construction.
    val claimedClosurePairs = snapshot.closures
        .associateTo(mutableMapOf()) { (it.scheduleId.value to it.occurrenceOn) to it.id }
    // Reachable from the destination for the same reason the closure pair is: a reference's second
    // identity is independent of its row id, and two phones reach the same URL by construction.
    val claimedReferencePairs = snapshot.references
        .associateTo(mutableMapOf()) { (it.assetId.value to it.uri) to it.id.value }
    // A schedule drives at most one non-archived subject (inv. 120). Seeded from the destination's
    // non-archived subjects; an archived subject, local or incoming, claims nothing.
    val localSubjectsBySchedule = snapshot.healthSubjects
        .filter { it.archivedAt == null && it.scheduleId != null }
        .associate { it.scheduleId!!.value to it.id.value }
    val claimedSubjectSchedules = localSubjectsBySchedule.toMutableMap()
    val claimedOccurrences = snapshot.events
        .filter { it.scheduleId != null && it.occurrenceOn != null }
        .associateTo(mutableMapOf()) {
            Triple(it.scheduleId!!.value, it.occurrenceOn!!, it.assetId.value) to it.id.value
        }

    // --- categories (#74, C13) -----------------------------------------------------------------
    // Decided **first**, though `CATEGORIES` is listed last: an accepted asset is written in the
    // spelling an accepted row gives its key, so the rows are settled before any asset is. Identity is
    // the key, and nothing here is ever a CONFLICT — a spelling is never worth refusing an archive.
    val localCategories = snapshot.categories.associateBy { it.key }
    val categoryWrites = mutableListOf<AssetCategory>()
    val acceptedCategoryKeys = mutableSetOf<String>()
    for (dto in data.assetCategories) {
        val key = dto.key
        val local = localCategories[key]
        decisions += when {
            CategoryCatalog.builtIn(key) != null ->
                MergeDecision(MergeTable.CATEGORIES, key, MergeVerdict.SKIPPED, MergeReason.CATEGORY_IS_BUILT_IN, key)
            // Key and display only: two phones that typed the same category agree about its
            // timestamps never, and that is not a disagreement about the category.
            local != null && local.display == dto.display ->
                MergeDecision(MergeTable.CATEGORIES, key, MergeVerdict.IDENTICAL)
            // The local spelling wins; a second row of one archive under the same key is reachable
            // only from a hand-built `Backup`, because the codec refuses a duplicate key.
            local != null || key in acceptedCategoryKeys ->
                MergeDecision(MergeTable.CATEGORIES, key, MergeVerdict.SKIPPED, MergeReason.CATEGORY_KEY_HELD, key)
            else -> {
                categoryWrites += dto.toDomain()
                acceptedCategoryKeys += key
                MergeDecision(MergeTable.CATEGORIES, key, MergeVerdict.INSERT)
            }
        }
    }

    // --- assets -----------------------------------------------------------------------------
    // Decided parents-first, so a child always sees whether its parent was accepted.
    // `parentsFirst` gives indegree 0 to an asset whose parent is outside the collection
    // (`AssetTree.kt:54`–`56`), which is exactly a parent that lives in the destination.
    val assetDtos = data.assets.associateBy { it.id }
    // R79-11b (option B): see the KDoc's canonical-content paragraph. The #74 normalisation applies
    // on every path; below format 11 the lead is set aside, and with it the stamp when a lead is here.
    val leadsCompared = backup.manifest.formatVersion >= BackupCodec.FIRST_LEAD_FORMAT
    fun sameAsset(incoming: AssetDto, here: AssetDto): Boolean = when {
        leadsCompared -> incoming.keyed() == here.keyed()
        here.warrantyReminderLeadDays == null -> incoming.copy(warrantyReminderLeadDays = null).keyed() == here.keyed()
        else -> incoming.copy(warrantyReminderLeadDays = null, updatedAt = here.updatedAt).keyed() ==
            here.copy(warrantyReminderLeadDays = null).keyed()
    }
    val assetWrites = mutableListOf<Asset>()
    val acceptedAssets = mutableSetOf<String>()
    for (row in AssetTree.parentsFirst(data.assets.map { it.toDomain() })) {
        val id = row.id.value
        val dto = assetDtos.getValue(id)
        val local = localAssets[id]
        val parent = row.parentAssetId?.value
        decisions += when {
            // The second normalisation (#74): `category` by its key, on both sides; the fourth (#79) inside.
            local != null && sameAsset(dto, local.toDto()) ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            parent != null && parent !in localAssets && parent !in acceptedAssets ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, parent)
            parent != null &&
                AssetTree.wouldCycle(localAssets.values + assetWrites, row.id, row.parentAssetId) ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.CONFLICT, MergeReason.PARENT_CYCLE, parent)
            else -> {
                assetWrites += row
                acceptedAssets += id
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- category promotions (#74, C13) ------------------------------------------------------
    // Planned here, never applied behind the plan: the one chooser (`CategoryBackfill.plan`, N6)
    // resolves every accepted asset's key — a built-in's label, then a local row's display, then an
    // accepted archive row's — and a key none of them holds becomes a synthesised INSERT, spelled by
    // C9's rule over the accepted assets. The assets are written in those canonical spellings, each
    // with the archive's own `updatedAt`.
    val promotion = CategoryBackfill.plan(
        assetWrites.map { AssetRow(it.id, it.category, it.createdAt) },
        existing = snapshot.categories + categoryWrites,
    )
    promotion.newRows.forEach { row ->
        categoryWrites += row
        decisions += MergeDecision(MergeTable.CATEGORIES, row.key, MergeVerdict.INSERT)
    }
    val canonicalAssetWrites = assetWrites.map { asset ->
        promotion.rewrites[asset.id]?.let { asset.copy(category = it) } ?: asset
    }

    /** True when a reference to an asset resolves — to a local row, or to one this plan inserts. */
    fun assetAvailable(assetId: String) = assetId in localAssets || assetId in acceptedAssets

    // --- groups -----------------------------------------------------------------------------
    // Identity is the row id and **only** the row id: a name is never identity, here or anywhere
    // else. Members travel inside the group, so they are decided with it — which is also why two
    // phones disagreeing about a group's membership is `CONTENT_DIFFERS` on the group row.
    val groupWrites = mutableListOf<MaintenanceGroup>()
    val acceptedGroups = mutableSetOf<String>()
    for (dto in data.maintenanceGroups) {
        val id = dto.id
        val local = localGroups[id]
        val missingAsset = dto.members.map { it.assetId }.firstOrNull { !assetAvailable(it) }
        val takenWindow = firstTakenTriple(
            dto.members.map { Triple(id, it.assetId, it.addedAt) },
            claimedMemberWindows,
        )
        val takenOpen = firstTakenPair(
            dto.members.filter { it.removedAt == null }.map { id to it.assetId },
            claimedOpenWindows,
        )
        val takenChild = firstTaken(dto.members.map { it.id }, claimedGroupMemberIds)
        decisions += when {
            local != null && dto.ordered() == local.toDto().ordered() ->
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            missingAsset != null ->
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingAsset)
            takenWindow != null ->
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.CONFLICT, MergeReason.GROUP_MEMBER_WINDOW_TAKEN, takenWindow.second)
            takenOpen != null ->
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.CONFLICT, MergeReason.GROUP_MEMBER_ALREADY_OPEN, takenOpen.second)
            takenChild != null ->
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, takenChild)
            else -> {
                groupWrites += dto.toDomain()
                acceptedGroups += id
                dto.members.forEach {
                    claimedMemberWindows[Triple(id, it.assetId, it.addedAt)] = id
                    if (it.removedAt == null) claimedOpenWindows[id to it.assetId] = id
                    claimedGroupMemberIds[it.id] = id
                }
                MergeDecision(MergeTable.GROUPS, id, MergeVerdict.INSERT)
            }
        }
    }

    fun groupAvailable(groupId: String) = groupId in localGroups || groupId in acceptedGroups

    // --- definitions ------------------------------------------------------------------------
    // ENTERED before DERIVED, so a derived row's two sources are already accepted when it is
    // decided — the same order `ImportBackupReplace.kt:73`–`75` writes them in.
    val definitionWrites = mutableListOf<MeasurementDefinition>()
    val acceptedDefinitions = mutableSetOf<String>()
    val (entered, derived) = data.measurementDefinitions
        .partition { it.kind == DefinitionKind.ENTERED.name }
    for (dto in entered + derived) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localDefinitions[id]
        val owner = row.assetId.value
        val keyHolder = claimedDefinitionKeys[owner to row.key]
        val missingSource = row.derived
            ?.let { listOf(it.sourceA.value, it.sourceB.value) }.orEmpty()
            .firstOrNull { it !in localDefinitions && it !in acceptedDefinitions }
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(owner) ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            keyHolder != null ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.DEFINITION_KEY_TAKEN, keyHolder)
            missingSource != null ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingSource)
            else -> {
                definitionWrites += row
                acceptedDefinitions += id
                claimedDefinitionKeys[owner to row.key] = id
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.INSERT)
            }
        }
    }

    fun definitionAvailable(id: String) = id in localDefinitions || id in acceptedDefinitions

    // --- profiles ---------------------------------------------------------------------------
    val profileWrites = mutableListOf<EventProfile>()
    val acceptedProfiles = mutableSetOf<String>()
    for (dto in data.eventProfiles) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localProfiles[id]
        val owner = row.assetId.value
        val missingField = row.fields.map { it.definitionId.value }.firstOrNull { !definitionAvailable(it) }
        val takenPair = firstTakenPair(
            row.fields.map { id to it.definitionId.value },
            claimedProfileFieldPairs,
        )
        val takenChild = firstTaken(row.fields.map { it.id }, claimedFieldIds)
            ?: firstTaken(row.consumables.map { it.id }, claimedProfileConsumableIds)
        decisions += when {
            local != null && dto.ordered() == local.toDto().ordered() ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(owner) ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            missingField != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingField)
            takenPair != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.PROFILE_FIELD_DEFINITION_TAKEN, takenPair.second)
            takenChild != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, takenChild)
            else -> {
                profileWrites += row
                acceptedProfiles += id
                row.fields.forEach {
                    claimedProfileFieldPairs[id to it.definitionId.value] = id
                    claimedFieldIds[it.id] = id
                }
                row.consumables.forEach { claimedProfileConsumableIds[it.id] = id }
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.INSERT)
            }
        }
    }

    fun profileAvailable(id: String) = id in localProfiles || id in acceptedProfiles

    // --- schedules --------------------------------------------------------------------------
    // Four references and one shape rule. The shape rule is read off the **DTO's** two target
    // columns and not off a domain value, because `toDomain()` throws on a row naming both targets
    // or neither — and the whole point of `SCHEDULE_TARGET_INVALID` is to *report* such a row.
    val scheduleWrites = mutableListOf<MaintenanceSchedule>()
    val acceptedSchedules = mutableSetOf<String>()
    for (dto in data.maintenanceSchedules) {
        val id = dto.id
        val local = localSchedules[id]
        val targetInvalid = (dto.assetId == null) == (dto.groupId == null)
        val missingOwner = when {
            dto.assetId != null && !assetAvailable(dto.assetId) -> dto.assetId
            dto.groupId != null && !groupAvailable(dto.groupId) -> dto.groupId
            dto.meterDefinitionId != null && !definitionAvailable(dto.meterDefinitionId) ->
                dto.meterDefinitionId
            dto.profileId != null && !profileAvailable(dto.profileId) -> dto.profileId
            else -> null
        }
        // `schedule_provider`'s primary key is `(schedule_id, provider)` and the row has no id of
        // its own, so a collision is a statement about the schedule's *content* and not about a
        // child row's identity — which is why it is reported as CONTENT_DIFFERS and not as
        // CHILD_ROW_ID_TAKEN. Like the membership keys, the key contains the parent's id, so the
        // live case is one schedule listing a provider twice.
        val takenProvider = firstTakenPair(
            dto.providers.map { id to it.provider },
            claimedProviderPairs,
        )
        decisions += when {
            local != null && dto.ordered() == local.toDto().ordered() ->
                MergeDecision(MergeTable.SCHEDULES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.SCHEDULES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            targetInvalid ->
                MergeDecision(MergeTable.SCHEDULES, id, MergeVerdict.CONFLICT, MergeReason.SCHEDULE_TARGET_INVALID, id)
            missingOwner != null ->
                MergeDecision(MergeTable.SCHEDULES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingOwner)
            takenProvider != null ->
                MergeDecision(MergeTable.SCHEDULES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, takenProvider.second)
            else -> {
                scheduleWrites += dto.toDomain()
                acceptedSchedules += id
                dto.providers.forEach { claimedProviderPairs[id to it.provider] = id }
                MergeDecision(MergeTable.SCHEDULES, id, MergeVerdict.INSERT)
            }
        }
    }

    fun scheduleAvailable(id: String) = id in localSchedules || id in acceptedSchedules

    // --- closures ---------------------------------------------------------------------------
    // The closure's **second identity**: `(schedule_id, occurrence_on)` is unique independently of
    // the row id, so the lookup is deliberately id-independent — the same shape the tag pass uses
    // for a payload. A closure has no `updated_at` and no mutable field, so a re-imported unchanged
    // closure is always IDENTICAL; and a closure is never coalesced by date.
    val closureWrites = mutableListOf<OccurrenceClosure>()
    for (dto in data.occurrenceClosures) {
        val id = dto.id
        val pair = dto.scheduleId to dto.occurrenceOn
        val local = localClosures[id]
        val samePair = localClosuresByPair[pair]
        val pairHolder = claimedClosurePairs[pair]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.CLOSURES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.CLOSURES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            // A local row under a different id already *is* this closure, field for field.
            samePair != null && dto.copy(id = samePair.id) == samePair.toDto() ->
                MergeDecision(
                    MergeTable.CLOSURES, id, MergeVerdict.IDENTICAL,
                    MergeReason.CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW, samePair.id,
                )
            samePair != null ->
                MergeDecision(
                    MergeTable.CLOSURES, id, MergeVerdict.CONFLICT,
                    MergeReason.CLOSURE_DIVERGED, samePair.id,
                )
            // No local row holds the pair, so any holder left is another row of this archive.
            pairHolder != null ->
                MergeDecision(
                    MergeTable.CLOSURES, id, MergeVerdict.CONFLICT,
                    MergeReason.CLOSURE_DUPLICATED_IN_ARCHIVE, pairHolder,
                )
            !scheduleAvailable(dto.scheduleId) ->
                MergeDecision(MergeTable.CLOSURES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.scheduleId)
            else -> {
                closureWrites += dto.toDomain()
                claimedClosurePairs[pair] = id
                MergeDecision(MergeTable.CLOSURES, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- links (2.6 tombstones: carried, never displayed) -----------------------------------
    val linkWrites = mutableListOf<ExternalLink>()
    val acceptedLinks = mutableSetOf<String>()
    for (dto in data.externalLinks) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localLinks[id]
        val owner = dto.assetId
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            owner != null && !assetAvailable(owner) ->
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            else -> {
                linkWrites += row
                acceptedLinks += id
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- tags -------------------------------------------------------------------------------
    // #44's second identity rule, and the `nfc_tag(payload_format, payload_key)` unique index
    // (`NfcTagEntity.kt:26`). The payload lookup is deliberately independent of the row id.
    val tagWrites = mutableListOf<TagBinding>()
    for (dto in data.nfcTags) {
        val row = dto.toDomain()
        val id = dto.id
        val payload = dto.payloadFormat to dto.payloadKey
        val local = localTags[id]
        val samePayload = localTagsByPayload[payload]
        val payloadHolder = claimedPayloads[payload]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            // A local tag under a different row id already *is* this tag, field for field.
            samePayload != null && dto.copy(id = samePayload.id.value) == samePayload.toDto() ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.IDENTICAL,
                    MergeReason.PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG, samePayload.id.value,
                )
            samePayload != null && samePayload.toDto().assetId != dto.assetId ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.CONFLICT,
                    MergeReason.PAYLOAD_BOUND_TO_ANOTHER_ASSET, samePayload.id.value,
                )
            samePayload != null ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.CONFLICT,
                    MergeReason.PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG, samePayload.id.value,
                )
            // No local tag holds it, so any holder left is another row of this archive.
            payloadHolder != null ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.CONFLICT,
                    MergeReason.PAYLOAD_DUPLICATED_IN_ARCHIVE, payloadHolder,
                )
            dto.assetId != null && !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            dto.linkId != null && dto.linkId !in localLinks && dto.linkId !in acceptedLinks ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.linkId)
            else -> {
                tagWrites += row
                claimedPayloads[payload] = id
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- events -----------------------------------------------------------------------------
    // History is append-oriented (#44): nothing here looks at a date, a title or a measurement
    // value. The only identities are the row's own id, its `(source, sourceRef)`, and its children's
    // durable ids.
    val eventWrites = mutableListOf<AssetEvent>()
    val acceptedEvents = mutableSetOf<String>()
    for (dto in data.assetEvents) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localEvents[id]
        val owner = dto.assetId
        val refHolder = dto.sourceRef?.let { claimedSourceRefs[dto.source to it] }
        // `UNIQUE(schedule_id, occurrence_on, asset_id)`. NULLs are distinct in SQLite, so the key
        // only exists — and the index is only live — for a completion.
        val occurrenceKey = if (dto.scheduleId != null && dto.occurrenceOn != null) {
            Triple(dto.scheduleId, dto.occurrenceOn, dto.assetId)
        } else {
            null
        }
        val occurrenceHolder = occurrenceKey?.let { claimedOccurrences[it] }
        val missingDefinition = dto.measurements.map { it.definitionId }.firstOrNull { !definitionAvailable(it) }
        val takenChild = firstTaken(row.measurements.map { it.id }, claimedMeasurementIds)
            ?: firstTaken(row.consumables.map { it.id }, claimedUsageIds)
        decisions += when {
            local != null && dto.ordered() == local.toDto().ordered() ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(owner) ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            dto.profileId != null && dto.profileId !in localProfiles && dto.profileId !in acceptedProfiles ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.profileId)
            dto.scheduleId != null && !scheduleAvailable(dto.scheduleId) ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.scheduleId)
            refHolder != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.EVENT_SOURCE_REF_TAKEN, refHolder)
            occurrenceHolder != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.SCHEDULE_OCCURRENCE_TAKEN, occurrenceHolder)
            missingDefinition != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingDefinition)
            takenChild != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, takenChild)
            else -> {
                eventWrites += row
                acceptedEvents += id
                dto.sourceRef?.let { claimedSourceRefs[dto.source to it] = id }
                occurrenceKey?.let { claimedOccurrences[it] = id }
                row.measurements.forEach { claimedMeasurementIds[it.id] = id }
                row.consumables.forEach { claimedUsageIds[it.id] = id }
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- attachments ------------------------------------------------------------------------
    // Four questions in one fixed order (decision 11). The locator check comes first on purpose:
    // when a local row already claims a locator its bytes are of course present, so asking "are the
    // bytes here?" first would answer yes and produce an INSERT the unique index refuses.
    //
    // That locator arm is a **planner guard** in 1.1.0, not a live path: `BackupCodec.kt:392`
    // requires `AttachmentLocator.matchesShape`, and that shape embeds the row's own id
    // (`model/Attachment.kt:78`–`80`), so through the API a locator collision implies an id
    // collision — which the `local != null` pair two arms above has already answered. The check is
    // kept because it is one map lookup, because a hand-built archive is not obliged to be
    // codec-shaped, and because slice B's owner remapping makes it live.
    val attachmentWrites = mutableListOf<Attachment>()
    // R67-12 (option B): see the KDoc's canonical-content paragraph. Giving a document its role in
    // the app (the sheet, through `UpdateAttachment`) moves the stamp too, so against an older archive
    // a row here that carries a role is compared without both; one without a role compares as always.
    val rolesCompared = backup.manifest.formatVersion >= BackupCodec.FIRST_ROLE_FORMAT
    fun sameAttachment(incoming: AttachmentDto, here: AttachmentDto): Boolean = when {
        rolesCompared -> incoming == here
        here.role == null -> incoming.copy(role = null) == here
        else -> incoming.copy(role = null, updatedAt = here.updatedAt) == here.copy(role = null)
    }
    for (dto in data.attachments) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localAttachments[id]
        val locatorKey = dto.storageProvider to dto.storageLocator
        val locatorHolder = claimedLocators[locatorKey]
        val owner = dto.assetId ?: dto.eventId!!
        val ownerAvailable = if (dto.assetId != null) {
            assetAvailable(dto.assetId)
        } else {
            dto.eventId in localEvents || dto.eventId in acceptedEvents
        }
        val stored = snapshot.storedBytes[dto.storageLocator]
        decisions += when {
            local != null && sameAttachment(dto, local.toDto()) ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !ownerAvailable ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            locatorHolder != null ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT, MergeReason.ATTACHMENT_LOCATOR_TAKEN, locatorHolder)
            !snapshot.attachmentStoreConfigured ->
                MergeDecision(
                    MergeTable.ATTACHMENTS, id, MergeVerdict.SKIPPED,
                    MergeReason.ATTACHMENT_STORE_NOT_CONFIGURED, dto.storageLocator,
                )
            stored == null ->
                MergeDecision(
                    MergeTable.ATTACHMENTS, id, MergeVerdict.SKIPPED,
                    MergeReason.ATTACHMENT_BYTES_ABSENT, dto.storageLocator,
                )
            // #44 acceptance 12: verify size + SHA-256 before writing.
            stored.sha256 != dto.sha256 || stored.sizeBytes != dto.sizeBytes ->
                MergeDecision(
                    MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT,
                    MergeReason.ATTACHMENT_BYTES_DIFFER, dto.storageLocator,
                )
            else -> {
                attachmentWrites += row
                claimedLocators[locatorKey] = id
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- references (1.3, D-18 C) -----------------------------------------------------------
    // After ATTACHMENTS in write order: a reference's only foreign key is `asset_id`, so any
    // position after ASSETS would do, and 1.3 appended it as the smaller diff; 1.4's three tables
    // follow it. The arm order is the closure pass's, with one
    // difference that is the whole of D-18 C: the **diverged** second identity is a `SKIPPED` and
    // never a `CONFLICT`, because there is no UPDATE verdict for the incoming name and description
    // to be adopted by, so refusing the archive could not produce a better merged state.
    //
    // A SKIPPED row claims no pair and writes nothing, which is why `claimedReferencePairs` is
    // only ever written in the INSERT arm.
    val referenceWrites = mutableListOf<AssetReference>()
    for (dto in data.assetReferences) {
        val id = dto.id
        val pair = dto.assetId to dto.uri
        val local = localReferences[id]
        val samePair = localReferencesByPair[pair]
        val pairHolder = claimedReferencePairs[pair]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.REFERENCES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.REFERENCES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            // A local row under a different id already *is* this reference, field for field.
            samePair != null && dto.copy(id = samePair.id.value) == samePair.toDto() ->
                MergeDecision(
                    MergeTable.REFERENCES, id, MergeVerdict.IDENTICAL,
                    MergeReason.REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW, samePair.id.value,
                )
            samePair != null ->
                MergeDecision(
                    MergeTable.REFERENCES, id, MergeVerdict.SKIPPED,
                    MergeReason.REFERENCE_HELD_BY_A_LOCAL_ROW, samePair.id.value,
                )
            // No local row holds the pair, so any holder left is another row of this archive.
            pairHolder != null ->
                MergeDecision(
                    MergeTable.REFERENCES, id, MergeVerdict.CONFLICT,
                    MergeReason.REFERENCE_DUPLICATED_IN_ARCHIVE, pairHolder,
                )
            !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.REFERENCES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            else -> {
                referenceWrites += dto.toDomain()
                claimedReferencePairs[pair] = id
                MergeDecision(MergeTable.REFERENCES, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- season activations and conditions (1.4) --------------------------------------------
    // Immutable facts: identity is the row id and nothing else, a re-import is IDENTICAL, and two
    // phones' rows are two inserts. The only owner is the asset. `eventId` is a soft link and is
    // **never** an owner (inv. 109): a row naming an event that exists nowhere still inserts. Neither
    // pass touches the asset row, so recording START, END or a condition never makes an asset
    // re-import as anything but IDENTICAL (inv. 126).
    val activationWrites = mutableListOf<SeasonActivation>()
    for (dto in data.seasonActivations) {
        val id = dto.id
        val local = localActivations[id]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.SEASON_ACTIVATIONS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.SEASON_ACTIVATIONS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(dto.assetId) ->
                MergeDecision(
                    MergeTable.SEASON_ACTIVATIONS, id, MergeVerdict.CONFLICT,
                    MergeReason.OWNER_NOT_AVAILABLE, dto.assetId,
                )
            else -> {
                activationWrites += dto.toDomain()
                MergeDecision(MergeTable.SEASON_ACTIVATIONS, id, MergeVerdict.INSERT)
            }
        }
    }

    val conditionWrites = mutableListOf<AssetCondition>()
    for (dto in data.assetConditions) {
        val id = dto.id
        val local = localConditions[id]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.CONDITIONS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.CONDITIONS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.CONDITIONS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            else -> {
                conditionWrites += dto.toDomain()
                MergeDecision(MergeTable.CONDITIONS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- health subjects (1.4) --------------------------------------------------------------
    // Configuration, with a **second identity**: a non-archived subject's schedule, which one
    // non-archived subject at most may drive (inv. 120). The arm order is the reference pass's. A
    // local non-archived subject under another id already driving the schedule makes this row
    // `SKIPPED` (D-18, as a reference declines); another non-archived row of this archive having
    // claimed it first is a `CONFLICT`. An archived subject claims no schedule at all. The owners
    // are the asset and, when set, the schedule; `baselineProfileId` and the asset's
    // `healthPrimarySubjectId` are soft and never owners.
    //
    // A SKIPPED row claims nothing and writes nothing, which is why `claimedSubjectSchedules` is
    // only ever written in the INSERT arm.
    val subjectWrites = mutableListOf<HealthSubject>()
    for (dto in data.healthSubjects) {
        val id = dto.id
        val local = localSubjects[id]
        val drives = dto.scheduleId?.takeIf { dto.archivedAt == null }
        val localDriver = drives?.let { localSubjectsBySchedule[it] }?.takeIf { it != id }
        val claimHolder = drives?.let { claimedSubjectSchedules[it] }?.takeIf { it != id }
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            localDriver != null ->
                MergeDecision(
                    MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.SKIPPED,
                    MergeReason.HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW, localDriver,
                )
            // No local row drives it, so any holder left is another row of this archive.
            claimHolder != null ->
                MergeDecision(
                    MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.CONFLICT,
                    MergeReason.HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE, claimHolder,
                )
            !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            dto.scheduleId != null && !scheduleAvailable(dto.scheduleId) ->
                MergeDecision(
                    MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.CONFLICT,
                    MergeReason.OWNER_NOT_AVAILABLE, dto.scheduleId,
                )
            else -> {
                subjectWrites += dto.toDomain()
                drives?.let { claimedSubjectSchedules[it] = id }
                MergeDecision(MergeTable.HEALTH_SUBJECTS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- service cases and their timelines (#79, C19) -----------------------------------------
    // A case is an aggregate root: identity is its id, and a case here is compared by its header
    // alone — IDENTICAL, or CONFLICT `CONTENT_DIFFERS` on any difference, since there is no UPDATE.
    // Its only owner is the asset; its Incident and repair links are soft and never owners (R79-4).
    // Its entries are append-only facts on the conditions shape, owned by the case: one here compares
    // IDENTICAL or CONFLICT, one the phone lacks is an INSERT when its case is here or accepted by this
    // plan. A note-only entry never moves the header, so a note added elsewhere re-merges as one entry
    // INSERT beside an IDENTICAL case; a status entry stamps the header, so it conflicts (plan §5).
    val localCases = snapshot.serviceCases.associateBy { it.id.value }
    val localEntries = snapshot.caseEntries.associateBy { it.id.value }
    val caseWrites = mutableListOf<ServiceCase>()
    val acceptedCases = mutableSetOf<String>()
    for (dto in data.serviceCases) {
        val id = dto.id
        val local = localCases[id]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.SERVICE_CASES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.SERVICE_CASES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.SERVICE_CASES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            else -> {
                caseWrites += dto.toDomain()
                acceptedCases += id
                MergeDecision(MergeTable.SERVICE_CASES, id, MergeVerdict.INSERT)
            }
        }
    }

    val entryWrites = mutableListOf<ServiceCaseEntry>()
    for (dto in data.serviceCaseEntries) {
        val id = dto.id
        val local = localEntries[id]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.CASE_ENTRIES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.CASE_ENTRIES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            dto.caseId !in localCases && dto.caseId !in acceptedCases ->
                MergeDecision(MergeTable.CASE_ENTRIES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.caseId)
            else -> {
                entryWrites += dto.toDomain()
                MergeDecision(MergeTable.CASE_ENTRIES, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- loans (#72, C7) ----------------------------------------------------------------------
    // A loan by its id, every field compared — no UPDATE (R72-18). A new one needs its asset here or
    // accepted by this plan, and an open one must not meet a different open loan of that asset here.
    val localLoans = snapshot.loans.associateBy { it.id.value }
    val localOpenLoanByAsset = snapshot.loans.filter { it.isOpen }.associateBy { it.assetId.value }
    val loanWrites = mutableListOf<AssetLoan>()
    for (dto in data.assetLoans) {
        val id = dto.id
        val local = localLoans[id]
        val holder = localOpenLoanByAsset[dto.assetId]?.id?.value
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.LOANS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.LOANS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.LOANS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            dto.returnedOn == null && holder != null && holder != id ->
                MergeDecision(MergeTable.LOANS, id, MergeVerdict.CONFLICT, MergeReason.ASSET_ALREADY_LENT, holder)
            else -> {
                loanWrites += dto.toDomain()
                MergeDecision(MergeTable.LOANS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- transfer records (#77, C10; R77-5, R77-12) ---------------------------------------------
    // A record by its id, every field compared — no UPDATE. One this phone lacks meets M1 against R', this
    // phone's records and every incoming one absent here: an IN or WITHDRAWN that would close an OUT open
    // **here** is refused (an ordinary archive never supersedes; a withdrawal never propagates); an OUT that
    // would leave its asset with two open OUTs in R' is the double mark; anything else lands, so another
    // installation's OUT makes the asset held here too.
    val localRecords = snapshot.transfers
    val localRecordsById = localRecords.associateBy { it.id }
    val incomingRecords = data.transferRecords.map { it.toDomain() }
    val recordsPrime = localRecords + incomingRecords.filter { it.id !in localRecordsById }
    val openHere = openOuts(localRecords)
    val openPrime = openOuts(recordsPrime)
    val transferWrites = mutableListOf<TransferRecord>()
    for (record in incomingRecords) {
        val id = record.id
        val local = localRecordsById[id]
        val rival = if (record.kind == TransferKind.OUT && openPrime.any { it.id == id }) {
            openPrime.filter { it.assetId == record.assetId && it.id != id }
                .minWithOrNull(compareBy({ it.id !in localRecordsById }, { it.id }))
        } else {
            null
        }
        decisions += when {
            local != null && local == record ->
                MergeDecision(MergeTable.TRANSFERS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.TRANSFERS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            openHere.any { closes(record, it) } ->
                MergeDecision(
                    MergeTable.TRANSFERS, id, MergeVerdict.CONFLICT, MergeReason.ASSET_TRANSFERRED_OUT, record.assetId.value,
                )
            rival != null ->
                MergeDecision(
                    MergeTable.TRANSFERS, id, MergeVerdict.CONFLICT, MergeReason.TRANSFER_DIVERGED,
                    "${rival.packId} (${shortPackId(rival.packId)}), ${record.packId} (${shortPackId(record.packId)})",
                )
            else -> {
                transferWrites += record
                MergeDecision(MergeTable.TRANSFERS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- M2 and M3: nothing lands for, or against, an asset held here (#77, C10) -------------------
    // With the records this plan would insert, `heldAfter` is what the phone will hold. M2: a row the rules
    // above would INSERT whose owning asset (`TransferOwnership`, the write guard's own rule) is held is
    // refused, so a plan never calls applicable what the guard would refuse (rm-2). M3: what stays after the
    // merge must still retain cleanly (C3) — an inserted row naming a row a held asset drops is refused on
    // that row; a local row naming one is refused on the inserted OUT that made it held. An entanglement no
    // incoming row introduced is not this plan's (the export reports it).
    val heldAfter = heldIds(localRecords + transferWrites)
    if (heldAfter.isNotEmpty()) {
        val eventsById = (snapshot.events + eventWrites).associateBy { it.id }
        val casesById = (snapshot.serviceCases + caseWrites).associateBy { it.id }
        val schedulesById = (snapshot.schedules + scheduleWrites).associateBy { it.id }
        val groupsById = (snapshot.groups + groupWrites).associateBy { it.id }
        val linksById = (snapshot.links + linkWrites).associateBy { it.id }
        val lookup = object : OwnerLookup {
            override fun event(id: EventId) = eventsById[id]
            override fun case(id: ServiceCaseId) = casesById[id]
            override fun schedule(id: ScheduleId) = schedulesById[id]
            override fun group(id: GroupId) = groupsById[id]
            override fun link(id: LinkId) = linksById[id]
        }
        val inserted = decisions.withIndex()
            .filter { it.value.verdict == MergeVerdict.INSERT }
            .associate { (it.value.table to it.value.id) to it.index }
        fun refuseInsert(table: MergeTable, id: String, detail: String) {
            val at = inserted[table to id] ?: return
            if (decisions[at].verdict != MergeVerdict.INSERT) return
            decisions[at] = MergeDecision(table, id, MergeVerdict.CONFLICT, MergeReason.ASSET_TRANSFERRED_OUT, detail)
        }
        fun heldOwner(refs: List<OwnerRef>): String? =
            TransferOwnership.resolve(refs, lookup).filter { it in heldAfter }.minOfOrNull { it.value }

        // M2 — every table a merge inserts into but the records' own.
        val owned: List<Triple<MergeTable, String, List<OwnerRef>>> =
            canonicalAssetWrites.map { Triple(MergeTable.ASSETS, it.id.value, TransferOwnership.of(it)) } +
                groupWrites.map { Triple(MergeTable.GROUPS, it.id.value, TransferOwnership.of(it)) } +
                definitionWrites.map { Triple(MergeTable.DEFINITIONS, it.id.value, TransferOwnership.of(it)) } +
                profileWrites.map { Triple(MergeTable.PROFILES, it.id.value, TransferOwnership.of(it)) } +
                scheduleWrites.map { Triple(MergeTable.SCHEDULES, it.id.value, TransferOwnership.of(it)) } +
                closureWrites.map { Triple(MergeTable.CLOSURES, it.id, TransferOwnership.of(it)) } +
                linkWrites.map { Triple(MergeTable.LINKS, it.id.value, TransferOwnership.of(it)) } +
                tagWrites.map { Triple(MergeTable.TAGS, it.id.value, TransferOwnership.of(it)) } +
                eventWrites.map { Triple(MergeTable.EVENTS, it.id.value, TransferOwnership.of(it)) } +
                attachmentWrites.map { Triple(MergeTable.ATTACHMENTS, it.id.value, TransferOwnership.of(it)) } +
                referenceWrites.map { Triple(MergeTable.REFERENCES, it.id.value, TransferOwnership.of(it)) } +
                activationWrites.map { Triple(MergeTable.SEASON_ACTIVATIONS, it.id, TransferOwnership.of(it)) } +
                conditionWrites.map { Triple(MergeTable.CONDITIONS, it.id, TransferOwnership.of(it)) } +
                subjectWrites.map { Triple(MergeTable.HEALTH_SUBJECTS, it.id.value, TransferOwnership.of(it)) } +
                caseWrites.map { Triple(MergeTable.SERVICE_CASES, it.id.value, TransferOwnership.of(it)) } +
                entryWrites.map { Triple(MergeTable.CASE_ENTRIES, it.id.value, TransferOwnership.of(it)) } +
                loanWrites.map { Triple(MergeTable.LOANS, it.id.value, TransferOwnership.of(it)) }
        for ((table, id, refs) in owned) heldOwner(refs)?.let { refuseInsert(table, id, it) }

        // M3 — the phone as this plan would leave it, cut by what it would hold.
        val after = BackupData(
            assets = (snapshot.assets + canonicalAssetWrites).map { it.toDto() },
            nfcTags = (snapshot.tags + tagWrites).map { it.toDto() },
            externalLinks = (snapshot.links + linkWrites).map { it.toDto() },
            measurementDefinitions = (snapshot.definitions + definitionWrites).map { it.toDto() },
            eventProfiles = (snapshot.profiles + profileWrites).map { it.toDto() },
            assetEvents = (snapshot.events + eventWrites).map { it.toDto() },
            attachments = (snapshot.attachments + attachmentWrites).map { it.toDto() },
            maintenanceGroups = groupsById.values.map { it.toDto() },
            maintenanceSchedules = schedulesById.values.map { it.toDto() },
            occurrenceClosures = (snapshot.closures + closureWrites).map { it.toDto() },
            assetReferences = (snapshot.references + referenceWrites).map { it.toDto() },
            seasonActivations = (snapshot.seasonActivations + activationWrites).map { it.toDto() },
            assetConditions = (snapshot.conditions + conditionWrites).map { it.toDto() },
            healthSubjects = (snapshot.healthSubjects + subjectWrites).map { it.toDto() },
            serviceCases = casesById.values.map { it.toDto() },
            serviceCaseEntries = (snapshot.caseEntries + entryWrites).map { it.toDto() },
            assetLoans = (snapshot.loans + loanWrites).map { it.toDto() },
        )
        val retention = TransferGraph.retain(after, heldAfter)
        if (retention is TransferRetention.Entangled) {
            val newlyHeld = heldAfter - heldIds(localRecords)
            val definitionsById = (snapshot.definitions + definitionWrites).associateBy { it.id.value }
            val profilesById = (snapshot.profiles + profileWrites).associateBy { it.id.value }
            fun targetOwners(ref: EntangledRef): List<OwnerRef> = when (ref.targetTable) {
                "assets" -> listOf(OwnerRef.OfAsset(AssetId(ref.targetId)))
                "maintenanceSchedules" -> listOf(OwnerRef.OfSchedule(ScheduleId(ref.targetId)))
                "measurementDefinitions" -> definitionsById[ref.targetId]?.let(TransferOwnership::of).orEmpty()
                "eventProfiles" -> profilesById[ref.targetId]?.let(TransferOwnership::of).orEmpty()
                else -> emptyList()
            }
            for (ref in retention.refs) {
                val owners = TransferOwnership.resolve(targetOwners(ref), lookup).filter { it in heldAfter }
                val table = MERGE_TABLE_OF.getValue(ref.table)
                if ((table to ref.rowId) in inserted) {
                    owners.minOfOrNull { it.value }?.let { refuseInsert(table, ref.rowId, it) }
                } else {
                    transferWrites
                        .filter { it.kind == TransferKind.OUT && it.assetId in newlyHeld && it.assetId in owners }
                        .forEach { refuseInsert(MergeTable.TRANSFERS, it.id, ref.rowId) }
                }
            }
        }
    }

    // --- review hints, which change nothing (#44 identity 3) --------------------------------
    val localBySignature = snapshot.assets
        .filter { it.manufacturer.isNotBlank() && it.model.isNotBlank() && it.serialNumber.isNotBlank() }
        .groupBy { Triple(it.manufacturer, it.model, it.serialNumber) }
    val candidates = data.assets
        .filter { it.manufacturer.isNotBlank() && it.model.isNotBlank() && it.serialNumber.isNotBlank() }
        .flatMap { dto ->
            localBySignature[Triple(dto.manufacturer, dto.model, dto.serialNumber)].orEmpty()
                .filter { it.id.value != dto.id }
                .map { DuplicateCandidate(dto.id, it.id.value, MergeHint.SAME_MANUFACTURER_MODEL_SERIAL) }
        }
        .sortedWith(compareBy({ it.incomingAssetId }, { it.localAssetId }))

    val ordered = decisions.sortedWith(compareBy({ it.table.ordinal }, { it.id }))
    val hasConflict = ordered.any { it.verdict == MergeVerdict.CONFLICT }
    return MergePlan(
        backup = backup,
        decisions = ordered,
        // No partial merge, made structural rather than remembered.
        writes = if (hasConflict) {
            MergeWrites()
        } else {
            MergeWrites(
                categories = categoryWrites.sortedBy { it.key },
                assets = canonicalAssetWrites,
                groups = groupWrites,
                definitions = definitionWrites,
                profiles = profileWrites,
                schedules = scheduleWrites,
                closures = closureWrites,
                links = linkWrites,
                tags = tagWrites,
                events = eventWrites,
                attachments = attachmentWrites,
                references = referenceWrites,
                seasonActivations = activationWrites,
                conditions = conditionWrites,
                healthSubjects = subjectWrites,
                serviceCases = caseWrites,
                caseEntries = entryWrites,
                loans = loanWrites,
                transfers = transferWrites,
            )
        },
        duplicateCandidates = candidates,
    )
}

/** M3's entangling rows, by the archive's list names (`TransferGraph.retain` names tables so). */
private val MERGE_TABLE_OF: Map<String, MergeTable> = mapOf(
    "assets" to MergeTable.ASSETS,
    "maintenanceGroups" to MergeTable.GROUPS,
    "maintenanceSchedules" to MergeTable.SCHEDULES,
    "eventProfiles" to MergeTable.PROFILES,
    "assetEvents" to MergeTable.EVENTS,
    "healthSubjects" to MergeTable.HEALTH_SUBJECTS,
)

/** The first id [claimed] already holds, or the first that repeats within [ids]; null if none. */
private fun firstTaken(ids: List<String>, claimed: Map<String, String>): String? {
    val seen = mutableSetOf<String>()
    return ids.firstOrNull { it in claimed || !seen.add(it) }
}

/**
 * The pair form of [firstTaken], for `profile_field(profile_id, definition_id)`,
 * `schedule_provider(schedule_id, provider)` and the at-most-one-open membership rule.
 */
private fun firstTakenPair(
    pairs: List<Pair<String, String>>,
    claimed: Map<Pair<String, String>, String>,
): Pair<String, String>? {
    val seen = mutableSetOf<Pair<String, String>>()
    return pairs.firstOrNull { it in claimed || !seen.add(it) }
}

/**
 * The triple form, for `maintenance_group_member(group_id, asset_id, added_at)`. It returns the
 * colliding **key**, as [firstTakenPair] does, because the holder is the incoming group itself in
 * the only live case and its own id would say nothing — the reasoning
 * [MergeReason.PROFILE_FIELD_DEFINITION_TAKEN] already records for its detail.
 */
private fun firstTakenTriple(
    triples: List<Triple<String, String, Long>>,
    claimed: Map<Triple<String, String, Long>, String>,
): Triple<String, String, Long>? {
    val seen = mutableSetOf<Triple<String, String, Long>>()
    return triples.firstOrNull { it in claimed || !seen.add(it) }
}

/**
 * Child lists in `(sortOrder, id)`. `sortOrder` is the order the backup format writes them in
 * (`BackupCodec.kt:85`–`96`); the id is the tie-break, because the format does not promise
 * `sortOrder` is unique within a parent and two stable sorts over two undefined bases would
 * otherwise disagree (decision 4).
 */
private fun EventProfileDto.ordered() = copy(
    fields = fields.sortedWith(compareBy({ it.sortOrder }, { it.id })),
    consumables = consumables.sortedWith(compareBy({ it.sortOrder }, { it.id })),
)

private fun AssetEventDto.ordered() = copy(
    measurements = measurements.sortedWith(compareBy({ it.sortOrder }, { it.id })),
    consumables = consumables.sortedWith(compareBy({ it.sortOrder }, { it.id })),
)

/** A group's members, in the same `(sortOrder, id)` the encoder writes them in. */
private fun MaintenanceGroupDto.ordered() = copy(
    members = members.sortedWith(compareBy({ it.sortOrder }, { it.id })),
)

/**
 * #74 (C13): an asset with its `category` read by key — the second normalisation, applied to both
 * sides. Blank is `""` on both, as a blank category has no key.
 */
private fun AssetDto.keyed() = copy(category = CategoryKey.of(category).orEmpty())

/** A schedule's providers, by the only key they have — which the encoder also sorts on. */
private fun MaintenanceScheduleDto.ordered() = copy(providers = providers.sortedBy { it.provider })

/**
 * What the store actually holds for each locator the archive names — **asked outside any
 * transaction**, because `open` is a document-provider round trip and a Room write transaction is
 * not where an IPC belongs.
 *
 * Each file is hashed and sized in one streaming pass with a 64 KiB buffer, exactly as
 * `AttachmentSweep.kt:45`–`65`'s `sha256Of` does and for the same reason: an attachment can be
 * hundreds of megabytes and is never materialised. A locator that is absent, that **fails to open**,
 * or that opens and then cannot be read, is simply not in the result — which the planner reports as
 * `ATTACHMENT_BYTES_ABSENT`, the same answer `sha256Of` gives an unreadable file.
 *
 * The open is inside the `try` on purpose, and that is the difference from `sha256Of`'s shape:
 * `SafTreeAttachmentStore.open` goes through `ContentResolver.openInputStream`, which raises
 * `FileNotFoundException` — an `IOException` — for a document that went away between the tree walk
 * and the open, or for a grant revoked in between. `sha256Of` feeds a best-effort sweep, so letting
 * that escape costs a sweep; this feeds the whole plan and the whole apply, where it would turn one
 * raced file into a failed merge. One raced file is a missing-bytes answer for one row.
 *
 * Takes the [store] the caller already resolved rather than asking [AttachmentStorage] again, so
 * "is there a folder?" and "what is in it?" are one answer and not two (review nit 11). A null
 * [store] is no attachment folder at all and yields an empty map; the caller passes that fact to
 * the planner separately, because "no folder" and "no bytes" are different answers.
 */
internal suspend fun storedBytesOf(
    backup: Backup,
    store: AttachmentStore?,
): Map<String, StoredBytes> {
    if (store == null) return emptyMap()
    val answers = LinkedHashMap<String, StoredBytes>()
    for (locator in backup.data.attachments.map { it.storageLocator }.distinct()) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val source = try {
            store.open(locator)
        } catch (e: IOException) {
            null
        } ?: continue
        val readable = try {
            source.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    size += n
                }
            }
            true
        } catch (e: IOException) {
            false
        }
        if (!readable) continue
        answers[locator] = StoredBytes(
            sha256 = digest.digest().joinToString("") { b -> "%02x".format(b) },
            sizeBytes = size,
        )
    }
    return answers
}

/**
 * Nineteen reads. **The caller owns the transaction** — see each use case for which one.
 *
 * Schema 8's other two tables are deliberately not among them, and are named nowhere in this
 * package: derived due state never appears in a plan, and device-local delivery state is never
 * merged. Nor does any health value, because none is stored.
 */
internal suspend fun mergeSnapshotOf(
    assets: AssetRepository,
    groups: GroupRepository,
    tags: TagRepository,
    links: LinkRepository,
    definitions: DefinitionRepository,
    profiles: ProfileRepository,
    schedules: ScheduleRepository,
    closures: ClosureRepository,
    events: EventRepository,
    attachments: AttachmentRepository,
    references: ReferenceRepository,
    seasonActivations: SeasonActivationRepository,
    conditions: ConditionRepository,
    healthSubjects: HealthSubjectRepository,
    categories: CategoryRepository,
    serviceCases: ServiceCaseRepository,
    caseEntries: ServiceCaseEntryRepository,
    loans: AssetLoanRepository,
    transfers: TransferRecordRepository,
    storedBytes: Map<String, StoredBytes>,
    attachmentStoreConfigured: Boolean,
): MergeSnapshot = MergeSnapshot(
    assets = assets.all(),
    groups = groups.all(),
    tags = tags.all(),
    links = links.all(),
    definitions = definitions.all(),
    profiles = profiles.all(),
    schedules = schedules.all(),
    closures = closures.all(),
    events = events.all(),
    attachments = attachments.all(),
    references = references.all(),
    seasonActivations = seasonActivations.all(),
    conditions = conditions.all(),
    healthSubjects = healthSubjects.all(),
    categories = categories.all(),
    serviceCases = serviceCases.all(),
    caseEntries = caseEntries.all(),
    loans = loans.all(),
    transfers = transfers.all(),
    storedBytes = storedBytes,
    attachmentStoreConfigured = attachmentStoreConfigured,
)
