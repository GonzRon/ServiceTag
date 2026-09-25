# D3 — Proposed target architecture

Status: design-phase document, 2026-09-14. Decisions here are committed recommendations; each
major one records its rejection grounds and the fact that would reverse it (§16).

---

## 1. Architectural stance

The app stays a **single Android application with two Gradle modules**: `:core` (pure Kotlin/JVM:
domain model, scheduling engine, NDEF codec, backup format, link policy, provider ports) and
`:app` (Android: Room, Compose UI, NFC, alarms, WorkManager, Todoist HTTP, SAF, Keystore). The
split exists for one reason: the compiler enforces that the scheduling engine and codecs never
import Android, which is what makes them unit-testable on the JVM in milliseconds and keeps the
NFC-hardware surface thin.

Deliberately **not** introduced: Hilt/Dagger (a hand-written `AppGraph` in `Application` wires
~15 objects), multi-module feature slicing, event sourcing, CQRS, a plugin framework, a backend,
Kotlin Multiplatform, Realm, SQLDelight (Room chosen, §5), Retrofit (a small `HttpURLConnection`
or OkHttp client is enough for one REST/Sync API), Paging.

Boundaries that earn their keep (they hide foreign vocabularies):

| Boundary (port in `:core`) | Foreign thing hidden | Adapters in `:app` |
|---|---|---|
| `*Repository` | Room entities, DAOs, SQL | `RoomAssetRepository`, … |
| `ReminderProvider` | AlarmManager/WorkManager/Notification vs Todoist HTTP | `LocalReminderProvider`, `TodoistReminderProvider` |
| `NdefCodec` (pure) + `TagReader`/`TagWriter` ports | `android.nfc.*` | `NfcReaderModeSession`, `TagWriter` |
| `AttachmentStore` | filesystem vs SAF vs future cloud | `LocalAttachmentStore`, `SafTreeAttachmentStore` |
| `SecretStore` | Android Keystore + `Cipher` | `KeystoreSecretStore` |
| `BackupCodec` (pure) + `BackupIO` port | ZIP/JSON layout vs SAF streams | `SafBackupIO` |
| `Clock`/`Today` | wall clock | injected; tests pass fixed dates |

## 2. Component diagram

```
┌───────────────────────────────────────── :app (Android) ─────────────────────────────────────────┐
│                                                                                                   │
│  ui (Compose, Material3, Navigation 3)                                                            │
│   Home · AssetDetail(Overview/Journal/Schedules/Docs/Links) · EventEntry(profile form)            │
│   ScheduleEditor · Scan · WriteTag · Links · Supplies · Settings · ReminderHealth · Backup         │
│        │ ViewModels (StateFlow)                                                                   │
│        ▼                                                                                          │
│  usecases (application services; one transaction each)                                            │
│   ResolveTag · BindTag · SaveEvent · CompleteSchedule · PostponeOccurrence · SnoozeReminder        │
│   EditSchedule · ExportBackup · ImportBackup · MigrateLegacy · SyncProvider · RunDailyReminders   │
│        │ uses ports ▼                        ▲ implemented by adapters                              │
│ ┌──────┴──────────────── :core (pure Kotlin) ───────────────────────────────────────────────────┐ │
│ │ model: Asset · TagBinding · ExternalLink · AssetEvent(+Measurement,+ConsumableUsage)           │ │
│ │        MeasurementDefinition · EventProfile · MaintenanceSchedule · ScheduleState · SupplyItem │ │
│ │        StockLedger · Attachment · ReminderProjection                                           │ │
│ │ scheduling: Rules · ScheduleRecompute(rebuild) · Status · Season · ReminderSubject              │ │
│ │ nfc: NdefCodec (bytes ⇄ TagPayload v1 / legacy md5) · OverwritePolicy · TagRoute               │ │
│ │ links: LinkLaunchPolicy (allowlist, kind detection)                                            │ │
│ │ backup: BackupManifest · BackupCodec (JSON) · MergePolicy                                       │ │
│ │ ports: repositories · ReminderProvider · AttachmentStore · SecretStore · BackupIO · Today       │ │
│ └────────────────────────────────────────────────────────────────────────────────────────────────┘ │
│        ▲ adapters                                                                                  │
│  data/room: AppDatabase(v1..v6) · entities · DAOs · migrations · Room*Repository · LegacyPrefsReader│
│  nfc: NfcReaderModeSession · TagWriter · NfcDispatchActivity (NDEF_DISCOVERED entry)           │
│  reminders/local: DailyDigestAlarm · ReminderWorker · BootReceiver · NotificationPublisher         │
│                   · QuickActionReceiver · ReminderHealthCheck                                       │
│  integrations/todoist: TodoistApi · TodoistReminderProvider · TodoistSyncWorker · OutboxDrainer     │
│  attachments: LocalAttachmentStore · SafTreeAttachmentStore · SafDocumentReference                  │
│  backup: SafBackupIO · AutoSnapshot · ScheduledBackupWorker                                         │
│  security: KeystoreSecretStore                                                                      │
│  di: AppGraph (manual wiring in Application)                                                        │
└───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 3. Package layout

```
core/src/main/kotlin/com/loosecannon/notenfc/core/
  model/         plain Kotlin data classes + enums (no Room annotations)
  scheduling/    Rule.kt · ScheduleRecompute.kt · StatusEvaluator.kt · Season.kt · ReminderSubject.kt
  nfc/           NdefCodec.kt (TagPayload) · OverwritePolicy.kt · TagRoute.kt
  links/         LinkKind.kt · LinkLaunchPolicy.kt
  backup/        BackupManifest.kt · BackupCodec.kt · MergePolicy.kt
  ports/         AssetRepository.kt … ReminderProvider.kt · AttachmentStore.kt · SecretStore.kt · Today.kt
  usecase/       pure application services that need only ports (most use cases live here)
