package com.loosecannon.servicetag.api

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
    states = graph.scheduleStates,
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
        graph.schedules, graph.scheduleStates, graph.assets, graph.groups, graph.definitions,
        graph.recomputeSchedules, graph.todayPort,
        snoozedUntilOf = { null },
    ),
    recompute = graph.recomputeSchedules,
    today = graph.todayPort,
)
