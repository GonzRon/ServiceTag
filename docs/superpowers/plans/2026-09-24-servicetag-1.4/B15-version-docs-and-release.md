# B15 — version, documents and the release proofs

**Read first:** master plan §1, §17 (release acceptance and the controller proofs), §18 (the issue map), §19.
**Spec:** §15 (seams and version; D-27), §12.3 (the release proof), Appendix A ("Docs"); `docs/versioning.md`, `docs/release-proofs.md`.
**Wave 9, single lane,** last. **After every other brief is merged and reviewed.**

## Goal

Make the release say what it is and make the documents stop saying what is no longer true. `versionName` **1.4.0**, `versionCode` **16**, and every version number agreeing — in the build, the database, the graph constant, the codec and `/v1/status` — proved by a JVM test that fails when one moves alone. `docs/versioning.md` records 1.4.0 as a MINOR by its own forward-only rule. D5's season sections stop describing a model 1.4 retired (spec §11: "nothing stored at season end" and "MANUAL_STARTUP deferred"), marked superseded rather than rewritten; D12 gains the condition and health treatment O-2 approved; D4 names schema 8. The standing release runbook gains the one generic lesson this release teaches — how a schema or format bump proves the development phone's data survived **with no export and no owner action** (controller ruling, Concern 2) — and this brief hands the controller the runnable proof list of master §17. **This brief changes no behaviour.**

## Files

**Modify**

