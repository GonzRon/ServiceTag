# B15 — the group screens and asset integration

**Read first:** the master plan's §1, §6 (groups, membership and occurrences), §11 (the Maintenance destination) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.3, §2.4, §2.6's last two bullets; rulings D-14, D-15, D-16, D-26; issue snapshot `issue-55.md` §"Dashboard / UX" and AC 11.

## Purpose

The visible half of #55, and the two navigation directions the issue calls its minimum: inside the Maintenance destination, **list and open maintenance groups**, see their **current members**, see the current occurrence's **progress**, and add, soft-remove, re-add and archive a member — with the archived group keeping its history. And on asset detail, a **schedules section** and a **"groups this asset belongs to"** section, so the owner can go from a group to a member and from an asset to its groups. It adds no domain rule: every membership operation is B03's use case, called, and the progress is B03's derived occurrence, read.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/GroupListScreen.kt` — the list inside Maintenance.
- `app/.../ui/maintenance/GroupDetailScreen.kt` and `GroupDetailViewModel.kt` — members, the current occurrence's progress, and the member operations.
- `app/.../ui/maintenance/GroupEditScreen.kt` and `GroupEditViewModel.kt` — name, description and the member set.
- `app/.../ui/asset/AssetMaintenanceSections.kt` — the two new asset-detail sections, in the shape of the screen's existing sections.
- Tests: `app/src/test/kotlin/.../ui/maintenance/GroupDetailViewModelTest.kt`, `GroupEditViewModelTest.kt`, `app/src/test/kotlin/.../ui/asset/AssetMaintenanceSectionsTest.kt`; connected `app/src/androidTest/kotlin/.../ui/maintenance/GroupScreensTest.kt`.

**Modify**

