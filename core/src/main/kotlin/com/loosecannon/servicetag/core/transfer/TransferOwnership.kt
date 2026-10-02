package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentOwner
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
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagTarget

/**
 * What a row points at to find the assets that own it (#77, C10 M2 and C12). Direct owners are
 * [OfAsset]; the rest name another row, which the caller resolves against whatever it can read.
 */
sealed interface OwnerRef {
    data class OfAsset(val id: AssetId) : OwnerRef
    /** An attachment of an event: the event's asset owns it. */
    data class OfEvent(val id: EventId) : OwnerRef
    /** A case's timeline entry: the case's asset owns it. */
    data class OfCase(val id: ServiceCaseId) : OwnerRef
    /** A closure: its schedule's owners own it. */
    data class OfSchedule(val id: ScheduleId) : OwnerRef
    /** A group schedule: every asset any row of the group names, current or removed. */
    data class OfGroup(val id: GroupId) : OwnerRef
    /** A tag on a 2.6 link: the link's asset, if it names one. */
    data class OfLink(val id: LinkId) : OwnerRef
    /** A measurement definition an entangled reference lands on: its asset owns it (NOTE 2). */
    data class OfDefinition(val id: DefinitionId) : OwnerRef
    /** An event profile an entangled reference lands on: its asset owns it (NOTE 2). */
    data class OfProfile(val id: ProfileId) : OwnerRef
}

/** How a caller answers an [OwnerRef] that names another row. `null`: the row is not there. */
interface OwnerLookup {
    fun event(id: EventId): AssetEvent?
    fun case(id: ServiceCaseId): ServiceCase?
    fun schedule(id: ScheduleId): MaintenanceSchedule?
    fun group(id: GroupId): MaintenanceGroup?
    fun link(id: LinkId): ExternalLink?
    fun definition(id: DefinitionId): MeasurementDefinition?
    fun profile(id: ProfileId): EventProfile?
}

/**
 * #77 — **the one home** of "which asset owns this row": the merge's M2 (C10) asks it of every row a plan
 * would insert, and B2b's write guard (C12) of every row a port would write, so a plan never calls
 * applicable an insert the guard would refuse (rm-2). An asset is owned by itself **and its parent**; a group
 * by every asset any of its rows names, current or removed; a schedule by its asset, or its group's owners;
 * a closure by its schedule's; an event's attachment by the event's asset; an entry by its case's asset; a
 * tag by the asset it targets (a link's tag by the link's asset); a succession by both its assets (#86). Everything
 * else by its own `assetId` — #15's applicability rows and #47's installed components included. A SupplyItem is
 * global: no asset owns it, so it has no overload here.
 * A guard also asks about a tag's **current** target — the stored row — which is a second call, not a rule.
 */
object TransferOwnership {
    fun of(asset: Asset): List<OwnerRef> = listOfNotNull(OwnerRef.OfAsset(asset.id), asset.parentAssetId?.let(OwnerRef::OfAsset))

    fun of(tag: TagBinding): List<OwnerRef> = when (val target = tag.target) {
        is TagTarget.AssetTarget -> listOf(OwnerRef.OfAsset(target.assetId))
        is TagTarget.LinkTarget -> listOf(OwnerRef.OfLink(target.linkId))
        TagTarget.None -> emptyList()
    }

    fun of(link: ExternalLink): List<OwnerRef> = listOfNotNull(link.assetId?.let(OwnerRef::OfAsset))
    fun of(definition: MeasurementDefinition): List<OwnerRef> = listOf(OwnerRef.OfAsset(definition.assetId))
    fun of(profile: EventProfile): List<OwnerRef> = listOf(OwnerRef.OfAsset(profile.assetId))
    fun of(event: AssetEvent): List<OwnerRef> = listOf(OwnerRef.OfAsset(event.assetId))

    fun of(attachment: Attachment): List<OwnerRef> = when (val owner = attachment.owner) {
        is AttachmentOwner.OfAsset -> listOf(OwnerRef.OfAsset(owner.assetId))
        is AttachmentOwner.OfEvent -> listOf(OwnerRef.OfEvent(owner.eventId))
    }

    fun of(group: MaintenanceGroup): List<OwnerRef> = group.members.map { OwnerRef.OfAsset(it.assetId) }.distinct()

    fun of(schedule: MaintenanceSchedule): List<OwnerRef> = when (val target = schedule.target) {
        is ScheduleTarget.AssetTarget -> listOf(OwnerRef.OfAsset(target.assetId))
        is ScheduleTarget.GroupTarget -> listOf(OwnerRef.OfGroup(target.groupId))
    }

