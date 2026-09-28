package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.MaintenanceGroupDto
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetId

/**
 * #77 (C1) — what a transfer does with each list of a [BackupData]. One table, one class per list; a
 * list the table does not name fails `TransferTableClassificationTest`, so a future table (#15's supply
 * items and stock among them) needs a decision before it can ship (AC 18).
 */
enum class TransferTableClass {
    /** Belongs to one asset and travels with it: the asset row itself and everything hanging off it. */
    ASSET_OWNED,

    /** Spans assets: travels only when **wholly** inside the pack ([TransferGraph.whollyIn]). */
    CROSS_ASSET,

    /** Global rows keyed by name: only the rows a pack asset uses travel. */
    GLOBAL_IN_USE,

    /** The sender's own facts: never in a pack. */
    SENDER_ONLY,

    /** 2.6 leftovers: never in a pack. */
    TOMBSTONE,
}

object TransferTables {
    /**
     * Keyed by the archive's list names (`BackupData`'s serial names). Two lists hold rows of two
     * classes, and the row decides: an `nfcTags` row targeting a link is a TOMBSTONE, and a group
     * schedule (with its closures) is CROSS_ASSET, travelling with its group.
     */
    val CLASSES: Map<String, TransferTableClass> = mapOf(
        "assets" to TransferTableClass.ASSET_OWNED,
        "nfcTags" to TransferTableClass.ASSET_OWNED,
        "externalLinks" to TransferTableClass.TOMBSTONE,
        "measurementDefinitions" to TransferTableClass.ASSET_OWNED,
        "eventProfiles" to TransferTableClass.ASSET_OWNED,
        "assetEvents" to TransferTableClass.ASSET_OWNED,
        "attachments" to TransferTableClass.ASSET_OWNED,
        "maintenanceGroups" to TransferTableClass.CROSS_ASSET,
        "maintenanceSchedules" to TransferTableClass.ASSET_OWNED,
        "occurrenceClosures" to TransferTableClass.ASSET_OWNED,
        "assetReferences" to TransferTableClass.ASSET_OWNED,
        "seasonActivations" to TransferTableClass.ASSET_OWNED,
        "assetConditions" to TransferTableClass.ASSET_OWNED,
        "healthSubjects" to TransferTableClass.ASSET_OWNED,
        "assetCategories" to TransferTableClass.GLOBAL_IN_USE,
        "serviceCases" to TransferTableClass.ASSET_OWNED,
        "serviceCaseEntries" to TransferTableClass.ASSET_OWNED,
        "assetLoans" to TransferTableClass.SENDER_ONLY,
        // #77 (B2a): the transfer records are this installation's own custody facts; a pack never carries one.
        "transferRecords" to TransferTableClass.SENDER_ONLY,
    )

    /** Whether any row of [table] can be in a pack. An unclassified list never travels. */
    fun travels(table: String): Boolean = when (CLASSES[table]) {
        TransferTableClass.ASSET_OWNED, TransferTableClass.CROSS_ASSET, TransferTableClass.GLOBAL_IN_USE -> true
        TransferTableClass.SENDER_ONLY, TransferTableClass.TOMBSTONE, null -> false
    }
}

/** Why a selection cannot become a pack (C2). Every refusal is collected; none short-circuits another. */
sealed interface TransferRefusal {
    /** P77-15: [groupId] has a row, current or removed, naming a selected asset, and rows naming [stayingIds]. */
    data class MixedGroup(val groupId: String, val stayingIds: List<AssetId>) : TransferRefusal

    /** P77-16: [childId] is selected and its parent [parentId] is not (R77-8). */
    data class ParentNotSelected(val childId: AssetId, val parentId: AssetId) : TransferRefusal

    /** P77-17: [assetId] is lent out (R77-6). */
    data class OpenLoan(val assetId: AssetId, val loanId: String) : TransferRefusal

    /**
     * P77-18: a row of [assetId] — [table] row [rowId] — names [targetId], a row the pack does not carry
     * (an event's or a subject's schedule; a schedule's meter definition or profile — unreachable through
     * the commands, which refuse a foreign one and any on a group target: `ScheduleCommands.kt:291-314`).
     */
    data class OutsideReference(
        val assetId: AssetId,
        val table: String,
        val rowId: String,
        val targetId: String,
    ) : TransferRefusal
}