- `app/.../ui/nav/ServiceTagRoot.kt` — `Route.GroupDetail` and `Route.GroupEdit` wired into the back stack.
- `app/.../ui/maintenance/MaintenanceScreen.kt` (B08's) — the groups section's row routing to the list.
- `app/.../ui/asset/AssetDetailScreen.kt` and `app/.../ui/asset/AssetViewModels.kt` — the two sections placed and fed. **B14 owns the "add a schedule" entry**; this brief owns the **section** that lists them.
- `app/.../di/AppGraph.kt` — the two view-model factories.

**Untouched:** `core/**` — every rule is B03's use case, **called, never re-implemented**; `api/**`; `data/**`; `reminders/**`; `nfc/**`; `tools/`; `libs/`; `ui/theme/**`. **`app/.../ui/asset/AssetEditScreen.kt`** — group membership is **not** an asset field and must not appear on the asset's own edit form.

## Interfaces

**Consumes from B03:** `SaveGroup` and the group command with its problem types (including `MemberAlreadyOpen`), `ArchiveGroup`, `CompleteGroupMembers`, `GroupOccurrence`, `GroupRepository`. **From B02:** `ScheduleRepository`, `statusOf`, `Today`. **From B08:** the Maintenance shell's groups row, `DueReadModel.forAsset(assetId)` for the asset's schedules section, and `DueItem`. **From B14:** `CompletionFlow` for a member completion started from the group screen, and `Route.ScheduleDetail`/`ScheduleEdit` for the schedules section's rows.

**Produces:** nothing another brief consumes. It is a leaf, alongside B09.

**What the group screens show and do, as contract** (#55 "Dashboard / UX" minimum, D-14, D-15, D-16, D-26):

| surface | contract |
|---|---|
| group list | every group, archived included but visibly distinguished, ordered by name. **A name is a label, never identity** (invariant 7) |
| group detail | the group's **current members** (open windows only), its **description** — there is **no location field** (D-26) — its group-targeted schedules, and for each the **current occurrence's progress** in the ratified form |
| add a member | one `SaveGroup` call; a member already open is refused with `MemberAlreadyOpen` and the UI says so rather than silently doing nothing |
| remove a member | **soft**: `removed_at` stamped, the row retained. The member disappears from the current-members list and **remains** in the group's history (invariants 8, 79) |
| re-add a member | a **new** membership row with a new id and a new `added_at`; **no `removed_at` is cleared**, and **no past occurrence's required set changes** (invariants 33, 79) |
| archive a group | the group and its schedules leave the active views; **every membership row, completion and closure is retained** (D-16, #55 AC "archived groups retain their maintenance history") |
| member navigation | a member row opens that member **Asset**'s ordinary detail — the group → member direction |
| complete a member | delegates to B14's `CompletionFlow`; completing one member marks **this member only** (invariants 28, 29) |
| an empty group | shown as empty, and **offering no schedule creation** — a group-targeted schedule on an empty group is refused (invariant 74) |

**What asset detail gains** (spec §2.6's last two bullets, #55 `issue-55.md:123-127`):

| section | contract |
|---|---|
| **schedules** | the asset's own schedules with their status words, from `DueReadModel.forAsset`; each row opens the schedule detail. It does **not** list the group schedules the asset is a member of as if they were its own — those belong in the groups section's context |
| **groups this asset belongs to** | the groups where this asset has an **open** membership window, each opening the group detail — the asset → groups direction |

`Plan decision:` the asset's schedules section lists **asset-targeted schedules only**, and the groups section is where group-targeted work is reached. Spec §2.6 says asset detail "gains a schedules section and a 'groups this asset belongs to' section" without saying whether group schedules appear in the first; listing them there would make one obligation appear twice on one screen and contradict D-15's counted-once principle, and the scan sheet (B09) is the surface that deliberately merges both for the standing-at-the-equipment case.

`Plan decision:` **archived groups are shown in the list, visibly distinguished, rather than hidden behind a filter.** #55 requires archived groups to retain their history and spec §2.3 says archiving "hides it and its schedules"; hiding the *schedules* and the *due work* is the ruled behaviour, but a group the owner cannot find is a group whose history is unreachable, and 1.2 has no filter UI to build.

## Invariants this brief must hold

**7, 8, 28, 29, 33, 74, 79, 80** (master plan §13) at the UI boundary, and it must not weaken **4, 5** — nothing here makes a group into an Asset or gives it a tag.

## Test matrix

One test per hazard class; unit tests over the two view models, plus one connected class.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| groups unreachable | the group list is reachable from the Maintenance shell, lists groups by name, and opens a detail (#55 AC 11's precondition) | a domain with no screen means the owner cannot use #55 at all |
| **progress misreported** | a group with five required members and three complete shows the ratified progress form, and the occurrence is shown as **still open**; the group counts **once** in any total on the screen (D-15, #55 AC 11) | reading progress from today's membership instead of `required(D)` changes the denominator whenever a member is added |
| **remove treated as delete** | soft-removing a member takes it out of the current-members list, **retains its row**, and leaves the group's recorded completions and any closure intact (invariants 8, 79) | a hard delete destroys the history a past round's required set is derived from — #55 AC 8 |
| **re-add** | re-adding a removed asset shows it in current members again, and a structural/behavioural assertion that a **second** membership row exists with a new `added_at` and the first's `removed_at` **unchanged** (invariants 33, 79) | clearing `removed_at` to "undo" the removal rewrites the window a past round keyed off |
| a duplicate open member | adding an asset that is already an open member is **refused** and the UI says so; the group still holds exactly one open row for it (invariant 80) | a silent no-op looks like the app ignored the tap; a second row makes `required(D)` double-count |
| **archive losing history** | archiving a group removes it and its schedules from the active views, **keeps** every membership row, completion and closure, and the group's history is readable when it is opened directly (D-16) | a cascade delete on archive is the destructive reading of "archive" that D-16 forbids |
| an archived group still due | an archived group's schedules appear in **no** due total and **no** dashboard section | an archived group that keeps reminding is worse than one that is hidden |
| **both navigation directions** | one test each: a group detail's member row opens that **Asset**'s detail; and an asset detail's groups section opens the **group** detail (#55's navigation minimum) | one direction only leaves half the model unreachable |
| the asset's sections wrong | asset detail's schedules section lists the asset's **own** schedules with their status words; the groups section lists the groups where its membership is **open** and not the closed windows | listing closed windows makes a removed asset look like a current member |
| **a group schedule shown twice** | a group-targeted schedule appears in the **groups** section's context and **not** in the asset's own schedules section | showing it in both makes one obligation look like two, against D-15 |
| an empty group getting a schedule | a group with **no members** offers **no** schedule creation, and if one is attempted the refusal is shown (invariant 74) | a schedule on an empty group is permanently unactionable and reports `NO_DATA` forever |
| **a member completed by the wrong path** | completing one member from the group screen writes one event on **that** member and leaves every other member's history byte-identical (invariants 28, 29) | a "complete the group" button is the false history #55 AC 5 forbids |
| a group becoming equipment | a structural assertion: nothing in this brief creates an `Asset`, sets `parentAssetId`, or reaches the tag-binding or writing path (invariants 4, 5) | the "fake parent asset" shortcut, arrived at through the UI instead of the domain |
| a location field | a structural assertion: the group screens have **no** location field; `description` carries context (D-26) | adding one creates a field with no domain column behind it |
| name as identity | two groups with the same name coexist and both are reachable; no screen resolves a group by name (invariant 7) | a name-keyed lookup coalesces two real groups |

**Connected set (`emulator-5554` only, `ANDROID_SERIAL` pinned, never a phone):** one class covering the group list → detail → member-asset navigation, an asset detail's two sections and the asset → group navigation, and a soft-remove followed by a re-add with the current-members list asserted at each step.

## Strings

**Ratified, verbatim** (master plan §17): **"Maintenance group"**; the progress form **"3 of 5 complete"**; **"Complete selected"** and **"Complete all"** where the group detail offers the round's checklist; **"Open asset"** where a member row's action is named; and the status terms **OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE**.

**PROPOSED it depends on but does not draft:** the Maintenance destination's section labels (B08) and the "Close this round" confirmation (B14) — **"Close this round" is B14's action and is not offered on these screens.**

**This brief drafts nothing.** It needs an empty-state line for a group with no members and for a phone with no groups; reuse B08's ratified empty-state wording once the owner has ratified it, and if neither fits, that is a finding for the controller rather than a sentence to invent.

## Ordering

**After B03 and B08's shell.** It also uses B14's `CompletionFlow` for a member completion; if B14 has not landed, the group detail may omit the inline completion and link to the schedule detail instead, and the review records which happened. Runs in lane B of wave 6 beside **B09**.

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected on **`emulator-5554`**: `./gradlew :app:connectedDebugAndroidTest --tests '…ui.maintenance.GroupScreensTest' --console=plain` → zero failures, zero skips.
- Structural, anchored:
  - `grep -rnE '(SaveGroup|ArchiveGroup|CompleteGroupMembers)' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance` shows the use cases **called**; `grep -rn 'GroupRepository\.upsert\|groups.upsert' app/src/main/kotlin/com/loosecannon/servicetag/ui` → **no match** (no direct write).
  - `grep -rniE '\blocation\b' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/GroupEditScreen.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/GroupDetailScreen.kt` → no match.
  - `grep -rnE '(CreateAsset|parentAssetId|BindTag|ProvisionTag)' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/Group*.kt` → no match.
  - `grep -rn 'removedAt = null\|removed_at = NULL' app/src/main/kotlin/com/loosecannon/servicetag/ui` → no match (invariant 79).
  - `grep -rn 'CloseRound' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/Group*.kt` → no match.
  - `git diff --stat master -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/data app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetEditScreen.kt` → **empty**.

## Estimated size

Medium. Three screens, two view models, two asset-detail sections, one connected class. No split expected.
