# ServiceTag 1.4.1 — #80 reminder delivery and #81 the editor's profile: plan

**Release:** 1.4.1 / `versionCode` 17, a PATCH (R6). **Scope:** #80 and #81, nothing else.
**Base:** master `a8717c2` (1.4.0, code 16). **Roadmap:** #76 — #80 → #81 → Phase 1A (`a8717c2`) → #78 → the rest.
**Inputs:** `rulings.md` beside this file (binding on every brief); #80's body (the owner's, with rulings and twelve acceptance criteria); #81's body (the acceptance list); the forensic record `.superpowers/sdd/2026-09-25-reminder-forensics/source-forensics.md` (read-only evidence; every `file:line` below was re-verified on `a8717c2`).
**Process:** `docs/superpowers/planning-policy.md` (plans specify; one task review per brief, batched fixes, at most one scoped re-review; the testing hierarchy: JVM → Compose instrumented → contract tests → the one signed-APK upgrade smoke; no UI driving). Implementers inherit `.superpowers/sdd/2026-09-24-servicetag-1.4/implementer-constraints.md` with §7's overrides.

## 1. The defect, in one paragraph

A schedule created through `/v1` with `providers` omitted stores `remindersEnabled = true` and no provider row (`MaintenanceDtos.kt:297-298` defaults; `SaveSchedule.kt:158` stores the list as given). `BuildReminderSubjects.forProvider` keeps only rows with an enabled provider (`BuildReminderSubjects.kt:51-53`, `:68`), so such a schedule never reaches `LocalReminderProvider`: reminders on, delivered by nobody. The Stage-B loader (`apply.py:95-105`) and the MCP `create_schedule` (`server.py:1330`, `_body` drops `None`) both omitted the key, which is how both phones hold the same 43 ACTIVE providerless rows. Reminder Health already sees them (`ReminderHealthCheck.kt:289-308`, `SCHEDULE_NO_PROVIDER`) but offers "Open the schedule", and a save from the editor drops a QUICK schedule's `profileId` (`ScheduleEditViewModel.kt:643`, `:839`) — 23 of the 43 have one (#81).

## 2. The briefs, the order, the lanes, the shared files

| brief | issue | scope | proof layer |
|---|---|---|---|
| `P1-provider-default-and-repair.md` | #80 prevention + repair | the `/v1` create default (R4); the MCP and loader send LOCAL explicitly; `RepairScheduleProviders` in `:core`; `/v1/repairs/schedule-providers/{plan,apply}`; the MCP `repair_schedule_providers` tool; `v1.md` | JVM (`:core`, `:app`), pytest ×2 |
| `P2-reminder-health-delivery-action.md` | #80 UX | the finding split, the ratified sentence and button, the in-app batch action over the same use case | JVM, Compose instrumented |
| `P3-editor-keeps-profile.md` | #81 | the editor keeps and shows a QUICK schedule's profile; an explicit way to clear one | JVM, Compose instrumented |
| `P4-version-docs-runbook.md` | release | 1.4.1 / 17, README, `versioning.md`, the runbook's direct-path line | grep, `VersionAgreementTest` |

**Order.** Wave 1: **P1** (lane A) beside **P3** (lane B). Wave 2: **P2** (lane A, branched from master after P1 merged; it may run beside P3 if P3 is still open). Wave 3: **P4** alone, after every merge. Two lanes is the ceiling; a lane that is blocked does not wait for the other.

