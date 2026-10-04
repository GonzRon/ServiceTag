# ServiceTag 1.7.1 — #94 the schedule body's 500, #99 the vacuous retain test, #103 the Maintenance tab and Reminder health: plan (rev 1.0, 2026-10-04)

**Release:** 1.7.1 / `versionCode` 21, a **PATCH** under #104's scope rule — bug fixes, test and reliability debt, documentation corrections and bounded UI cleanup; **no new user-facing capability rides this train**. **Scope:** #94, #99, #103, nothing else. #101 (documentation corrections) completed before this train and is not re-planned.
**Base:** master `5cd878c` (1.7.0 / code 20, published 2026-10-04; Room schema **21** / backup format **20**; gitlink `7e0377a`; the MCP server at 90 tools). **Roadmap:** #104 is the authority; its "ServiceTag 1.7.1 — first maintenance release" section is this plan's scope statement.
**Inputs:** the bodies of #94, #99 and #103 (the owner's; #103 carries the layout ruling of 2026-10-04); `rulings.md` beside this file (the rulings in force, the decisions still open, the PENDING strings); the 1.4.1 train (`../2026-09-25-servicetag-1.4.1/`) as the PATCH-train precedent; `docs/versioning.md`; `docs/release-proofs.md`; `docs/superpowers/planning-policy.md`.
**Process:** the planning policy — plans specify; one task review per brief, batched fixes, at most one scoped re-review; mechanical changes under about fifty lines close by controller inspection plus the automated gates; the testing hierarchy (JVM → Compose instrumented → contract tests → the one signed-APK upgrade smoke; no UI driving). Hygiene as the 1.4.1 plan §5.9: no e-mail, no home-directory path, no serial but `emulator-5554`, no private equipment names; commits with one casual lowercase subject.

