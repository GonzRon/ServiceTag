package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository

/**
 * #77 (C12, R77-4) — an ordinary write reached a row that an asset transferred out from this phone owns, or would
 * give a row that stays a hard reference into a held asset's graph (R77-B2b-GUARD). [assetId] is the held asset:
 * the row's owner, or the owner of the row the reference names. Nothing was written — the guard throws before the
 * port writes, inside the caller's transaction. The API answers it 409 `asset_transferred_out`; it carries no
 * sentence of its own.
 */
class AssetTransferredOut(val assetId: AssetId) :
    IllegalStateException("AssetTransferredOut(assetId=${assetId.value})")

/**
 * #77 (C12, R77-4; the owner's direction) — **the one home of the write guard**: a held asset (an open OUT in the
 * transfer records, `heldIds`) is inspectable and ordinarily immutable everywhere — the phone's use cases, the API,
 * and everything the MCP tools reach through it. It wraps the eighteen asset-owned repository ports (#15's
 * applicability the seventeenth, C14; #47's installed components the eighteenth, C14), and #86's successions;
 * `AppGraph` hands every use case the wrapped ones, so no use case, screen or route needs a guard of its own.
 *
 * Each write reads `heldIds` **in the caller's transaction** and, when that set is empty — the ordinary case — writes
 * exactly as before. Otherwise it throws [AssetTransferredOut] **before** the port writes when:
 * - the row's **owning asset** is held — [TransferOwnership], the one home M2 uses too, asked of the row being
 *   written **and** of the stored row it replaces or deletes (a tag's new and current target; an asset and its
 *   parent; a group with any row, current or removed, naming a held asset, so a save that soft-removes a held
 *   member or adds a staying one is refused; a schedule by its asset or its group; a closure by its schedule; an
 *   attachment by its asset or its event's asset; an entry by its case's asset; everything else by `assetId`);
 * - or (R77-B2b-GUARD) the row being written would **gain** a reference to a row the held graph owns — a staying
 *   event naming a held schedule, profile or measured definition, a subject naming a held schedule, a schedule naming
 *   a held meter definition or profile, a profile field naming a held definition — by exactly
 *   [TransferGraph.retain]'s rules: [TransferGraph.droppedBy] over the held graph's rows and
 *   [TransferGraph.entangledRefs] over the one row, less the references the stored row already carries. There is no
 *   second list of references, so an ordinary write can never make the next ordinary export entangled (P77-58); an
 *   edit that keeps an earlier reference (merged history) gains nothing and passes — that one is the export's to
 *   report.
 *
 * A command that fails its own validation still answers its own refusal first: the guard fires at the write.
 *
 * **Not guarded, each by ruling:**
 * - `AssetRepository.delete` — `DeleteAsset`, the explicit destructive "forget this local history" (R77-4); the
 *   transfer records have no foreign key and survive it.
 * - the catalog command's category rewrite — `RenameCategory` alone, which rewrites every asset of a category, held
 *   ones included — and only through [catalogAssets]: a rewrite there that changes nothing but `category` and
 *   `updatedAt` passes. Every other asset write, a category-only edit or an idempotent lifecycle call included, is a
 *   mutation and is refused (R77-17: every mutation answers 409). `PromoteCategory` writes catalog rows only.
 * - every port's `deleteAll` — the Replace restore's wipe, which wipes the records first (R77-13 refuses a held
 *   graph before anything is wiped).
 * - the derived and device-local tables (schedule state, the two delivery tables), the 2.6 link tombstones, the
 *   categories and the transfer records themselves: none of them is one of the eighteen ports.
 * - #15's SupplyItem catalog (C14): global, not asset-owned, so archiving or editing an item a held asset's rows name
 *   writes no held row and passes. A material line's link rides the profile and event ports, already guarded.
 *
 * Marking (C8) writes its archives before it appends the OUTs, the merge apply appends records last, and C15's
 * return appends its IN first, so each passes without a special case. A bulk writer must **skip** held rows, never
 * fail on them (RM-1: `RepairScheduleProviders` bounds its universe by `targetInService(…, held)`); `ResolveTag`
 * does not stamp a held asset's tag.
 *
 * The lookups read the unwrapped ports handed here — reads are the same through either.
 */