sealed interface TransferSelection {
    /** A pack's graph: [data] carries every row that travels, each list sorted by id; it decodes. */
    data class Selected(
        val rootIds: List<AssetId>,
        val assetIds: List<AssetId>,
        val data: BackupData,
    ) : TransferSelection

    data class Refused(val refusals: List<TransferRefusal>) : TransferSelection
}

/** One retained row naming a dropped one (C3): [table] row [rowId] names [targetTable] row [targetId]. */
data class EntangledRef(val table: String, val rowId: String, val targetTable: String, val targetId: String)

/**
 * The ids of every row [TransferGraph.retain] drops for a held set (C3), by table — [assets] is the held set
 * itself. One computation ([TransferGraph.droppedBy]) that `retain` and the write guard (C12,
 * R77-B2b-GUARD) share.
 */
data class DroppedRows(
    val assets: Set<String>,
    val groups: Set<String>,
    val schedules: Set<String>,
    val events: Set<String>,
    val links: Set<String>,
    val cases: Set<String>,
    val definitions: Set<String>,
    val profiles: Set<String>,
)

sealed interface TransferRetention {
    data class Retained(val data: BackupData) : TransferRetention
    data class Entangled(val refs: List<EntangledRef>) : TransferRetention
}

/**
 * #77 (C2, C3) — the cut between what leaves in a Transfer Pack and what stays, as pure functions over
 * the archive's rows. Nothing here reads a store or writes one.
 */
object TransferGraph {

    /**
     * "Wholly" (MJ-2), the one definition: a group is wholly in [ids] iff it has at least one membership
     * row and **every** row, current or removed, names an asset in [ids]. An empty group is wholly in
     * nothing, so it never travels and is never dropped.
     */
    fun whollyIn(group: MaintenanceGroupDto, ids: Set<String>): Boolean =
        group.members.isNotEmpty() && group.members.all { it.assetId in ids }

    /**
     * C2 (AC 1–3; R77-6 to R77-10): [rootIds] plus every descendant (forced); their asset-owned rows;
     * each group wholly in the selection with its schedules and closures; the custom categories the
     * selection uses; soft links as they are. Loans, 2.6 links and the tags on them never travel.
     *
     * Refused, every reason collected: a group with a row naming a selected asset that is not wholly in
     * the selection ([TransferRefusal.MixedGroup], removed rows counted); a root whose parent is not
     * selected; an open loan on any selected asset; a pack row naming a schedule, definition or profile
     * the pack does not carry. Any asset may be a root — archived and retired included (R77-10).
     *
     * @throws IllegalArgumentException when [rootIds] is empty or names an asset not in [data].
     */
    fun select(data: BackupData, rootIds: Collection<AssetId>): TransferSelection {
        val known = data.assets.associateBy { it.id }
        val roots = rootIds.map { it.value }.toSortedSet()
        require(roots.isNotEmpty()) { "a transfer needs at least one root" }
        roots.forEach { require(it in known) { "root $it is not an asset" } }

        // Roots and their whole subtree: a parent never leaves without its components (R77-8).
        val children = data.assets.filter { it.parentAssetId != null }.groupBy({ it.parentAssetId!! }, { it.id })
        val selected = HashSet<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (selected.add(id)) children[id].orEmpty().forEach { queue.addLast(it) }
        }

        val refusals = mutableListOf<TransferRefusal>()
        roots.forEach { root ->
            val parent = known.getValue(root).parentAssetId
            if (parent != null && parent !in selected) {
                refusals += TransferRefusal.ParentNotSelected(AssetId(root), AssetId(parent))
            }
        }
        data.assetLoans.filter { it.assetId in selected && it.returnedOn == null }.sortedBy { it.id }.forEach {
            refusals += TransferRefusal.OpenLoan(AssetId(it.assetId), it.id)
        }
        val groups = data.maintenanceGroups.filter { whollyIn(it, selected) }
        data.maintenanceGroups.sortedBy { it.id }.forEach { group ->
            if (group.members.any { it.assetId in selected } && !whollyIn(group, selected)) {
                val staying = group.members.map { it.assetId }.filter { it !in selected }.distinct().sorted()
                refusals += TransferRefusal.MixedGroup(group.id, staying.map(::AssetId))
            }
        }
        val groupIds = groups.map { it.id }.toSet()

