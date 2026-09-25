# P4 — 1.4.1 / versionCode 17: the version, the README, `versioning.md`, the runbook

**Read first:** plan.md §5, §7, §8; `rulings.md` R6.
**Lane:** alone, wave 3, branched from master after P1, P2 and P3 merged.
**Blocked on:** nothing. Mechanical; the controller may author it directly and close it by inspection plus the gate (review budget), in which case no implementer is dispatched.

## Files

**Modify**
- `app/build.gradle.kts:52-53`: `versionCode = 17`, `versionName = "1.4.1"`; the comment block `:45-51` only where it names the current tag.
- `README.md`: since the owner's rewrite (`966aa91`, 2026-09-25) the README names no current release and has no Releases section, and it says the roadmap of record is #76. Touch it only if a line names the current release (none does today); add no version sentence and no release notes to it. `docs/versioning.md` is the release record.
- `docs/versioning.md`: one row after 1.4.0 — `| 1.4.1 | 17 | PATCH: …` naming #80 (the `/v1` create default, the two repair routes, the MCP tool, the loader; Reminder Health's finding and action) and #81 (the editor keeps a QUICK schedule's `profileId`; the picker under One tap; `None`); Room schema **8** and backup format **8** unchanged; `/v1` extended compatibly (one create default, two routes, no code changed); the MCP at 56 tools; the unit-gate, MCP, bundle and schedules counts as B15 left the pattern — the controller replaces them with the release tip's R1–R3 counts before tagging. The "Reserved next" table unchanged.
- `docs/release-proofs.md`, "R7 in full" (`:74-80`): one paragraph after the schema-and-format one — **when the production phone runs an older release than the development phone**, the direct path from its installed release is proven on `emulator-5554` before the production install: the older signed release asset is downloaded and its checksum verified against that release's evidence; the debug app is uninstalled; the older release is installed and the owner's data loaded the way that phone was loaded; the candidate APK (`tools/release-dry-run.sh`) is installed over it in place; the release's own data proofs run; the emulator's debug install is restored afterwards. The R1–R7 table itself unchanged.

**Untouched:** every source file; `tools/servicetag-mcp/pyproject.toml` (stays `1.2.0`, plan.md §7); the 1.4.0 rows and evidence; the workflows.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain`: zero failures, zero skips (`VersionAgreementTest` and `ReleaseProofPolicyTest` among them — exactly one R4 row remains).
- `git grep -nE 'versionCode = 17|versionName = "1\.4\.1"' app/build.gradle.kts` → 2; `git grep -nE '^\| 1\.4\.1 \| 17 \|' docs/versioning.md` → 1.
- `git diff <base> --stat -- app/src core tools .github` → empty except nothing (no source change).
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Must NOT

Touch code or tests; bump the MCP; edit a 1.4.0 row; change the R1–R7 table; create a tag.

## Size

Small, mechanical.