core/src/test/kotlin/…                       JUnit 5 + kotlin.test; property tests where cheap

app/src/main/kotlin/com/loosecannon/notenfc/
  NoteNfcApp.kt (AppGraph)
  data/room/     AppDatabase.kt · entities/ · dao/ · migrations/ · repos/ · LegacyPrefsMigration.kt
  nfc/           NfcReaderModeSession.kt · TagWriter.kt · NfcDispatchActivity.kt
  reminders/     local/ · health/
  integrations/todoist/
  attachments/
  backup/
  security/
  ui/            theme/ · nav/ · home/ · asset/ · event/ · schedule/ · scan/ · links/ · supplies/ · settings/ · health/ · backup/
app/schemas/     exported Room schemas (committed)
app/src/test/    Robolectric + Room in-memory tests, migration tests
app/src/androidTest/  a small smoke suite (Compose UI + NFC dispatch intent tests)
```

Kotlin package root changes from `com.looseCannon.noteNFC` to `com.loosecannon.notenfc`; the
`applicationId` **stays** `com.looseCannon.noteNFC` (D1 §9). The legacy activities are deleted in
Phase 1 and replaced by `NfcDispatchActivity` + Compose screens; their intent filters are preserved.

## 4. UI architecture: Compose

Decision: **Jetpack Compose + Material 3 + Navigation 3**, MVVM with `ViewModel` + `StateFlow`,
unidirectional data flow, no Fragments, no XML except the manifest and notification/launcher
resources.

Evidence-based reasoning:

| Input | Finding | Weight |
|---|---|---|
| Current code size | 2 layouts, 2 `TextView`s, zero UI logic — nothing to migrate | decisive |
| Amount of new UI | ~15 screens; the central one is a profile-driven dynamic form (variable list of typed rows) — painful in RecyclerView/ViewBinding, natural in Compose | decisive |
| Android versions | minSdk 26; Compose needs 23 | no constraint |
| Long-term maintainability | Google, May 2026: `android.widget` Views, Fragments, RecyclerView are in maintenance mode; new Studio tooling is Compose-only | strong |
| Testing | Compose semantics tests need no idling resources; screenshot tests available | moderate |
| Cost | BOM 2026.08.00 (Compose 1.12, M3 1.4.0), Navigation 3 1.1.x stable (1.1.7); APK ~ +0.8 MB, R8 minified | acceptable |
| Team | one developer + AI assistance; Compose is now the documentation default | moderate |

Rejected: keep XML Views (frozen toolkit, worst fit for dynamic forms); Flutter/KMP (no
cross-platform requirement). What would reverse it: a hard requirement to ship on API < 21 (not
the case) or a developer with deep Views expertise and a strong aversion to Compose.

Visual design: the approved direction is **Apollo Service Binder** (D12). Phase 1C creates the
theme once (`ui/theme/`: M3 light/dark schemes, `NoteNfcSemanticColors`, typography, shapes);
feature lanes consume the semantic status tokens and never reference raw colours for operational
state. Dynamic colour is an optional appearance setting that never touches the semantic layer.

Navigation 3 rather than Navigation-Compose 2.x because it is stable, Compose-first, and its
back-stack-as-state model fits deep links from tags, notifications, and `notenfc://` URIs (§13).
Fallback if Nav3 proves rough in the Phase 1 spike: `androidx.navigation:navigation-compose`.

## 5. Persistence: Room 3.0

Decision: **Room 3.0.x (`androidx.room3`, KSP-only, Kotlin 2.x)** — the stable line as of
September 2026 (3.0.0 … 3.0.3; 3.0.3 dated 2026-09-09 per the release page) — with the Room
Gradle plugin's `schemaDirectory`, hand-written migrations plus auto-migrations for pure
additions, and migration tests on every version bump. Room 2.8.5 (same date) is the **fallback**
only if spike S1 shows a concrete disadvantage in Android tooling or in the migration/testing
experience. Starting a brand-new database on the line that is in maintenance mode, and planning a
package migration later, would be the wrong default; this decision was corrected in review 1
(see `11-review-1-changes.md`).

What Room 3.0 changes for this design: nothing in the schema (annotations, foreign keys,
indices, schema export, `@Transaction` are shared with 2.8); the database is opened through an
`androidx.sqlite` driver, and with `BundledSQLiteDriver` (SQLite 3.50) DAO and migration tests can
run as plain JVM tests without Robolectric, which strengthens the testing pyramid. Platform
SQLite at minSdk 26 is 3.18 (no UPSERT, window functions, JSON1, FTS5); the schema in D4 needs
none of them, and the bundled driver removes the constraint anyway if a later feature wants them.

Rejected: Room 2.8 as the starting line (maintenance mode; a later 2→3 package migration for no
gain); SQLDelight (equally capable, but Room brings schema export, migration test helper,
`@Relation`, and is the Android default — the decision is made rather than left open); Realm
(sunset); SharedPreferences/DataStore for structured data (no relations, no queries — the current
failure mode).

