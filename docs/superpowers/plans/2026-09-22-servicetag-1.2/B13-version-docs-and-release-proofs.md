# B13 — version, documents and the release proofs

**Read first:** the master plan's §1, §15 (the release gate), §16 (the controller proofs) and §17 (the ratification table).
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §1.1, §3.5, §2.1 and §2.2 (the two D5 corrections); rulings D-1, D-2, D-27; `docs/versioning.md`.

## Purpose

Make the release say what it is, and make the design documents stop saying what is no longer true. Four things: the version numbers and the schema and format numbers agreeing everywhere; the `docs/versioning.md` edits D-1 and D-2 require; the **two corrections to D5** that the spec's engine rules supersede — its `prevDue` reconstruction and its recurrence-edit example — plus the design-document updates the navigation and channel rulings imply; and the release-level proof commands, written as the anchored, runnable list the controller executes. It goes last because it asserts agreement across everything the other fourteen briefs changed.

## Files

**Modify**

- `app/build.gradle.kts` — `versionName = "1.2.0"`, `versionCode = 13` (D-1). `minSdk` 26 and `targetSdk` 36 are **unchanged**; `targetSdk 37` plus `DISPATCH_NFC_MESSAGE` is Phase 7 (spec §5.8).
- `docs/versioning.md` — three edits: the forward-only clarification of spec §3.5, quoted below; **the `1.1.1` / code 13 reservation at `docs/versioning.md:33` struck**; a `1.2.0` / **13** row added to the supported release history describing the release in the register of the two rows above it.
- `docs/design/05-scheduling-semantics.md` — **two corrections**, each marked as superseded-by and dated, not silently rewritten:
  - **§5** (`:135-137`): the `prevDue` reconstruction ("the largest series date `<= last_completed_on`") is corrected to read `prevDue` from the terminating row's `occurrence_on`, with the D5 §10.1-derived counter-example recorded and the NULL fallback documented as **approximate for an early completion** (spec §2.2, invariant 70).
  - **§10.5** (`:346`): "edited to monthly with a new anchor Oct 1: due = first series date ≥ T" is **superseded** by D-27 and the edit-date floor (spec §2.1, T3).
- `docs/design/03-target-architecture.md` — the local provider's constraints table and §7.3's findings noted as shipped in 1.2 where they are, and as Phase 5 where they are not.
- `docs/design/04-domain-data-model.md` — §15's stale version plan (schedules at v3, attachments at v4) corrected to the shipped reality: attachments at v5, **schedules at v6** (spec §3.1).
- `docs/design/12-visual-design-apollo-service-binder.md` — one note that primary navigation is now **Dashboard · Assets · Maintenance**, consistent with §16's removal of the Scan tab.
- `README.md` — one sentence under the existing feature summary naming maintenance schedules, groups and reminders, with a link to the spec.
- `docs/superpowers/plans/2026-09-22-servicetag-1.2/master-plan.md` — **nothing**. The plan is the record of what was planned; a correction goes in the ledger.

**Untouched:** all of `app/src/main`, `core/src/main`, `tools/`, `libs/`, `app/schemas/`. **This brief changes no behaviour.** The one code-adjacent edit is the version block, and the release workflow refuses to publish when the APK's `versionName` differs from the tag, which is what makes that edit load-bearing.

## Interfaces

**Consumes:** every other brief's result. **Produces:** the proof list of master plan §16, as commands; nothing in code.

**The versioning clarification, verbatim** — spec §3.5's sentence, to be added to `docs/versioning.md` as written:

> A **forward-only** backup-format bump — where the new app reads every older archive and an older app safely refuses a newer one rather than dropping rows — is a **MINOR**. A change that makes the app unable to read data it previously could is a **MAJOR**.