> **Status at rev 1.0 (2026-10-04).** **P1 (#94) and P2 (#99) are executed on this branch, in the two commits before this plan's** (§9): neither adds a user-visible string, each is well under fifty lines of production and test change, and each is the shape its issue asked for — so both close by controller inspection plus the automated gates, as the review budget allows. **P3 (#103) is gated** on the owner's rulings Q1–Q4 in `rulings.md` and on §4's PENDING strings; nothing of it is built until the gate passes. **P4** runs last, at the release tip, after P3 merges.

## 1. The three defects, one paragraph each

**#94.** `ScheduleForms.read` (`app/src/main/kotlin/com/loosecannon/servicetag/api/ScheduleForms.kt`) reads the schedule POST/PATCH body as a raw `JsonObject` before deciding its form, through `request.decode(JsonObject.serializer())`. kotlinx's map decoder answers a key with no value — `{"title":}` — with a bare `IllegalArgumentException` ("Value must follow key in a map"), which `decodeOr400` does not catch, so `POST /v1/schedules` and `PATCH /v1/schedules/{id}` answered **500 `internal`** for a caller's typo. The planner verified this against kotlinx-serialization 1.9.0 (the catalog's version) in a scratch project, and verified one thing the issue guessed at: the map decoder *does* refuse a missing comma between two pairs ("Expected comma after the key-value pair"), so on these two routes a missing comma was already the 400 — the #91 laxity was the typed decoder's, which these routes never reach first. The one defect is the 500.

**#99.** `TransferGraphRetainTest.retainingNothingIsTheIdentity` claims "the fixture fills every list the archive has" and encodes the fixture through the default `Json`, which **omits** an empty defaulted list. `TransferFixtures.estate()` never fills `supplyItems`, `assetSupplies`, `assetSuccessions` or `transferRecords`, so for those four the "fills every list" line asserted nothing and the identity held vacuously; `installedComponents` was covered only because #47 added one row and an explicit presence assertion. The KDoc's "#15's tables and every later one" was never true.

**#103.** On the Maintenance tab **Supplies** is the fifth row, below Due work, Schedules and the inline Maintenance groups list, so it sits below the fold; **Installed components** has no entry in the tab at all; and **Reminders**, the last row, opens the reminder-health page (#27), which with every check passing shows nothing and reads as empty or broken. It is a diagnostics-and-repair tool, not maintenance work (owner ruling 2026-10-04, after using 1.7.0 on both phones).

## 2. Briefs, order, lanes, file ownership

| brief | issue | scope | proof layer | state |
|---|---|---|---|---|
| `P1-schedule-body-400.md` | #94 | the raw read of the schedule command through the shipped 400 path; one route case per body shape on both routes; one `v1.md` paragraph | JVM (`MaintenanceRoutesTest`) | **executed** (§9) |
| `P2-retain-test-coverage.md` | #99 | the identity check encodes defaults and asserts the archive's whole key set; a fixture row in every list; the KDoc corrected | JVM (`:core:test`) | **executed** (§9) |
| `P3-maintenance-tab-and-reminder-health.md` | #103 | the tab's new layout, the Utilities row, the healthy state, the device classes, the view-model pins, the design-doc table | JVM, Compose instrumented | **gated** (rulings Q1–Q4, strings §4) |
| `P4-version-docs-runbook.md` | release | 1.7.1 / 21 with its `VersionAgreementTest` cases, the `versioning.md` row, `docs/releases/1.7.1.md`, the README's two lines, the runbook's and the evidence file's 1.7.1 lines | `VersionAgreementTest`, grep | after P3 |

**Order.** P1 and P2 shared no file and ran together (lane A, done). P3 is one lane after the gate. P4 alone, at the release tip. Connected runs on `emulator-5554` belong to P3 alone; nothing else in this train touches a device.

**File ownership.** Every file is owned by exactly one brief; the pre-executed briefs' files are listed so P3 cannot wander into them.

| file | P1 | P2 | P3 | P4 |
|---|---|---|---|---|
| `app/…/api/ScheduleForms.kt`; `app/src/test/…/api/MaintenanceRoutesTest.kt`; `docs/api/v1.md` (**Deprecated inputs (1.4.0)**, one paragraph) | owner | — | — | — |
| `core/src/test/…/transfer/TransferGraphRetainTest.kt` | — | owner | — | — |
| `core/src/test/…/transfer/TransferFixtures.kt` | **untouched** by all four (P2 §"Why the rows are local") | | | |
| `app/…/ui/maintenance/MaintenanceScreen.kt`, `MaintenanceViewModel.kt`; `app/…/ui/settings/SettingsScreen.kt`; `app/…/ui/nav/Route.kt`, `ServiceTagRoot.kt`; `app/…/ui/maintenance/ReminderHealthScreen.kt`, `ReminderHealthViewModel.kt`; any new list screen Q1/Q2 rules in; `app/src/test/…/ui/maintenance/*Test.kt`, `app/src/test/…/ui/nav/RouteTest.kt`; `app/src/androidTest/…/ui/maintenance/MaintenanceShellTest.kt`, `ReminderHealthScreenTest.kt`; `app/src/androidTest/…/ui/settings/SettingsBackupEntryTest.kt`; `docs/design/03-target-architecture.md` §7.3; `docs/design/12-visual-design-apollo-service-binder.md` (the 1.2 navigation note); `docs/capabilities.md` where it names the Reminders row | — | — | owner | — |
| `app/build.gradle.kts` (the version lines and their comment); `app/src/test/…/VersionAgreementTest.kt`; `docs/versioning.md`; `docs/releases/1.7.1.md` (new); `README.md` (the current-release line and the two release lines); `docs/release-proofs.md` (one line); `docs/architecture/product-split-evidence.md` (one section) | — | — | — | owner |
| `app/…/reminders/ReminderHealthCheck.kt`, `LocalReminderProvider.kt` (the checks and the repairs); `core/**` production; the schema, the migration, the backup codec, the merge planner; `tools/**`; `.github/**`; `libs/nfc-tag-core` | **untouched** by all four | | | |

**`<base>` per brief.** P1 and P2: `5cd878c`. P3: the master commit that carries this plan and P1–P2 (the controller names it at dispatch). P4: the master commit that merged P3.

## 3. Cross-cutting invariants

1. Room schema **21**, backup format **20**, the merge planner, the migration, the backup codec and `libs/nfc-tag-core` (`7e0377a`) unchanged. No new column, table, DTO field or archive key; `GET /v1/status` reads schema 21 / format 20 before and after.
2. `/v1` extended compatibly, with **one status change a script could meet**: a schedule command body with a key and no value is 400 `bad_request` where it was 500 `internal` (#94). No route, code or `problems` entry changes; no field is added or removed; the MCP server is unchanged (its own suite is R3's, untouched).
3. **No new capability.** #103 moves entries and adds a healthy state; the checks, the repairs, the schedules, the groups, the Supplies and the installed-components screens themselves do not change. If Q2 rules in a list of installed components, it is a navigation entry over rows that already exist and it writes nothing.
4. The reminder-health checks and their repairs are unchanged in number, code, sentence and order; the healthy state is drawn from the same run the findings come from, never from a second check.
5. Every user-visible string is §4's once ratified, or already ratified; a state that seems to need words it has not been given is left with none and recorded. P1 and P2 add no string.
6. The Dashboard's health badge keeps navigating to the reminder-health page and nothing else about the Dashboard changes (#103 ruling 4).
7. No device but `emulator-5554` for any implementer; the phones are the controller's at the steps §6 names.
8. R6 greps are anchored patterns, never a bare quoted literal (planning policy).

## 4. Strings — PENDING, for the owner's gate before P3

All of them are #103's. Voice as the 1.4 spec §10.7 and the 1.2 master plan §17: plain nouns for rows and headings, one sentence for a state. Each is one literal on one line in source. The IDs are this train's (`P171-`). `rulings.md` carries the same table with the alternatives the planner considered.

| # | surface | proposed text | notes |
|---|---|---|---|
| P171-1 | Maintenance tab, the heading of the grouped section (Q1 recommended) or the one row that opens the second-level list (Q1 alternative) | `Groups, supplies and components` | names the three peers so the row cannot be mistaken for more maintenance work; alternatives `Catalogs`, `Equipment and supplies` |
| P171-2 | Settings › Utilities, the new row | `Reminder health` | the issue's own words; the row sits after `Categories` and before `Home Assistant`, among the tools |
| P171-3 | the reminder-health page's title | `Reminder health` | today the title is the tab row's word `Reminders` (`REMINDERS_SECTION`); with that row gone the page is named by the row that opens it (P171-2), the rule `ReminderHealthScreen` already follows |
| P171-4 | the healthy state, its one sentence, when the last run found nothing | `No problems found.` | drawn only when `loaded` and the row list is empty; never while a finding stands |
| P171-5 | the healthy state, the time of the last check | `Last checked <date> at <time>` | `<date>` by the app's `displayDate`, `<time>` by the phone's short time format; the instant is the run's own, recorded with its result (P3 §"The healthy state") |
| P171-6 | the healthy state, the heading over the checks that passed | `Checks that passed` | |
| P171-7a | a passed check: notifications (`NOTIFICATIONS_BLOCKED` absent) | `Notifications are allowed` | |
| P171-7b | reminders switched on (`REMINDERS_GLOBALLY_OFF` absent) | `Reminders are turned on` | |
| P171-7c | the daily reminder alarm (`DIGEST_ALARM_MISSING` absent) | `The daily reminder check is scheduled` | |
| P171-7d | the backstop (`BACKSTOP_WORK_MISSING` absent) | `The background safety check is running` | the finding's own noun, so the pair reads as one check |
| P171-7e | background restriction (`APP_RESTRICTED` absent) | `This phone is not holding ServiceTag back in the background` | the finding's own noun |
| P171-7f | reminder delivery (`SCHEDULE_NO_PROVIDER` and `SCHEDULE_PROVIDER_DISABLED` absent) | `Every schedule with reminders can deliver them` | one check, two finding codes |
| P171-7g | meter baselines (`NO_DATA` absent) | `Every meter schedule has a baseline reading` | |

Kept as ratified and reused without a new string: `Maintenance groups` (`GROUPS_SECTION`), `Supplies` (`SUPPLIES_SECTION`), `Installed components` and `No installed components` (`INSTALLED_COMPONENTS_SECTION`, `NO_INSTALLED_COMPONENTS`), `Maintenance group` (the create row), every finding sentence and repair label of #27 and 1.4.1, `REMINDER FAILED` (the badge). A Maintenance groups list screen (Q1) is titled by `GROUPS_SECTION`; an Installed components list (Q2) by `INSTALLED_COMPONENTS_SECTION`, its empty line `NO_INSTALLED_COMPONENTS`, and a row's second line the asset's name as the share picker's path already draws it (P69-20) — no new string.

## 5. What each brief must prove (summary; the matrices are in the briefs)

- **P1:** on both routes, a key with no value (three spellings) is 400 `bad_request`; a missing comma (two spellings) is 400; a non-object body (`[]`, a string, `null`, a number) is 400 carrying the map decoder's own message as before; nothing is written by any of them (the schedule table before and after); every existing schedule case stays green, the `"providers": null` 400 among them; `v1.md` says it.
- **P2:** the identity fixture's encoded key set equals `BackupData.serializer().descriptor.elementNames`; no encoded list is empty; the five lists `estate()` never fills are present and non-empty; `retain(fitted, emptySet())` is `Retained(fitted)`; every other case of the class and of `TransferGraphTest` is unchanged and green.
- **P3:** the tab lists Due work, Schedules and the three peers and no Reminders row (Compose, `MaintenanceShellTest`; JVM, `MaintenanceViewModelTest` for anything the view model now carries); each peer opens its list (the two existing routes, the Q1 groups list, the Q2 components list); Settings › Utilities has `Reminder health` and it calls its seam (`SettingsBackupEntryTest`); the reminder-health page is reachable from Settings and still lists a real finding and repairs it (`ReminderHealthScreenTest`, re-routed); with nothing found the page draws P171-4, P171-5 with the run's instant and P171-6 over all seven P171-7 lines, and with a finding it draws the finding and no healthy line (JVM, `ReminderHealthViewModelTest`; Compose where the seam allows); the Dashboard badge still opens the page; `RouteTest` pins any new route as neither top-level nor tag-reading; the design docs say the new placement.
- **P4:** `versionName`/`versionCode` 1.7.1 / 21; the `VersionAgreementTest` cases moved and a `versioningRecords171` case; the `versioning.md` row naming the three issues, schema 21 / format 20 unchanged, the one 500→400 change; the release notes; the README; one runbook line; one evidence section.

## 6. Proofs at the integrated tip (controller)

1. **Gates, locally, CI's command:** `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug :share-test-sender:assembleDebug --console=plain`, zero failures and zero skips; R3's three pytest suites unchanged and green. R1–R6 of `docs/release-proofs.md` at the tip; R2 the whole connected suite once, the touched classes first (`MaintenanceShellTest`, `ReminderHealthScreenTest`, `SettingsBackupEntryTest`, and any class P3 adds).
2. One task review for P3 (P1 and P2 closed by inspection, §9); one whole-branch review at the final tip, at most one scoped follow-up.
3. **No schema or format step, so no emulator upgrade paragraph of its own:** the signed candidate is installed in place over 1.7.0 on `emulator-5554` (`adb install -r`; the UID and `firstInstallTime` unchanged; `/v1/status` reads 1.7.1 / 21, schema 21, format 20, every count identical), then the three #103 surfaces are looked at by hand once — the tab, Settings › Utilities, the healthy or the found state as the emulator has it.
4. **Release:** CI green on the tip → annotated tag `servicetag-v1.7.1` → the release workflow → the owner approves → download, checksum, `apksigner` one signer, badging 1.7.1 / 21, the permission set unchanged from 1.7.0, not debuggable.
5. **Development phone, then production phone** (both run 1.7.0 / 20): R7 in full, in place, counts and per-table hashes identical; the owner's look at the three surfaces. No data step: 1.7.1 writes nothing new on install.

## 7. Acceptance per issue

- **#94** closes when the route case of P1 is green in CI on both routes, `v1.md` carries the paragraph, and `git grep -nE 'JsonObject\.serializer\(\)' app/src/main` finds only the non-object fallback inside `ScheduleForms.readObject`.
- **#99** closes when `retainingNothingIsTheIdentity` asserts the archive's whole key set with defaults encoded, the five lists are filled in its fixture, and the KDoc's claim is the true one.
- **#103** closes when its four acceptance bullets are each named by a test or a doc line (P3 §"Acceptance map"), the strings of §4 are ratified and shipped verbatim, and the three design-doc sites say the new placement.

## 8. Open owner decisions

`rulings.md` §"Decisions pending" — **Q1** the sub-menu's shape (recommended: a grouped section of three rows in the tab, the inline group list moving to its own pushed screen); **Q2** the Installed components entry (recommended: a new read-only pushed list of every current installed component, by asset, each row opening the asset's detail where the component is edited); **Q3** the strings of §4; **Q4** the healthy state's "last check" instant (recommended: the run's own instant, cached beside its result in `ReminderHealth`, in no preference and no table). #90's promotion is unchanged by this train (#104's rule).

## 9. Execution record (rev 1.0)

- **P1 executed** in the first commit before this plan's: `ScheduleForms.readObject` (the element parser through the shipped 415/400 paths, a non-object handed to the map decoder for its own 400), `MaintenanceRoutesTest.aMalformedScheduleBodyIs400OnCreateAndPatchAndWritesNothing` (nine bodies × two routes, the table unchanged after), one `v1.md` paragraph. **Gate status:** the planning environment reaches neither Google's Maven repository nor a toolchain download, so `:app:testDebugUnitTest` could not be run there; the serialization behaviour was proven against kotlinx-serialization 1.9.0 in a scratch project (P1 §"What the probe showed"), and CI's unit gate on the branch is the proof of record. The controller does not merge on the probe alone.
- **P2 executed** in the second, beside P1's: the identity fixture, `encodeDefaults = true`, the key-set assertion, the KDoc. **Gate status:** `:core:test` was run in full in the planning environment through a scratch settings file aimed at the same sources (P2 §"Gate"); CI's run is the proof of record.
- **P3 not started;** **P4 not started.**