Transactions: one use case = one transaction — Room 3 spells them `withWriteTransaction {}` and `withReadTransaction {}` (there is no bare `withTransaction {}`). All cross-aggregate effects of a write
(event saved → `rebuild` → outbox op queued) happen inside it. DAOs are internal to `data/room`;
repositories return `:core` models.

Question-decides-the-store check (storage-selection skill): the caller's questions are "which
assets are due?", "what happened to this asset?", "pH over time for this asset?", "which tag is
this?" — all "which ones match" queries with a single-process single-writer workload. SQLite is
the correct engine; no cache tier, no second store.

## 6. Data-driven vs compiled

| Data-driven (rows a user can edit) | Compiled (Kotlin) |
|---|---|
| measurement definitions (label, unit, type, decimals, range, is_meter) | the one generic entry form and its row renderers (NUMBER/TEXT/BOOLEAN, consumables, notes, date, cost) |
| profiles: which definitions, order, required, suggested consumables, default kind/title | validation, range classification (below/in/above), required-field gating |
| seed templates (JSON in `assets/templates/*.json`) | template application (creates definitions, profiles, schedules once) |
| schedule rules, windows, lead times, provider choice | the scheduling engine, status, season logic |
| supply items, roles, thresholds | ledger arithmetic, low-stock evaluation |

No expression language, no conditional visibility, no computed fields, no custom widgets in the
MVP. Adding one of those later is a code change to the renderer, not a schema change.

## 7. Scheduler and reminder architecture

### 7.1 Provider port (`:core`)

```kotlin
data class ReminderSubject(              // provider-neutral view of "a thing to remind about"
    val key: SubjectKey,                 // SCHEDULE(id) | SUPPLY(id)
    val title: String,                   // "Load test — CyberPower Rack UPS"
    val body: String,                    // last done, meter state, notenfc:// link
    val dueOn: LocalDate?,               // effective due date (null for usage-only or parked)
    val leadDays: Int,
    val state: SubjectState,             // ACTIVE | PARKED(reentryOn) | COMPLETED | WITHDRAWN
    val rule: RuleFacts?,                // basis, interval, unit, hasMeter, seasonal — so a provider
                                         // can decide whether its own recurrence engine can carry it
    val contentHash: String,             // for no-op detection
)

interface ReminderProvider {
    val id: ProviderId                                              // LOCAL, TODOIST
    suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport   // idempotent upsert/park/withdraw
    suspend fun pullChanges(): List<RemoteChange>                    // LOCAL: empty
    suspend fun health(): List<HealthFinding>
}
```

`reconcile` is the whole write surface: it receives the desired state of every subject the
provider is responsible for and makes the provider match it. That makes both providers
idempotent by construction; the Todoist adapter diffs against `reminder_projection` and enqueues
`provider_op` rows; the local adapter re-arms the daily alarm and posts/clears notifications.

Which providers receive a schedule comes from the `schedule_provider` rows (D4 §8): the use case
builds one subject list per enabled provider. The MVP editor writes at most one enabled provider
per schedule (single-choice Local / Todoist / None) to avoid double notifications, but nothing in
the ports or tables prevents enabling both later for a critical schedule (ruling R-12).

### 7.2 Local provider

| Concern | Decision | Why (research lane, Android docs) |
|---|---|---|
| Timing | One inexact **daily digest alarm** at the user's hour via `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP)` (or `setWindow` with a 30-min window), re-armed by its own receiver | Google: inexact alarms for user-specified times; fires in Doze; no permission |
| Exact alarms | **Not used** | `SCHEDULE_EXACT_ALARM` is denied by default on 34+, `USE_EXACT_ALARM` is Play-restricted to alarm/calendar apps; "due today" needs no second precision |
| Backstop | `PeriodicWorkRequest` every 12 h (flex 4 h): recompute all `schedule_state`, post anything missed, **re-arm the alarm if absent** | WorkManager survives reboot and force-stop; alarms do not |
| Reboot / clock | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED` receivers re-arm | alarms are cleared on shutdown |
| Notifications | Channels: `maintenance_due`, `maintenance_overdue`, `supplies`, `sync_problems`; `POST_NOTIFICATIONS` requested on first schedule creation with rationale | API 26 channels; API 33 runtime permission |
| Quick actions | `Done` → broadcast to `QuickActionReceiver` (QUICK mode) or `PendingIntent.getActivity` straight into the completion form (FORM mode); `Snooze 1d`; `Open` | Android 12 forbids service/receiver → activity trampolines; activity PendingIntents are fine |
| Digest policy (MVP) | One summary notification per digest run listing DUE/OVERDUE (+ DUE_SOON on first entry); per-item notifications only for DUE/OVERDUE not snoozed; overdue re-notify every 3 days by default | keeps noise low; fatigue controls (#11) are NEXT |
| Usage-only schedules | Evaluated when a meter reading is saved (immediate notification if it crosses DUE), not by the clock | no date to alarm on |

> **What of this table shipped, and where it was superseded. Amended at implementation
> (2026-09-22, ServiceTag 1.2 / B13).** Every row above **ships in 1.2** as written, with two
> corrections:
>
> - **Channels: `supplies` and `sync_problems` are not created** (ruling D-20 = B, 1.2 spec §5.5,
>   invariant 53). Exactly two channels exist — `maintenance_due` at DEFAULT importance and
>   `maintenance_overdue` at HIGH — because a channel a user can see in system settings for a
>   feature that does not exist is noise. Supplies and sync belong to later phases and will bring
>   their own channels with them.
> - **Quick actions carry a carve-out.** A `QUICK` schedule that also carries a **meter rule**
>   cannot be completed without its reading, so its "Done" routes into the canonical completion
>   flow as an **activity** `PendingIntent`, exactly as a `FORM` schedule's does, and the
>   notification never fabricates a reading. A **group-targeted** schedule's notification offers
>   **"Open"** only. Each action also carries a random per-notification nonce (D-21).
>
> Also shipped beyond this table: the digest hour is a user preference defaulting to 09:00 local,
> and neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM` appears in the merged manifest, which
> is asserted rather than assumed.

