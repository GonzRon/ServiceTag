# P4 — 1.4.1 / versionCode 17: the version, its test, `versioning.md`, the runbook, two design notes

**Read first:** plan.md §2 (the reconciliation), §5, §7, §8; `rulings.md` R6.
**Lane:** alone, wave 3, branched from `<base>` = the master commit that merged the last of P1–P3.
**Blocked on:** nothing. Under the review budget the test edit is mechanical (well under 50 lines), so the controller may author this brief directly and close it by inspection plus the gate; otherwise one implementer.

## Files

**Modify**
- `app/build.gradle.kts:52-53`: `versionCode = 17`, `versionName = "1.4.1"`; the comment block `:45-51` only where it names the current tag.
- `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt`: `theReleaseIdentityIs140AndCode16` (`:52-56`) renamed and moved to `"1.4.1"`, `17`, `servicetag-v1.4.1`; `statusEchoesTheVersionsTheBuildCarries` (`:133`) to `"1.4.1"`; a new `versioningRecords141` beside `versioningRecordsThisRelease` (`:207-247`): exactly one `^\| 1\.4\.1 \| 17 \|` row, it names schema **8** and format **8**, contains no `PLACEHOLDER`, and no other row claims 17; the 1.4.0 row case kept.
- `docs/versioning.md`: one row after 1.4.0 — `| 1.4.1 | 17 | PATCH: …` naming #80 (the `/v1` create default, the two repair routes, the MCP tool, the loader; Reminder Health's finding and action) and #81 (the editor keeps a QUICK schedule's `profileId`; the picker under One tap; `None`); Room schema **8** and backup format **8** unchanged; `/v1` extended compatibly (one create default, two routes, no code changed); the MCP at 56 tools; the unit-gate, MCP, bundle and schedules counts as B15 left the pattern — the controller replaces them with the release tip's R1–R3 counts before tagging. The "Reserved next" table unchanged.
- `docs/release-proofs.md`, "R7 in full" (`:74-80`): one paragraph after the schema-and-format one — **when the production phone runs an older release than the development phone**, the direct path from its installed release is proven on `emulator-5554` before the production install: the older signed release asset is downloaded and its checksum verified against that release's evidence; the debug app is uninstalled; the older release is installed and the owner's data loaded with the MCP and loader **of that release's tag** (a whole-tree worktree, since the loader imports the MCP in process and newer tools refuse an older schema); the candidate APK (`tools/release-dry-run.sh`, which needs the local signing material) is installed over it in place; the release's own data proofs run with the current tools; the emulator's debug install is restored afterwards. The R1–R7 table itself unchanged.
- `docs/design/03-target-architecture.md:259` and `docs/design/issues/new-reminder-health.md:29`: one line each, a 1.4.1 supersession note — the providerless shape now carries `Fix reminder delivery` (the canonical repair), and "open the editor" remains only for the residual `SCHEDULE_PROVIDER_DISABLED` shape.
- `README.md`: since the owner's rewrite (`966aa91`, 2026-09-25) it names no current release and has no Releases section; touch it only if a line names the current release (none does today); add no version sentence and no release notes to it. `docs/versioning.md` is the release record.

**Untouched:** every source file under `app/src/main`, `core`, `tools`; `tools/servicetag-mcp/pyproject.toml` (stays `1.2.0`, plan.md §7); the 1.4.0 rows and evidence; the workflows; the R1–R7 table.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain`: zero failures, zero skips (`VersionAgreementTest` and `ReleaseProofPolicyTest` among them — exactly one R4 row remains).
- `git grep -nE 'versionCode = 17|versionName = "1\.4\.1"' app/build.gradle.kts` → 2; `git grep -nE '^\| 1\.4\.1 \| 17 \|' docs/versioning.md` → 1; `git grep -nE '1\.4\.1' docs/design/03-target-architecture.md docs/design/issues/new-reminder-health.md` → 2.
- `git diff <base> --stat -- app core tools .github` names exactly `app/build.gradle.kts` and `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt`, nothing under `app/src/main`, `core`, `tools` or `.github`.
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Must NOT

Touch production code; bump the MCP; edit a 1.4.0 row; change the R1–R7 table; create a tag.

## Size

Small, mechanical.