**The classification, stated so the review can check it:** 1.2.0 is a **MINOR** because it adds a new user-facing capability (`docs/versioning.md:10-11`, "a schedules and reminders subsystem" is the document's own example) and because format 6 is forward-only under the clarification above — 1.2 reads formats 1–6, and 1.1.x refuses a format-6 archive loudly with `BackupNewerFormat` rather than dropping rows (invariants 62, 63). `versionCode` is **+1 to 13**, never reset (`docs/versioning.md:19`).

**What each document edit must contain** — the substance, not the sentences, so the review can check it landed:

| file | edit | must say |
|---|---|---|
| `docs/versioning.md` | the clarification | the forward-only paragraph above, verbatim |
| `docs/versioning.md:33` | strike | the `1.1.1` / code 13 reservation row removed entirely, not amended |
| `docs/versioning.md` history table | one new row | `1.2.0` / **13**, naming maintenance schedules, maintenance groups, local reminders with quick actions, the scan completion sheet, reminder health, per-tag placement labels; **schema 6, format 6**; and the contract documents (`docs/api/v1.md`, the spec path) |
| `docs/design/05-scheduling-semantics.md` §5 | correction | `prevDue` is the terminating row's `occurrence_on`; the old reconstruction is wrong for an **early** completion, with the D5 §10.1 counter-example (anchor Jan 1, quarterly, due Apr 1, completed Mar 20 → **Jul 1**, not Apr 1); and the NULL-`occurrence_on` fallback documented as **approximate** |
| `docs/design/05-scheduling-semantics.md:346` | superseded | the line is marked superseded by D-27 and the **edit-date floor**, dated, not deleted |
| `docs/design/04-domain-data-model.md` §15 | correction | attachments shipped at **v5**, schedules land at **v6** |
| `docs/design/03-target-architecture.md` §7.2–§7.3 | annotation | which findings ship in 1.2 (the seven local ones) and which are **Phase 5** (the six Todoist ones) |
| `docs/design/12-visual-design-apollo-service-binder.md` | note | primary navigation is **Dashboard · Assets · Maintenance**, consistent with §16's removal of the Scan tab |
| `README.md` | one sentence | the capability and a link to the spec; **no invented feature name** |

## Invariants this brief must hold

None of the eighty directly — it changes no behaviour. It **asserts** that the numbers behind invariants **62** and **63** agree across four files, and that invariants **25** and **70** are no longer contradicted by a design document.

## Test matrix

This brief's "tests" are assertions over the tree, and they belong in the unit gate where they can fail in CI rather than in a review's eyes.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| **the four numbers disagreeing** | one test asserting `BuildConfig.VERSION_NAME == "1.2.0"`, `versionCode == 13`, `AppGraph.SCHEMA_VERSION == 6`, `AppDatabase`'s `version == 6`, `BackupCodec.FORMAT_VERSION == 6`, and that `/v1/status` echoes the last two (`app/.../api/ApiHandlers.kt:104-119`) | a schema bumped in one place and not the other makes an import refuse an archive it could read, or accept one it cannot |
| the tag disagreeing with the APK | the release workflow's own check (`versionName` must equal the tag) is exercised by the controller's badging proof, and the review confirms the tag name `servicetag-v1.2.0` matches `versionName` | the workflow refuses to publish, which is the right failure but a late one |
| **the struck reservation surviving** | a structural assertion: `docs/versioning.md` contains **no** `1.1.1` row, **does** contain a `1.2.0` / `13` row, and **does** contain the forward-only clarification sentence | leaving the reservation means two rows claim code 13 and the next release has no number |
| the D5 corrections not landing | a structural assertion: `docs/design/05-scheduling-semantics.md` no longer asserts the old reconstruction as the rule, and its §10.5 line is marked superseded | a design document that contradicts the engine is what produced the B2 blocking finding in the first place; leaving it invites the same bug back |
| the stale version plan | `docs/design/04-domain-data-model.md` §15 names **v6** for schedules | the stale plan is what almost put schedules at v3 |
| a document claiming two tabs | `docs/design/12-visual-design-apollo-service-binder.md` and `README.md` describe three primary destinations | a released document describing the wrong navigation is a support cost |

**No connected run and no device step in this brief.** The device work is the controller's release gate (master plan §15.8).

## The release-level proof commands

This brief's deliverable beyond the edits: the list below, handed to the controller as the runnable form of master plan §16. Every grep is **anchored**, so a comment that names one can never match it.

1. `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest --rerun-tasks --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips. Counts compared against the controller's recorded baseline at `d790506` plus the per-brief counts.
2. `./gradlew :app:connectedDebugAndroidTest --console=plain` on **`emulator-5554`** with `ANDROID_SERIAL` pinned → zero failures, zero skips.
3. `cd tools/servicetag-mcp && uv run --frozen pytest` and `cd tools/servicetag-bundle && uv run --frozen pytest` → both green.
4. `bash tools/check-submodule-pin.sh` → the gitlink at its pinned tag.
5. `./gradlew :app:assembleDebug` then badging on the built APK → `versionName 1.2.0`, `versionCode 13`.
6. The version-agreement greps: `grep -n 'versionName' app/build.gradle.kts`; `grep -n 'SCHEMA_VERSION' app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt`; `grep -n 'version = 6' app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt`; `grep -n 'const val FORMAT_VERSION' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt` — one occurrence each, all agreeing.
7. The document greps: `grep -c '| 1.1.1' docs/versioning.md` → 0; `grep -c '1.2.0' docs/versioning.md` → ≥ 1; `grep -c 'forward-only' docs/versioning.md` → 1; `grep -ci 'twenty-one' tools/servicetag-mcp/README.md` → 0; `grep -c 'seven tables' docs/api/v1.md` → 0.
8. The full structural list of master plan §16.4, each with its expected count.
9. Hygiene over the whole range: no private nouns, no e-mail address, no `/home/<user>` path, no device serial, no pairing code, no real `backupSetId`; single-subject commits; one author identity; no attribution trailer.
10. `git diff --stat <base> HEAD -- libs tools/servicetag-bundle` → empty.

## Strings

**None.** Every string in this brief is a document sentence, not a user-visible one. The `README.md` sentence and the `docs/versioning.md` row are developer- and owner-facing prose and need no ratification; they must nonetheless carry **no unratified product wording** — describe the capability, do not invent a feature name.

## Ordering

**Last.** It asserts agreement across every other brief, so it runs only after all fourteen are merged and reviewed. Single lane, wave 8. **Its own execution gates the release**, and the owner's environment approval gates the publish.

## Review gate

- Unit: `./gradlew :core:test :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; the review confirms the version-agreement test class exists and fails when a number is changed in one place only (the reviewer flips one and re-runs).
- Structural, anchored: items 6 and 7 of the proof list above.
- `git diff --stat master -- app/src/main core/src/main tools libs app/schemas` → **empty**; `app/build.gradle.kts` is the brief's one code-adjacent edit and sits outside that path list, so the review states both facts explicitly. **The seven `docs/` files and `README.md` this brief edits are also outside this diff's scope** and are covered by item 7's document greps instead — "empty" here does not mean "nothing else changed anywhere".

## Estimated size

Small. One version block, three document files with substantive edits, three with a note each, one test class, one proof list. No split expected.
