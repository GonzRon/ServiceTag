# ServiceTag 1.4.1 — #80 reminder delivery and #81 the editor's profile: plan

**Release:** 1.4.1 / `versionCode` 17, a PATCH (R6). **Scope:** #80 and #81, nothing else.
**Base:** master `3b1d9ea` (1.4.0, code 16; the owner's README rewrite `966aa91` sits on top of `a8717c2`). **Roadmap:** #76 — #80 → #81 → Phase 1A (`a8717c2`) → #78 → the rest.
**Inputs:** `rulings.md` beside this file (binding on every brief); #80's body (the owner's, with rulings and twelve acceptance criteria); #81's body (the acceptance list); the forensic record `.superpowers/sdd/2026-09-25-reminder-forensics/source-forensics.md` (read-only evidence; every `file:line` below was re-verified on `3b1d9ea`); the plan review `.superpowers/sdd/2026-09-25-servicetag-1.4.1/plan-review.md` (APPROVE WITH FIXES, 2026-09-25; every finding is folded in below).
**Process:** `docs/superpowers/planning-policy.md` (plans specify; one task review per brief, batched fixes, at most one scoped re-review; the testing hierarchy: JVM → Compose instrumented → contract tests → the one signed-APK upgrade smoke; no UI driving). Implementers inherit `.superpowers/sdd/2026-09-24-servicetag-1.4/implementer-constraints.md` with §7's overrides.

## 1. The defect, in one paragraph

A schedule created through `/v1` with `providers` omitted stores `remindersEnabled = true` and no provider row (`MaintenanceDtos.kt:297-298` defaults; `SaveSchedule.kt:158` stores the list as given). `BuildReminderSubjects.forProvider` keeps only rows with an enabled provider (`BuildReminderSubjects.kt:51-53`, `:68`), so such a schedule never reaches `LocalReminderProvider`: reminders on, delivered by nobody. The Stage-B loader (`apply.py:95-105`) and the MCP `create_schedule` (`server.py:1330`, `_body` drops `None`) both omitted the key, which is how both phones hold the same 43 ACTIVE providerless rows. Reminder Health already sees them (`ReminderHealthCheck.kt:289-308`, `SCHEDULE_NO_PROVIDER`) but offers "Open the schedule", and a save from the editor drops a QUICK schedule's `profileId` (`ScheduleEditViewModel.kt:643`, `:839`) — 23 of the 43 have one (#81).

## 2. The briefs, the order, the lanes, the shared files

| brief | issue | scope | proof layer |
|---|---|---|---|
| `P1-provider-default-and-repair.md` | #80 prevention + repair | the `/v1` create default (R4); the MCP and loader send LOCAL explicitly; `RepairScheduleProviders` in `:core`; `/v1/repairs/schedule-providers/{plan,apply}`; the MCP `repair_schedule_providers` tool; `v1.md` | JVM (`:core`, `:app`), pytest ×2 |
| `P2-reminder-health-delivery-action.md` | #80 UX | the finding split, the ratified sentence and button, the in-app batch action over the same use case | JVM, Compose instrumented |
| `P3-editor-keeps-profile.md` | #81 | the editor keeps and shows a QUICK schedule's profile; an explicit way to clear one | JVM, Compose instrumented |
| `P4-version-docs-runbook.md` | release | 1.4.1 / 17 with its `VersionAgreementTest` cases, `versioning.md`, the runbook's direct-path line, two design-doc notes | `VersionAgreementTest`, grep |

**Reconciliation before wave 1 (controller, mechanical).** `966aa91` removed three phrases `VersionAgreementTest` asserts (`theDocumentsDescribeThreePrimaryDestinations` `:345-362`, `theReadmeNamesTheShareIntakeAndLinksItsSpec` `:371-385`, `theReadmeNamesSeasonsConditionAndHealthAndLinksTheSpec` `:393-410`), so `:app:testDebugUnitTest` is **red at `3b1d9ea`** (3 of 15 fail, proven locally with `--rerun`). CI stayed green because `README.md` is not among the test task's declared inputs (`app/build.gradle.kts:183-195`), so the cached task never re-ran. Before any lane branches: the three cases are re-anchored to the new README's own wording and its surviving spec links, and `README.md` joins the declared inputs. Under 50 lines, closed by controller inspection plus the class run and one CI run; recorded in the ledger. If the owner prefers the README to carry the old phrases instead (§10 Q3), the tests go back and the README changes — either way master is green before wave 1.

**Order.** Wave 1: **P1** (lane A) beside **P3** (lane B). Wave 2: **P2** (lane A, branched from master after P1 merged). Wave 3: **P4** alone, after every merge. Two lanes is the ceiling; a lane that is blocked does not wait for the other. **Connected runs never overlap:** `emulator-5554` runs one connected class for one lane at a time, scheduled by the controller; P2 starts beside a still-open P3 only after P3's connected gate has run, otherwise P2 waits for P3's merge. (Both classes wipe the store — `ReminderHealthScreenTest.kt:58-62` — and the 1.4 programme kept one device-using lane per wave.)

**Why this is a clean arrangement (the owner's caveat).** P2 consumes P1's contract, so it is serialized behind P1 and the contract is frozen in §3 — P1 implements §3 verbatim, P2 calls it verbatim. Every file is owned by exactly one brief:

| file | P1 | P2 | P3 | P4 |
|---|---|---|---|---|
| `core/…/usecase/RepairScheduleProviders.kt` (new) | owner | reads | — | — |
| `core/…/usecase/ScheduleCommands.kt`, `SaveSchedule.kt` | **untouched** by all four | | | |
| `app/…/api/MaintenanceDtos.kt`, `ScheduleForms.kt`, `MaintenanceHandlers.kt`, `ApiRouter.kt`, `ApiDtos.kt`, `ApiHandlers.kt` | owner | — | — | — |
| `app/…/di/AppGraph.kt`; `app/src/test/…/testing/FakeGraph.kt`; `app/src/test/…/api/MaintenanceFixtures.kt` | adds `repairScheduleProviders` | reads it (P1 is merged first) | — | — |
| `app/…/reminders/ReminderHealthCheck.kt` | — | owner | — | — |
| `app/…/ui/maintenance/ReminderHealthViewModel.kt`, `ReminderHealthScreen.kt` | — | owner | — | — |
| `app/…/ui/maintenance/ScheduleEditViewModel.kt`, `ScheduleEditScreen.kt` | — | — | owner | — |
| `tools/servicetag-mcp/**`, `tools/servicetag-schedules/**` (its `tests/conftest.py` included) | owner | — | — | — |
| `docs/api/v1.md` | owner (the schedule body paragraph, the endpoints table, one new subsection) | — | — | — |
| `app/build.gradle.kts` (version lines), `app/src/test/…/VersionAgreementTest.kt`, `docs/versioning.md`, `docs/release-proofs.md`, `docs/design/03-target-architecture.md:259`, `docs/design/issues/new-reminder-health.md:29` | — | — | — | owner |
| `README.md`, `app/build.gradle.kts` (test inputs) | the pre-wave reconciliation (controller) | | | |

No navigation file changes: the Health screen's row already routes tap kinds through `HealthAction` (`ReminderHealthScreen.kt:125`), and the editor's picker is already in the screen. Nothing touches the schema, the backup format, the merge planner, the migration or `libs/nfc-tag-core`.

**`<base>` per lane.** P1 and P3: `3b1d9ea` plus the reconciliation commit (the controller names it in each dispatch). P2: the master commit that merged P1. P4: the master commit that merged the last of P1–P3. Every diff gate below reads its own `<base>`.

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
    suspend fun apply(): ProviderRepairReport       // re-plans inside uow.write { }, then writes there
}
```

- **Universe:** `schedules.all().listedForDue()` — the non-archived rows, the health check's own universe (`ScheduleStatus.kt:63-64`). An ARCHIVED row is never matched, never listed, never written (#80 AC 9).
- **Matched:** `remindersEnabled && providers.none { it.enabled }` — reminders on, nothing enabled.
- **Repairable (R3):** matched, `status == ACTIVE`, `providers.isEmpty()`. **Skipped:** `status != ACTIVE` → `PAUSED`; otherwise a non-empty set with nothing enabled → `PROVIDERS_DISABLED` (status is judged first).
- **Apply:** inside one `uow.write { }`: read the plan, then for each repairable row `schedules.upsert(row.copy(providers = listOf(ScheduleProviderRow(ProviderId.LOCAL.name, enabled = true)), updatedAt = clock.nowMillis()))` — the canonical repository path (the DAO's delete-and-insert of provider rows, `MaintenanceDaos.kt:111-119`), every other field carried by `copy`; deterministic order (title, then id). It **never** runs `RecomputeSchedules`, touches `ruleChangedAt`, a rule, a policy, `postponedDueOn`, `profileId`, `createdAt`, a closure or an event (R5). A skipped, archived or reminders-off row is written **zero** times: its `updatedAt` does not move.
- **Idempotent:** after an apply, `repairable` is empty and `skipped` is unchanged; a second apply writes nothing. On the phones `skipped` is 0, so the re-plan reads `matched 0`.
- **Target service state is not read:** the invariant is about the row's own configuration; on both phones every target is in service, so the count equals the health finding's.
- **Where it may run (design decision, binding).** Only on an explicit owner tap in Reminder Health (P2) or an explicit `apply` through `/v1` and the MCP (P1). It is **never** a `RepairAction.Automatic`: `ReminderHealthCheck.runAndRepair()` runs the automatic repairs unattended from the backstop worker (`BackstopWorker.kt:68`; launch calls `refresh()`, which repairs nothing, `ServiceTagApp.kt:43-57`), which would repair a phone silently on the first backstop run after install and make the "43 before apply" proof impossible. The finding therefore carries `RepairAction.OpenInApp(ReminderRepair.RESTORE_REMINDER_DELIVERY)` and the view model performs it through a function seam, exactly as `TURN_REMINDERS_ON` is performed today (`ReminderHealthViewModel.kt:182-199`):

```kotlin
class ReminderHealthViewModel(health: ReminderHealth, prefs: AppPrefs,
    restoreDelivery: suspend () -> Unit,   // { graph.repairScheduleProviders.apply() } — the one canonical repair
    resumeDelivery: suspend () -> Unit)    // last, so the trailing-lambda construction in tests survives
// neither seam has a default: a wiring that omitted one must fail to compile
```

  The tap runs `restoreDelivery()`, then `resumeDelivery()`, then refreshes. If `restoreDelivery()` throws, the exception is caught and logged (one `Log.w`, no token, no body), the refresh still runs and the finding stays; nothing is drawn for it.

**The `/v1` adapter (P1).** `POST /v1/repairs/schedule-providers/plan` and `POST /v1/repairs/schedule-providers/apply`, body `{}` (`application/json`; a zero-byte body or any key is the existing malformed-body 400), both **200** with:

```json
{"matched": 43, "repairable": 43, "skipped": 0, "repaired": 0,
 "schedules": [{"id": "…", "title": "…", "outcome": "REPAIR", "reason": null}]}
```

`outcome` ∈ `REPAIR` (plan) | `REPAIRED` (apply) | `SKIPPED`; `reason` ∈ `PAUSED` | `PROVIDERS_DISABLED` | `null`. `plan` writes nothing and `repaired` is 0; `apply` reports its own re-plan and `repaired == repairable`. The path is top-level so no `/v1/schedules/{id}` segment can ever read `repairs` as an id; the router dispatches on segments (`rest == listOf("repairs", "schedule-providers", "plan")`, as `ApiRouter.kt:230-234` does for the merge). The rows are titled so the owner can read a plan. Like every other `/v1` write, an apply does **not** run a reminder sweep (`reconcileAll()` has no API caller); the next digest, backstop or Health-screen action does (the in-app action sweeps at once, P2) — see §8 step 12 and §10 Q1.

**The MCP adapter (P1).** `repair_schedule_providers(plan_only: bool = True)` — the safe default is the plan; `plan_only=False` posts the apply. The plan path joins `_POSTS_THAT_WRITE_NOTHING` (`server.py:145`) beside the merge plan, so it is allowed on any app. It returns the response above unchanged and raises `ToolError` on any error, as every 1.1.1+ tool does. `import_merge`'s vocabulary (`plan_only`) is kept; its default differs because a repair has no "applicable" gate of its own — the plan **is** the gate.

## 4. Owner gate — the strings (ratify before P2 and P3 start)

R1 is reopened: provider terminology is not product vocabulary, so the finding names the missing thing without naming LOCAL. Voice as 1.4 §10.7: plain, imperative, no jargon. Count sentences follow the 1.4 plural rule (one form for exactly 1, one for every other count; plain strings read by id). Each finding sentence is **one literal on one line** in source (the residual sentence is re-joined from its two literals at `ReminderHealthCheck.kt:303-304`), so the R6 greps match it.

| # | surface | proposed text | notes |
|---|---|---|---|
| P141-1a | Reminder Health, the `SCHEDULE_NO_PROVIDER` finding, count ≠ 1 | `<n> schedules have reminders turned on, but reminder delivery isn't configured.` | the owner's pair A sentence |
| P141-1b | the same, count = 1 | `1 schedule has reminders turned on, but reminder delivery isn't configured.` | |
| P141-2 | the finding's button | `Fix reminder delivery` | the owner's pair B label: no count, so no plural; it names what the tap does |
| P141-3 | schedule editor, the profile picker's label when **One tap** is selected | `Quick action` | the product noun for a profile (`v1.md`: "a quick action needs a name"); in FORM mode the ratified `Use this form` stays; alternative `Log it as` (rulings.md R1) |
| P141-4 | schedule editor, the picker's first row, clearing the profile | `None` | needed so a chosen profile can still be cleared once switching modes no longer clears it (#81) |

Kept as ratified in 1.2 (`master-plan.md:876`, `:892`): the sentence `<n> schedules have reminders switched on but no way to deliver them.` with `Open the schedule`, now drawn **only** for the residual shape the batch repair must not sweep — a non-empty provider set with nothing enabled — under a new finding code `SCHEDULE_PROVIDER_DISABLED` (§6 P2). No such row exists on either phone today, and the editor cannot make one. No detail line is added: the Health row has no second line today (`ReminderHealthScreen.kt:146-180`) and the sentence carries the meaning.

## 5. Cross-cutting invariants

1. Room schema **8**, backup format **8**, the merge planner, the migration and `libs/nfc-tag-core` (`7e0377a`) unchanged. No new column, table, DTO field or archive key.
2. `/v1` extended compatibly: no route, status, `code` or `problems` entry changes; two routes and one create default are added; PATCH keeps today's full-replace omission semantics **and** today's 400 for a `null` literal (R4; P1).
3. Provider rows are never synthesised on restore, import or merge (#80 AC 10): a repaired row exports `LOCAL`, an old providerless archive still imports providerless.
4. `remindersEnabled = false` is never repaired, a disabled provider is never enabled, PAUSED and ARCHIVED rows are never written (R3).
5. `ProviderId` stays `{ LOCAL }`; no reminder-provider choice appears anywhere in the UI; no user-visible string names a *reminder* provider (the Settings screen's `Provider` label at `SettingsScreen.kt:211`, `:215` is the attachment folder's documents provider and stays).
6. No `Automatic` repair for providers (§3). No new `runAndRepair` behaviour.
7. Every user-visible string is §4's or already ratified; a state that seems to need words is `NEEDS_CONTEXT`.
8. No device but `emulator-5554` for any implementer; phones are the controller's, at the steps §8 names, and the production phone only after the owner's stop.
9. Hygiene: no e-mail, no home-directory path (write `~`), no serial but `emulator-5554`, no private equipment names, "the owner's hardened Android build" never a distribution's name; commits by `git -c user.name=GonzRon -c user.email="$(git log -1 --format=%ae master)"`, one casual subject, no body, no trailer.
10. R6 greps are anchored patterns (planning policy), never a bare quoted literal.

## 6. What each brief must prove (summary; the matrices are in the briefs)

- **P1:** the `/v1` create truth table (omitted → LOCAL `enabled = remindersEnabled`, both values; `null` → the same; `[]` → empty; a list → kept; PATCH omitted → `[]` as today and PATCH `null` → 400 as today, both pinned); the MCP sends LOCAL by default and `[]` on request; the loader's args carry LOCAL derived from `remindersEnabled` and its plan ignores providers and `updatedAt`; the use case's predicate matrix, field preservation, the untouched skipped/archived rows at apply level, idempotence, the read-inside-the-write transaction and ordering; the routes' plan-writes-nothing, apply-then-replan; a repaired row is a LOCAL subject (`BuildReminderSubjectsTest`); format-8 round trip of a repaired row; `v1.md` documents all of it.
- **P2:** the finding split (isEmpty → P141-1 + P141-2, the whole value `OpenInApp("RESTORE_REMINDER_DELIVERY")`; non-empty-disabled → the 1.2 pair, `SCHEDULE_PROVIDER_DISABLED`); the singular form; the view model restores, then sweeps, then refreshes; a throwing repair leaves the finding and does not crash; the finding clears on the next run (JVM and Compose); `ReminderHealthCheck.repair` never reaches the use case.
- **P3:** the 23-row shape (QUICK, asset target, `profileId`, `remindersEnabled`) survives load → save unchanged, load → edit description → save, and FORM → QUICK → save, with both a `[LOCAL enabled]` and a providerless fixture (the phones' shape until the repair); `None` clears; a group offers neither label and ignores `onProfile` under One tap; `theEditorWritesExactlyOneProviderRowAndNeverLosesIt` stays green; `CompleteSchedule` untouched.
- **P4:** `versionName` / `versionCode` 1.4.1 / 17 with the `VersionAgreementTest` cases moved to 1.4.1 / 17 / `servicetag-v1.4.1` and a 1.4.1-row case; the `versioning.md` row; one runbook paragraph; a one-line 1.4.1 note in the two design documents that still describe the old repair.

## 7. Overrides to the standing constraints

Requirements come from the briefs, this plan and `rulings.md`, not the 1.4 spec or master plan. Strings: §4 once ratified, plus the 1.2 pair §4 names; nothing from 1.4 §10.7. The MCP's `pyproject.toml` version stays `1.2.0` as it did through 1.3.0 and 1.4.0 (a tools versioning rule is a carry-forward, §10). Every other constraint stands.

## 8. Proofs at the integrated tip (controller; the owner's 19 steps)

1. **Gates, locally, CI's command:** `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug :share-test-sender:assembleDebug --console=plain`, zero failures and zero skips (green again since the pre-wave reconciliation); `uv run --frozen pytest` in `tools/servicetag-mcp` and `tools/servicetag-schedules`; lint as B15 left it. R1–R6 of `docs/release-proofs.md` at the tip (R2 whole connected suite once, one class per invocation for the touched classes first: `ReminderHealthScreenTest`, `ScheduleEditorTest`).
2. Task reviews per brief; 3. one whole-patch review at the final tip (most capable model), ≤ 1 scoped follow-up.
4. **Emulator repair proof and 5. the direct 1.3.0 → 1.4.1 path, in one run on `emulator-5554`:** download the `servicetag-v1.3.0` release asset and verify its SHA-256 equals the 1.3.0 evidence (`a4ca5ec8…3442`) and its signer; uninstall the debug app; install 1.3.0. **Load the owner's data with the tools of that release:** a whole-tree worktree at `servicetag-v1.3.0` (`d1d0460`) supplies **both** the MCP and the loader (the loader imports the MCP in process through a path dependency, `tools/servicetag-schedules/pyproject.toml:13`; master's tools refuse schema 7 — `server.py:140`, `phone.py:69` — and master's MCP after P1 would send LOCAL and not reproduce the defect): `import_merge` of the Stage A bundle, then the Stage B manifest through that loader, so the 43 providerless rows are reproduced — `list_schedules` shows 43 ACTIVE rows with `providers: []`. Build the 1.4.1 APK with `tools/release-dry-run.sh` (it needs the local ServiceTag signing material; without it the script exits 3 and the proof waits — "same signer" is read from its fingerprint compare); `adb install -r` it over 1.3.0 (same package, same signer): `/v1/status` reads 1.4.1 / 17, schema 8, format 8, every 1.3 count identical (R7's key-set hash). **From here master's tools:** MCP plan → `matched 43 / repairable 43 / skipped 0`; apply → `repaired 43`; re-plan → `matched 0`; `list_schedules` before/after differ only in `providers` and `updatedAt`; the loader re-plans IDENTICAL (4 groups + 43 schedules); `ReminderHealthScreenTest` and the JVM suites stand for the finding and the subjects. There is no export route and the emulator's UI is not driven, so no archive is taken from the 1.3.0 stage: the reproducer is the recipe itself (the 1.3.0 tools, the Stage A bundle and the Stage B manifest in the owner's folder) plus the existing `prod-pre-migration-20260924/` and `dev-r7-*` API snapshots. Then uninstall the release build (the next connected run reinstalls debug).
6. **Release:** CI green on the tip → annotated tag `servicetag-v1.4.1` → the release workflow reaches the protected environment → **the owner approves** → download, checksum, `apksigner` one signer == `RELEASE_CERT_SHA256`, badging 1.4.1 / 17, the eight permissions, not debuggable.
7. **Development phone** (1.4.0 / 16 → 1.4.1 / 17): R7 in full — `adb install -r` in place, `firstInstallTime` and UID unchanged, R7 (i) counts and per-table hashes identical on the 1.4 key set. **The owner does not open Reminder Health's new button before step 10 is recorded** (its tap is the same apply and would void the count) — unless Q1's third option is chosen, in which case the tap *is* step 9. 8. MCP plan → 43 (the population the forensic inventory named). 9. apply. 10. re-plan → 0. 11. `list_schedules` before/after: every row's id, `profileId`, rule, policy, `postponedDueOn`, `ruleChangedAt` identical; only `providers` and `updatedAt` moved on the 43; closures and events counts identical; the 5 ARCHIVED rows still providerless. 12. **Declared deviation from ruling step (12):** a `/v1` apply does not sweep, and delivery state has no API, so "LOCAL subjects exist" is proven by `BuildReminderSubjectsTest` (JVM) and on the phone by the Reminder Health screen showing neither delivery sentence (P141-1 nor the residual) with the `REMINDER FAILED` badge clearing unless another WARN finding stands (the owner's look at the stop; the check runs on that screen). Q1 in §10 offers the owner two stronger alternatives. 13. **Post-repair backup (R5), proven:** the owner makes one export from Settings on the development phone into its usual folder; the controller runs `import_merge(archive, plan_only=True)` against the same phone — every table `insert 0 / conflict 0`, every exported row `IDENTICAL` (attachment rows `skipped` only where bytes are absent) — reads `scheduleProviders` = **43**, and archives a copy under `noteNFC-backups/dev-post-repair-1.4.1/` beside the before/after API snapshots. 14. **STOP** for owner approval.
15. **Production phone**, only after the stop, the direct path already proven: the owner grants INTERNET for the session (revoked after, as the owner prefers); install 1.4.1 over 1.3.0 (code 15 → 17) in place; R7 (i) on the 1.3 key set with the 1.4 mapping **and** R7 (ii), the loader re-plan IDENTICAL (4 groups + 43 schedules), because this install crosses schema 7 → 8; the same no-tap instruction as step 7. 16. plan → the count it reads (43 by provenance; the plan is the read-only confirmation #80 asks for). 17. apply. 18. re-plan 0 and the step-11 invariants. 19. the owner's export into the production folder, proven exactly as step 13, archived under `noteNFC-backups/prod-post-repair-1.4.1/`, and the evidence.

Old providerless exports (`prod-pre-migration-20260924/`, `dev-r7-*`) stay as history; a merge of one against a repaired phone plans `CONTENT_DIFFERS` on those rows by design (R5, `MergePlanner.kt:375-377` compares the whole DTO).

## 9. Acceptance per issue

- **#80** closes when AC 1–12 of its body are each named with a test or a proof: 1–3 `MaintenanceRoutesTest`; 4 the MCP and loader pytest cases; 5–7 the development-phone steps 8–12 (with step 12's declared deviation); 8–9 `RepairScheduleProvidersTest` (the apply-level untouched-rows case); 10 the codec test (a repaired row round-trips `LOCAL`) and the merge note above; 11 the ratified §4 strings; 12 the order of §8.
- **#81** closes when the acceptance list in its body is each named: `ScheduleEditViewModelTest` (load, save unchanged, edit-unrelated, mode switch, the 23-row shape in both fixtures, clear, the group ignoring `onProfile`), `ScheduleEditorTest` (the picker under One tap, `None`, the group drawing neither label), `CompleteSchedule` untouched (`git diff` empty), no provider/rule/history change (the command equality assertions).

## 10. Carry-forwards and open owner decisions

- **Strings §4** (P141-1a/b, -2, -3, -4): ratify or amend before P2 and P3 dispatch. P1 may start after the pre-wave reconciliation.
- **Q1 — step 12 on the phones.** (i) accept the declared deviation (JVM subjects + the finding gone); (ii) authorize a one-line sweep after an API apply (a design change: today no `/v1` write sweeps); (iii) make the owner's own `Fix reminder delivery` tap the apply on each phone, bracketed by the MCP plan before and re-plan after — it exercises the shipped UX, sweeps at once, and needs no code change; the controller recommends (iii) with the MCP apply as the fallback and the emulator proving the MCP path.
- **Q2 — PATCH with `"providers": null`.** Today a 400. The plan keeps it a 400 (P1 pins it); no ruling needed unless the owner wants it to mean "store no provider".
- **Q3 — the README and `VersionAgreementTest`.** The controller re-anchors the three cases to the new README before wave 1 (and declares `README.md` a test input). Say so if the README should carry the old phrases instead.
- The MCP's version string and a tools versioning rule (unchanged here; Phase 1A/1B doc item).
- `docs/api` joined the declared unit-test inputs with the reconciliation (the golden and reference-route tests read `v1.md`; a docs-only commit could otherwise be answered from the cache the same way).
