package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.maintenance.DueReadModel

/**
 * The 1.2 handlers over a [FakeGraph], built in one place because four suites need them.
 *
 * `ApiHandlers` grew exactly one collaborator in 1.2 — this one — so the four existing call sites
 * each gained one line rather than fifteen, and a later collaborator lands here instead of in all
 * of them.
 *
 * The due projection is B08's own class over the fake graph's real tables, with **no snooze**:
 * `schedule_local_delivery` is device-local and `/v1/due` does not carry a snooze field, so the
 * seam is satisfied with the honest constant rather than a reader this brief does not own.
 */
internal fun maintenanceHandlersFor(graph: FakeGraph): MaintenanceHandlers = MaintenanceHandlers(
    groups = graph.groups,
    schedules = graph.schedules,
    closures = graph.closures,
    assets = graph.assets,
    saveGroup = graph.saveGroup,
    archiveGroup = graph.archiveGroup,
    saveSchedule = graph.saveSchedule,
    pauseSchedule = graph.pauseSchedule,
    archiveSchedule = graph.archiveSchedule,
    postponeSchedule = graph.postponeSchedule,
    completeSchedule = graph.completeSchedule,
    completeGroupMembers = graph.completeGroupMembers,
    closeRound = graph.closeRound,
    due = DueReadModel(
        graph.schedules, graph.assets, graph.groups, graph.definitions,
        graph.recomputeSchedules, graph.todayPort, graph.assetHealthReadModel,
        snoozedUntilOf = { null },
    ),
    recompute = graph.recomputeSchedules,
    today = graph.todayPort,
)

/**
 * The 1.3 reference handlers over a [FakeGraph], here for the reason above: `ApiHandlers` grew one
 * collaborator in 1.3 too, and eight call sites each gained one line rather than four.
 *
 * The two use cases are built here rather than read off the graph because [FakeGraph] exposes the
 * reference **repository** and not the use cases over it; the production wiring in `AppGraph`
 * builds exactly these three objects from exactly these members, and `ReferenceHandlers`' own
 * `constructor(graph)` is what the app uses.
 */
internal fun referenceHandlersFor(graph: FakeGraph): ReferenceHandlers {
    // One instance answering both save and launch, as the production graph does (spec §4.2).
    val policy = LinkLaunchPolicy()
    return ReferenceHandlers(
        references = graph.references,
        assets = graph.assets,
        addReference = AddReference(
            graph.references, graph.assets, policy, graph.uow, graph.ids, graph.clock,
        ),
        updateReference = UpdateReference(graph.references, graph.uow, graph.clock),
    )
}

/**
 * The 1.4 handlers over a [FakeGraph], for the reason above: `ApiHandlers` grew one collaborator in
 * 1.4 too, and every call site gains this one line. Every member is read off the fake graph, which
 * mirrors `AppGraph`'s fields by name (master plan §1), so this is the production wiring exactly.
 */
internal fun seasonHealthHandlersFor(graph: FakeGraph): SeasonHealthHandlers = SeasonHealthHandlers(
    assets = graph.assets,
    activations = graph.seasonActivations,
    conditions = graph.conditions,
    healthSubjects = graph.healthSubjects,
    setSeasonMode = graph.setSeasonMode,
    setMaintenanceBreak = graph.setMaintenanceBreak,
    recordSeasonActivation = graph.recordSeasonActivation,
    getAssetSeason = graph.getAssetSeason,
    recordCondition = graph.recordCondition,
    saveHealthSubject = graph.saveHealthSubject,
    archiveHealthSubject = graph.archiveHealthSubject,
    setHealthPolicy = graph.setHealthPolicy,
    health = graph.assetHealthReadModel,
    attention = graph.attentionReadModel,
)