    fun of(closure: OccurrenceClosure): List<OwnerRef> = listOf(OwnerRef.OfSchedule(closure.scheduleId))
    fun of(reference: AssetReference): List<OwnerRef> = listOf(OwnerRef.OfAsset(reference.assetId))
    fun of(activation: SeasonActivation): List<OwnerRef> = listOf(OwnerRef.OfAsset(activation.assetId))
    fun of(condition: AssetCondition): List<OwnerRef> = listOf(OwnerRef.OfAsset(condition.assetId))
    fun of(subject: HealthSubject): List<OwnerRef> = listOf(OwnerRef.OfAsset(subject.assetId))
    fun of(case: ServiceCase): List<OwnerRef> = listOf(OwnerRef.OfAsset(case.assetId))
    fun of(entry: ServiceCaseEntry): List<OwnerRef> = listOf(OwnerRef.OfCase(entry.caseId))
    fun of(loan: AssetLoan): List<OwnerRef> = listOf(OwnerRef.OfAsset(loan.assetId))
    /** #15 (C13, C14): an applicability row is its asset's; the SupplyItem it names is global and owns nothing here. */
    fun of(applicability: AssetSupply): List<OwnerRef> = listOf(OwnerRef.OfAsset(applicability.assetId))

    /**
     * #47 (C13, C14): an installed component is its asset's — current or removed, its composition riding the row. Its
     * parent is a row of the same asset, and the SupplyItems it names are global and own nothing here.
     */
    fun of(component: InstalledComponent): List<OwnerRef> = listOf(OwnerRef.OfAsset(component.assetId))

    /** #86 (C4 M2, C6): a succession is owned by both its assets — a new row may name a held asset at neither end. */
    fun of(succession: AssetSuccession): List<OwnerRef> =
        listOf(OwnerRef.OfAsset(succession.predecessorAssetId), OwnerRef.OfAsset(succession.successorAssetId))

    /** Every asset [refs] reach through [lookup]; a row the lookup cannot find owns nothing more. */
    fun resolve(refs: List<OwnerRef>, lookup: OwnerLookup): Set<AssetId> {
        val owners = LinkedHashSet<AssetId>()
        for (ref in refs) {
            when (ref) {
                is OwnerRef.OfAsset -> owners += ref.id
                is OwnerRef.OfEvent -> lookup.event(ref.id)?.let { owners += it.assetId }
                is OwnerRef.OfCase -> lookup.case(ref.id)?.let { owners += it.assetId }
                is OwnerRef.OfSchedule -> lookup.schedule(ref.id)?.let { owners += resolve(of(it), lookup) }
                is OwnerRef.OfGroup -> lookup.group(ref.id)?.let { owners += resolve(of(it), lookup) }
                is OwnerRef.OfLink -> lookup.link(ref.id)?.let { owners += resolve(of(it), lookup) }
                is OwnerRef.OfDefinition -> lookup.definition(ref.id)?.let { owners += resolve(of(it), lookup) }
                is OwnerRef.OfProfile -> lookup.profile(ref.id)?.let { owners += resolve(of(it), lookup) }
            }
        }
        return owners
    }

    /**
     * The row an [EntangledRef] lands on (`TransferGraph.entangledRefs`' four target tables), as an [OwnerRef]; null for
     * a target table this map does not know. A caller that reads ahead fetches it before [heldOwnersOf] resolves it.
     */
    fun targetOf(ref: EntangledRef): OwnerRef? = when (ref.targetTable) {
        "assets" -> OwnerRef.OfAsset(AssetId(ref.targetId))
        "maintenanceSchedules" -> OwnerRef.OfSchedule(ScheduleId(ref.targetId))
        "measurementDefinitions" -> OwnerRef.OfDefinition(DefinitionId(ref.targetId))
        "eventProfiles" -> OwnerRef.OfProfile(ProfileId(ref.targetId))
        else -> null
    }

    /**
     * #77 (NOTE 2) — **the one map** from an [EntangledRef] to the assets of [held] that own the row it lands on: M3
     * (C10) refuses by it and the write guard (C12, R77-B2b-GUARD) names its refusal by it. It fails **closed**: a
     * target table [targetOf] does not know, or a target [lookup] cannot resolve to a held asset, is owned by every
     * asset of [held] (in id order), never by none — so a reference added later can slip past neither.
     */
    fun heldOwnersOf(ref: EntangledRef, held: Set<AssetId>, lookup: OwnerLookup): Set<AssetId> {
        val every = held.sortedBy { it.value }.toCollection(LinkedHashSet())
        val target = targetOf(ref) ?: return every
        return resolve(listOf(target), lookup).filterTo(LinkedHashSet()) { it in held }.ifEmpty { every }
    }
}
