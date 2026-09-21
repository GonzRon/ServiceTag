package com.loosecannon.servicetag.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.api.DeveloperApiScreen
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.asset.AssetEditScreen
import com.loosecannon.servicetag.ui.asset.AssetsScreen
import com.loosecannon.servicetag.ui.backup.BackupScreen
import com.loosecannon.servicetag.ui.dashboard.DashboardScreen
import com.loosecannon.servicetag.ui.journal.EventDetailScreen
import com.loosecannon.servicetag.ui.journal.EventEntryScreen
import com.loosecannon.servicetag.ui.nfc.ReaderMode
import com.loosecannon.servicetag.ui.nfc.rememberReaderMode
import com.loosecannon.servicetag.ui.scan.ScanScreen
import com.loosecannon.servicetag.ui.scan.TagResultSheet
import com.loosecannon.servicetag.ui.scan.WriteTagScreen
import com.loosecannon.servicetag.ui.settings.SettingsScreen
import com.loosecannon.servicetag.ui.setup.AssetSetupScreen
import com.loosecannon.servicetag.ui.setup.DefinitionEditScreen
import com.loosecannon.servicetag.ui.setup.ProfileEditScreen
import kotlinx.coroutines.flow.SharedFlow

/**
 * The whole app below the theme: one back stack, one `NavDisplay`, one bottom bar (D12 §3).
 * The activity owns the two flows because only the activity sees intents; everything else about
 * navigation lives here, so no screen ever has to know what an `Intent` is.
 */