**Why this is a clean arrangement (the owner's caveat).** P2 consumes P1's contract, so it is serialized behind P1 and the contract is frozen in §3 — P1 implements §3 verbatim, P2 calls it verbatim. Every file is owned by exactly one brief:

| file | P1 | P2 | P3 | P4 |
|---|---|---|---|---|
| `core/…/usecase/RepairScheduleProviders.kt` (new) | owner | reads | — | — |
| `core/…/usecase/ScheduleCommands.kt`, `SaveSchedule.kt` | **untouched** by all four | | | |
| `app/…/api/MaintenanceDtos.kt`, `ScheduleForms.kt`, `MaintenanceHandlers.kt`, `ApiRouter.kt`, `ApiDtos.kt` | owner | — | — | — |
| `app/…/di/AppGraph.kt` | adds `repairScheduleProviders` | reads it (P1 is merged first) | — | — |
| `app/…/reminders/ReminderHealthCheck.kt` | — | owner | — | — |
| `app/…/ui/maintenance/ReminderHealthViewModel.kt`, `ReminderHealthScreen.kt` | — | owner | — | — |
| `app/…/ui/maintenance/ScheduleEditViewModel.kt`, `ScheduleEditScreen.kt` | — | — | owner | — |
| `tools/servicetag-mcp/**`, `tools/servicetag-schedules/**` | owner | — | — | — |
| `docs/api/v1.md` | owner (the schedule body paragraph, the endpoints table, one new subsection) | — | — | — |
| `app/build.gradle.kts`, `README.md`, `docs/versioning.md`, `docs/release-proofs.md` | — | — | — | owner |

No navigation file changes: the Health screen's row already routes tap kinds through `HealthAction` (`ReminderHealthScreen.kt:125`), and the editor's picker is already in the screen. Nothing touches the schema, the backup format, the merge planner, the migration or `libs/nfc-tag-core`.

## 3. The frozen contract (P1 builds it, P2 consumes it)

```kotlin
// core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/RepairScheduleProviders.kt
enum class ProviderRepairSkip { PAUSED, PROVIDERS_DISABLED }
data class ProviderRepairEntry(val scheduleId: ScheduleId, val title: String, val skip: ProviderRepairSkip?) // skip == null: repairable
data class ProviderRepairPlan(val entries: List<ProviderRepairEntry>) {
    val repairable: List<ProviderRepairEntry> get() = entries.filter { it.skip == null }
    val skipped: List<ProviderRepairEntry> get() = entries.filter { it.skip != null }
    val matched: Int get() = entries.size
}
data class ProviderRepairReport(val plan: ProviderRepairPlan, val repaired: List<ScheduleId>)
class RepairScheduleProviders(schedules: ScheduleRepository, uow: UnitOfWork, clock: Clock) {
    suspend fun plan(): ProviderRepairPlan          // writes nothing
    suspend fun apply(): ProviderRepairReport       // re-plans inside one unit of work, then writes
}
```

- **Universe:** `schedules.all().listedForDue()` — the non-archived rows, the health check's own universe (`ScheduleStatus.kt:63`). An ARCHIVED row is never matched, never listed, never written (#80 AC 9).
- **Matched:** `remindersEnabled && providers.none { it.enabled }` — reminders on, nothing enabled.
- **Repairable (R3):** matched, `status == ACTIVE`, `providers.isEmpty()`. **Skipped:** `status != ACTIVE` → `PAUSED`; otherwise a non-empty set with nothing enabled → `PROVIDERS_DISABLED` (status is judged first).
- **Apply:** for each repairable row, `schedules.upsert(row.copy(providers = listOf(ScheduleProviderRow(ProviderId.LOCAL.name, enabled = true)), updatedAt = clock.nowMillis()))` — the canonical repository path (the DAO's delete-and-insert of provider rows, `MaintenanceDaos.kt:111-119`), every other field carried by `copy`. All writes in one `uow` transaction; deterministic order (title, then id). It **never** runs `RecomputeSchedules`, touches `ruleChangedAt`, a rule, a policy, `postponedDueOn`, `profileId`, `createdAt`, a closure or an event (R5).
- **Idempotent:** a plan after an apply has `matched == 0`; an apply on a clean store writes nothing.
- **Target service state is not read:** the invariant is about the row's own configuration; on both phones every target is in service, so the count equals the health finding's.
- **Where it may run (design decision, binding).** Only on an explicit owner tap in Reminder Health (P2) or an explicit `apply` through `/v1` and the MCP (P1). It is **never** a `RepairAction.Automatic`: `ReminderHealthCheck.runAndRepair()` runs the automatic repairs unattended at app launch and from the backstop worker (`ReminderHealthCheck.kt:222-240`), which would repair a phone silently on the first launch after install and make the "43 before apply" proof impossible. The finding therefore carries `RepairAction.OpenInApp(ReminderRepair.RESTORE_REMINDER_DELIVERY)` and the view model performs it, exactly as `TURN_REMINDERS_ON` is performed today (`ReminderHealthViewModel.kt:182-199`).

**The `/v1` adapter (P1).** `POST /v1/repairs/schedule-providers/plan` and `POST /v1/repairs/schedule-providers/apply`, body `{}` (`application/json`; an unknown key is the existing malformed-body refusal), both **200** with:

```json
{"matched": 43, "repairable": 43, "skipped": 0, "repaired": 0,
 "schedules": [{"id": "…", "title": "…", "outcome": "REPAIR", "reason": null}]}
```

`outcome` ∈ `REPAIR` (plan) | `REPAIRED` (apply) | `SKIPPED`; `reason` ∈ `PAUSED` | `PROVIDERS_DISABLED` | `null`. `plan` writes nothing and `repaired` is 0; `apply` reports its own re-plan and `repaired == repairable`. The path is top-level so no `/v1/schedules/{id}` segment can ever read `repairs` as an id. The rows are titled so the owner can read a plan. Like every other `/v1` write, an apply does **not** run a reminder sweep; the next digest, backstop or Health-screen action does (the in-app action sweeps at once, P2).

**The MCP adapter (P1).** `repair_schedule_providers(plan_only: bool = True)` — the safe default is the plan; `plan_only=False` posts the apply. It returns the response above unchanged and raises `ToolError` on any error, as every 1.1.1+ tool does. `import_merge`'s vocabulary (`plan_only`) is kept; its default differs because a repair has no "applicable" gate of its own — the plan **is** the gate.

## 4. Owner gate — the strings (ratify before P2 and P3 start)

R1 is reopened: provider terminology is not product vocabulary, so the finding names the missing thing without naming LOCAL. Voice as 1.4 §10.7: plain, imperative, no jargon. Count sentences follow the 1.4 plural rule (one form for exactly 1, one for every other count; plain strings read by id).

| # | surface | proposed text | notes |
|---|---|---|---|
| P141-1a | Reminder Health, the `SCHEDULE_NO_PROVIDER` finding, count ≠ 1 | `<n> schedules have reminders turned on, but reminder delivery isn't configured.` | the owner's pair A sentence |
| P141-1b | the same, count = 1 | `1 schedule has reminders turned on, but reminder delivery isn't configured.` | |
| P141-2 | the finding's button | `Fix reminder delivery` | the owner's pair B label: no count, so no plural; it names what the tap does |
| P141-3 | schedule editor, the profile picker's label when **One tap** is selected | `Quick action` | the product noun for a profile (`v1.md`: "a quick action needs a name"); in FORM mode the ratified `Use this form` stays |
| P141-4 | schedule editor, the picker's first row, clearing the profile | `None` | needed so a chosen profile can still be cleared once switching modes no longer clears it (#81) |

Kept as ratified in 1.2 (`master-plan.md:876`, `:892`): the sentence `<n> schedules have reminders switched on but no way to deliver them.` with `Open the schedule`, now drawn **only** for the residual shape the batch repair must not sweep — a non-empty provider set with nothing enabled — under a new finding code `SCHEDULE_PROVIDER_DISABLED` (§6 P2). No such row exists on either phone today, and the editor cannot make one. Alternatives the owner may prefer are recorded in `rulings.md` R1 (pair B's sentence; `Log it as` for P141-3). No detail line is added: the Health row has no second line today (`ReminderHealthScreen.kt:146-180`) and the sentence carries the meaning.

## 5. Cross-cutting invariants

1. Room schema **8**, backup format **8**, the merge planner, the migration and `libs/nfc-tag-core` (`7e0377a`) unchanged. No new column, table, DTO field or archive key.
2. `/v1` extended compatibly: no route, status, `code` or `problems` entry changes; two routes and one create default are added; PATCH keeps today's full-replace omission semantics (R4).
3. Provider rows are never synthesised on restore, import or merge (#80 AC 10): a repaired row exports `LOCAL`, an old providerless archive still imports providerless.
4. `remindersEnabled = false` is never repaired, a disabled provider is never enabled, PAUSED and ARCHIVED rows are never written (R3).
5. `ProviderId` stays `{ LOCAL }`; no provider choice appears anywhere in the UI; the word "provider" appears in no user-visible string.
6. No `Automatic` repair for providers (§3). No new `runAndRepair` behaviour.
7. Every user-visible string is §4's or already ratified; a state that seems to need words is `NEEDS_CONTEXT`.
8. No device but `emulator-5554` for any implementer; phones are the controller's, at the steps §8 names, and the production phone only after the owner's stop.
9. Hygiene: no e-mail, no home-directory path (write `~`), no serial but `emulator-5554`, no private equipment names, "the owner's hardened Android build" never a distribution's name; commits by `git -c user.name=GonzRon -c user.email="$(git log -1 --format=%ae master)"`, one casual subject, no body, no trailer.

## 6. What each brief must prove (summary; the matrices are in the briefs)

- **P1:** the `/v1` create truth table (omitted → LOCAL `enabled = remindersEnabled`, both values; `null` → the same; `[]` → empty; a list → kept; PATCH omitted → `[]` as today, pinned); the MCP sends LOCAL by default and `[]` on request; the loader's args carry LOCAL derived from `remindersEnabled` and its plan ignores providers and `updatedAt`; the use case's predicate matrix, field-preservation, idempotence, atomicity and ordering; the routes' plan-writes-nothing, apply-then-replan-zero; a repaired row is a LOCAL subject (`BuildReminderSubjectsTest`); format-8 round trip of a repaired row; `v1.md` documents all of it.
- **P2:** the finding split (isEmpty → P141-1 + P141-2, `OpenInApp(RESTORE_REMINDER_DELIVERY)`; non-empty-disabled → the 1.2 pair, `SCHEDULE_PROVIDER_DISABLED`); the singular form; the view model applies the use case, then sweeps (`resumeDelivery`), then refreshes; the finding clears on the next run (JVM and Compose); `runAndRepair` never touches providers.
- **P3:** the 23-row shape (QUICK, asset target, `profileId`, `remindersEnabled`) survives load → save unchanged, load → edit description → save, and FORM → QUICK → save; `None` clears; a group still offers no picker (`aGroupTargetOffersNoMeterNoProfileAndNoFollowAssetSeason`, `aGroupScheduleIsCreatedAndOffersNoneOfItsThreeForbiddenControls` stay green); `theEditorWritesExactlyOneProviderRowAndNeverLosesIt` stays green; `CompleteSchedule` untouched.
- **P4:** `versionName` / `versionCode` 1.4.1 / 17 (`VersionAgreementTest`), the README lead sentence, the `versioning.md` row, one runbook line.

## 7. Overrides to the standing constraints

Requirements come from the briefs, this plan and `rulings.md`, not the 1.4 spec or master plan. Strings: §4 once ratified, plus the 1.2 pair §4 names; nothing from 1.4 §10.7. The MCP's `pyproject.toml` version stays `1.2.0` as it did through 1.3.0 and 1.4.0 (a tools versioning rule is a carry-forward, §10). Every other constraint stands.

## 8. Proofs at the integrated tip (controller; the owner's 19 steps)

1. **Gates, locally, CI's command:** `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug :share-test-sender:assembleDebug --console=plain`, zero failures and zero skips; `uv run --frozen pytest` in `tools/servicetag-mcp` and `tools/servicetag-schedules`; lint as B15 left it. R1–R6 of `docs/release-proofs.md` at the tip (R2 whole connected suite once, one class per invocation for the touched classes first: `ReminderHealthScreenTest`, `ScheduleEditorTest`).
2. Task reviews per brief; 3. one whole-patch review at the final tip (most capable model), ≤ 1 scoped follow-up.
4. **Emulator repair proof and 5. the direct 1.3.0 → 1.4.1 path, in one run on `emulator-5554`:** download the `servicetag-v1.3.0` release asset and verify its SHA-256 equals the 1.3.0 evidence (`a4ca5ec8…3442`) and its signer; uninstall the debug app; install 1.3.0; load the owner's data the way the production phone was loaded (`import_merge` of the Stage A bundle, then the Stage B manifest through the loader **checked out at `a8717c2`**, before P1, so the 43 providerless rows are reproduced — `list_schedules` shows 43 ACTIVE rows with `providers: []`); build the 1.4.1 APK with `tools/release-dry-run.sh`; `adb install -r` it over 1.3.0 (same package, same signer): `/v1/status` reads 1.4.1 / 17, schema 8, format 8, every 1.3 count identical (R7's key-set hash); MCP plan → `matched 43 / repairable 43 / skipped 0`; apply → `repaired 43`; re-plan → `matched 0`; `list_schedules` before/after differ only in `providers` and `updatedAt`; the loader (master) re-plans IDENTICAL 47; `ReminderHealthScreenTest` and the JVM suites stand for the finding and the subjects. Then uninstall the release build (the next connected run reinstalls debug).
6. **Release:** CI green on the tip → annotated tag `servicetag-v1.4.1` → the release workflow reaches the protected environment → **the owner approves** → download, checksum, `apksigner` one signer == `RELEASE_CERT_SHA256`, badging 1.4.1 / 17, the eight permissions, not debuggable.
7. **Development phone** (1.4.0 / 16 → 1.4.1 / 17): R7 in full — `adb install -r` in place, `firstInstallTime` and UID unchanged, R7 (i) counts and per-table hashes identical on the 1.4 key set. 8. MCP plan → 43 (the population the forensic inventory named). 9. apply. 10. re-plan → 0. 11. `list_schedules` before/after: every row's id, `profileId`, rule, policy, `postponedDueOn`, `ruleChangedAt` identical; only `providers` and `updatedAt` moved; closures and events counts identical; the 5 ARCHIVED rows still providerless. 12. `SCHEDULE_NO_PROVIDER` gone: the Reminder Health screen shows no finding and no `REMINDER FAILED` badge (the owner's look at the stop; the check runs on that screen) — LOCAL subjects are proven by `BuildReminderSubjectsTest` and delivery by the next sweep, which the API path does not force. 13. **Post-repair backup (R5):** the owner makes one export from Settings on the development phone into its usual folder; the controller reads its `scheduleProviders` count (43 + the editor-made rows) and archives a copy under `noteNFC-backups/dev-post-repair-1.4.1/` beside the before/after API snapshots. 14. **STOP** for owner approval.
15. **Production phone**, only after the stop, the direct path already proven: install 1.4.1 over 1.3.0 (code 15 → 17) in place, R7 (i) on the 1.3 key set with the 1.4 mapping; 16. plan → 43; 17. apply; 18. re-plan 0 and the step-11 invariants; 19. the owner's export into the production folder, archived under `noteNFC-backups/prod-post-repair-1.4.1/`, and the evidence.

Old providerless exports (`prod-pre-migration-20260924/`, `dev-r7-*`) stay as history; a merge of one against a repaired phone plans `CONTENT_DIFFERS` on those rows by design (R5, `MergePlanner.kt:375-377` compares the whole DTO).

## 9. Acceptance per issue

- **#80** closes when AC 1–12 of its body are each named with a test or a proof: 1–3 `MaintenanceRoutesTest`; 4 the MCP and loader pytest cases; 5–7 the development-phone steps 8–12; 8–9 `RepairScheduleProvidersTest`; 10 `BackupCodecTest` (a repaired row round-trips `LOCAL`) and the merge note above; 11 the ratified §4 strings; 12 the order of §8.
- **#81** closes when the acceptance list in its body is each named: `ScheduleEditViewModelTest` (load, save unchanged, edit-unrelated, mode switch, the 23-row shape, clear), `ScheduleEditorTest` (the picker under One tap, `None`), `CompleteSchedule` untouched (`git diff` empty), no provider/rule/history change (the command equality assertions).

## 10. Carry-forwards and open owner decisions

- Strings §4 (P141-1a/b, -2, -3, -4): ratify or amend before P2 and P3 dispatch. P1 may start now.
- The MCP's version string and a tools versioning rule (unchanged here; Phase 1A/1B doc item).
- An API apply does not force a sweep; if the owner wants delivery to start within the minute of an API repair, that is one line in the handler and a ruling, not a design change.
- Whether a 1.3.0-era backup archive should be kept in `noteNFC-backups/` from the emulator run (format 7, providerless) as the reproducer for future migration proofs — the controller will keep one unless told not to.