        val schedules = carry("maintenanceSchedules", data.maintenanceSchedules) {
            it.assetId in selected || it.groupId in groupIds
        }
        val scheduleIds = schedules.map { it.id }.toSet()
        val definitions = carry("measurementDefinitions", data.measurementDefinitions) { it.assetId in selected }
        val profiles = carry("eventProfiles", data.eventProfiles) { it.assetId in selected }
        val events = carry("assetEvents", data.assetEvents) { it.assetId in selected }
        val subjects = carry("healthSubjects", data.healthSubjects) { it.assetId in selected }

        // What a carried row names must be carried too, or the pack would not decode (AC 3).
        val definitionIds = definitions.map { it.id }.toSet()
        val profileIds = profiles.map { it.id }.toSet()
        events.sortedBy { it.id }.forEach { event ->
            if (event.scheduleId != null && event.scheduleId !in scheduleIds) {
                refusals += outside(event.assetId, "assetEvents", event.id, event.scheduleId)
            }
        }
        subjects.sortedBy { it.id }.forEach { subject ->
            if (subject.scheduleId != null && subject.scheduleId !in scheduleIds) {
                refusals += outside(subject.assetId, "healthSubjects", subject.id, subject.scheduleId)
            }
        }
        schedules.sortedBy { it.id }.forEach { schedule ->
            val targets = listOfNotNull(
                schedule.meterDefinitionId?.takeIf { it !in definitionIds },
                schedule.profileId?.takeIf { it !in profileIds },
            )
            // A group schedule is refused in the name of its first member asset, by id.
            val owner = schedule.assetId
                ?: groups.firstOrNull { it.id == schedule.groupId }?.members?.minOfOrNull { it.assetId }
            if (owner != null) targets.forEach { refusals += outside(owner, "maintenanceSchedules", schedule.id, it) }
        }
        if (refusals.isNotEmpty()) return TransferSelection.Refused(refusals)

