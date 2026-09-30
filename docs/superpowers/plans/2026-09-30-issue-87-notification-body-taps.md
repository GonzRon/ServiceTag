# #87 — maintenance notification body taps: plan and briefs (rev 1.1, 2026-09-30)

> **Rev 1.1** applies the plan review (`.superpowers/sdd/2026-09-30-issue-87/brief-review.md`: APPROVE WITH CONDITIONS):
> C-1 (R87-2, C6 and §7 state that the stack reset cancels a write already running on a popped screen, with the
> owner's alternative), C-2 (rows 11–12 name the exact "SERVICE RECORD"/"Back" matcher, bounded absence waits,
> `waitForIdle()`), C-3 (B2's exact-count greps anchored to code lines), and R87-1's explicit-intent note (m4).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-09-30-issue-87/progress.md`.
> Two briefs (§10, §11) on one branch `issue-87` from master **`a35cacb0`**, strictly sequential — B1, then B2 with
> `<base>` = B1's accepted tip; one task review each, at most one bounded fix round each; one whole-branch review; the
> merge; one merged-tip gate. Planned read-only from issue #87 (`.superpowers/sdd/2026-09-30-issue-87/issue-87.md`),
> the owner's scope ruling of 2026-09-30 (in the ledger) and the audit (`.superpowers/sdd/2026-09-30-issue-87/audit.md`,
> the inventory of record; every citation re-verified on `a35cacb0`). **Three owner rulings are open (§6); B1 is
> written to the recommended answers and does not start until they are given.**

**Goal:** the body of a posted maintenance notification navigates. The **summary** body opens the Dashboard, whose
first section is ATTENTION; an **item** body opens its exact schedule — the very `PendingIntent` its "Open" action
fires. A body tap writes nothing, consumes no nonce and leaves the quick actions exactly as shipped. No schema, backup
format, API or MCP change; no version bump; no notification refactoring; no #90 work.

**Inputs:** issue #87; the owner's scope ruling (2026-09-30); the audit; `docs/superpowers/planning-policy.md`; the #11,
#79 and #72 precedents the audit cites. `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, `T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`,
`CT/` = `core/src/test/kotlin/com/loosecannon/servicetag/core/`, `AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`.

## Global constraints

- **Roadmap (owner, 2026-09-30):** #85 DONE → **#87** → release 1.5.0 → #90. #87 is the last code change before the
  cut. **No version bump:** `versionName = "1.4.1"`, `versionCode = 17` (`app/build.gradle.kts:51-52`). **Schema and
  format stay 16** (`A/di/AppGraph.kt:1011`, `C/backup/BackupCodec.kt:144`): no entity, DAO, migration, `app/schemas`,
  codec, DTO, API route or MCP tool moves.
- **Tests.**
  - JVM first, with the existing fakes (`FakeReminderNotifications`, the `QuickActionsTest` fixtures). A device test
    only for a platform-only fact: here, what the real `NotificationManager` holds as the posted `contentIntent` and
    where the real activity lands when it is sent. Exactly **two new device cases**, inside the shipped
    `QuickActionDeviceProofTest`; **no new device class**.
  - **No #90 harness work:** no gate, runner, `tools/emulator/*` or shared-helper change (`AT/ui/AppSmokeTest.kt`'s
    `clearInstall`/`awaitText` are used, not edited); no batching; no Robolectric or JVM Compose; no UiAutomator shade
    taps (#62).
  - **No rerun until green:** a device failure is reported with its log and stops the brief; never retried away. One
    device mutation, because AC 8's "remove the content intent" is observable on the posted notification itself.
  - Every counted RED is a real mutation run with `--no-build-cache --rerun-tasks` (a `--tests` filter allowed), its
    failing assertion quoted, the mutation reverted before the commit. A row with no natural RED says why.
- **The merged-tip gate** runs once, after the merge (§8). Its 14- and 15-minute lines are **reporting only**.
- **Review budget:** one task review per brief; at most one bounded fix round per brief; one scoped re-review only for
  a substantive correctness finding; mechanical fixes (under about 50 lines) close by controller inspection plus the
  automated gates; one whole-branch review; the merge immediately after it; then the gate.
- **Bounded:** every brief has a counted-mutation cap and a time box — **1 hour target, 2 hours hard stop**; a fix
  round is **45 minutes**, at most 3 mutation runs (at most 1 on the device), touches only its findings' files, and
  stops on the first finding it cannot close. Past 1 hour a brief reports what is done, proven and not proven.
- **Standing rules.**
  - Every user-visible string is ratified before it ships (§5: **none** is needed).
  - The 2.6 tombstones `external_link` / `externalLinks` are never touched; the gitlink `libs/nfc-tag-core` stays
    `7e0377a`.
  - Commits: one casual lowercase subject line; no body, no trailers, no AI attribution. No personal data: fictional
    fixtures only ("Pump house filter", "Example Heater", ids like `device-body-1`).
  - Device runs on `emulator-5554` only. Implementers never start, stop or restart the emulator or adb
    (`tools/emulator/prepare-emulator.sh` only waits for the device and settles one setting).

## 1. Scope

| issue AC | contract | brief | rows |
|---|---|---|---|
| 1 summary body → Dashboard / ATTENTION | C2, C4, C5, C6 | B1, B2 | 5, 6, 8, 9, 10, 12 |
| 2 item body → its exact schedule | C1, C3, C4 | B1, B2 | 1, 4, 11 |
| 3 item body == the Open action | C1, C4 | B1, B2 | 1, 4, 11 |
| 4 no domain write, no nonce | C7 | B1, B2 | 2, 11, 12 |
| 5 quick actions unchanged | C7 | B1 | 1, 4, 6, 10 |
| 6 explicit, immutable, security invariants | C4 | B1, B2 | 6, 7, 11, 12 |
| 7 device proof of the real content intent + JVM target selection | all | B1, B2 | 1–12 |
| 8 removing the content intent fails the tests | C3 | B1, B2 | 4, 7, 11, 12 |

**Out of scope, once each:** the warranty and loan warning bodies (they stay inert as shipped; row 3 pins it so the
scope cannot widen silently; a follow-up issue if the owner wants them); grouping (`setGroup`) and auto-cancel; the
platform's own auto-group summary (§7); the pre-existing push of a schedule already on top (§7); the Maintenance tab;
DigestPolicy, its words, tags and fatigue rules; #90.

## 2. Contracts

**C1 — the item body's target.** A new pure decision on `QuickActions`:

```kotlin
/** What the body of this subject's notification opens: navigation only, no nonce, no read. */
fun contentFor(key: SubjectKey): QuickActionTarget? = when (key) {
    is SubjectKey.Schedule -> QuickActionTarget.OpenSchedule(key.scheduleId)
    is SubjectKey.Deadline -> null // #87 scope: maintenance only; warranty and loan bodies stay as shipped
}
```

- Not `suspend`; reads neither `shapes` nor `nonces`; writes nothing.
- For every schedule shape — `QUICK`, `FORM`, `QUICK` with a meter rule, group-targeted — `contentFor(Schedule(id))`
  equals the target of the "Open" action `forSchedule(id)` returns (data-class equality).
- Derived from the **key alone**, never from the action list, so it is present in the vanished-schedule race where
  `forSchedule` offers nothing; the schedule detail's own missing-id handling then applies, exactly as for the shipped
  `servicetag://schedule/<uuid>` link ("whether the id exists is the screen's question").

**C2 — the summary body's target.** A new `QuickActionTarget.OpenDashboardAttention` — a `data object`, **not** a
`ScheduleActionTarget`, carrying no id and no nonce; its kdoc says navigation only, `servicetag://dashboard`, invariant
57. Every `SummaryPost` is posted with it. A summary never names a schedule (the issue: "a multi-item summary must not
arbitrarily choose one schedule").

**C3 — the seam.** `ReminderNotifications` changes in two signatures and nothing else, with **no default values**, so
the compiler finds every caller:

```kotlin
fun postItem(post: ItemPost, actions: List<QuickAction>, content: QuickActionTarget?)
fun postSummary(summary: SummaryPost, content: QuickActionTarget)
```

- `LocalReminderProvider.reconcile` (`:218-219`) passes `quickActions.contentFor(post.key)` and
  `QuickActionTarget.OpenDashboardAttention`. The order of writes (rows, nonce clearance, stamps, then posts) is
  untouched: `contentFor` issues nothing.
- `AndroidReminderNotifications`: the item builder sets `setContentIntent(intents.pendingIntentFor(it))` when `content`
  is non-null; the summary builder always sets it. **Nothing else in either builder moves:** `setAutoCancel(false)`
  stays on both (the shade is the provider's projection, invariant 44), no `setGroup`, no new action, labels, icons,
  channels, ids and `onlyAlertOnce` unchanged. The kdoc at `Notifications.kt:53-57` is rewritten: the body is
  navigation-only and opens what C1/C2 say.
- `FakeReminderNotifications` (`T/reminders/LocalReminderProviderTest.kt:50`) records `postedContent` (one entry per
  item post, in post order) and `summaryContent`.
- The four hand-built `postItem` calls in `AT/reminders/ReminderPlatformDeviceProofTest.kt` (`:218`, `:281`, `:434`,
  `:443`) pass `null` — the shipped behaviour for a tag-identity post and for deadline posts. That is the only edit to
  that file.

**C4 — the `PendingIntent`s.**
- `AndroidQuickActionIntents.pendingIntentFor(OpenDashboardAttention)` goes through the **existing** `activity()`
  site with a new kind code `REQUEST_OPEN_ATTENTION = 2306` and a link built, not parsed:
  `Uri.Builder().scheme(DeepLinkRoute.SCHEME).authority(DASHBOARD_HOST).build()`, `DASHBOARD_HOST = "dashboard"` named
  once beside `SCHEDULE_HOST`. So: explicit component `MainActivity`; `ACTION_VIEW`; `getActivity` **directly** (no
  receiver, invariant 55); `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` on the one call line (invariant 54); no extra.
- **The item body is the Open action's own `PendingIntent`.** It is built by the same call with the same target
  (`OpenSchedule(id)` → code 2304, the same intent), so the platform holds one record and `PendingIntent.equals` is
  true. No new item code. It must never be 2303: `CompletionForm`'s intent differs from Open's only by an extra that
  `filterEquals` ignores.
- Six kinds, six distinct codes (2301–2306); still exactly one `getBroadcast` and one `getActivity` site in
  `QuickActions.kt`, none in `Notifications.kt`; `DigestAlarm`'s 2101 untouched.

**C5 — the link grammar (R87-1).**
- Core: `DeepLink.Dashboard` (`data object`); `DeepLinkRoute.parse("servicetag", "dashboard", [])` →
  `DeepLink.Dashboard`; the host with any path segment → `DeepLink.Malformed(…)` (its reason is never shown; the owner
  sees `routeFrom`'s shipped sentence). Every other host answers as today (`group`, `health` still not ours).
- App: `routeForDeepLink(DeepLink.Dashboard) == Route.Dashboard`; `routeForQuickCompletion(DeepLink.Dashboard) == null`.
  **No new `Route`.**
- **The manifest does not change:** no `android:host="dashboard"`; the app's explicit `PendingIntent` needs none, and
  the public BROWSABLE contract stays `asset` · `tag` · `schedule` (`ScheduleDeepLinkTest.kt:91` unchanged). Another
  app can still reach it by an explicit intent to the exported `MainActivity` — harmless, it only navigates — and the
  `DeepLink.Dashboard` kdoc says so beside the "no group host" paragraph.

**C6 — the landing (R87-2).** One pure, file-level function in `A/ui/nav/ServiceTagRoot.kt`, beside `switchTopLevel`,
called by the collector at `:76` in place of `backStack.add(it)`:

```kotlin
/**
 * A top-level route replaces the stack, as its tab does; every other link is pushed, as shipped.
 * R87-2: the reset pops every entry above the root and clears its view-model store, so a write
 * already running on a popped screen (import, asset save, replace, transfer marking) is cancelled.
 */
internal fun MutableList<NavKey>.openDeepLink(route: Route) {
    if (route in TopLevelRoutes) switchTopLevel(route) else add(route)
}
```

- A cold start (`[Dashboard]`) stays `[Dashboard]` — never a second identical key (audit §6.5).
- **The consequence (R87-2, option (a)).** `switchTopLevel` pops every entry above the root, and `ServiceTagRoot`
  scopes each entry to its own `ViewModelStore`, cleared on pop — which cancels that entry's `viewModelScope`. Four
  screens block back for exactly this reason while their write runs: `TransferImportScreen.kt:58` (`state.importing`;
  MJ-1: "leaving would cancel it mid-write"), `AssetEditScreen.kt:312` (`state.saving`, #67 R67-8's copies),
  `ReplaceAssetScreen.kt:94` (`state.saving`) and `TransferFlowScreen.kt:99` (`PackPhase.MARKING`). The bottom bar can
  never reach them (it shows only at a root); a summary tap can, from any depth, even with the guarded screen below a
  pushed detail. The tap itself writes nothing, but it can abandon the owner's own write in flight (and an idle unsaved
  form). The plan is written to option (a), reset regardless; option (b) is in R87-2.
- Behaviour-preserving for every shipped link: `AssetDetail`, `ScheduleDetail` (with or without `complete`) and
  `TagResult` are pushed exactly as before; no shipped link produces a top-level route.
- No scroll-to-top, no filter reset, no anchor: the Dashboard is reached exactly as its tab reaches it, and ATTENTION
  is its first section (`DashboardScreen.kt:183-212`).

**C7 — navigation only.** A body tap travels shade → activity `PendingIntent` → `MainActivity.routeFrom` →
`routeForDeepLink` → `openDeepLink`: no receiver, no use case, no repository, no nonce issued, read or consumed, no
change to the shade. The quick actions keep their labels, order, targets, nonces and codes 2301–2305;
`QuickActionReceiver` and `MainActivity.routeFrom` are not edited beyond C5's one mapping line;
`theLinkHandlerNamesNoMutation` holds.

**C8 — the upgrade transition (R87-3).** Nothing forces a re-post: tags stay as they are (DigestPolicy untouched), so a
notification posted by 1.4.1 that is still standing after the upgrade keeps its inert body until its next re-post.

## 3. Test matrix

| row | hazard | test (class · case) | RED mutation |
|---|---|---|---|
| **B1** 1 | C1: the item body opens something other than Open | `T/reminders/QuickActionsTest` · new `aScheduleBodyOpensWhatItsOpenActionOpens` — for the QUICK, FORM, QUICK+meter and group shapes, `contentFor(Schedule(id)) == OpenSchedule(id)` and `==` the Open action's target from `forSchedule(id)`; for a vanished schedule (`shape = null`) still `OpenSchedule(id)` | `Schedule → CompletionForm(key.scheduleId)` |
| 2 | C1/C7: a body target that reads or issues | same class · new `aBodyTargetReadsAndIssuesNothing` — a counting shape source sees 0 reads; the delivery row stays null (no nonce) | none counted: a non-`suspend` `contentFor` cannot reach the suspend ports; the row is the pin that keeps it so |
| 3 | scope: deadline bodies widen silently | same class · new `aDeadlineBodyStaysInert` — warranty and loan keys → null | `Deadline → OpenAsset(…)` |
| 4 | C3: the provider drops the body (the 1.4.1 behaviour, AC 8 at the seam) | `T/reminders/LocalReminderProviderTest` · new `everyScheduleItemIsPostedWithItsOpenTargetAsItsBody` — one OVERDUE and one DUE `QUICK` schedule plus one warranty in its window: each schedule post's recorded content `== OpenSchedule(its id) ==` its Open action's target; the warranty's is null; the recorded actions unchanged | the provider passes `null` as `content` |
| 5 | C2: the summary picks one schedule | same class · new `theSummaryIsPostedWithTheDashboardAttentionBody` — a run posting two items and a summary: `summaryContent == OpenDashboardAttention`; an identical second run posts nothing (invariant 45 as shipped) | the provider passes `OpenSchedule(<first post's id>)` |
| 6 | C4: a code collision or a second activity site | `T/reminders/QuickActionReceiverTest` · `everyPendingIntentTheBuilderMakesIsImmutableAndTheFiveActionsStayFive` — **the one named pin move:** renamed `…AndTheSixKindsStaySix`, `REQUEST_OPEN_ATTENTION` added to `codes`, `5 → 6`; the immutability, one-`getBroadcast`, one-`getActivity`, no-`getService` and receiver-component assertions unchanged | `REQUEST_OPEN_ATTENTION = 2304` |
| 7 | C3: a builder sets no body (AC 8 at the builder) | same class · new `bothNotificationShapesSetABodyAndNeitherAutoCancels` — a source scan of `Notifications.kt` (`sourceFile`, `ManifestContractTest.kt:409`): `'\.setContentIntent\(intents\.pendingIntentFor\('` → 2; `'\.setAutoCancel\(false\)'` → 2; `'setGroup\('` → 0; `'PendingIntent\.get'` → 0 | the summary's `setContentIntent` line deleted |
| 8 | C5: the host is not parsed | `CT/links/DeepLinkRouteTest` · new `dashboardRoute` — no segment → `DeepLink.Dashboard`; one uuid segment → `Malformed`; `otherSchemesAndHostsAreNotOurs` unchanged | `parse` answers null for `dashboard` (one run, rows 8 and 9 RED together) |
| 9 | C5: the link lands elsewhere or opens a completion; the host leaks into the manifest | `T/links/ScheduleDeepLinkTest` · new `theDashboardLinkLandsOnTheDashboardAndOpensNoCompletion` (`routeForDeepLink(parse(…dashboard…)) == Route.Dashboard`; `routeForQuickCompletion(…) == null`) and `theDashboardHostIsNotInTheManifest` (0 lines carrying `android:host="dashboard"`); `:37`, `:46`, `:56`, `:69`, `:91` unchanged | as row 8 |
| 10 | C6: a duplicate Dashboard, or a shipped link no longer pushed | `T/ui/nav/RouteTest` · new `aTopLevelLinkReplacesTheStackAndEveryOtherLinkIsPushed` — `[Dashboard]` + Dashboard → `[Dashboard]`; `[Assets, AssetDetail(a)]` + Dashboard → `[Dashboard]`; `[Maintenance, ScheduleDetail(s), ScheduleEdit(s)]` + Dashboard → `[Dashboard]`; `[Dashboard]` + `ScheduleDetail(s)` → `[Dashboard, ScheduleDetail(s)]`; `AssetDetail` and `TagResult` likewise pushed | `openDeepLink` always `add`s (the shipped collector) |
| **B2** 11 | AC 2, 3, 4, 6, 7 on the platform — the item | `AT/reminders/QuickActionDeviceProofTest` · new `anItemBodyOpensItsScheduleExactlyAsOpenDoesAndWritesNothing` — seed one overdue `QUICK` schedule (`seedOverdue`), `reconcileAll()`, read the item back (`standingFor`): `notification.contentIntent` non-null, `isImmutable`, `isActivity`, `== open.actionIntent` (the Open action's own intent), `!= done.actionIntent`; note the persisted nonce; `send()` it with no activity running (the cold `onCreate` path) → the schedule detail is on screen: the **detail matcher** `hasText("SERVICE RECORD")` — exact, as `SectionHeader` upper-cases its title (`ui/components/SectionHeader.kt`; precedent `EditorsDeviceProofTest.kt:129`), unconditional on the screen (`ScheduleDetailScreen.kt:308`) and absent from the Dashboard — awaited present, plus the title; then `waitForIdle()`, and "When was this done?" is **absent**; the asset's events still 0; the persisted nonce unchanged; the item and the summary still standing; the activity finished | **device (1 run, shared with row 12):** both `setContentIntent` calls removed → each case fails on its non-null `contentIntent` assertion |
| 12 | AC 1, 4, 6, 7 on the platform — the summary | same class · new `theSummaryBodyOpensTheDashboardAndWritesNothing` — the same seed and run; the standing summary (`SUMMARY_ID`): `contentIntent` non-null, `isImmutable`, `isActivity`, `== AndroidQuickActionIntents(context).pendingIntentFor(QuickActionTarget.OpenDashboardAttention)`, `!=` the item's Open; first `send()` the item's **Open action** (shipped path) and await the **same detail matcher as row 11**, `hasText("SERVICE RECORD")`, present, so the landing is discriminating; then `send()` the summary's `contentIntent` (the warm `onNewIntent` path) → the Dashboard: `hasText("ServiceTag") and hasNoClickAction()`, the `ATTENTION` header, the seeded row's title, and `hasText("SERVICE RECORD")` **gone**, by a bounded wait (`waitUntil { onAllNodes(hasText("SERVICE RECORD")).fetchSemanticsNodes().isEmpty() }`), because the pop animation composes the outgoing entry for a moment; events 0; nonce unchanged; both notifications standing; the activity finished | as row 11 |

**Planned counted REDs: 9** — B1 8 JVM runs (rows 1, 3, 4, 5, 6, 7, 8+9 as one run, 10), B2 1 device run (rows 11 and
12 together). **New cases:** 9 JVM in `app` (rows 1–5, 7, 9 ×2, 10), 1 in `core` (row 8), 2 device (rows 11, 12);
one renamed pin (row 6). The two device cases observe the destination the way the shipped device tests do — Compose
semantics on the running activity through an empty compose rule — because the suite has no route probe and this plan
adds none to production.

## 4. Files, fences and order

One branch `issue-87` from `a35cacb0`: **B1 → B2**. B1 owns every production change; B2 adds only device cases.

| brief | production files | test files |
|---|---|---|
| B1 | `C/links/DeepLinkRoute.kt`; `A/MainActivity.kt` (one `routeForDeepLink` arm + its kdoc line); `A/reminders/QuickActions.kt`; `A/reminders/Notifications.kt`; `A/reminders/LocalReminderProvider.kt` (`:218-219` only); `A/ui/nav/ServiceTagRoot.kt` (`:76` and the new function beside `switchTopLevel`) | `CT/links/DeepLinkRouteTest.kt`; `T/links/ScheduleDeepLinkTest.kt`; `T/reminders/{QuickActionsTest,LocalReminderProviderTest,QuickActionReceiverTest}.kt`; `T/ui/nav/RouteTest.kt`; `AT/reminders/ReminderPlatformDeviceProofTest.kt` (the four `null` arguments only) |
| B2 | none | `AT/reminders/QuickActionDeviceProofTest.kt` |

**Untouched, whole branch:** `AndroidManifest.xml`; `A/reminders/{DigestPolicy,QuickActionReceiver,DigestAlarm,
NotificationChannels,DeadlineDelivery}.kt`; `A/ui/nav/Route.kt`; `A/ui/dashboard/**`; `A/ui/maintenance/**`; every
DAO, entity, migration, codec, DTO, `app/schemas`, `docs/api`, `tools/servicetag-mcp`; `AT/ui/AppSmokeTest.kt` and
every other androidTest file but the two named; `tools/emulator/*`; the gate script.

## 5. Strings

**None.** The Dashboard landing is the shipped screen; the item landing is the shipped schedule detail; a malformed
`servicetag://dashboard/<x>` reuses the shipped "That link doesn't point at anything here." (`MainActivity.kt:101`); no
label, content description, title or empty state is added. `DeepLink.Malformed`'s reason is never shown. A string that
appears anyway stops the brief.

## 6. Owner rulings needed

- **R87-1 — the link.** `servicetag://dashboard`, no path, parsed in core and routed to the existing `Route.Dashboard`,
  **not declared in the manifest** (only this app's explicit `PendingIntent` uses it; the public BROWSABLE hosts stay
  `asset`, `tag`, `schedule`). `MainActivity` is exported, so any app can send `servicetag://dashboard` to it as an
  **explicit** intent — harmless, because it only navigates, and already true of the three shipped hosts; the new
  `DeepLink.Dashboard` kdoc says why it is not a D-17 permanent contract (no manifest host). Alternatives: declare it
  (a permanent public host, D-17's bar), or spell it `servicetag://attention`. **Recommended: as proposed.**
- **R87-2 — the landing. OPEN.** The summary lands exactly as the Dashboard tab does: the back stack becomes
  `[Dashboard]`, the Dashboard's scroll position and filters are left as they are, and ATTENTION is its first section;
  no anchor, no new route. **The consequence:** anything open above the root closes — an idle unsaved form, and also
  **a write already running on a popped screen, which is cancelled** (its `ViewModelStore` is cleared on pop). Four
  screens block back for that reason: `TransferImportScreen.kt:58` (MJ-1), `AssetEditScreen.kt:312`,
  `ReplaceAssetScreen.kt:94`, `TransferFlowScreen.kt:99`; the tab can never reach them, a summary tap can. The tap
  itself writes nothing.
  - **(a) Reset regardless — recommended; the plan is written to it.** The tap is deliberate and the write window is
    short. No code beyond C6; the consequence is recorded (C6 kdoc, §7).
  - **(b) Do not reset while a write-flow route — `Route.TransferImport`, `Route.AssetEdit`, `Route.ReplaceAsset`,
    `Route.TransferAssets` — is anywhere on the stack: ignore the tap's route**, so the app simply comes forward where
    it was. Not "push instead": with the Dashboard as root, a push is the duplicate key C6 exists to avoid. Cost: AC 1
    is narrowed on those four flows (the summary opens the app on the flow, not the Dashboard); one more pure rule in
    `openDeepLink` and its cases in row 10 (about 5 production, 20 test lines, 1 more counted RED in B1); B1's fences
    unchanged. It also protects an idle unsaved form on those four screens.
  - A further alternative, not recommended: scroll to ATTENTION on arrival (a signal into `DashboardScreen`, i.e. a new
    route parameter).
- **R87-3 — the upgrade transition.** A notification posted by 1.4.1 that is still standing after the upgrade keeps its
  inert body until its next re-post (a content change, the three-day OVERDUE re-announcement, a count change for the
  summary). Forcing a re-post changes the tag and re-alerts every standing reminder. **Recommended: accept; one line in
  the 1.5.0 release notes; and two taps added to the ratified 1.5.0 dev-phone smoke — one summary body, one item body —
  on a post 1.5.0 made.**

## 7. What this plan does not do, and records

- **Warranty and loan bodies** stay inert (row 3 pins it). If the owner wants them to open their asset, that is a
  follow-up issue using the same seam (`contentFor`'s Deadline arm).
- **The platform's auto-group.** With four or more of the app's notifications standing, Android may bundle them under
  a system-generated summary whose tap is the platform's (it usually expands the bundle). The app's own summary and
  items inside the bundle fire their own `contentIntent`. Nothing here calls `setGroup`.
- **A schedule already on top.** Opening the schedule that is already the top entry pushes a second identical key; that
  is shipped behaviour of "Open" since 1.2 and a body tap reaches it the same way. C6 deliberately keeps non-top-level
  pushes as they are (AC 5). Recorded for #90's look at navigation, not fixed here.
- **Auto-cancel.** A body tap does not dismiss the notification, exactly as "Open" does not; the next reconcile owns
  the shade.
- **A summary tap can cancel a running write (R87-2 (a)).** The reset clears each popped entry's `ViewModelStore`, so an
  import, an asset save's copies, a replace or a transfer marking that is running on a screen above the root is
  cancelled — the case the four back-blocking screens (`TransferImportScreen.kt:58`, `AssetEditScreen.kt:312`,
  `ReplaceAssetScreen.kt:94`, `TransferFlowScreen.kt:99`) guard against for back. Recorded, not guarded, under (a); (b)
  would guard it by ignoring the tap on those flows.
- **Harness sender opt-in.** On API 34+ a `PendingIntent.send()` from a process with no visible window may need the
  sender's background-activity-start opt-in; B2 may pass it in the case's own `send` call. It never goes into
  production: the real sender is the system shade.

## 8. Proofs — the merged-tip gate

After the whole-branch review, merge `--no-ff issue-87` into master at once, then run the controller's gate **once** at
the merge commit: `.superpowers/sdd/2026-09-29-issue-85/gate/run.sh` copied unchanged into
`.superpowers/sdd/2026-09-30-issue-87/gate/` (its 55 classes: #86's 54 plus `ReplaceAssetFlowTest`; no
`EXTRA_CLASSES`). **#87 adds no device class:** its two device cases live inside `QuickActionDeviceProofTest`, already
one of the 55, which goes from 2 to 4 cases (the device suite from 281 to 283 tests); `ReminderPlatformDeviceProofTest`
keeps its 13. **Expected:** 55 device classes; JVM core 1654 + 1, app 1586 + 9; MCP 384 unchanged; the whole near
11.3–11.5 minutes (two activity launches add seconds). **Record in the ledger and this plan's errata** (reporting
only): the whole time with its JVM, device and MCP portions; the class and test counts; **#87's delta against 11.32
minutes whole, 10.32 minutes device, 55 classes, 281 device tests**. **A failure is recorded, never rerun:** its class,
case and log; a flake at an untouched site goes to #90 with the log; a fix is a new commit and a new gate, by the
controller's decision. The 1.5.0 release gate (version bump, artifacts, upgrade proof, the ratified dev-phone smoke,
plus R87-3's two taps if ruled) follows separately.

## 9. Time boxes

| unit | target | hard stop | mutation cap | device runs |
|---|---|---|---|---|
| B1 (JVM, all production) | 1 h | 2 h | 9 JVM | 0 |
| B2 (device cases) | 45 min | 2 h | 1 device | 3 (the mutation run, `QuickActionDeviceProofTest` green once, `ReminderPlatformDeviceProofTest` once) |
| each fix round | 45 min | 45 min | 3 (≤ 1 device) | ≤ 1 |
| whole-branch review | 45 min | 1 h | — | — |
| merged-tip gate | ~12 min | 20 min | — | once, no reruns |

## Briefs — common to both

Read §1–§9, the audit, issue #87 and its scope ruling, and every earlier report on this branch.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun` with zero failures and skips;
`:app:compileDebugAndroidTestKotlin` in both briefs (B1 changes a signature androidTest consumes). Before a connected
class, `tools/emulator/prepare-emulator.sh`; then one class per run, **once**: `ANDROID_SERIAL=emulator-5554 ./gradlew
:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. Then the brief's anchored
greps (`git grep -nE '<pattern>' -- <paths>`); the untouched diff (`git diff <base> --stat` against §4);
`git diff <base> | grep -cE '^[-+].*(external_link|externalLinks)'` → 0; `git ls-tree HEAD libs/nfc-tag-core` →
`7e0377a…`; each → 1: `'^\s*versionName = "1\.4\.1"$'` and `'^\s*versionCode = 17$'` in `app/build.gradle.kts`,
`'^\s*const val SCHEMA_VERSION = 16$'` in `A/di/AppGraph.kt`, `'^\s*const val FORMAT_VERSION = 16$'` in
`C/backup/BackupCodec.kt`; `git status` clean.

**Pin rule.** A shipped assertion moves only at row 6 (the builder test's name, its code list and `5 → 6`). Every other
shipped assertion stays. **Commits.** One casual lowercase subject line; a row with a natural RED is committed
test-first. **Report.** Every counted RED with its quoted failure; each uncounted row and why; each device run with its
class, cases and time; each grep with its count; what is done, proven and not proven.

**Stop and report, always:** an edit to an untouched file; an assertion that must move outside row 6; any device
failure, with its log (never rerun) — except B2's counted device mutation run, which must fail on exactly rows 11 and
12's non-null `contentIntent` assertions (quoted) and precede the class's one green run; a JVM or build failure the
brief did not cause; the emulator unavailable for 30 minutes; a user-visible string (§5 has none); the cap or the time
box reached, with what is done, proven and not.

**Must NOT, always:** delete a shipped assertion; commit outside the brief's files; add a device class; use a device
other than `emulator-5554`; start, stop or restart the emulator or adb; touch a shared harness helper, `tools/emulator/*`
or the gate script; add Robolectric or a JVM Compose dependency; bump a version; write a real name, address, serial or
tag key; change a quick action's label, order, target, nonce, code or receiver; add `setGroup` or turn auto-cancel on;
add a manifest host; add a `Route`.

## 10. B1 — the targets, the seam, the link and the landing (C1–C7; JVM only)

**Read:** audit §1–§4 and §6; `A/reminders/{Notifications,QuickActions,LocalReminderProvider}.kt`;
`C/links/DeepLinkRoute.kt`; `A/MainActivity.kt:76-154`; `A/ui/nav/ServiceTagRoot.kt:64-80`, `:610-624`;
`A/ui/nav/Route.kt:204-216`; `T/reminders/QuickActionsTest.kt` (`:31-60` fixtures, `:100`, `:138`, `:172`);
`T/reminders/LocalReminderProviderTest.kt:50-89`; `T/reminders/QuickActionReceiverTest.kt:240-286`;
`T/links/ScheduleDeepLinkTest.kt`; `CT/links/DeepLinkRouteTest.kt`; `T/ui/nav/RouteTest.kt:1-30`.
**`<base>`** = `a35cacb0`. **Rows:** 1–10. **Rulings:** R87-1, R87-2 (as given).

**Connected:** none (B2 runs `ReminderPlatformDeviceProofTest` for B1's four call-site edits).

**Greps.**
- `A/reminders/Notifications.kt`: `'\.setContentIntent\(intents\.pendingIntentFor\('` → 2; `'\.setAutoCancel\(false\)'`
  → 2; `'setGroup\('` → 0; `'no content intent'` → 0 (the old kdoc is gone).
- `A/reminders/QuickActions.kt`: `'const val REQUEST_OPEN_ATTENTION = 2306$'` → 1; `'PendingIntent\.getActivity\('` → 1;
  `'PendingIntent\.getBroadcast\('` → 1; `'^\s+fun contentFor\(key: SubjectKey\): QuickActionTarget\?'` → 1 (not
  `suspend`); `'data object OpenDashboardAttention : QuickActionTarget$'` → 1.
- `A/reminders/LocalReminderProvider.kt`: `'quickActions\.contentFor\(post\.key\)'` → 1;
  `'QuickActionTarget\.OpenDashboardAttention'` → 1.
- `C/links/DeepLinkRoute.kt`: `'"dashboard" ->'` → 1; `A/MainActivity.kt`: `'is DeepLink\.Dashboard -> Route\.Dashboard$'`
  → 1.
- `A/ui/nav/ServiceTagRoot.kt`: `'deepLinks\.collect \{ backStack\.openDeepLink\(it\) \}'` → 1;
  `'deepLinks\.collect \{ backStack\.add'` → 0.
- `app/src/main/AndroidManifest.xml`: `'android:host="dashboard"'` → 0.
- Over `app/src/androidTest`: `git diff <base> --numstat -- app/src/androidTest` → exactly one line,
  `4	4	…/reminders/ReminderPlatformDeviceProofTest.kt` (the four `postItem` calls, nothing else).

**Untouched:** §4's list, plus every line of `MainActivity.kt` but the one `routeForDeepLink` arm and its kdoc; every
line of `LocalReminderProvider.kt` but `:218-219`; `forSchedule`, `forDeadline`, `broadcast()` and the five shipped
branches of `pendingIntentFor`.
**Must NOT:** make `contentFor` `suspend` or let it read a port; derive the item body from the action list or a label;
give items a new request code or reuse 2303; add a second `getActivity` site; give the seam a default value; change
the provider's write order; change `routeFrom`'s explicit-intent guard or `routeForQuickCompletion`; change how a
non-top-level link is pushed.

**Counted RED (8 runs; rows 8 and 9 share one):** rows 1, 3, 4, 5, 6, 7, 8+9, 10. **Caps:** 9 JVM mutation runs, 0
device; **1 h target, 2 h hard stop**. **Stop also** if the seam change forces an edit in a test file outside §4's B1
list; if `openDeepLink` cannot be driven on a plain `mutableListOf<NavKey>()` in a JVM test; if any shipped
`DigestPolicyTest`, `QuickActionsTest` or `LocalReminderProviderTest` assertion moves.
**Size:** about 60 production and 220 test lines.

## 11. B2 — the two platform proofs (rows 11–12; device only)

**Read:** audit §5 and §6.9; `AT/reminders/QuickActionDeviceProofTest.kt` (all); `AT/reminders/ReminderPlatformDeviceProofTest.kt:264-296`,
`:333-358` (the `isImmutable` / `isActivity` / `PendingIntent`-equality precedent); `AT/ui/AppSmokeTest.kt:44-122`,
`:238-266` (`clearInstall`, `awaitText`, the `singleTask`/`ActivityScenario` lesson and the Dashboard title check);
`A/ui/maintenance/ScheduleDetailScreen.kt:115-145`, `:300-310`; B1's report. **`<base>`** = B1's accepted tip.
**Rows:** 11, 12.

**Harness, inside the class only.** A `createEmptyComposeRule()` rule observes the activity the `PendingIntent` starts
(no `ActivityScenario` around a `PendingIntent` launch: the audit's `singleTask` lesson). A private `send` may pass the
sender's background-activity-start opt-in on API 34+ (§7). Each new case finishes the activity it started (on the main
thread, via the activity lifecycle monitor) and waits, bounded, until it is destroyed, so the next case's cold start is
genuinely cold. Private helpers only; the two shipped cases and their helpers keep every line.

**Connected, once each, each with its time:** the counted mutation run of `reminders.QuickActionDeviceProofTest`
(both `setContentIntent` calls removed, then reverted); `reminders.QuickActionDeviceProofTest` green (4 cases);
`reminders.ReminderPlatformDeviceProofTest` green (13 cases).

**Matchers (C-2).** One detail matcher, `hasText("SERVICE RECORD")` (exact; rendered upper-case), for every presence
and absence check of the schedule detail in both cases — never the title alone, never "Service record". A check that a
present node **goes** is a bounded wait (`waitUntil { … .isEmpty() }`); the check that "When was this done?" **never
appears** runs after the detail matcher is present and `waitForIdle()`.

**Greps (anchored to code lines, so a kdoc naming them cannot count; master `a35cacb0` counts in brackets).** In
`QuickActionDeviceProofTest.kt`: `'^\s*@Test$'` → 4 [2]; `'^\s*[^*/[:space:]].*\.isImmutable'` → 2 [0];
`'^\s*[^*/[:space:]].*\.isActivity'` → 2 [0]; `'^\s*[^*/[:space:]].*\.contentIntent'` → at least 2 [0];
`'"SERVICE RECORD"'` → at least 1 [0] (one shared matcher is fine); `'"Service record"'` → 0 [0]; `'ActivityScenario'` → 0 [0]; `'UiDevice|uiautomator'`
→ 0 [0]. `git diff <base> -- app/src/main core` → empty.

**Untouched:** everything but `QuickActionDeviceProofTest.kt`; in it, the two shipped cases, `seedOverdue`,
`standingFor` and `await`. **Must NOT:** tap the shade; add a route probe, a test hook or any production line; seed
through anything but `seedOverdue` and `reconcileAll()`; assert the destination on the title text alone (the Dashboard
row shows it too); use a different detail matcher for the present and the absent check; assert a disappearance
without a bounded wait; leave an activity running at the end of a case.

**Counted RED (1 device run):** rows 11 and 12 together. **Caps:** 1 device mutation run, 3 device runs in all;
**45 min target, 2 h hard stop**. **Stop also** if the `PendingIntent` launch is refused on `emulator-5554` even with
the sender's opt-in, or the empty compose rule cannot see the launched activity — report with the log; the controller
decides (no production workaround, no creator-side opt-in). **Size:** about 110 test lines.