class HeldWriteGuard(
    private val transfers: TransferRecordRepository,
    private val events: EventRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val groups: GroupRepository,
    private val schedules: ScheduleRepository,
    private val cases: ServiceCaseRepository,
    private val links: LinkRepository,
) {

    fun assets(port: AssetRepository): AssetRepository = GuardedAssets(port, this)

    /**
     * The catalog command's asset port (mn-1): a rewrite of a stored asset that changes only its `category` and
     * `updatedAt` passes; every other write goes through [assets]'s guard. `AppGraph` hands it to `RenameCategory`
     * and to nothing else.
     */
    fun catalogAssets(port: AssetRepository): AssetRepository = CatalogAssets(port, assets(port))
    fun tags(port: TagRepository): TagRepository = GuardedTags(port, this)
    fun definitions(port: DefinitionRepository): DefinitionRepository = GuardedDefinitions(port, this)
    fun profiles(port: ProfileRepository): ProfileRepository = GuardedProfiles(port, this)
    fun events(port: EventRepository): EventRepository = GuardedEvents(port, this)
    fun groups(port: GroupRepository): GroupRepository = GuardedGroups(port, this)
    fun schedules(port: ScheduleRepository): ScheduleRepository = GuardedSchedules(port, this)
    fun closures(port: ClosureRepository): ClosureRepository = GuardedClosures(port, this)
    fun attachments(port: AttachmentRepository): AttachmentRepository = GuardedAttachments(port, this)
    fun references(port: ReferenceRepository): ReferenceRepository = GuardedReferences(port, this)
    fun activations(port: SeasonActivationRepository): SeasonActivationRepository = GuardedActivations(port, this)
    fun conditions(port: ConditionRepository): ConditionRepository = GuardedConditions(port, this)
    fun subjects(port: HealthSubjectRepository): HealthSubjectRepository = GuardedSubjects(port, this)
    fun cases(port: ServiceCaseRepository): ServiceCaseRepository = GuardedCases(port, this)
    fun entries(port: ServiceCaseEntryRepository): ServiceCaseEntryRepository = GuardedEntries(port, this)
    fun loans(port: AssetLoanRepository): AssetLoanRepository = GuardedLoans(port, this)

    /** #15 (C14): an Asset's applicability rows — the row written and the stored row it replaces or deletes. */
    fun assetSupplies(port: AssetSupplyRepository): AssetSupplyRepository = GuardedAssetSupplies(port, this)

    /**
     * #47 (C14): an Asset's installed components, the eighteenth port — the row written **and** the stored row an
     * update replaces. The composition rides its row, so a recomposition is an update like any other.
     */
    fun installedComponents(port: InstalledComponentRepository): InstalledComponentRepository =
        GuardedInstalledComponents(port, this)

    /**
     * #86 (C6, I8; R86-16): no **new** succession may name a held asset at either end. The one writer that re-inserts
     * rows naming one — a transfer back's kept successions, which existed before its transaction — takes the raw port
     * instead (`ApplyBackupMergePlan`, MJ-2).
     */
    fun successions(port: AssetSuccessionRepository): AssetSuccessionRepository = GuardedSuccessions(port, this)

    /**
     * The shared check: reads the held set once and, when it is not empty, runs [block], whose [Held.owned] and
     * [Held.references] throw. An empty held set writes as before, reading nothing more.
     */
    internal suspend fun check(block: suspend Held.() -> Unit) {
        val held = transfers.heldIds()
        if (held.isEmpty()) return
        Held(held).block()
    }

    internal inner class Held(private val held: Set<AssetId>) {

        /** Refuses when any asset [refs] reach — the row written, the row it replaces — is held. */
        suspend fun owned(refs: List<OwnerRef>) {
            ownersOf(refs).firstOrNull { it in held }?.let { throw AssetTransferredOut(it) }
        }

        /**
         * R77-B2b-GUARD: refuses when [row] — the one row being written, as the archive names it — would **gain** a
         * hard reference into the held graph, by [TransferGraph.retain]'s own rules: the references [row] makes, less
         * those [stored] — the row it replaces, if any — already makes.
         */
        suspend fun references(row: BackupData, stored: BackupData?) {
            val dropped = TransferGraph.droppedBy(heldGraph(), held)
            val kept = stored?.let { TransferGraph.entangledRefs(it, dropped) }.orEmpty().toSet()
            val ref = TransferGraph.entangledRefs(row, dropped).firstOrNull { it !in kept } ?: return
            throw AssetTransferredOut(targetOwner(ref))
        }

        /**
         * The rows of the held graph a reference can land on — every definition, profile and schedule of a held
         * asset, and every group a held asset ever joined with its schedules — as the archive names them; which of
         * them are dropped is [TransferGraph.droppedBy]'s answer, not this read's.
         */
        private suspend fun heldGraph(): BackupData {
            val candidateGroups = held.flatMap { groups.allWindowsFor(it) }.distinctBy { it.id }
            return NO_ROWS.copy(
                measurementDefinitions = held.flatMap { definitions.forAsset(it) }.map { it.toDto() },
                eventProfiles = held.flatMap { profiles.forAsset(it) }.map { it.toDto() },
                maintenanceGroups = candidateGroups.map { it.toDto() },
                maintenanceSchedules = (held.flatMap { schedules.forAsset(it) } +
                    candidateGroups.flatMap { schedules.forGroup(it.id) }).map { it.toDto() },
            )
        }

        /** The held asset whose row [ref] names: [TransferOwnership.heldOwnersOf]'s first, which fails closed. */
        private suspend fun targetOwner(ref: EntangledRef): AssetId {
            val lookup = Lookup()
            TransferOwnership.targetOf(ref)?.let { lookup.fetch(it) }
            return TransferOwnership.heldOwnersOf(ref, held, lookup).first()
        }
    }

    /** Every asset [refs] reach, through [TransferOwnership.resolve] over the rows read here. */
    private suspend fun ownersOf(refs: List<OwnerRef>): Set<AssetId> {
        val lookup = Lookup()
        refs.forEach { lookup.fetch(it) }
        return TransferOwnership.resolve(refs, lookup)
    }

    /** The rows an [OwnerRef] names, read ahead so [TransferOwnership.resolve] can answer from memory. */
    private inner class Lookup : OwnerLookup {
        private val eventsById = HashMap<EventId, AssetEvent>()
        private val casesById = HashMap<ServiceCaseId, ServiceCase>()
        private val schedulesById = HashMap<ScheduleId, MaintenanceSchedule>()
        private val groupsById = HashMap<GroupId, MaintenanceGroup>()
        private val linksById = HashMap<LinkId, ExternalLink>()
        private val definitionsById = HashMap<DefinitionId, MeasurementDefinition>()
        private val profilesById = HashMap<ProfileId, EventProfile>()

        suspend fun fetch(ref: OwnerRef) {
            when (ref) {
                is OwnerRef.OfAsset -> Unit
                is OwnerRef.OfEvent -> events.get(ref.id)?.let { eventsById[ref.id] = it }
                is OwnerRef.OfCase -> cases.get(ref.id)?.let { casesById[ref.id] = it }
                is OwnerRef.OfSchedule -> if (ref.id !in schedulesById) {
                    schedules.get(ref.id)?.let { schedulesById[ref.id] = it; TransferOwnership.of(it).forEach { r -> fetch(r) } }
                }
                is OwnerRef.OfGroup -> if (ref.id !in groupsById) groups.get(ref.id)?.let { groupsById[ref.id] = it }
                is OwnerRef.OfLink -> if (ref.id !in linksById) {
                    links.get(ref.id)?.let { linksById[ref.id] = it; TransferOwnership.of(it).forEach { r -> fetch(r) } }
                }
                is OwnerRef.OfDefinition -> definitions.get(ref.id)?.let { definitionsById[ref.id] = it }
                is OwnerRef.OfProfile -> profiles.get(ref.id)?.let { profilesById[ref.id] = it }
            }
        }

        override fun event(id: EventId): AssetEvent? = eventsById[id]
        override fun case(id: ServiceCaseId): ServiceCase? = casesById[id]
        override fun schedule(id: ScheduleId): MaintenanceSchedule? = schedulesById[id]
        override fun group(id: GroupId): MaintenanceGroup? = groupsById[id]
        override fun link(id: LinkId): ExternalLink? = linksById[id]
        override fun definition(id: DefinitionId): MeasurementDefinition? = definitionsById[id]
        override fun profile(id: ProfileId): EventProfile? = profilesById[id]
    }
}