### 7.3 Reminder health (`reminders/health`)

`ReminderHealthCheck.run()` returns findings, each with severity, explanation, and an optional
repair action:

| Finding | Detection | Repair |
|---|---|---|
| `NOTIFICATIONS_BLOCKED` | `NotificationManagerCompat.areNotificationsEnabled()` false or channel importance NONE | open system settings |
| `DIGEST_ALARM_MISSING` | `PendingIntent.getBroadcast(FLAG_NO_CREATE)` == null while reminders enabled | automatic re-arm (safe, idempotent) |
| `BACKSTOP_WORK_MISSING` | `WorkManager.getWorkInfosForUniqueWork` empty | automatic re-enqueue |
| `APP_RESTRICTED` | standby bucket RESTRICTED / battery optimisation "restricted" | explain + open settings |
| `REMINDERS_GLOBALLY_OFF` | preference | one tap to enable |
| `SCHEDULE_NO_PROVIDER` | active schedule with `reminders_enabled` but no enabled `schedule_provider` row | open editor — **superseded in 1.4.1:** the providerless shape offers "Fix reminder delivery" (the canonical `RepairScheduleProviders`); "open editor" remains only for a non-empty set with nothing enabled, now `SCHEDULE_PROVIDER_DISABLED` |
| `NO_DATA` schedules | meter baseline missing | open completion/anchor form |
| `TODOIST_DISCONNECTED`, `PROJECTION_MISSING`, `PROJECTION_CONFLICT`, `PROJECTION_DUE_DRIFT` (native task's date ≠ canonical after verification), `SYNC_STALE` (> 48 h), `OUTBOX_FAILING` | Phase 5, from `integration_account`, `reminder_projection`, `provider_op` | recreate only when the remote task is confirmed absent; due drift is corrected automatically (canonical wins); conflicts are never auto-repaired |

Health runs on app launch, after every sync, in the backstop worker, and on the Health screen.
A red badge on Home appears when any finding has severity ≥ WARN.

> **Which findings shipped, and which did not. Amended at implementation (2026-09-22, ServiceTag
> 1.2 / B13).** The **seven local findings ship in 1.2** — `NOTIFICATIONS_BLOCKED`,
> `DIGEST_ALARM_MISSING`, `BACKSTOP_WORK_MISSING`, `APP_RESTRICTED`, `REMINDERS_GLOBALLY_OFF`,
> `SCHEDULE_NO_PROVIDER` and `NO_DATA` — each with the ratified sentence and repair label of the
> 1.2 master plan §17.1a, and each with a positive test and a negative control (invariant 51).
> The **six Todoist findings remain Phase 5**: `TODOIST_DISCONNECTED`, `PROJECTION_MISSING`,
> `PROJECTION_CONFLICT`, `PROJECTION_DUE_DRIFT`, `SYNC_STALE` and `OUTBOX_FAILING`. They have no
> detection in 1.2 because the tables they read — `integration_account`, `reminder_projection`,
> `provider_op` — do not exist yet.
>
> Three qualifications on the rows that did ship:
>
> - Repair is **only** what is unambiguous and idempotent: re-arming the alarm and re-enqueuing
>   the worker are the *only* automatic repairs. **No conflict is ever auto-repaired** (invariant
>   50), so this table's "due drift is corrected automatically" describes Phase 5 and nothing in
>   1.2.
> - `NO_DATA` is keyed on the **missing meter baseline**, never on the status word. A group round
>   whose required set is empty reports the same `NO_DATA` status and gets **no finding, no meter
>   sentence and no repair**: it is not actionable at all (invariants 74, 77).
> - Both store-backed findings carry two lifecycle bounds: an archived schedule and a schedule on
>   a retired asset or an archived group raise nothing, because the app has already withdrawn
>   that obligation from delivery.
>
> "After every sync" is Phase 5. In 1.2 the check runs at **app launch**
> (`app/.../ServiceTagApp.kt` calls `reminderHealth.refresh()`), from the backstop worker and from
> the Maintenance and Health surfaces; the ≥ WARN badge ships, on the dashboard and on Maintenance.

## 8. Todoist adapter boundary (Phase 5)

Facts from the research lane that shape this: only "Todoist API v1" exists
(`https://api.todoist.com/api/v1/`, old APIs shut down 2026-02-10); OAuth for a public client
needs either a client secret or an HTTPS redirect domain and PKCE; personal API tokens are an
officially supported alternative; the Sync endpoint offers incremental `sync_token` and
documented idempotent commands via `uuid`; `X-Request-Id` is undocumented; completing a
recurring task keeps the same task id and advances its date; recurring completions are visible in
the activity log (`item:completed`) and `completed_info`, not as `checked = true`; and Todoist
computes the next occurrence **from the completion date whenever a recurring task was manually
rescheduled before completion**, even for plain `every` rules (Todoist help, "Complete a
rescheduled task").

The invariant: **noteNFC is canonical.** A native Todoist recurrence is an optimised provider
representation, never schedule authority. After every completion, from either side, the adapter
verifies Todoist's resulting due date against the canonical computation and corrects Todoist if
they differ.

| Concern | Decision |
|---|---|
| Authentication (first release of the integration) | **Personal API token** pasted from Todoist Settings → Integrations → Developer (ruling R-3). Stored via `SecretStore` (Keystore-wrapped AES-GCM file), excluded from backups. Auth sits behind `integration_account.auth_kind` so OAuth can be added without touching the provider. |
| OAuth | Deferred; requires a domain for an HTTPS App-Link redirect (public-client PKCE) or embedding a client secret. Revisit before Phase 5 per ruling R-4. |
| Transport | `POST /api/v1/sync` for writes (`item_add` with `temp_id` + command `uuid` = our `provider_op.request_id`, `item_update`, `item_close`, `item_uncomplete`, `item_delete`) and for reads (`sync_token`, `resource_types = ["items"]`), plus `GET /api/v1/activities?object_event_types=["item:completed"]` (cursor-paginated, since the last consumed event) for native-recurring completions. REST `GET /tasks/{id}` only for spot checks. |
| **Representation selection** (capability-based) | `NATIVE_RECURRING` when **all** hold: a time rule exists, no meter rule, `time_basis = COMPLETION`, unit ∈ {DAY, WEEK, MONTH, YEAR}, no seasonal window applies (asset year-round or `season_behavior = IGNORE`). Everything else → `MANAGED_OCCURRENCE`: usage-based, time-OR-meter, seasonal, paused-with-placeholder, and — until spike S8 says otherwise — FIXED-basis rules, because Todoist's reschedule-then-complete behaviour recomputes from the completion date and would silently turn a fixed series into a rolling one. Re-evaluated on every rule edit; a change of representation is an `UPSERT` that rewrites the task's due object. |
| Native recurring projection | One task per schedule created with `due: {date: <effective_due_on>, string: "every! <N> <unit>"}` (Todoist's completion-relative grammar matches our COMPLETION basis exactly, including its reschedule-then-complete rule). Completion in noteNFC → `item_close` (Todoist advances by its own rule) → compare Todoist's new `due.date` with `computed_due_on` → `SYNC_DUE` (`item_update` with `due: {date: canonical, string: unchanged}`) if different. Completion in Todoist → activity-log `item:completed` event → local completion event (`source = TODOIST_SYNC`, `source_ref = <task id>:<activity event id>`) → `rebuild` → the same comparison. Benefit: the next occurrence is visible in Todoist immediately, even if noteNFC's background work is delayed. |
| Managed occurrence projection | One ordinary dated task per schedule, due = `effective_due_on`, re-dated by noteNFC after each completion (`item_uncomplete` if closed, then `item_update` due; delete+add if the closed task is too old to reopen). Completion in Todoist closes the task; the pull sync re-dates it — the gap between the two is the price of rules Todoist cannot express, and only those rules pay it. |
| Content | title "Load test — CyberPower Rack UPS"; description: last performed, meter state, `notenfc://schedule/<id>` link, and a one-line "Open noteNFC → Reminders" fallback in case custom schemes are not tappable (spike S3 in D8). |
| Parking | season-inactive → managed task re-dated to the re-entry date (`PARKED`); paused → due cleared (native: recurrence string retained) and restored on resume; archived → task deleted (`WITHDRAWN`). |
| Pull sync | on launch (foreground, throttled to 15 min), a `PeriodicWorkRequest` every 6 h with network constraint, and manual "Sync now". Incremental sync returns items with `checked`, `completed_at`, `due`, `is_deleted`; the activity log supplies native-recurring completions. |
| Interpretation of remote changes | managed `checked = true` or native `item:completed` → completion event (`details_pending` if `FORM`); remote `due.date` differs from projected **without** a completion → **postpone current occurrence** to that date (flag `EXTERNALLY_MODIFIED`); remote `due.date` differs **after** a completion → `SYNC_DUE` to canonical (flag `PROJECTION_DUE_DRIFT` if it recurs); remote recurrence string edited → overwritten on next reconcile (never adopted); `is_deleted` → `MISSING` finding; content edits → ignored. Both sides changed since last sync → `CONFLICT`, user chooses. |
| Idempotency | command `uuid` per outbox op; `UNIQUE(source, source_ref)` on events plus `last_remote_completion_ref` on the projection; `content_hash` to skip no-op updates; `UNIQUE(provider, schedule_id)` prevents duplicate tasks. |
| Rate limits | 1 000 partial syncs / 15 min per user; commands batched (≤100 per request); activity-log reads are incremental. |
| Disconnect | user chooses "delete projected tasks" or "leave them"; projections become `WITHDRAWN` either way. |
| Usage-only schedules | projected only as undated managed tasks whose description carries the meter state; description refreshed on debounce (status change or reading change ≥ 10 % of the interval) per #12. |

## 9. NFC codec and resolver boundary

```
Tag ──▶ NfcDispatchActivity (NDEF_DISCOVERED for :tag and :md5_short)  ──┐
Tag ──▶ NfcReaderModeSession (in-app Scan/Write screens, enableReaderMode) ┤
                                                                            ▼
                        NdefCodec.decode(records): TagPayload
                          = V1(tagId) | LegacyMd5(key) | Foreign(summary) | Malformed(reason) | NewerVersion(n)
                                                                            ▼
                        ResolveTag(payload) : Resolution
                          = OpenAsset(id) | LaunchLink(link) | UnknownTag(payload) | Revoked(tag) | Unbound(tag)
```

- `NdefCodec`, `OverwritePolicy` and `TagRoute` live in `:core` and are tested with byte fixtures; no Android types.
- Reader mode (`enableReaderMode` with `FLAG_READER_NFC_A|B|F|V`; **never** `FLAG_READER_SKIP_NDEF_CHECK`,
  which stops the platform marking the tag as NDEF so `Ndef.get()` returns null — Phase 1B finding) replaces foreground dispatch for in-app scanning and writing: callback-based, no
  PendingIntent, no activity relaunch. Background scans (app not open) still arrive through the
  manifest `NDEF_DISCOVERED` filters on `NfcDispatchActivity`.
- Writer: reads the tag first; if it already holds a noteNFC payload for a *different* binding or
  foreign NDEF content, asks to confirm; checks `Ndef.maxSize`; writes off the main thread;
  reads back and compares; optional "lock tag" with an irreversible warning. Records: `:tag`
  external record first, AAR second.
- Manifest: keep the `md5_short` filter; add the `:tag` filter; remove the `TECH_DISCOVERED`
  catch-all; for targetSdk 37 add `android:permission="android.permission.DISPATCH_NFC_MESSAGE"`
  on `NfcDispatchActivity` (Android 17 requirement). Observed on the owner's Android 17 phone: a
  freshly installed, never-launched package gets no NFC dispatch until its first launch (1B
  evidence), but a package that was launched once and then force-stopped (even with its data
  cleared) still gets dispatched (1C evidence row 16). The health screen explains the first case.
- Product identity (applicationId, NDEF record type, deep-link scheme, brand) stays at adapter
  boundaries, never in domain code: the maintenance product will be separated from the narrow
  noteNFC utility before first real deployment (D7 "Product separation"), and that discipline is
  what keeps the separation mechanical.
- Normal reads are ambient (manifest NDEF dispatch → `NfcDispatchActivity` → resolver → route);
  reader mode is reserved for intentional tag operations (write, rebind, rewrite legacy,
  inspect). No top-level Scan destination is required for reading (D12 §16 correction). With
  Phase 3, an asset reached by dispatch surfaces its maintenance context (D5 §7A).
- Unknown-tag resolutions offer: bind to an existing asset/link, create an asset, or (legacy tag)
  bind as-is / rewrite in payload format v1 (D13 §3). Re-link was dropped by D13.

## 10. External links

`LinkLaunchPolicy` (`:core`) classifies and validates URIs at save time and at launch time:

| Kind | Detection | Launch |
|---|---|---|
| `JOPLIN` | scheme `joplin`, host `x-callback-url`, path `/openNote` with 32-hex `id` | `ACTION_VIEW` |
| `OBSIDIAN` | scheme `obsidian`, action `open` with `vault` and `file`/`path` | `ACTION_VIEW` |
| `LOGSEQ` | scheme `logseq`, host `graph` | `ACTION_VIEW` |
| `WEB` | `http`/`https` | `ACTION_VIEW` (browser or app link) |
| `OTHER` | any other scheme the user confirms once | `ACTION_VIEW` after confirmation |
| Blocked | `javascript`, `file`, `content`, `intent`, `android-app`, `tel`, `sms`, `mailto` (unless explicitly allowed) | rejected at save |

Share-sheet input is parsed: the first URI token in the text is extracted (Joplin's share may
include a title); the full text is never stored as the URI. On API 30+ the manifest declares
`<queries>` for `joplin`, `obsidian`, `logseq`, `http`, `https` so the app can show "no app
installed for this link" instead of crashing; `ActivityNotFoundException` is caught regardless.

Standalone link tags preserve the original behaviour: scan → launch immediately, no interstitial.
A preference can switch to "show a card first".

## 11. Attachments and the storage-provider boundary (Phase 4; model fixed now)

```kotlin
interface AttachmentStore {
    val provider: StorageProvider                                     // LOCAL, SAF_TREE, later WEBDAV/S3/…
    suspend fun put(source: ByteSource, locator: Locator): StoredObject   // writes bytes at a provider-relative locator
    suspend fun open(locator: Locator): ByteSource
    suspend fun delete(locator: Locator)
    suspend fun exists(locator: Locator): Boolean
    suspend fun health(): StoreHealth                                 // permission still valid? tree reachable?
}
```

- **Managed** attachments are copied into the *configured* store: `LocalAttachmentStore`
  (`filesDir/attachments/…`, always available, included in Auto Backup up to the 25 MB cap) or
  `SafTreeAttachmentStore` (a tree the user picked with `ACTION_OPEN_DOCUMENT_TREE` and a
  persisted permission; Nextcloud's provider supports trees, Google Drive/OneDrive/Dropbox tree
  support is unverified, so the UI must handle "this provider cannot host a folder" and fall back).
- **Referenced** attachments keep a persisted single-document URI (`SAF_DOCUMENT`); they are never
  copied and are listed in backups as metadata only.
- Locators are provider-relative (`assets/<asset-id>/<attachment-id>.<ext>`); the store root is one
  setting. "Move attachments to another store" is a copy loop over rows, not a schema change.
- Thumbnails are a cache under `cacheDir`, regenerated on demand.
- Persisted URI grants are capped at 512 per package; the store uses one tree grant, and
  references count one each — the UI warns near the cap.
- Future providers (WebDAV/S3-compatible, then native cloud APIs) implement the same interface;
  credentials go through `SecretStore`.

## 12. Backup, export, import

Format: a ZIP with

```
manifest.json    { format_version: 1, app_version, schema_version, created_at, device, table_counts, sha256(data.json) }
data.json        { assets:[…], nfc_tags:[…], external_links:[…], measurement_definitions, event_profiles, profile_fields,
                   profile_consumables, asset_events, measurements, consumable_usages, maintenance_schedules,
                   supply_items, asset_supplies, stock_ledger, attachments, reminder_projections }   // canonical tables + projections
attachments/<attachment-id>.<ext>   managed bytes only (references are metadata)
```

- IDs are preserved verbatim; dates as ISO strings; no derived tables; **no secrets** (tokens),
  no preferences except the reminder hour and unit choices.
- Export via `ACTION_CREATE_DOCUMENT` (any SAF provider, including cloud ones) and an optional
  "auto-backup folder" (`ACTION_OPEN_DOCUMENT_TREE`, persistable grant) written by a WorkManager
  job as immutable versioned files with a daily/weekly/monthly retention policy — D7 Phase 3R,
  provider-neutral so Google Drive works through its `DocumentsProvider` without a Drive API
  integration. Also "share backup" through the share sheet.
- Import modes: **Replace** (wipe and load in one Room transaction, attachments after commit;
  failure leaves the previous data intact because the transaction rolls back) and **Merge** (by
  ID: missing rows inserted, existing rows kept unless the import's `updated_at` is newer;
  conflicts reported, never guessed). After import: `rebuild` for every schedule, `reconcile` for
  providers, health check.
- Versioning: `format_version` in the manifest; older formats pass through `BackupUpgrader` steps
  before load; newer formats are refused with a clear message. `schema_version` is informational.
- Safety nets: an automatic local snapshot (same format, app-private, keep last 5) before every
  destructive operation (asset delete, Replace import, legacy migration) and weekly; Android Auto
  Backup keeps the DB and local attachments under 25 MB as a best-effort extra, with
  `dataExtractionRules` excluding the secret file.
- Restoring tag relationships is automatic: `nfc_tags` rows carry the payload keys, so every tag
  resolves after Replace or Merge.

Encryption: optional passphrase (AES-GCM, Argon2id or PBKDF2-HMAC-SHA256 with a high iteration
count) as a NEXT item; default unencrypted with an explicit warning, because an encrypted backup
with a forgotten passphrase defeats the purpose of tag survival (ruling in D8).

## 13. Deep links

Scheme `notenfc://`, navigation-only, validated, versioned by path shape:

| URI | Opens |
|---|---|
| `notenfc://asset/<uuid>` | asset detail |
| `notenfc://asset/<uuid>/schedule/<uuid>` and `notenfc://schedule/<uuid>` | schedule detail (complete/snooze/postpone actions are taps inside the app) |
| `notenfc://event/<uuid>` | event detail |
| `notenfc://tag/<uuid>` | resolves as if scanned |
| `notenfc://health` | reminder health |

- Handled by the single-activity host through Navigation 3; malformed or unknown ids show a toast
  and land on Home. No URI ever performs a mutation.
- Custom schemes can be claimed by other apps (navigation hijack only; nothing sensitive is in a
  URL). HTTPS App Links would remove that and make links tappable in any client, but require a
  domain and a hosted `assetlinks.json` — the same prerequisite as Todoist OAuth (ruling in D8).
- External-link launch behaviour (§10) is a separate outbound path and is unaffected.

## 14. Compatibility and package identity (revised by D13)

1. Legacy compatibility is **best-effort, non-blocking** (D13 §1). The `md5_short` intent filter
   and the ~20-line legacy decode stay; a scanned legacy tag is recognised and offered "rewrite
   in payload format v1" or "bind as-is". No data migration, no re-link, no recovery screens.
2. The application id and Kotlin package root are normalised to `com.loosecannon.notenfc` as the
   first commit of Phase 1A (D13 §4). The new app installs beside the old one; cutover is
   install-new / uninstall-old. A new signing keystore is created and kept outside the repo.
3. New tags use the `:tag` record with an AAR for the new package. The production requirement is
   that newly provisioned tags survive phone replacement, reinstall, and backup/restore.

## 15. Build and toolchain targets (Phase 0)

| Item | Target |
|---|---|
| AGP / Gradle / Kotlin | AGP 9.4.x (already in the user's cache), Gradle 9.7.1 wrapper committed, Kotlin 2.4.x via AGP built-in Kotlin, KSP 2 for Room |
| JDK | 17 for `compileOptions`/`jvmTarget` (JDK 25 as the Gradle daemon is fine) |
| applicationId / namespace | `com.loosecannon.notenfc` from Phase 1A (D13 §4); Phase 0 still built the old id |
| minSdk / targetSdk / compileSdk | 26 / 36 / 37 (platform 37 is what the local SDK has installed; raise targetSdk to 37 when the `DISPATCH_NFC_MESSAGE` permission work lands in Phase 7) |
| Version catalog | `gradle/libs.versions.toml` |
| Dependencies | Compose BOM 2026.08.00, Material3 1.4.x, Navigation 3 1.1.x (1.1.7 stable, 2026-08-26; 1.2.0-rc01 pending), Room 3.0.x (`androidx.room3`, 3.0.3) + KSP 2 + `androidx.sqlite` bundled driver for JVM tests, WorkManager 2.11.x, DataStore, kotlinx-serialization (backup JSON), kotlinx-coroutines, OkHttp (Phase 5), JUnit 5 + Robolectric + Room testing |
| Manifest | remove `package` attribute; declare `android:exported` everywhere; `dataExtractionRules`; `<queries>` |
| CI | GitHub Actions: `./gradlew :core:test :app:testDebugUnitTest lint` on every push |

## 16. Decision ledger

| # | Decision | Rejected alternatives (ground) | Would reverse it |
|---|---|---|---|
| A1 | Room + SQLite as the sole canonical store | SQLDelight (equal capability, less Android tooling); Realm (sunset); prefs (no queries) | a KMP requirement |
| A2 | UUID text primary keys | autoincrement (not portable); ULID (dependency, no need) | none foreseeable |
| A3 | Tag identity ≠ asset identity, binding table | asset id on tag (no revocation, no standalone links) | a strong preference for "tag = asset" simplicity (codec unchanged) |
| A4 | noteNFC tag payload format v1: NDEF external record `:tag`, version byte `0x01`, 18-byte payload, + AAR; legacy `md5_short` read-only | URI record with custom scheme (claimable by any app; larger); https App Link record (needs domain) | owning a domain and wanting iOS background reads |
| A5 | Compose + M3 + Navigation 3 | XML Views (maintenance mode, poor fit for dynamic forms) | see §4 |
| A6 | `:core` pure-JVM module + `:app` | single module (no compiler-enforced boundary); many modules (ceremony) | none |
| A7 | Manual `AppGraph` DI | Hilt (build cost and ceremony for ~15 objects) | the graph growing past ~40 objects |
| A8 | Calendar dates for due/occurred; instants for audit only | instants everywhere (DST/zone bugs in recurrence) | none |
| A9 | Single current occurrence per schedule; `schedule_state` derived by `rebuild` | occurrence table (rows/states/UI without a requirement) | per-occurrence audit requirement |
| A10 | FIXED = skip-forward after completion, no backlog | Todoist-style one-step advance (creates overdue-again loops) | a user wanting explicit "missed" records |
| A11 | Season window on asset; schedule FOLLOW/IGNORE; re-entry AT_START(+offset) and RESUME_CLAMPED | per-schedule windows with inheritance (unspecified semantics); MANUAL_STARTUP in MVP | users needing per-schedule windows |
| A12 | Local reminders = inexact daily digest alarm + WorkManager backstop + boot/time receivers; no exact alarms | exact alarms (permission denied by default; Play-restricted); WorkManager only (time-of-day drift) | a requirement for minute-precise reminders |
| A13 (revised in review 1) | Todoist: personal API token first; **capability-based representation** — `NATIVE_RECURRING` (Todoist `every!`) for completion-relative, time-only, year-round schedules, `MANAGED_OCCURRENCE` (dated task re-dated by noteNFC) for everything else; canonical due verified after every completion; Sync endpoint with command `uuid`; activity log for native completions; pull sync | "one non-recurring task for every schedule" (discards Todoist's recurrence engine and makes Todoist wait for noteNFC's background sync after a Todoist-side completion); native recurrence for all rules (Todoist cannot express usage/season, and recomputes FIXED rules from the completion date after a manual reschedule); OAuth first (needs domain or embedded secret) | S8 proving FIXED rules round-trip safely (widens native eligibility); a domain for App Links (enables PKCE OAuth) |
| A14 | Controlled EAV for measurements with asset-scoped definitions | per-profile tables; JSON blobs; free-string EAV | none |
| A15 | Attachments: metadata in Room; `AttachmentStore` with LOCAL + SAF tree; references via persisted document URIs; provider-relative locators | BLOBs in Room; absolute paths; vendor SDKs first | none (vendor adapters are additive) |
| A16 | Backup ZIP (manifest + JSON + managed attachments), Replace/Merge, auto snapshots, weekly SAF export; unencrypted by default | CSV only (lossy); encrypted by default (forgotten passphrase orphans tags) | your ruling on default encryption |
| A17 | `notenfc://` custom scheme, navigation-only | App Links (need domain) | owning a domain (ruling R-4: design for it, do not block on it) |
| A18 (revised in review 1) | Room 3.0.x (`androidx.room3`) from the first schema; 2.8.5 only as the S1 fallback | Room 2.8 now with a package migration later (maintenance-mode line; the premise that 3.0 was still alpha was out of date — 3.0.0…3.0.3 are stable) | S1 showing a concrete Room 3 tooling or migration-test deficiency |
| A19 (added in review 1) | Providers per schedule are a set (`schedule_provider` rows; one projection per provider); the MVP UI is single-choice | a single `reminder_provider` enum column (would encode "exactly one provider" as a schema invariant and need a migration to relax) | none |
| A21 (added by D13) | Normalise the application id / package root to `com.loosecannon.notenfc` before the first Room schema; legacy compatibility demoted to best-effort recognition + rewrite | keep `com.looseCannon.noteNFC` for in-place update (worthless without the signing key); elaborate migration/re-link machinery (constrains Phase 1 for data nobody needs) | a production user base appearing before Phase 1A ships |
| A20 (added in review 1) | The Todoist representation is chosen per schedule from rule capabilities and re-evaluated on edit; verification of the remote due date after each completion is mandatory in both representations | trusting Todoist's post-completion date; a global per-account setting | none |
