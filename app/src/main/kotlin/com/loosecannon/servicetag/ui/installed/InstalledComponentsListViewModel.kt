package com.loosecannon.servicetag.ui.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One installed component as the cross-asset list draws it (#103, 1.7.1; owner ruling Q2): its name, the
 * asset it is fitted in, and [path] — the asset's name, then each component it sits inside, joined as the
 * share picker joins them (P69-20, `›`) — which is the "enough parent-asset context to be unambiguous"
 * the ruling asks for. A tap opens [assetId]'s detail, where the component is edited, replaced and removed.
 */
data class InstalledComponentListRow(
    val id: InstalledComponentId,
    val assetId: AssetId,
    val name: String,
    val path: String,
)

/**
 * The Installed components list's state (#103, 1.7.1): every **current** installed component of every
 * asset, read once on each open or return — the component repository observes per asset only, and a
 * list of what is fitted everywhere is a reading, not a subscription. It writes nothing and offers no
 * action: the ruling's "do not invent a second component-management surface" is honoured by the rows
 * opening the asset, where the one surface already is.
 */
class InstalledComponentsListViewModel(
    private val components: InstalledComponentRepository,
    private val assets: AssetRepository,
) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.installedComponents, graph.assets)

    private val _rows = MutableStateFlow<List<InstalledComponentListRow>?>(null)

    /** Null until the store has answered once, so the empty line is never drawn before it is true. */
    val rows: StateFlow<List<InstalledComponentListRow>?> = _rows.asStateFlow()

    /** Re-read. The screen calls it on every start, so a change made on the asset's detail shows on the way back. */
    fun refresh() {
        viewModelScope.launch { _rows.value = componentListRowsOf(components.all(), assets.all()) }
    }
}

/**
 * [components] as the list's rows: the current ones only (`removedOn == null`), each with its asset's
 * name and its parents' names as [InstalledComponentListRow.path], in `(asset name casefolded, asset id,
 * sortOrder, name casefolded, id)` order so an asset's components sit together under it. A component
 * whose asset the store no longer holds is left out: there is nothing its tap could open.
 */
internal fun componentListRowsOf(
    components: List<InstalledComponent>,
    assets: List<Asset>,
): List<InstalledComponentListRow> {
    val assetsById = assets.associateBy { it.id }
    val componentsById = components.associateBy { it.id }
    return components
        .filter { it.removedOn == null && it.assetId in assetsById }
        .sortedWith(
            compareBy(
                { assetsById.getValue(it.assetId).name.lowercase() },
                { it.assetId.value },
                { it.sortOrder },
                { it.name.lowercase() },
                { it.id.value },
            ),
        )
        .map { component ->
            InstalledComponentListRow(
                id = component.id,
                assetId = component.assetId,
                name = component.name,
                path = (listOf(assetsById.getValue(component.assetId).name) + parentNames(component, componentsById))
                    .joinToString(" › "),
            )
        }
}

/** The names of [component]'s parents, outermost first; bounded, so a malformed cycle cannot spin. */
private fun parentNames(
    component: InstalledComponent,
    componentsById: Map<InstalledComponentId, InstalledComponent>,
): List<String> {
    val names = ArrayDeque<String>()
    var parentId = component.parentId
    var hops = 0
    while (parentId != null && hops < MAX_NESTING) {
        val parent = componentsById[parentId] ?: break
        names.addFirst(parent.name)
        parentId = parent.parentId
        hops++
    }
    return names.toList()
}

private const val MAX_NESTING = 32