/** An archive slice with no rows: each check below names the one list it fills. */
private val NO_ROWS = BackupData(assets = emptyList(), nfcTags = emptyList(), externalLinks = emptyList())

// ---- the eighteen wrapped ports: reads delegate, each write asks the guard first ---------------------------------

private class GuardedAssets(private val port: AssetRepository, private val guard: HeldWriteGuard) :
    AssetRepository by port {
    override suspend fun upsert(asset: Asset) {
        guard.check {
            val stored = port.get(asset.id)
            owned(TransferOwnership.of(asset) + stored?.let(TransferOwnership::of).orEmpty())
            references(NO_ROWS.copy(assets = listOf(asset.toDto())), stored?.let { NO_ROWS.copy(assets = listOf(it.toDto())) })
        }
        port.upsert(asset)
    }
    // `delete` is DeleteAsset's (R77-4): allowed, and the records outlive it. `deleteAll` is the Replace wipe's.
}

/** [HeldWriteGuard.catalogAssets]: the category rewrite of `RenameCategory`, and the guarded port for the rest. */
private class CatalogAssets(private val port: AssetRepository, private val guarded: AssetRepository) :
    AssetRepository by guarded {
    override suspend fun upsert(asset: Asset) {
        val stored = port.get(asset.id)
        if (stored != null && stored.copy(category = asset.category, updatedAt = asset.updatedAt) == asset) {
            port.upsert(asset)
        } else {
            guarded.upsert(asset)
        }
    }
}

