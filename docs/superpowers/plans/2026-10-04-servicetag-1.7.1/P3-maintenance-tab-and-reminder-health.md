# P3 — #103: the Maintenance tab's layout, Reminder health under Utilities, the healthy state

**Read first:** plan.md §1 (#103), §3, §4 (the strings), §5, §6; `rulings.md` R171-4 and Q1–Q4; #103's body (the owner's layout ruling and acceptance list); `docs/superpowers/planning-policy.md` (the testing hierarchy).
**Lane:** alone, after P1 and P2, branched from `<base>` = the master commit carrying the plan (the controller names it).
**Blocked on:** the owner's gate — Q1, Q2, Q4 ruled and §4's strings ratified. **Nothing below is built before that.** This brief is written to the recommended answers; where a ruling differs, the controller amends the brief at the same commit as the ruling and re-dispatches.

## Goal

The Maintenance tab reads Due work, Schedules, then one grouped section of three peers — Maintenance groups, Supplies, Installed components — and no Reminders row. Settings › Utilities gains **Reminder health**, opening `Route.ReminderHealth` as the Dashboard badge does. The reminder-health page, with nothing found, draws a healthy state: one sentence, the time of the last check, and the checks that passed; with a finding, exactly what it draws today. No check, repair, schedule, group, supply or installed-component screen changes.

## Design (to the recommended rulings)

**The tab (Q1 recommended).** `MaintenanceScreen` draws, after Schedules: `MaintenanceSectionTitle(P171-1)`, then three `NavigatingRow`s in this order — `GROUPS_SECTION` → `onOpenGroups`, `SUPPLIES_SECTION` → `onOpenSupplies`, `INSTALLED_COMPONENTS_SECTION` → `onOpenInstalledComponents`. The `REMINDERS_SECTION` row and `onReminderHealth`'s row go; the top-bar badge keeps `onReminderHealth`. The inline `GroupList` moves to a new pushed screen, `MaintenanceGroupsScreen` (`Route.MaintenanceGroups`), titled `GROUPS_SECTION`, hosting the same `GroupList` with its create row (`onNewGroup`) — the composable moves, its behaviour does not. `MaintenanceViewModel` keeps `groups` only if the new screen's view model does not take it over; either way the state the tab no longer draws is not left in its state class unused.

**Installed components (Q2 recommended).** `Route.InstalledComponents`, a pushed destination; `InstalledComponentsListScreen` over a small `InstalledComponentsListViewModel` reading `installedComponents.all()` filtered to `removedOn == null`, joined to `assets` for the name, ordered by asset name then `sortOrder` then name; each row the component's name with the asset's name as the quiet second line; tapping opens `Route.AssetDetail(assetId)`. Empty: `NO_INSTALLED_COMPONENTS`. Read-only; no action, no search, no filter (R15-8's rule for the Supplies list applies).

**Settings.** `SettingsScreen` takes `onReminderHealth: () -> Unit` (no default: a wiring that forgets it does not compile) and draws `UtilityRow(icon = ServiceTagIcons.NotificationsOff, label = P171-2, onClick = onReminderHealth)` after `Categories` and before `HA_TITLE`. `ServiceTagRoot` wires it to `backStack.add(Route.ReminderHealth)`.

**The healthy state (Q4 recommended).** `ReminderHealth` records `checkedAt: Long?` beside `cached` whenever a run publishes (`refresh`, `runAndRepair`), from the graph's clock, exposed read-only. `HealthState` gains `checkedAt: Long?` and `passed: List<HealthCheck>`, where `HealthCheck` is a small enum of the **seven** checks in display order — NOTIFICATIONS, REMINDERS_ON, DIGEST_ALARM, BACKSTOP, APP_RESTRICTION, DELIVERY (`SCHEDULE_NO_PROVIDER` and `SCHEDULE_PROVIDER_DISABLED`), METER_BASELINES (`NO_DATA`) — each mapped from the finding codes it owns; `passed` is every check none of whose codes is among the rows. The view model computes it; the screen draws, **only when `loaded` and `rows` is empty**: P171-4, P171-5 with `checkedAt` through `displayDate` and the phone's short time format, `MaintenanceSectionTitle(P171-6)`, then one quiet line per `passed` entry (P171-7a…g) in enum order. With any row, the screen draws the rows as today and none of the healthy lines; `passed` is still computed (the tests read it) but not drawn, because a half-healthy list under a finding was not ruled and has no words. The page's title becomes P171-3.

**Routes.** `Route.MaintenanceGroups` and `Route.InstalledComponents` are `@Serializable data object`s with KDoc; `TopLevelRoutes` unchanged; `readsTags()` false for both (`RouteTest` pins it). `Route.ReminderHealth`'s KDoc says "under Settings › Utilities, and from the Dashboard badge".

## Files

**Create:** `app/…/ui/maintenance/MaintenanceGroupsScreen.kt`; `app/…/ui/installed/InstalledComponentsListScreen.kt`, `InstalledComponentsListViewModel.kt`; `app/src/test/…/ui/installed/InstalledComponentsListViewModelTest.kt`; `app/src/androidTest/…/ui/installed/InstalledComponentsListScreenTest.kt` (one Compose class: rows render with the asset's name, a tap reports the asset id, the empty line).
**Modify:** `MaintenanceScreen.kt` (the sections, the KDoc's navigation sentence, the new seams), `MaintenanceViewModel.kt` (if `groups` moves); `SettingsScreen.kt`; `Route.kt`, `ServiceTagRoot.kt`; `ReminderHealthViewModel.kt` (`HealthState`, `HealthCheck`, `ReminderHealth.checkedAt`), `ReminderHealthScreen.kt`; `MaintenanceViewModelTest.kt`, `ReminderHealthViewModelTest.kt`, `RouteTest.kt`; `MaintenanceShellTest.kt`, `ReminderHealthScreenTest.kt`, `SettingsBackupEntryTest.kt`; `docs/design/03-target-architecture.md` §7.3 (one amendment note: where the page lives since 1.7.1 and the healthy state); `docs/design/12-visual-design-apollo-service-binder.md` (the 1.2 navigation note: one sentence, "since 1.7.1 reminder health is under Settings › Utilities"); `docs/capabilities.md` where it names the Reminders row.
**Untouched:** `ReminderHealthCheck.kt`, `LocalReminderProvider.kt`, `BackstopWorker.kt`, `DashboardScreen.kt`, `DashboardViewModel.kt`, `HealthSummary.kt`; the groups', supplies' and installed-components' own screens and view models; `core/**`; `tools/**`.

## Test matrix

| layer | case | proves |
|---|---|---|
| JVM `MaintenanceViewModelTest` | the tab's state carries what the tab draws and nothing it no longer draws | no dead state |
| JVM `InstalledComponentsListViewModelTest` | current rows only; removed rows absent; order asset name → sortOrder → name; a component whose asset is retired or archived still listed (the asset's detail still opens); empty list; a tap target is the asset id | the list's whole contract |
| JVM `ReminderHealthViewModelTest` | nothing found → `loaded`, `rows` empty, `checkedAt` = the clock at the run, `passed` = all seven in order; one finding per code → that check absent from `passed` and the rest present; `SCHEDULE_NO_PROVIDER` and `SCHEDULE_PROVIDER_DISABLED` each alone remove DELIVERY only; a repair that clears the last finding → `passed` all seven and `checkedAt` moved to the refresh | the healthy state is derived from the same run |
| JVM `RouteTest` | the two new routes are not top-level and do not read tags | navigation invariants |
| Compose `MaintenanceShellTest` | `theFiveSectionsEachReachSomething` becomes the five navigations from the new layout: a due row, a Schedules-only row, the groups row (→ `groups`), the Supplies row, the Installed components row; `onAllNodesWithText("Reminders").assertCountEquals(0)`; `aPhoneWithNoSchedulesSaysSo` asserts the empty line and that the three peers are still reachable; `MaintenanceTabTest` no longer awaits `Reminders` and reaches the group form through the groups row then the create row | the tab's acceptance bullet |
| Compose `MaintenanceGroupsScreenTest` (new, or a case in `GroupScreensTest`) | the pushed list draws the groups and its create row reaches the group form | C5's reachability still holds |
| Compose `SettingsBackupEntryTest` | `reminderHealthRowIsPresentAndInvokesOnReminderHealth`, the shape of the four existing cases | the Utilities bullet |
| Compose `ReminderHealthScreenTest` | the reachability case re-routed: Settings › Utilities › Reminder health lists the provoked finding; the two repair cases unchanged; the title is P171-3 | the page still works where it moved to |
| Compose, healthy state | drawn only if a seam lets the test hand the screen a view model whose run found nothing (the screen already takes `model`); otherwise JVM-only, recorded in the brief's closing note. An emulator's own run is rarely healthy (`POST_NOTIFICATIONS` is denied), so the healthy state is never waited for on the device | honesty about what the device can show |

## Acceptance map (#103's four bullets)

1. The tab: `MaintenanceShellTest`, `MaintenanceTabTest`, `MaintenanceGroupsScreenTest`, `InstalledComponentsListScreenTest`.
2. Settings › Utilities and the Dashboard badge: `SettingsBackupEntryTest`, `ReminderHealthScreenTest` (reachability), `DashboardViewModelMaintenanceTest` unchanged and green.
3. The healthy state and the found state: `ReminderHealthViewModelTest`; the Compose case where the seam allows.
4. Device classes updated, JVM pins, the design docs: the greps below.

## Gate

- CI's command, zero failures, zero skips; `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --tests '*MaintenanceShellTest*' --tests '*MaintenanceTabTest*' --tests '*ReminderHealthScreenTest*' --tests '*SettingsBackupEntryTest*' --tests '*InstalledComponentsListScreenTest*' --tests '*GroupScreensTest*'`, then the whole connected suite once at the tip.
- `git grep -nE '^\s*NavigatingRow\(title = REMINDERS_SECTION' app/src/main` → 0; `git grep -nE 'REMINDERS_SECTION' app/src/main` → only the constant's own line and, if kept for the title, none (P171-3 replaces it — then the constant goes); `git grep -nE 'label = REMINDER_HEALTH_ROW' app/src/main/kotlin/com/loosecannon/servicetag/ui/settings/SettingsScreen.kt` → 1 (the P171-2 constant's name is the implementer's; the grep names whatever it is).
- `git grep -nE '1\.7\.1' docs/design/03-target-architecture.md docs/design/12-visual-design-apollo-service-binder.md` → 2.
- `git diff <base> --stat -- core tools .github` empty; `app/…/reminders/` untouched; hygiene; `git status` clean.

## Must NOT

Change a check, a repair, a finding sentence or a repair label; add a fourth tab; add an action to the installed-components list; draw any healthy line while a finding stands; write a preference or a row for the check time; ship a string §4 does not ratify.