- `app/build.gradle.kts` — `versionName = "1.4.0"`, `versionCode = 16`. `minSdk` and `targetSdk` unchanged.
- `docs/versioning.md` — a `1.4.0` / **16** row in the supported history, in the register of the rows above it: operating seasons (calendar and manual), maintenance service policy and the maintenance break, operational condition and derived health; Room schema **8**, backup format **8** — the new app reads every older archive and 1.3.x refuses a format-8 one with `BackupNewerFormat`, which is why this is a MINOR; `/v1` extended compatibly with deprecated season inputs; the app, the MCP server and the schedules loader released together; contracts `docs/api/v1.md` and the 1.4 spec; the gate counts filled in by the controller at the release tip.
- `docs/design/05-scheduling-semantics.md` — §6 (`:193-229`): "Deferred (not MVP): `MANUAL_STARTUP`…" and "nothing is stored at 'season end'" each marked **superseded by the 1.4 spec** (§3.3 manual activation facts; §4 service policy), dated; §10.4 (the winter hot-tub example) annotated with the 1.4 answer — AT_START moves a raw date only when it is earlier than the season start plus its offset (O-5), so the example is DUE on the first in-season day, not OVERDUE (Finding A-1 fixed).
- `docs/design/12-visual-design-apollo-service-binder.md` — §5 gains the condition and health rows of spec §10.6 (families, words, icons, positions; DEGRADED's own token; DEFERRED season-inactive grey; colour as reinforcement), citing O-2.
- `docs/design/04-domain-data-model.md` — §15 gains a dated row `> | v8 | **1.4.0** | …` naming `asset_season_activation`, `asset_condition`, `health_subject` and the recreated `maintenance_schedule` and `schedule_state`; the reservation "will take **v8 upward** (amended 2026-09-23 …)" (`:612`) moves to **v9 upward**, amended 2026-09-24 because v8 is taken (M9).
- `docs/release-proofs.md` — R7 gains one generic line for a release that bumps the schema or the backup format: data survival is proven by **(i)** `/v1/status` counts and a per-table hash of every list route read through the loopback API before and after the install (the Developer API screen opened by one `adb shell am start`), each object hashed over the **previous release's key set**, with **added or normalised keys compared through the release's documented mapping** (for 1.4, a schedule's pre-install triple passes through `toLegacy(toPolicy(…))` before comparison; every other key verbatim) so neither an added key nor a normalised one false-fails, and the new tables' counts reported; **(ii)** the schedules loader's re-plan IDENTICAL; **(iii)** R2's `PreservedSetRestoreTest`. **No export, no owner action.** Nothing else in the runbook changes; R4 stays `ShareBoundaryTest` alone.
- `README.md` — one sentence under the feature summary naming seasons, maintenance timing around seasons and breaks, operational condition and derived health, with a link to the spec. No invented feature name.
- `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt` — the release identity 1.4.0 / 16; the D5, D4, versioning and README assertions extended.

**Untouched:** every `app/src/main` and `core/src/main` source; `app/schemas/`; `tools/`; `libs/`; `docs/api/` (B09); the plan and the spec (a correction is a ledger entry).

## Interfaces

**Consumes:** every other brief's merged result. **Produces:** the proof list below, and nothing in code.

**The classification, stated for the review:** a new user-facing capability (`docs/versioning.md`: "a new feature, workflow, integration or substantial capability") with a **forward-only** schema and format bump — 1.4 reads formats 1–8 and 1.3.x refuses format 8 loudly (inv. 63's shape) — and a `/v1` that keeps every 1.3 request's meaning on every row a 1.3 body can describe (spec §15). MINOR; `versionCode` +1 to 16, never reset.

### What each document edit must contain (so the review can check it landed)

| file | edit | must say |
|---|---|---|
| `docs/versioning.md` | one history row | `1.4.0` / **16**; the three capabilities in the spec's words; schema **8**, format **8**; the forward-only reason it is a MINOR; `/v1` compatible with deprecated season inputs; the lockstep tools; the contracts; the gate counts (this brief's run, replaced by the final tip's) |
| `docs/design/05-scheduling-semantics.md` §6 | two markers | "`MANUAL_STARTUP` … deferred" and "nothing is stored at 'season end'" each **superseded by the 1.4 spec**, dated 2026-09-24, naming §3.3 (activation facts) and §4 (service policy); the sentences themselves kept |
| `docs/design/05-scheduling-semantics.md` §10.4 | one annotation | the hot-tub example reads DUE on the first in-season day under IN_SERVICE_AT_START (O-5), not OVERDUE (archaeology Finding A-1) |
| `docs/design/12-visual-design-apollo-service-binder.md` §5 | rows added | OPERATIONAL, DEGRADED (own token), DOWN, not recorded, NOMINAL / WARNING / CRITICAL, NOT TRACKED, DEFERRED, IN SEASON with word, icon, family and position; colour as reinforcement (O-2) |
| `docs/design/04-domain-data-model.md` §15 | a row and a moved reservation | `v8` / **1.4.0** with its three tables and two recreated ones; what is unnumbered now takes **v9 upward** (amended 2026-09-24) |
| `docs/release-proofs.md` R7 | one line | survival after a schema or format bump by (i) counts and per-table hashes over the previous key set, (ii) the loader's re-plan IDENTICAL, (iii) `PreservedSetRestoreTest`; no export and no owner action |
| `README.md` | one sentence | the capability and a link to the 1.4 spec; no feature name |

### Ordering and hand-off

Last, single lane. It runs after the whole-branch review's scope is fixed, because it asserts agreement across every other brief. Its own green gate is the controller's cue to run items 1–10 below; the owner's approval of the protected release environment gates the publish, and the development-phone steps (items 6 and 7) are the controller's, never the implementer's.

## Invariants this brief must hold

None directly. It **asserts** that the numbers behind inv. 62–64 and 124 agree everywhere, and that no design document still states a rule 1.4 retired (spec 1.2 inv. 26; D5 §6). It **asserts inv. 129 at release** — master §17's anchored grep over the whole range is item 8 of the proof list; the accountable owner is B01, with B09 and B11 running the same grep in their gates.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the numbers disagree | `VersionAgreementTest` · `theReleaseIdentityIs140AndCode16` and `theSchemaAndTheFormatAreBothEight` (`BuildConfig`, `AppDatabase`, `AppGraph.SCHEMA_VERSION`, `BackupCodec.FORMAT_VERSION`, `/v1/status`) | bump `versionCode` alone |
| the history silent | `VersionAgreementTest` · `versioningRecordsThisRelease` (a `1.4.0` / `16` row; no reservation of 16) | leave the row out |
| a retired rule stands | `VersionAgreementTest` · `theSchedulingDocumentMarksItsSupersededRules` (the two §6 sentences and §10.4 marked by anchored regexes) | delete the marker |
| the data model stale | `VersionAgreementTest` · `theDataModelDocumentNamesV7` extended (its "v8 upward" regex becomes "v9 upward … 2026-09-24") and a new `theDataModelDocumentNamesV8` (`^> \| v8 \| \*\*1\.4\.0\*\* \|`) | leave the reservation at v8 |
| the README silent | `VersionAgreementTest` · `theReadmeNamesSeasonsConditionAndHealthAndLinksTheSpec` | omit the link |
| the runbook loses R4's cap | the shipped `ReleaseProofPolicyTest` stays green **unchanged** | add a class to R4 |

## The release-level proof commands (the runnable form of master §17)

1. R1 — `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` → zero failures, zero skips; counts against the controller's baseline plus each brief's recorded delta.
2. R2 — stage the preserved set (`docs/release-proofs.md`), then `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` → zero failures, zero skips.
3. R3 — the three Python suites (the runbook's one line); the MCP tool count **55**; the bundle suite unchanged.
4. R4, R5, R6 — as the runbook states; R5's parser line exits 0 (no new exported component).
5. **The format-7 import proof** (master §17.8) — `Format7RestoreContractTest` inside item 2's R2 run, over B01's golden archive.
6. R7 on the development phone (master §17.9): the in-place install, then **(i)** counts and per-table hashes over the 1.3 key set identical before and after (schedules compared after the pre-install triple passes through `toLegacy(toPolicy(…))`, other keys verbatim; **(ii)** stays the semantic check), the three new count keys at 0; **(ii)** is item 7; **(iii)** is `PreservedSetRestoreTest` inside item 2. No export and no owner action.
7. **Stage-B re-plan** — `servicetag-schedules plan <the owner's manifest> --code <code>` against the upgraded development phone → exit 0, every entry IDENTICAL.
8. Every anchored proof of master §17, each with its expected answer.
9. Hygiene over `<base>..HEAD` (R6) and `bash tools/check-submodule-pin.sh` → `submodule pin ok`.
10. `./gradlew :app:assembleRelease` (through the release workflow) → badging `1.4.0` / `16`; the permission set equal to 1.3.0's.

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; the reviewer flips one version number and sees `VersionAgreementTest` fail.
- Anchored: `grep -cE '^[[:space:]]*versionCode = 16$' app/build.gradle.kts` → 1; `grep -cE '^[[:space:]]*versionName = "1\.4\.0"$' app/build.gradle.kts` → 1; `grep -cE '^\| 1\.4\.0 \| 16 \|' docs/versioning.md` → 1; `git diff --stat <base> -- app/src/main core/src/main app/schemas tools libs docs/api` → empty (`app/build.gradle.kts` is outside that list and is the one code-adjacent edit).
- No connected run and no device step here; the device work is the controller's (items 5–7).

## Strings

**None.** Document sentences describe the capability in the spec's own terms and invent no product wording.

## Must NOT

- change any behaviour, source, schema or tool;
- rewrite a superseded design sentence rather than marking it;
- add a class to R4, or a UI-driving step anywhere in the runbook;
- edit the plan or the spec.

## Edge cases

- **The gate counts** in the versioning row are first written from this brief's own gate run; the controller replaces them with the final tip's R1, R2 and R3 numbers in a docs-only commit before tagging. `VersionAgreementTest` refuses a `PLACEHOLDER` marker in the row (the shipped check), so a half-written row cannot pass.
- **A fix round after this brief merges** that changes a count updates only the versioning row; no other document names a count.
- **The superseded D5 sentences** are kept verbatim under their markers, because the anchored regexes assert the marker, not the absence of the old words.
- **The README sentence** links the spec path, not a section anchor, so a later spec revision cannot break it.

## Review focus

- Flip each version number alone and watch `VersionAgreementTest` fail; restore and watch it pass.
- Read each document edit against the table above; nothing outside those seven files and the one test changes.
- The proof list is runnable as written: every command one physical line, every grep anchored.

## Size

Small: one version block, five documents, one test class, one proof list. No split.
