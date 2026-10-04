package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One maintenance group as the list draws it: its name, how many members it currently holds, and
 * whether it is archived.
 *
 * [archived] is carried rather than filtered on (master plan decision 39). Spec §2.3's "archiving
 * hides it and its schedules" is applied to the **schedules** and the due work — `DueReadModel`
 * drops a group target whose group is archived — but the group itself stays listed and visibly
 * distinguished: #55 requires an archived group to keep its maintenance history, and a group the
 * owner cannot find is a group whose history is unreachable. 1.2 has no filter UI to hide it behind.
 */
data class MaintenanceGroupRow(
    val id: GroupId,
    val name: String,
    val memberCount: Int,
    val archived: Boolean,
)

/**
 * The group list's state (#103, 1.7.1): every group, archived included and marked as such, in
 * `(name casefolded, id)` order — exactly what `MaintenanceViewModel` carried while the list was
 * drawn inline on the Maintenance tab. It reads the live flow and writes nothing: a row and the
 * create row only navigate.
 */
class MaintenanceGroupsViewModel(groups: GroupRepository) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.groups)

    /** Null until the store has answered once, so the create row is never drawn over a list not yet read. */
    val rows: StateFlow<List<MaintenanceGroupRow>?> = groups.observeAll()
        .map(::groupRowsOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)
}

/**
 * [groups] as the list's rows. Every group, archived included and marked (decision 39): what
 * archiving takes away is the group's *due work*, which the projection drops; taking the group
 * itself off the list as well would put the history D-16 preserves out of reach.
 */
internal fun groupRowsOf(groups: List<MaintenanceGroup>): List<MaintenanceGroupRow> =
    groups
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
        .map { group ->
            MaintenanceGroupRow(
                id = group.id,
                name = group.name,
                // The windows that are open now. A closed window is history, not membership, and
                // nothing here is the occurrence's required set — that is per round and the
                // projection derives it.
                memberCount = group.members.count { it.removedAt == null },
                archived = group.archivedAt != null,
            )
        }

/**
 * The Maintenance groups list (#103, 1.7.1), pushed from the Maintenance tab's groups row. It hosts
 * the same [GroupList] B15 drew inline on the tab, create row included, so C5's reachability ruling
 * of 2026-09-22 — the create affordance is the only in-app way to make the first group — holds one
 * tap from the tab. The title is the RATIFIED section label the row that opened it carries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceGroupsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
) {
    val model: MaintenanceGroupsViewModel = viewModel { MaintenanceGroupsViewModel(graph) }
    val rows by model.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(GROUPS_SECTION) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Nothing to draw until the store has answered once; the title and the way back are enough.
        val current = rows ?: return@Scaffold
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            GroupList(groups = current, onOpenGroup = onOpenGroup, onNewGroup = onNewGroup)
        }
    }
}
