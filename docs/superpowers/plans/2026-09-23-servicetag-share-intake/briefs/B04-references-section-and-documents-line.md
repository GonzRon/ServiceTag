# B04 — the References section, "Add link", and the Documents row's second quiet line

**Read first:** the master plan's §1 (global constraints), §5 (the policies this brief calls), §8
(the UI surfaces), §9 (invariants), §13 (waves — **this brief holds `emulator-5554` for wave 4**),
§17 (strings, **including the Add-link sheet's two strings the owner ratified on 2026-09-23**),
§18.4, §18.8 and §18.14. Those sections are the contract; this brief is the work. **Spec:**
`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` §3.3, §4.2, §4.4, §7, §10, Rulings
D-9, D-10, D-19, D-21. **Report:**
`.superpowers/sdd/2026-09-23-servicetag-share-intake/B04-report.md`.

## Purpose

Land the place on the asset that shows and opens what a share saved: a **References** section below
Documents, built from the same section primitives, with Open, Edit, Remove and the section's own
**Add link** action; and D-19's second quiet line on the Documents row, so a shared document's
description stops being collected, stored and invisible forever. `LinkLauncher`'s refusal is
corrected to the ratified sentence in the same commit (§18.8). **No share target, no manifest
change, no route, no MCP tool.**

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/references/ReferencesSection.kt` — the section
  and its row, in the shape of `ui/attachments/DocumentsSection.kt`.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/references/ReferencesSectionViewModel.kt` — the
  state, in the shape of `AttachmentsSectionViewModel`: a `Flow` off
  `ReferenceRepository.observeForAsset`, a message channel, and one `when` over `ReferenceProblem`.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/references/ReferenceSheets.kt` — the edit
  sheet, the add-link sheet and the remove confirmation, in the shape of
  `ui/attachments/AttachmentEditSheet.kt`.
- Tests: `app/src/test/kotlin/.../ui/references/ReferencesSectionViewModelTest.kt`,
  `app/src/androidTest/kotlin/.../ui/references/ReferencesSectionTest.kt`,
  `app/src/androidTest/kotlin/.../ui/attachments/DocumentsDescriptionLineTest.kt`.

**Modify**

- `app/.../ui/asset/AssetDetailScreen.kt` — one call, `ReferencesSection(...)`, **immediately
  below** the shipped `AttachmentsSection(...)` call (`AssetDetailScreen.kt:262`) and above
  `NotesSection`. Nothing else in that file moves.
- `app/.../ui/attachments/DocumentsSection.kt` — `DocumentRow`'s `Column` gains the D-19 line, and
  `AttachmentRowState` gains whatever field carries `notes` if it does not already. **No other
  change**: the header, the empty state, the action buttons, the storage block, `quietLine()`,
  `label()`, `glyph()` and `asFileSize()` are untouched.
- `app/.../ui/attachments/AttachmentsSectionViewModel.kt` — **only** if `AttachmentRowState` needs
  the `notes` field carried through `row(...)`. The exhaustive `when` in `say(problem)` and every
  string in it are **untouched**.
- `app/.../links/LinkLauncher.kt` — the refusal message becomes the ratified **"No app can open this
  link"** with **no URI** (§4.4, §18.8), held as an `internal const val` so a JVM test can assert
  the exact text (see the matrix). Two consequences to settle rather than discover: once the URI
  leaves the message, `noHandler`'s `uri` parameter (`LinkLauncher.kt:21`) is **unused and is
  removed**, and its two call sites lose the argument; and its only shipped caller,
  `ui/settings/SettingsScreen.kt:264`, **does change what a person sees** — its *toast* now reads
  the ratified sentence instead of naming the project URL, which is the point of §18.8 — while its
  own snackbar, "No browser available for this link" (`SettingsScreen.kt:266`), is a different
  string on a different surface and is **untouched**. `SettingsScreen.kt` itself is not edited.
- `app/.../di/AppGraph.kt` — nothing new unless the section needs an accessor B02 did not expose; if
  it does, it is one `val` and the report says so.

**Untouched:** `app/.../share/**` and the manifest (B03); `api/**` and `tools/` (B05); every `:core`
file (B01's and B02's); `AttachmentProblem` and the `when` over it; `AttachmentKind.label()`;
`AttachmentKinds.inferFrom`; `AttachmentStore` and both implementations;
`ui/settings/SettingsScreen.kt`; **`ui/components/**`** — `SectionHeader` (`SectionHeader.kt:20`)
and `QuietLine` (`QuietLine.kt:13`) are consumed by this brief and by B03, and neither lane may
tweak a shared primitive: a change needed there is a finding for the controller;
`ui/maintenance/**`, `ui/dashboard/**`, `ui/theme/**`; every tombstone; `app/build.gradle.kts` and
every document.

## Interfaces

**Consumes:** B01's `AssetReference`, `ReferenceKind`, `ReferenceId`, `ReferenceRepository`; B02's
`LinkLaunchPolicy`, `LinkDecision`, `AddReference`, `AddReferenceCommand`, `UpdateReference`,
`UpdateReferenceCommand`, `RemoveReference`, `ReferenceProblem`, `ReferenceResult`,
`MAX_REFERENCE_NAME_CHARS`, `MAX_REFERENCE_DESCRIPTION_CHARS`; the shipped `LinkLauncher`,
`SectionHeader`, `QuietLine`.

**Produces** (internal to `:app`):

```kotlin
// app/.../ui/references/ReferencesSectionViewModel.kt
data class ReferenceRowState(
    val id: String,
    val displayName: String,
    val description: String,
    val uri: String,
    val kind: ReferenceKind,
    /** False when LinkLaunchPolicy now refuses this stored URI: shown, never launched. */
    val launchable: Boolean,
)