private class GuardedTags(private val port: TagRepository, private val guard: HeldWriteGuard) : TagRepository by port {
    override suspend fun upsert(tag: TagBinding) {
        // The new target and the current one: rebinding a held asset's tag away is a write on the held asset.
        guard.check { owned(TransferOwnership.of(tag) + port.get(tag.id)?.let(TransferOwnership::of).orEmpty()) }
        port.upsert(tag)
    }

    override suspend fun delete(id: TagId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedDefinitions(private val port: DefinitionRepository, private val guard: HeldWriteGuard) :
    DefinitionRepository by port {
    override suspend fun upsert(d: MeasurementDefinition) {
        guard.check {
            val stored = port.get(d.id)
            owned(TransferOwnership.of(d) + stored?.let(TransferOwnership::of).orEmpty())
            references(
                NO_ROWS.copy(measurementDefinitions = listOf(d.toDto())),
                stored?.let { NO_ROWS.copy(measurementDefinitions = listOf(it.toDto())) },
            )
        }
        port.upsert(d)
    }

    override suspend fun delete(id: DefinitionId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedProfiles(private val port: ProfileRepository, private val guard: HeldWriteGuard) :
    ProfileRepository by port {
    override suspend fun upsert(p: EventProfile) {
        guard.check {
            val stored = port.get(p.id)
            owned(TransferOwnership.of(p) + stored?.let(TransferOwnership::of).orEmpty())
            references(NO_ROWS.copy(eventProfiles = listOf(p.toDto())), stored?.let { NO_ROWS.copy(eventProfiles = listOf(it.toDto())) })
        }
        port.upsert(p)
    }

    override suspend fun delete(id: ProfileId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedEvents(private val port: EventRepository, private val guard: HeldWriteGuard) :
    EventRepository by port {
    override suspend fun upsert(e: AssetEvent) {
        guard.check {
            val stored = port.get(e.id)
            owned(TransferOwnership.of(e) + stored?.let(TransferOwnership::of).orEmpty())
            references(NO_ROWS.copy(assetEvents = listOf(e.toDto())), stored?.let { NO_ROWS.copy(assetEvents = listOf(it.toDto())) })
        }
        port.upsert(e)
    }

    override suspend fun delete(id: EventId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedGroups(private val port: GroupRepository, private val guard: HeldWriteGuard) :
    GroupRepository by port {
    override suspend fun upsert(group: MaintenanceGroup) {
        guard.check {
            val stored = port.get(group.id)
            owned(TransferOwnership.of(group) + stored?.let(TransferOwnership::of).orEmpty())
            references(
                NO_ROWS.copy(maintenanceGroups = listOf(group.toDto())),
                stored?.let { NO_ROWS.copy(maintenanceGroups = listOf(it.toDto())) },
            )
        }
        port.upsert(group)
    }

    /**
     * #77 (C15): the return's group delete. A group with any row, current or removed, naming a held asset is a
     * write on that asset — so the return appends its IN first, and a group still naming another held asset is
     * refused.
     */
    override suspend fun delete(id: GroupId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedSchedules(private val port: ScheduleRepository, private val guard: HeldWriteGuard) :
    ScheduleRepository by port {
    override suspend fun upsert(schedule: MaintenanceSchedule) {
        guard.check {
            val stored = port.get(schedule.id)
            owned(TransferOwnership.of(schedule) + stored?.let(TransferOwnership::of).orEmpty())
            references(
                NO_ROWS.copy(maintenanceSchedules = listOf(schedule.toDto())),
                stored?.let { NO_ROWS.copy(maintenanceSchedules = listOf(it.toDto())) },
            )
        }
        port.upsert(schedule)
    }
}

private class GuardedClosures(private val port: ClosureRepository, private val guard: HeldWriteGuard) :
    ClosureRepository by port {
    override suspend fun insert(closure: OccurrenceClosure) {
        guard.check { owned(TransferOwnership.of(closure)) }
        port.insert(closure)
    }
}

private class GuardedAttachments(private val port: AttachmentRepository, private val guard: HeldWriteGuard) :
    AttachmentRepository by port {
    override suspend fun upsert(a: Attachment) {
        guard.check { owned(TransferOwnership.of(a) + port.get(a.id)?.let(TransferOwnership::of).orEmpty()) }
        port.upsert(a)
    }

    override suspend fun delete(id: AttachmentId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedReferences(private val port: ReferenceRepository, private val guard: HeldWriteGuard) :
    ReferenceRepository by port {
    override suspend fun upsert(reference: AssetReference) {
        guard.check {
            owned(TransferOwnership.of(reference) + port.get(reference.id)?.let(TransferOwnership::of).orEmpty())
        }
        port.upsert(reference)
    }

    override suspend fun delete(id: ReferenceId) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedActivations(private val port: SeasonActivationRepository, private val guard: HeldWriteGuard) :
    SeasonActivationRepository by port {
    override suspend fun insert(row: SeasonActivation) {
        guard.check { owned(TransferOwnership.of(row)) }
        port.insert(row)
    }
}

private class GuardedConditions(private val port: ConditionRepository, private val guard: HeldWriteGuard) :
    ConditionRepository by port {
    override suspend fun insert(row: AssetCondition) {
        guard.check { owned(TransferOwnership.of(row)) }
        port.insert(row)
    }
}

private class GuardedSubjects(private val port: HealthSubjectRepository, private val guard: HeldWriteGuard) :
    HealthSubjectRepository by port {
    override suspend fun upsert(subject: HealthSubject) {
        guard.check {
            val stored = port.get(subject.id)
            owned(TransferOwnership.of(subject) + stored?.let(TransferOwnership::of).orEmpty())
            references(NO_ROWS.copy(healthSubjects = listOf(subject.toDto())), stored?.let { NO_ROWS.copy(healthSubjects = listOf(it.toDto())) })
        }
        port.upsert(subject)
    }
}

private class GuardedCases(private val port: ServiceCaseRepository, private val guard: HeldWriteGuard) :
    ServiceCaseRepository by port {
    override suspend fun upsert(case: ServiceCase) {
        guard.check { owned(TransferOwnership.of(case) + port.get(case.id)?.let(TransferOwnership::of).orEmpty()) }
        port.upsert(case)
    }
}

private class GuardedEntries(private val port: ServiceCaseEntryRepository, private val guard: HeldWriteGuard) :
    ServiceCaseEntryRepository by port {
    override suspend fun insert(entry: ServiceCaseEntry) {
        guard.check { owned(TransferOwnership.of(entry)) }
        port.insert(entry)
    }
}

private class GuardedLoans(private val port: AssetLoanRepository, private val guard: HeldWriteGuard) :
    AssetLoanRepository by port {
    override suspend fun upsert(loan: AssetLoan) {
        guard.check { owned(TransferOwnership.of(loan) + port.get(loan.id)?.let(TransferOwnership::of).orEmpty()) }
        port.upsert(loan)
    }
}

private class GuardedAssetSupplies(private val port: AssetSupplyRepository, private val guard: HeldWriteGuard) :
    AssetSupplyRepository by port {
    override suspend fun insert(row: AssetSupply) {
        guard.check { owned(TransferOwnership.of(row)) }
        port.insert(row)
    }

    // The port writes the row whole, so the stored row's asset counts too: a row moved off a held asset is a write
    // on it.
    override suspend fun update(row: AssetSupply) {
        guard.check { owned(TransferOwnership.of(row) + port.get(row.id)?.let(TransferOwnership::of).orEmpty()) }
        port.update(row)
    }

    override suspend fun delete(id: String) {
        guard.check { owned(port.get(id)?.let(TransferOwnership::of).orEmpty()) }
        port.delete(id)
    }
}

private class GuardedInstalledComponents(
    private val port: InstalledComponentRepository,
    private val guard: HeldWriteGuard,
) : InstalledComponentRepository by port {
    override suspend fun insert(row: InstalledComponent) {
        guard.check { owned(TransferOwnership.of(row)) }
        port.insert(row)
    }

    // The port writes the row whole, its composition with it, so the stored row's asset counts too: a row moved off a
    // held asset is a write on it.
    override suspend fun update(row: InstalledComponent) {
        guard.check { owned(TransferOwnership.of(row) + port.get(row.id)?.let(TransferOwnership::of).orEmpty()) }
        port.update(row)
    }
}

private class GuardedSuccessions(private val port: AssetSuccessionRepository, private val guard: HeldWriteGuard) :
    AssetSuccessionRepository by port {
    override suspend fun append(row: AssetSuccession) {
        guard.check { owned(TransferOwnership.of(row)) }
        port.append(row)
    }
}