        val eventIds = events.map { it.id }.toSet()
        val cases = carry("serviceCases", data.serviceCases) { it.assetId in selected }
        val caseIds = cases.map { it.id }.toSet()
        val categoryKeys = data.assets.filter { it.id in selected }.mapNotNull { CategoryKey.of(it.category) }.toSet()
        val pack = BackupData(
            assets = carry("assets", data.assets) { it.id in selected },
            // Only tags on a pack asset; a link's tag is a tombstone like the link (C1).
            nfcTags = carry("nfcTags", data.nfcTags) { it.assetId != null && it.assetId in selected },
            externalLinks = carry("externalLinks", data.externalLinks) { it.assetId in selected },
            measurementDefinitions = definitions,
            eventProfiles = profiles,
            assetEvents = events,
            attachments = carry("attachments", data.attachments) { it.assetId in selected || it.eventId in eventIds },
            maintenanceGroups = if (TransferTables.travels("maintenanceGroups")) groups else emptyList(),
            maintenanceSchedules = schedules,
            occurrenceClosures = carry("occurrenceClosures", data.occurrenceClosures) { it.scheduleId in scheduleIds },
            assetReferences = carry("assetReferences", data.assetReferences) { it.assetId in selected },
            seasonActivations = carry("seasonActivations", data.seasonActivations) { it.assetId in selected },
            assetConditions = carry("assetConditions", data.assetConditions) { it.assetId in selected },
            healthSubjects = subjects,
            assetCategories = carry("assetCategories", data.assetCategories) { it.key in categoryKeys },
            serviceCases = cases,
            serviceCaseEntries = carry("serviceCaseEntries", data.serviceCaseEntries) { it.caseId in caseIds },
            assetLoans = carry("assetLoans", data.assetLoans) { it.assetId in selected },
            transferRecords = carry("transferRecords", data.transferRecords) { false },
        ).sorted()
        return TransferSelection.Selected(
            rootIds = roots.map(::AssetId),
            assetIds = selected.sorted().map(::AssetId),
            data = pack,
        )
    }

    /**
     * C3 — the archive without [held]: the held assets and their asset-owned rows, each group wholly in
     * [held] with its schedules and closures, and every tag, loan and 2.6 link naming a held asset (with
     * the tags on those links). Exactly the held set: no descendant or group member is added to it. A row
     * that stays and names a dropped row makes the whole answer [TransferRetention.Entangled], naming
     * every such reference — a later archive of what stays must still decode.
     */
    fun retain(data: BackupData, held: Set<AssetId>): TransferRetention {
        val dropped = droppedBy(data, held)
        val (heldIds, droppedGroups, droppedSchedules, droppedEvents, droppedLinks, droppedCases) = dropped
        val droppedDefinitions = dropped.definitions
        val droppedProfiles = dropped.profiles

        // A copy, not a construction: a list this function does not name is kept whole (mn-2), so a table
        // added later can never vanish from every ordinary backup without a sound.
        val kept = data.copy(
            assets = data.assets.filterNot { it.id in heldIds },
            nfcTags = data.nfcTags.filterNot { it.assetId in heldIds || it.linkId in droppedLinks },
            externalLinks = data.externalLinks.filterNot { it.id in droppedLinks },
            measurementDefinitions = data.measurementDefinitions.filterNot { it.id in droppedDefinitions },
            eventProfiles = data.eventProfiles.filterNot { it.id in droppedProfiles },
            assetEvents = data.assetEvents.filterNot { it.id in droppedEvents },
            attachments = data.attachments.filterNot { it.assetId in heldIds || it.eventId in droppedEvents },
            maintenanceGroups = data.maintenanceGroups.filterNot { it.id in droppedGroups },
            maintenanceSchedules = data.maintenanceSchedules.filterNot { it.id in droppedSchedules },
            occurrenceClosures = data.occurrenceClosures.filterNot { it.scheduleId in droppedSchedules },
            assetReferences = data.assetReferences.filterNot { it.assetId in heldIds },
            seasonActivations = data.seasonActivations.filterNot { it.assetId in heldIds },
            assetConditions = data.assetConditions.filterNot { it.assetId in heldIds },
            healthSubjects = data.healthSubjects.filterNot { it.assetId in heldIds },
            serviceCases = data.serviceCases.filterNot { it.id in droppedCases },
            serviceCaseEntries = data.serviceCaseEntries.filterNot { it.caseId in droppedCases },
            assetLoans = data.assetLoans.filterNot { it.assetId in heldIds },
        )

        val refs = entangledRefs(kept, dropped)
        return if (refs.isEmpty()) TransferRetention.Retained(kept) else TransferRetention.Entangled(refs)
    }

    /**
     * C3 — which rows [held] drops from [data], by id: the held assets; each group **wholly** in [held]; the
     * schedules of a held asset or a dropped group; and the events, 2.6 links, cases, definitions and profiles
     * of a held asset. [retain] cuts by it; the write guard (C12) asks it of the held graph's rows, so the two
     * can never disagree about what "dropped" means.
     */
    fun droppedBy(data: BackupData, held: Set<AssetId>): DroppedRows {
        val heldIds = held.map { it.value }.toSet()
        val droppedGroups = data.maintenanceGroups.filter { whollyIn(it, heldIds) }.map { it.id }.toSet()
        val droppedSchedules = data.maintenanceSchedules
            .filter { it.assetId in heldIds || it.groupId in droppedGroups }.map { it.id }.toSet()
        val droppedEvents = data.assetEvents.filter { it.assetId in heldIds }.map { it.id }.toSet()
        val droppedLinks = data.externalLinks.filter { it.assetId in heldIds }.map { it.id }.toSet()
        val droppedCases = data.serviceCases.filter { it.assetId in heldIds }.map { it.id }.toSet()
        val droppedDefinitions = data.measurementDefinitions.filter { it.assetId in heldIds }.map { it.id }.toSet()
        val droppedProfiles = data.eventProfiles.filter { it.assetId in heldIds }.map { it.id }.toSet()
        return DroppedRows(
            heldIds, droppedGroups, droppedSchedules, droppedEvents, droppedLinks, droppedCases, droppedDefinitions,
            droppedProfiles,
        )
    }

    /**
     * C3 — **the one list of hard references** (R77-B2b-GUARD): every reference a row of [kept] makes to a row
     * [dropped] names — a parent, a group member, a schedule's meter definition or profile, a profile field's
     * definition, an event's schedule, profile or measured definition, a subject's schedule. [retain] asks it of
     * the whole kept archive; the write guard (C12) asks it of the one row a write would store.
     */
    fun entangledRefs(kept: BackupData, dropped: DroppedRows): List<EntangledRef> {
        val heldIds = dropped.assets
        val droppedSchedules = dropped.schedules
        val droppedDefinitions = dropped.definitions
        val droppedProfiles = dropped.profiles
        val refs = mutableListOf<EntangledRef>()
        kept.assets.forEach { asset ->
            if (asset.parentAssetId in heldIds) refs += EntangledRef("assets", asset.id, "assets", asset.parentAssetId!!)
        }
        kept.maintenanceGroups.forEach { group ->
            group.members.filter { it.assetId in heldIds }.forEach {
                refs += EntangledRef("maintenanceGroups", group.id, "assets", it.assetId)
            }
        }
        kept.maintenanceSchedules.forEach { schedule ->
            schedule.meterDefinitionId?.takeIf { it in droppedDefinitions }?.let {
                refs += EntangledRef("maintenanceSchedules", schedule.id, "measurementDefinitions", it)
            }
            schedule.profileId?.takeIf { it in droppedProfiles }?.let {
                refs += EntangledRef("maintenanceSchedules", schedule.id, "eventProfiles", it)
            }
        }
        kept.eventProfiles.forEach { profile ->
            profile.fields.filter { it.definitionId in droppedDefinitions }.forEach {
                refs += EntangledRef("eventProfiles", profile.id, "measurementDefinitions", it.definitionId)
            }
        }
        kept.assetEvents.forEach { event ->
            event.scheduleId?.takeIf { it in droppedSchedules }?.let {
                refs += EntangledRef("assetEvents", event.id, "maintenanceSchedules", it)
            }
            event.profileId?.takeIf { it in droppedProfiles }?.let {
                refs += EntangledRef("assetEvents", event.id, "eventProfiles", it)
            }
            event.measurements.filter { it.definitionId in droppedDefinitions }.forEach {
                refs += EntangledRef("assetEvents", event.id, "measurementDefinitions", it.definitionId)
            }
        }
        kept.healthSubjects.forEach { subject ->
            subject.scheduleId?.takeIf { it in droppedSchedules }?.let {
                refs += EntangledRef("healthSubjects", subject.id, "maintenanceSchedules", it)
            }
        }
        return refs
    }

    /** A list travels only if its class says so (C1); then only the rows [keep] names. */
    private inline fun <T> carry(table: String, rows: List<T>, keep: (T) -> Boolean): List<T> =
        if (TransferTables.travels(table)) rows.filter(keep) else emptyList()

    private fun outside(assetId: String, table: String, rowId: String, targetId: String) =
        TransferRefusal.OutsideReference(AssetId(assetId), table, rowId, targetId)

    /** Every list by id (categories by key), so equal selections are equal whatever the input order. */
    private fun BackupData.sorted() = BackupData(
        assets = assets.sortedBy { it.id },
        nfcTags = nfcTags.sortedBy { it.id },
        externalLinks = externalLinks.sortedBy { it.id },
        measurementDefinitions = measurementDefinitions.sortedBy { it.id },
        eventProfiles = eventProfiles.sortedBy { it.id },
        assetEvents = assetEvents.sortedBy { it.id },
        attachments = attachments.sortedBy { it.id },
        maintenanceGroups = maintenanceGroups.sortedBy { it.id },
        maintenanceSchedules = maintenanceSchedules.sortedBy { it.id },
        occurrenceClosures = occurrenceClosures.sortedBy { it.id },
        assetReferences = assetReferences.sortedBy { it.id },
        seasonActivations = seasonActivations.sortedBy { it.id },
        assetConditions = assetConditions.sortedBy { it.id },
        healthSubjects = healthSubjects.sortedBy { it.id },
        assetCategories = assetCategories.sortedBy { it.key },
        serviceCases = serviceCases.sortedBy { it.id },
        serviceCaseEntries = serviceCaseEntries.sortedBy { it.id },
        assetLoans = assetLoans.sortedBy { it.id },
        transferRecords = transferRecords.sortedBy { it.id },
    )
}