data class ReferencesSectionState(
    val rows: List<ReferenceRowState> = emptyList(),
    val pendingConfirmation: String? = null,   // the unknown scheme awaiting "Save this link?"
)
```

```kotlin
// app/.../ui/references/ReferencesSection.kt
@Composable
fun ReferencesSection(
    assetId: AssetId,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onOpen: (String) -> Boolean,       // hands the URI to LinkLauncher; false == no handler
)
```

The section never constructs an `Intent`: it calls `onOpen`, and `AssetDetailScreen` routes that to
`LinkLauncher`, which is the one place `ACTION_VIEW` is fired. That keeps the Android surface in one
file and makes the "shown, never launched" rule testable without an activity — a fake `onOpen`
records what it was asked to open, or refuses. It returns `Boolean` (as `LinkLauncher.open` already
does) so the section can show the ratified missing-handler snackbar itself.

## Behaviour contract

**The section.** Header "References" when empty, "References · \<n\>" otherwise — the exact shape
`DocumentsSection` uses at `:81`. Empty state: the single quiet line **"No references yet"**. Each
row shows the display name (one line, ellipsised), a **quiet kind label** — "Web link" for
`WEB_URL`, "Note" for `NOTE_LINK`, "Other" for `OTHER` — and, **when the description is non-empty**,
a second quiet line carrying it, `maxLines = 1` with `Ellipsis`. A trailing overflow offers
**"Open"**, **"Edit"**, **"Remove"**. The section's own action is **"Add link"**.

**Open** applies `LinkLaunchPolicy` **again, at launch**: a row whose stored URI now classifies
`Blocked` is **shown and refused**, never launched — the sentence is the ratified "ServiceTag will
not save that kind of link.", since it is the same fact. §10 ratifies that string under "Blocked
scheme" and says nothing about launch, so **master plan §18.14 flags the wording to the owner**;
ship the ratified string unless the owner rules otherwise, and never paraphrase it. A
`LinkDecision.Unknown` at launch **takes no second confirmation** (spec §4.2) and is launched.

**A missing handler** surfaces the ratified **"No app can open this link"** with **no URI in it**,
through the **`SnackbarHostState` the section already takes** — not a `Toast`. That is a design
decision with a test behind it: a Compose semantics tree cannot read a `Toast`, so a `Toast` would
make the ratified string unassertable in `androidTest`, and the missing-handler path is the one this
feature introduces. `LinkLauncher`'s own toast is still corrected (§18.8) for the Settings caller,
but the References section's refusal is a snackbar. `ReferencesSection` therefore treats `onOpen`'s
`false` return as "no handler" and shows the snackbar itself.

**Edit** opens the sheet titled **"Edit reference"** with **"Name"** and **"Description"** only,
plus **"Save"** and **"Cancel"**. The URI is not editable and is not a field on the sheet (I-1).

**Remove** is a **plain confirm** — not the typed-REPLACE dialog — headed **"Remove this
reference?"** with the body **"The link is removed from this asset. Nothing in the other app is
changed."** and the buttons **"Remove"** / **"Cancel"**, then a **hard delete**. One metadata row
with nothing to orphan; note in a code comment that merge's insert-never-delete rule means
re-importing an older archive re-inserts it, as it does for events (D-9).

**"Add link"** calls **the same `AddReference` a share does** and writes an identical row — there is
no `provenance` and the two are indistinguishable afterwards, deliberately (D-21 C). An unknown
scheme raises the ratified confirmation **"Save this link?"** / "ServiceTag does not recognise
\"\<scheme\>\" links. It will be saved as written and opened with whatever app claims it." / "Save"
/ "Cancel", and only a Save re-runs the use case with `confirmedUnknownScheme = true`.

**The Add-link sheet's strings are RATIFIED** (owner, 2026-09-23; spec Amendments `spec:579`, master
plan §17 and §18.4): the sheet title is **"Add link"** — the action label, reused — the URI field's
label is **"Link"**, and the rest are the already-ratified **"Name"**, **"Description"**, **"Save"**
and **"Cancel"**. Those five are the whole of the sheet; **no sixth string is invented**, and the
earlier blocker on this brief is closed.

~~Superseded 2026-09-23: this brief previously carried a blocking callout saying §10 ratified no
label for the Add-link sheet's URI field and that the sheet was not to be written until the owner
ruled. The owner ruled (spec Amendments `spec:579`); the callout is withdrawn, not hidden, and the
ratified strings are above.~~

**D-19, the Documents row.** `DocumentRow`'s `Column` holds exactly one `QuietLine` today
(`DocumentsSection.kt:208`), mutually exclusive: `QuietLine(if (row.present) row.quietLine() else "Not on this device")`. The new line is a **second** `QuietLine`, below it, with two rules: it is
drawn **only when `notes` is non-empty and `present` is true** — a row whose whole message is that
the bytes are gone must not also carry prose — and it is `maxLines = 1` with `Ellipsis`, because the
description is capped at 2,000 characters and this is a compact row. **No new string**: the line is
the owner's own text.

## Invariants this brief must hold

**I-1** (the edit sheet has no URI field; an edit changes only name, description and `updated_at`);
**I-3** (a reference row never reaches `AttachmentStore`, has no thumbnail, no presence scan and no
"Not on this device" state); **I-5** (no tombstone is read or drawn); **I-6** (there is no
move-between- assets affordance anywhere in the section); **I-2** and **I-7** are inherited from B02
— the section maps `ReferenceProblem` to a §10 sentence and never re-implements a refusal.

## Test matrix

One test per hazard class. Each must fail without the change it names. View-model cases are JVM; the
section and row cases are `androidTest`.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| the section is invisible when empty | with no references the header reads "References" and the single line "No references yet" is drawn | an empty section that draws nothing leaves the owner with no way to reach "Add link" |
| the count drifts | with three references the header reads "References · 3" | a hardcoded header hides the count the shipped Documents header carries |
| a kind label is invented | one case per kind: `WEB_URL` → "Web link", `NOTE_LINK` → "Note", `OTHER` → "Other" | a `kind.name` rendered raw shows `WEB_URL` to a person |
| the description is invisible | a row with a non-empty description draws it as a second quiet line; a row with an empty one draws only the kind label | a row that never renders `description` repeats the share path's own failure |
| a stored URI that is now illegal is launched | a row whose URI classifies `Blocked` at launch is drawn, and Open shows "ServiceTag will not save that kind of link." **without calling `onOpen`** | a policy applied only at save lets a URI that became illegal be fired at `ACTION_VIEW` |
| an unknown scheme asks twice | a stored `zotero://` row launches on Open with **no** confirmation | a second confirmation at launch contradicts spec §4.2 |
| a missing handler crashes or leaks the URI | **two assertions, each on a mechanism that can actually be read.** `androidTest`: with a fake `onOpen` returning `false`, the section shows a **snackbar** whose text is exactly "No app can open this link" and which **does not contain the row's URI** — readable from the semantics tree, which a `Toast` is not. JVM: `LinkLauncher`'s refusal constant equals that same string, and carries no format placeholder | the shipped `LinkLauncher` toast names the URI, against §4.4 (§18.8); and a refusal routed through a `Toast` would be unassertable, so the hazard would have a row and no real test |
| the URI becomes editable | the edit sheet has exactly two fields, "Name" and "Description"; after a save the stored `uri`, `kind`, `scheme` and `created_at` are unchanged (I-1) | a URI field on the sheet makes I-1 unenforceable |
| remove asks the wrong question | Remove draws the plain confirm with the ratified heading, body and buttons — **not** the typed-REPLACE dialog — and Cancel leaves the row | a typed confirmation for one metadata row is disproportionate; no confirmation is destructive |
| remove leaves a row | confirming removes exactly that row and leaves every other reference and every attachment on the asset | a delete keyed on the wrong id removes a sibling |
| "Add link" writes a different row | a reference added in-app and a reference added by a share, with the same URI, name and description on different assets, are **field-for-field identical** but for `id`, `assetId` and the timestamps — asserted at the view-model layer against the use case's output | a second creation path that sets a field differently is exactly what D-21 C forbids |
| the confirmation is skipped in-app | "Add link" with `zotero://select/items/0` shows "Save this link?" and writes nothing until Save; then the row exists with `kind = OTHER` | a screen passing `confirmedUnknownScheme = true` unconditionally removes the confirmation |
| a blocked scheme is added in-app | "Add link" with `javascript:alert(1)` shows "ServiceTag will not save that kind of link." and writes nothing | a UI that only filters at launch stores a URI I-2 forbids |
| a duplicate is added in-app | "Add link" with a URI already on the asset shows "That link is already on this asset" and writes nothing | mapping `DuplicateUri` to a generic failure hides a recoverable state |
| a blank name is savable | with the name cleared, Save on the edit sheet shows "Give the reference a name" and writes nothing | an unguarded save writes a row the list cannot label |
| an archived asset loses its references | a reference on an archived asset still lists and still opens | a lifecycle filter applied to the section hides rows the owner put there |
| a reference grows bytes | the section draws no thumbnail, runs no presence scan and never shows "Not on this device"; `ReferenceRowState` carries no locator, size or sha256 (I-3) | copying `DocumentsSection`'s row wholesale drags the byte machinery onto a pointer |
| **the D-19 line survives a missing file** | an attachment row with a non-empty `notes` and `present = false` draws **"Not on this device"** and **no description line** | a line drawn unconditionally puts prose under a row whose whole message is that the bytes are gone |
| the D-19 line wraps the row | a 2,000-character description renders on **one** line, ellipsised, and the row's height equals a row with a short description | a wrapping line turns a compact list into a wall of text |
| the D-19 line replaces the existing one | a row with a non-empty `notes` and `present = true` draws **both** the shipped `kind · size · captured-on` line **and** the description line | replacing rather than adding loses the shipped quiet line |
| the D-19 line appears without a description | a row whose `notes` is empty draws exactly one quiet line | an unguarded second `QuietLine` draws a blank line on every row |
| the section lands in the wrong place | `ReferencesSection` is invoked **below** `AttachmentsSection` and above `NotesSection` in `AssetDetailScreen` | a section above Documents contradicts D-10's ordering |
| Documents changes shape | `DocumentsSection`'s header, empty state, action buttons, storage block and `say(problem)` strings are byte-identical to `$BASE` but for the row's new line — asserted by the reviewer against the diff, and by the shipped `DocumentsSection` tests still passing unchanged | an incidental edit to a shipped string is a release blocker |

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` — green, with `ReferencesSectionViewModelTest`
  counted and every shipped `ui/attachments` test still green **unchanged**.
- Connected, **holding `emulator-5554` with `ANDROID_SERIAL` pinned**: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.loosecannon.servicetag.ui` → green; then
  the whole suite once before hand-back.
