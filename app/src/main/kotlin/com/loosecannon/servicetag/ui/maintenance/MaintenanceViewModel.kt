package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NotificationPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** One maintenance group as the shell lists it: its name and how many members it currently holds. */
data class MaintenanceGroupRow(val id: GroupId, val name: String, val memberCount: Int)

/**
 * What the Maintenance destination draws: its four sections' contents, plus the two facts its
 * header needs.
 *
 * [dueWork] and [schedules] are two views of the **same** projection, and the difference between
 * them is the whole reason the projection keeps a sectionless row. Due work is what needs
 * attention — the ATTENTION and UPCOMING rows, in attention order, which is the same set the
 * dashboard's promotion rule calls actionable (decision 29). Schedules is **everything listed**,
 * the PAUSED ones included, because a paused schedule is exactly what an owner comes here to find
 * and the dashboard is the surface that deliberately omits it (master plan §11.1, T8).
 *
 * [loaded] is why the empty state does not flash: "no schedules" and "not read yet" look identical
 * in an empty list, and only one of them should be told to the owner.
 */
data class MaintenanceState(
    val dueWork: List<DueItem> = emptyList(),
    val schedules: List<DueItem> = emptyList(),
    val groups: List<MaintenanceGroupRow> = emptyList(),
    val dueCount: Int = 0,
    val worstSeverity: Severity? = null,
    /** Whether notifications are denied at the OS level, which D-22's one line explains. */
    val notificationsBlocked: Boolean = false,
    val loaded: Boolean = false,
) {
    /** A phone with no schedules at all: the one state the ratified empty line is for. */
    val isEmpty: Boolean get() = loaded && schedules.isEmpty()
}

/**
 * The Maintenance destination's state.
 *
 * It holds no rule of its own about what is due or what order it comes in: those are the shared
 * projection's, so this destination and the dashboard cannot disagree — which is the point of
 * decision 27. What it adds is the second view the dashboard does not have (every schedule,
 * including the paused ones), the group list, and the two header facts.
 *
 * Nothing here writes. The completion path is B14's `CompletionFlow`, which F4's "Log maintenance"
 * opens and this view model never reimplements (#50).
 */
class MaintenanceViewModel(
    schedules: ScheduleRepository,
    states: ScheduleStateRepository,
    groups: GroupRepository,
    private val due: DueReadModel,
    private val health: HealthSummary,
    private val notifications: NotificationPermission,
) : ViewModel() {

    constructor(graph: AppGraph) : this(
        graph.schedules,
        graph.scheduleStates,
        graph.groups,
        graph.dueReadModel,
        graph.healthSummary,
        graph.notificationPermission,
    )

    private val refreshes = MutableStateFlow(0)
    private val noticeDismissed = MutableStateFlow(false)

    val state: StateFlow<MaintenanceState> =
        combine(
            // Either schedule table moving can move a status; the group table moving can move a
            // round's required set. None of the three is what a row *says* — each is the signal to
            // re-derive from the projection (invariant 18).
            combine(schedules.observeAll(), states.observeAll()) { _, _ -> Unit },
            groups.observeAll(),
            refreshes,
            noticeDismissed,
        ) { _, groupRows, _, dismissed ->
            // An archived group keeps its history and leaves the listing (D-16).
            groupRows.filter { it.archivedAt == null } to dismissed
        }
            .map { (groupRows, dismissed) ->
                val items = due.items()
                MaintenanceState(
                    dueWork = items.filter {
                        it.section == AttentionSection.ATTENTION || it.section == AttentionSection.UPCOMING
                    },
                    schedules = items,
                    groups = groupRows.map { group ->
                        MaintenanceGroupRow(
                            id = group.id,
                            name = group.name,
                            // The windows that are open now. A closed window is history, not
                            // membership, and nothing here is the occurrence's required set — that
                            // is per round and the projection derives it.
                            memberCount = group.members.count { it.removedAt == null },
                        )
                    },
                    dueCount = items.count { it.countsAsDue },
                    worstSeverity = health.worstSeverity(),
                    notificationsBlocked = !dismissed && !notifications.granted(),
                    loaded = true,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), MaintenanceState())

    /** Re-read the platform facts and emit. The screen calls it on returning into composition. */
    fun refresh() = refreshes.update { it + 1 }

    /** D-22: the line is dismissible, and a denied permission disables nothing either way. */
    fun dismissReminderNotice() { noticeDismissed.value = true }
}
