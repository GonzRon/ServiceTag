# B11 — #49 per-tag placement labels

**Read first:** the master plan's §1 and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.7; issue snapshot `issue-49.md`.

## Purpose

Make the already-supported "several NFC tags per Asset" model usable for equipment whose scan points are in different physical places — a multi-zone system with a tag at each indoor head — **with no schema change at all**. `nfc_tag.label` already exists (`core/.../core/model/TagBinding.kt:19`), `nfc_tag.asset_id` is already non-unique (`app/.../data/room/entities/NfcTagEntity.kt:25-29` declares the unique index on `(payload_format, payload_key)` only), and the label already round-trips through backup and the merge. #49 is therefore a **UI-and-wording** issue: reuse the existing label as the human-readable **"Tag placement"** value rather than adding a second structured column. Independent of every other brief, which is why it runs in its own worktree in wave 1.

## Files

**Create**

- Tests: `app/src/test/kotlin/.../ui/scan/TagPlacementTest.kt`; connected `app/src/androidTest/kotlin/.../ui/asset/AssetTagsSectionTest.kt`.

**Modify**

- `app/.../ui/scan/WriteTagScreen.kt` and `app/.../ui/scan/TagWriteController.kt` — binding and provisioning accept an **optional** placement value, captioned **"Tag placement"**. `Route.WriteTag` already carries a `label` parameter (`app/.../ui/nav/Route.kt:46`), so the route needs no change.
- `app/.../ui/asset/AssetDetailScreen.kt` and `app/.../ui/asset/AssetViewModels.kt` — the asset's **tags section lists every bound tag** with its placement label and its status, not only the first (#49 AC 6). The repository already offers `TagRepository.forAsset` and `observeForAsset` (`core/.../core/ports/Repositories.kt:42`, `:46`), so no port changes.
- `app/.../ui/scan/TagResultSheet.kt` and `app/.../ui/scan/ScanViewModels.kt` — the scan result **surfaces the placement label when present** (#49 AC 3).
- One editing affordance for an **already-bound** tag's label. `Plan decision:` it lives in the asset detail's tags section — an inline edit on the row — rather than as a new screen. Spec §2.7 requires the value be editable "without rewriting the NFC payload" but does not say where; the tags list is where the owner is already looking at the two tags they are trying to tell apart, and a new screen for one text field would be the larger change.

**Untouched — the whole point of this brief:** `core/**` in its entirety (no model, no port, no use case, no DTO, no merge change); `app/.../data/**` (**no schema change, no migration, no entity change**); `app/.../api/**` (#49 explicitly does not widen the automation contract — a later follow-up may expose label editing); `tools/`; `libs/`; `reminders/**`; `ui/maintenance/**`.

## Interfaces

**Consumes:** nothing from another 1.2 brief. It uses only shipped types — `TagBinding`, `TagStatus`, `TagRepository`, `BindTag`, `ProvisionTag`, `Resolution`.

**Produces, for B09:** the placement label on the scan result, which B09's sheet shows as the scanned-tag context. `Plan decision:` B09 reads the label off the `TagBinding` its `Resolution.OpenAsset` already carries (`core/.../core/usecase/ResolveTag.kt:17`), so this brief exposes **no new interface** and the two briefs have no ordering dependency. If B11 merges after B09, B09's context line is simply already correct.

**The behaviour, as contract** (spec §2.7, #49's "Required behavior"):

| rule | statement |
|---|---|
| no schema change | the existing `nfc_tag.label` is the placement value. **No second structured column**, no `label` + `location` pair with unclear ownership (#49 "Persistence / backup / merge") |
| binding and provisioning | each accepts an **optional** placement; a **blank** label stays valid for ordinary one-tag equipment |
| editing | editing the label of a bound tag **rewrites no NFC payload** and **changes no binding** (#49 AC 4, invariant 59) |
| asset detail | **all** tags are listed, with label and status — the screen must no longer imply the first tag is the only one (#49 AC 6) |
| the scan result | surfaces the label when present, and says nothing extra when it is blank (#49 AC 3) |
| lifecycle | lost, retired and rebound tags **retain** their historical label |
| identity | two tags with different labels may target the same Asset; a second tag **never** implies a child Asset and **never** a duplicate schedule (#50 "Multiple tags") |
| backup and merge | the label survives export, restore and merge **because it already does** — this brief proves it rather than implementing it (#49 AC 5) |

**The four surfaces, and what each changes** — every one reuses a shipped symbol, which is the evidence that no schema change is needed:

| surface | change | the shipped symbol it reuses |
|---|---|---|
| binding / provisioning (`WriteTagScreen`, `TagWriteController`) | an optional placement field, captioned **"Tag placement"**, carried into the bind or provision call | `Route.WriteTag(targetKind, targetId, label)` already has the parameter (`app/.../ui/nav/Route.kt:46`); `TagBinding.label` already has the column (`core/.../core/model/TagBinding.kt:19`) |
| asset detail's tags section | **all** bound tags listed with label and status, and an inline label edit per row | `TagRepository.forAsset` / `observeForAsset` (`core/.../core/ports/Repositories.kt:42`, `:46`) |
| the scan result (`TagResultSheet`, `ScanViewModels`) | the scanned tag's label shown when present | `Resolution.OpenAsset(tag, asset)` already carries the tag (`core/.../core/usecase/ResolveTag.kt:17`) |
| the label edit's write | `label` and `updated_at` only | `TagRepository.upsert`, called with the row read back and one field changed — **not** through `ProvisionTag` or `BindTag`, neither of which this brief touches |

## Invariants this brief must hold

**57, 59** (master plan §13), and it must leave **5** untouched. Invariant 5 is the **group-identity discipline**, not a group rule this brief implements: this brief adds tag-binding affordances, and the fact it must not break is that **a tag still resolves only to an Asset** — no affordance here may offer a group as a binding target, and `TagTarget` (`core/.../core/model/TagBinding.kt:7-11`) has no group case for it to reach.

## Test matrix

One test per hazard class.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| two tags, one asset | two **active** tags bound to one Asset with **different** labels coexist, and scanning **either** reaches the **same** Asset and the same canonical maintenance and history state (#49 AC 1, AC 2) | a unique index or a lookup keyed on `asset_id` would make the second binding impossible — the test confirms the shipped schema really does allow it |
| the scan point unidentifiable | the scan result shows the scanned tag's label when present, and shows no placeholder or empty caption when it is blank (#49 AC 3) | without it the owner cannot tell which of two identical-looking scan points they just used |
| **editing rewriting the tag** | editing a bound tag's label changes **no NFC payload** — `payload_format` and `payload_key` byte-identical — and **no binding** — `asset_id`, `link_id`, `status` and `physical_uid` byte-identical; only `label` and `updated_at` move (invariant 59, #49 AC 4) | an edit routed through the provisioning path re-encodes the tag, which needs the physical tag present and can brick a mounted sticker |
| only the first tag shown | asset detail lists **every** bound tag with its label and status, proved with three tags including one `LOST` and one `RETIRED` (#49 AC 6) | the shipped screen implies the first tag is the only one, so the second scan point is invisible |
| history lost on retirement | a `LOST`, a `RETIRED` and a **rebound** tag each **retain** their historical label | clearing the label on a lifecycle change destroys the record of where the sticker was |
| a blank label rejected | binding with **no** placement succeeds and the row's label is blank/null, and the detail row renders without an empty caption | making the field required breaks every ordinary one-tag asset |
| **the label lost in transit** | one composed test: a tag with a label exports, restores and **merges** with the label preserved byte-identically, and a re-import of the same archive plans **`IDENTICAL`** (#49 AC 5) | this is the test that proves the no-schema-change claim: if the label did not already round-trip, #49 would need a migration after all |
| a scan mutating | the edit affordance is the **only** write; a scan, a dispatch and `servicetag://tag/<id>` still write nothing but the shipped informational `lastScannedAt` (invariant 57) | an "update the label from the tag" convenience turns a read into a write |
| a second tag becoming a second asset | a structural and behavioural assertion: binding a second tag to one Asset creates **no** child Asset row and **no** second schedule (#49 "Do not overload Asset hierarchy", #50 "Multiple tags") | the "one tag per asset" mental model leads to a child asset per scan point, which duplicates every schedule |
| the automation contract widened | a structural assertion: `git diff` over `app/src/main/kotlin/com/loosecannon/servicetag/api`, `tools/` and `docs/api/v1.md` is **empty** (#49 "Automation") | exposing label editing here widens the released 1.1.0 contract, which #49 forbids |
| a schema change smuggled in | a structural assertion: `git diff` over `app/src/main/kotlin/com/loosecannon/servicetag/data`, `app/schemas/` and `core/src/main` is **empty** | a "structured placement field" is exactly the redundant second column #49 rules out unless design proves it necessary — and if an implementer believes it is, that is a **finding for the controller**, not a migration |

**Connected set (`emulator-5554` only, `ANDROID_SERIAL` pinned, never a phone):** one class over the asset detail's tags section — three seeded tags listed with labels and statuses, and an inline label edit that leaves the payload and the binding unchanged. The scan-result rows are unit tests with an injected resolution (the emulator has no NFC).

## Strings

**Ratified, verbatim** (master plan §17): **"Tag placement"** — the field's caption at binding, on the asset detail row, and on the scan result.
**This brief drafts nothing.** If the tags section needs a heading or an empty-state line beyond what the screen already carries, reuse the shipped wording; a new sentence is a finding for the controller.

## Ordering

**Nothing precedes it and nothing waits on it.** It runs in **lane B of wave 1**, in **its own git worktree** from the current `master` tip, and merges `--no-ff` as soon as it is green — before, during or after B01, because their file sets do not intersect. **It is the one brief with no dependency in either direction.**

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected on **`emulator-5554`**: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.asset.AssetTagsSectionTest --console=plain`.
- Structural, anchored — these are the brief's headline claims and the review must run all four:
  - `git diff --stat master -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/data app/schemas` → **empty**.
  - `git diff --stat master -- app/src/main/kotlin/com/loosecannon/servicetag/api tools docs/api` → **empty**.
  - `grep -n 'Index(' -A 2 app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/NfcTagEntity.kt` → unchanged: the unique index on `(payload_format, payload_key)` only.
  - `grep -rn 'ProvisionTag\|NdefCodec\|writeNdef\|TagWriteController' app/src/main/kotlin/com/loosecannon/servicetag/ui/asset` → no match (the label edit does not reach the writing path).

## Estimated size

Small. Four screen changes, no new production file, two test classes. If it grows past that, the growth is almost certainly a structured placement column, and that is a finding rather than work.
