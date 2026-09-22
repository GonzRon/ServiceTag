package com.loosecannon.servicetag.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every destination the single activity can show (D12 §3). Keys are `@Serializable` so
 * `rememberNavBackStack` can save and restore the stack through process death; they carry ids and
 * never whole domain objects, which is what keeps a restored stack honest after a backup import.
 */
sealed interface Route : NavKey {
    @Serializable data object Dashboard : Route
    @Serializable data object Assets : Route
    @Serializable data class AssetDetail(val id: String) : Route
    /**
     * New when [id] is null. [parentId] is the "Part of" a new asset opens with, which is how
     * "+ Add component" on a parent's screen makes a child (spec §9); it is ignored on an edit,
     * whose stored parent always wins.
     */
    @Serializable data class AssetEdit(val id: String?, val parentId: String? = null) : Route

    /** What this asset measures and what can be logged against it — the editors of spec §9. */
    @Serializable data class AssetSetup(val assetId: String) : Route

    /** New when [definitionId] is null, otherwise that reading of [assetId]. */
    @Serializable data class DefinitionEdit(val assetId: String, val definitionId: String?) : Route

    /** New when [profileId] is null, otherwise that action of [assetId]. */
    @Serializable data class ProfileEdit(val assetId: String, val profileId: String?) : Route

    /**
     * New when [eventId] is null; [profileId] null is a free-form entry with no profile behind it.
     * [kind] presets the kind of such an entry — the retirement follow-on of spec §7 opens
     * REPLACEMENT or NOTE — and is the name of an `EventKind`, never an index.
     */
    @Serializable data class EventEntry(
        val assetId: String,
        val profileId: String?,
        val eventId: String?,
        val kind: String? = null,
    ) : Route
    @Serializable data class EventDetail(val id: String) : Route

    @Serializable data object Scan : Route
    @Serializable data class TagResult(val format: String, val key: String) : Route
    @Serializable data class WriteTag(val targetKind: String, val targetId: String?, val label: String?) : Route
    @Serializable data object Backup : Route
    @Serializable data object Settings : Route

    /**
     * The loopback automation API's one screen (1.1.0, #46). A pushed destination reached from
     * Settings, exactly like [Backup] and [Scan]: the listener is alive only while this entry is on
     * top, so there is deliberately no deep link and no way to reach it but through Settings.
     */
    @Serializable data object DeveloperApi : Route

    /**
     * 1.2 — the Maintenance destination (spec §2.6, the navigation ruling): due work, schedules
     * including the paused ones the dashboard deliberately omits, maintenance groups and reminder
     * health. The third and last tab.
     */
    @Serializable data object Maintenance : Route

    /** One schedule, in full. Reached from a due row, the Schedules list and the schedule deep link. */
    @Serializable data class ScheduleDetail(val id: String) : Route

    /**
     * New when [scheduleId] is null, and then aimed at exactly one target: [targetAssetId] or
     * [targetGroupId], never both. On an edit the stored target always wins and both are ignored.
     */
    @Serializable data class ScheduleEdit(
        val scheduleId: String?,
        val targetAssetId: String? = null,
        val targetGroupId: String? = null,
    ) : Route

    /** One maintenance group, with its members and their progress. */
    @Serializable data class GroupDetail(val id: String) : Route

    /** New when [id] is null. */
    @Serializable data class GroupEdit(val id: String?) : Route

    /** #27's findings and their repairs, under Maintenance. */
    @Serializable data object ReminderHealth : Route

    /**
     * The scan completion sheet (#50, spec §2.8). [tagId] is the binding the read resolved, kept so
     * the sheet can say which tag it came from; it carries an id and never a payload.
     */
    @Serializable data class MaintenanceSheet(val assetId: String, val tagId: String? = null) : Route
}

/**
 * The three roots the bottom bar switches between; nothing else ever shows it.
 *
 * 1.2 added the third (spec §2.6, the navigation ruling): Maintenance is where due work, the
 * schedule list, the maintenance groups and reminder health live, and it is a tab because it is a
 * place the owner goes looking. [Route.Scan] is still **not** one — it is a pushed destination
 * reached from Settings or the dashboard's empty-state action (D12 §16 correction, spec §9):
 * normal tag reading is ambient dispatch, so "Scan" does not earn a slot in the primary navigation
 * for something the app never asks the user to open.
 *
 * The order is the ruled one and the bar iterates it, so a reorder here is a reorder on screen.
 */
val TopLevelRoutes: List<Route> = listOf(Route.Dashboard, Route.Assets, Route.Maintenance)

/**
 * The target kinds this app writes. 2.6 removed "link": a serialised back stack or an old process
 * can still carry `WriteTag("link", …)`, and it is refused at the nav boundary — no screen, no
 * provisioned row — rather than inside the write screen.
 */
internal val SupportedWriteTargetKinds: Set<String> = setOf("asset", "none")

internal fun Route.WriteTag.isSupported(): Boolean = targetKind in SupportedWriteTargetKinds

/**
 * The screens that read tags, and so the screens reader mode belongs to (2.7, #37). It is held
 * while one of them is on top of the back stack, which makes a move between them — inspect to
 * write, or back — no hand-over at all.
 *
 * `Route.TagResult` is deliberately absent. It reads nothing; it is where the ambient trampoline
 * lands, with no reader mode, exactly as at 2.6; and the inspect screen no longer pushes it, since
 * its result is drawn on the screen the user is already on. Adding it here would hold reader mode
 * over an ambient result and release it again the instant the sheet opened its asset — with the tag
 * still on the phone, which is the re-dispatch this release removed.
 *
 * An unsupported write route reads nothing either: the nav shell draws no screen for one and pops
 * it a frame later, so holding reader mode over it would be two binder calls to `NfcService` with
 * no sink ever installed.
 *
 * **1.2 adds no member here, and that is deliberate.** None of the new destinations reads a tag:
 * `Maintenance` and its four surfaces are lists, `ScheduleDetail` and `ScheduleEdit` are a screen
 * and a form, and `MaintenanceSheet` opens *after* a read has already resolved — the hold for that
 * read belongs to `Route.Scan` or to the ambient trampoline, neither of which is this. Adding one
 * would hold reader mode over a screen with no sink and re-introduce the #37 re-dispatch 2.7
 * removed.
 */
internal fun Route.readsTags(): Boolean = when (this) {
    Route.Scan -> true
    is Route.WriteTag -> isSupported()
    else -> false
}