@Composable
fun ServiceTagRoot(
    graph: AppGraph,
    deepLinks: SharedFlow<Route>,
    snackbars: SharedFlow<String>,
    readerMode: ReaderMode = rememberReaderMode(),
) {
    val backStack = rememberNavBackStack(Route.Dashboard)
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { deepLinks.collect { backStack.add(it) } }
    LaunchedEffect(Unit) { snackbars.collect { snackbarHost.showSnackbar(it) } }

    val current = backStack.lastOrNull()

    // #37 — one reader-mode session for the activity, held for as long as a tag-reading screen is
    // on top. The inspect screen used to end its own session the moment it pushed a result, with
    // the tag still against the phone: the platform re-discovered that tag, dispatched it under
    // normal dispatch, and an inspect inside ServiceTag opened another app. A move between the two
    // tag screens is now no hand-over at all, which is runbook R1.
    //
    // What this does *not* cover, and deliberately: the ambient trampoline. `Route.TagResult` —
    // where `NfcDispatchActivity` lands — is not a tag-reading route, and the bound-tag auto-open
    // it still performs goes to `AssetDetail`, which reads no tags either, so nothing on that path
    // holds reader mode and the platform may re-dispatch a tag still in the field to this app's
    // own trampoline, exactly as it did before 2.7. Only tag-reading routes hold reader mode; that
    // is ratified, because holding it over the route an auto-open lands on reopens the spin
    // Decision 4 rejected. #37 as filed — an inspect of a foreign tag, which auto-opens nothing —
    // is the case this hold fixes; the inspect screen's bound tag is no longer an exception to it,
    // because 2.7.1 (#41) has `Route.Scan`'s own sheet name that tag and wait for `Open asset`, so
    // the answer stays on `Route.Scan`, the hold runs for the whole look, and it ends when the
    // owner leaves. The bound-tag row left for the runbook is the ambient tap, not an inspect.
    val readsTags = current is Route && current.readsTags()
    LifecycleResumeEffect(readerMode, readsTags) {
        readerMode.hold(readsTags)
        onPauseOrDispose { readerMode.hold(false) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (current is Route && current in TopLevelRoutes) {
                BottomBar(current = current, onSelect = { backStack.switchTopLevel(it) })
            }
        },
    ) { padding ->
        // NavDisplay decorates entries with a SaveableStateHolder and nothing else by default,
        // which leaves every `viewModel(...)` inside an entry resolving against the *activity's*
        // store: one instance per key for the life of the process. The sheets and forms here
        // compute their state in `init` or hold a `done` flag, so an activity-scoped model shows
        // the previous visit's answer on the next one, and `onCleared` — where the write screen
        // abandons an unwritten tag — never fires until the activity dies. Scoping each entry to
        // its own ViewModelStore restores per-visit models and clears them on pop. Order matters:
        // the ViewModelStore decorator must come after the saveable-state one.
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.padding(padding),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Route.Dashboard> {
                    DashboardScreen(
                        graph = graph,
                        onOpenAsset = { backStack.add(Route.AssetDetail(it)) },
                        onNewAsset = { backStack.add(Route.AssetEdit(null)) },
                        onBackup = { backStack.add(Route.Backup) },
                        onSettings = { backStack.add(Route.Settings) },
                        // Scan is a pushed destination, not a tab (D12 §16 correction): a plain
                        // push means one back press returns to the dashboard that sent it there.
                        onScan = { backStack.add(Route.Scan) },
                    )
                }
                entry<Route.Assets> {
                    AssetsScreen(
                        graph = graph,
                        onOpenAsset = { backStack.add(Route.AssetDetail(it)) },
                        onNewAsset = { backStack.add(Route.AssetEdit(null)) },
                    )
                }
                entry<Route.AssetDetail> { key ->
                    AssetDetailScreen(
                        graph = graph,
                        assetId = key.id,
                        onBack = { backStack.removeLastOrNull() },
                        onEdit = { backStack.add(Route.AssetEdit(it)) },
                        onSetup = { backStack.add(Route.AssetSetup(it)) },
                        onWriteTag = { backStack.add(Route.WriteTag("asset", it, null)) },
                        onBackup = { backStack.add(Route.Backup) },
                        onLogEvent = { asset, profile ->
                            backStack.add(Route.EventEntry(asset, profile, null))
                        },
                        onOpenEvent = { backStack.add(Route.EventDetail(it)) },
                        // "Part of" and a component row both push the other asset's own screen:
                        // the hierarchy is navigated, never nested inside one screen (spec §2).
                        onOpenAsset = { backStack.add(Route.AssetDetail(it)) },
                        onAddComponent = { backStack.add(Route.AssetEdit(null, parentId = it)) },
                        onLogOutcome = { asset, kind ->
                            backStack.add(Route.EventEntry(asset, null, null, kind = kind))
                        },
                        // DOCUMENTS with no attachment folder yet: 4A adds no route of its own.
                        onOpenSettings = { backStack.add(Route.Settings) },
                    )
                }
                entry<Route.AssetEdit> { key ->
                    AssetEditScreen(
                        graph = graph,
                        assetId = key.id,
                        parentId = key.parentId,
                        // A new asset opens on its own detail screen and the form leaves the stack:
                        // backing out of the asset should not land back on the form that made it.
                        onDone = { id ->
                            backStack.removeLastOrNull()
                            if (key.id == null) backStack.add(Route.AssetDetail(id))
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Route.AssetSetup> { key ->
                    AssetSetupScreen(
                        graph = graph,
                        assetId = key.assetId,
                        onBack = { backStack.removeLastOrNull() },
                        onEditDefinition = { asset, definition ->
                            backStack.add(Route.DefinitionEdit(asset, definition))
                        },
                        onEditProfile = { asset, profile ->
                            backStack.add(Route.ProfileEdit(asset, profile))
                        },
                    )
                }
                entry<Route.DefinitionEdit> { key ->
                    DefinitionEditScreen(
                        graph = graph,
                        assetId = key.assetId,
                        definitionId = key.definitionId,
                        // Saved, archived or deleted, the editor is done: the setup screen behind
                        // it is already watching the rows and redraws itself.
                        onDone = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Route.ProfileEdit> { key ->
                    ProfileEditScreen(
                        graph = graph,
                        assetId = key.assetId,
                        profileId = key.profileId,
                        onDone = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Route.EventEntry> { key ->
                    EventEntryScreen(
                        graph = graph,
                        assetId = key.assetId,
                        profileId = key.profileId,
                        eventId = key.eventId,
                        onDone = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                        kind = key.kind,
                    )
                }
                entry<Route.EventDetail> { key ->
                    EventDetailScreen(
                        graph = graph,
                        eventId = key.id,
                        // Edit is the same route in edit mode: the event names its own profile, so
                        // the entry screen does not need one handed to it.
                        onEdit = { asset, event -> backStack.add(Route.EventEntry(asset, null, event)) },
                        onBack = { backStack.removeLastOrNull() },
                        onOpenSettings = { backStack.add(Route.Settings) },
                    )
                }
                entry<Route.Scan> {
                    ScanScreen(
                        graph = graph,
                        readerMode = readerMode,
                        // 2.7 (#37): the read's answer is drawn on this screen rather than pushed
                        // as a `Route.TagResult` — pushing it took the screen, and with it the
                        // reader mode, out from under the tag. What the answer decides still
                        // navigates, exactly as the pushed sheet's entry did; the entry itself
                        // stays for the ambient trampoline, which is the only thing that uses it.
                        onOpenAsset = { backStack.add(Route.AssetDetail(it)) },
                        onNewAsset = { backStack.add(Route.AssetEdit(null)) },
                        onWriteTag = { backStack.add(it) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Route.TagResult> { key ->
                    TagResultSheet(
                        graph = graph,
                        format = key.format,
                        key = key.key,
                        onDismiss = { backStack.removeLastOrNull() },
                        // The sheet is done once it has pushed the next thing: a result is never
                        // somewhere to come back to.
                        onWriteTag = { backStack.removeLastOrNull(); backStack.add(it) },
                        onOpenAsset = { backStack.removeLastOrNull(); backStack.add(Route.AssetDetail(it)) },
                        onNewAsset = { backStack.removeLastOrNull(); backStack.add(Route.AssetEdit(null)) },
                    )
                }
                entry<Route.WriteTag> { key ->
                    if (key.isSupported()) {
                        WriteTagScreen(
                            graph = graph,
                            readerMode = readerMode,
                            key = key,
                            onDone = { backStack.removeLastOrNull() },
                        )
                    } else {
                        // 2.6: a link-kinded route is pre-split navigation. No screen, nothing
                        // provisioned, nothing written — it simply leaves the stack.
                        LaunchedEffect(key) { backStack.removeLastOrNull() }
                    }
                }
                entry<Route.Backup> {
                    BackupScreen(graph = graph, onBack = { backStack.removeLastOrNull() })
                }
                entry<Route.Settings> {
                    SettingsScreen(
                        graph = graph,
                        onBack = { backStack.removeLastOrNull() },
                        onReadTag = { backStack.add(Route.Scan) },
                        onBackup = { backStack.add(Route.Backup) },
                        onDeveloperApi = { backStack.add(Route.DeveloperApi) },
                    )
                }
                entry<Route.DeveloperApi> {
                    DeveloperApiScreen(
                        graph = graph,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            },
        )
    }
}

/**
 * Top-level switch keeps one entry per destination at the root: tapping Assets from three screens
 * deep inside Dashboard lands on Assets, not on Assets stacked on that history. `add` follows
 * `clear` unconditionally, so the stack is never left empty.
 */
private fun MutableList<NavKey>.switchTopLevel(route: Route) {
    clear()
    add(route)
}