- `grep -c 'No app can open this link' app/src/main/kotlin/com/loosecannon/servicetag/links/LinkLauncher.kt` → **1**; `grep -c '\$uri' app/src/main/kotlin/com/loosecannon/servicetag/links/LinkLauncher.kt` → **0**.
- `git diff --stat $BASE..HEAD -- app/src/main/AndroidManifest.xml app/src/main/kotlin/com/loosecannon/servicetag/share app/src/main/kotlin/com/loosecannon/servicetag/api` → empty.
- `grep -rn 'Toast' app/src/main/kotlin/com/loosecannon/servicetag/ui/references` → no output (the
  section's refusals are snackbars, which the semantics tree can read).
- Report the `:app` unit and connected deltas, and whether §18.14's launch-wording flag drew a
  ruling.

## This brief must NOT

Invent a string. The Add-link sheet ships **exactly** "Add link", "Link", "Name", "Description",
"Save", "Cancel" (§17) — a sixth is a **finding for the controller**, not a brief's to fill. Touch
the share activity, the manifest, `ManifestContractTest`, any route, any DTO or any MCP tool. Add a
member to `AttachmentProblem` or change a single character of `say(problem)`'s sentences. Give a
reference a thumbnail, a presence scan, a locator, a size or an `AttachmentKind`. Offer a
move-between-assets or a change-URI affordance. Draw the References section above Documents, or fold
the two into one. Use the typed-REPLACE dialog for Remove. Read or draw a tombstone. Change
`app/build.gradle.kts`, `docs/versioning.md`, `docs/api/v1.md` or `README.md`.
