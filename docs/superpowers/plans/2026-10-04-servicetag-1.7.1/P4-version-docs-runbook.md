# P4 — 1.7.1 / versionCode 21: the version, its tests, `versioning.md`, the release notes, the README, the runbook, the evidence file

**Read first:** plan.md §2, §6, §7; `rulings.md` R171-1; the 1.7.0 release commit `a70aa89` (the shape to mirror: seven files).
**Lane:** alone, last, branched from `<base>` = the master commit that merged P3.
**Blocked on:** P3 merged. Under the review budget this brief is mechanical; the controller may author it and close it by inspection plus the gate.

## Files

**Modify**
- `app/build.gradle.kts`: `versionCode = 21`, `versionName = "1.7.1"`; the comment block rewritten for a PATCH — #94, #99, #103; no schema or format change (21 / 20); the tag `servicetag-v1.7.1`.
- `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt`: `theReleaseIdentityIs170AndCode20` renamed and moved to `"1.7.1"`, `21`, `servicetag-v1.7.1`; `statusEchoesTheVersionsTheBuildCarries` to `"1.7.1"`; a new `versioningRecords171` beside `versioningRecords170`: exactly one `^\| 1\.7\.1 \| 21 \|` row, it begins `PATCH:`, names `#94`, `#99` and `#103`, says schema **21** and format **20** unchanged, links `docs/releases/1.7.1.md` (which must exist) and names `servicetag-v1.7.1`, follows the 1.7.0 row, and no other row claims 21; the 1.7.0 cases kept as they are; the README cases extended to require the 1.7.1 release-notes link beside the 1.7.0 one they already require.
- `docs/versioning.md`: one row after 1.7.0 — `| 1.7.1 | 21 | PATCH: …` — the three issues in their own words: the schedule command's malformed body answers 400 where it was 500 (#94, the one `/v1` behaviour change, no code or route changed); the retain identity test covers every archive list (#99, test-only); the Maintenance tab's layout, Reminder health under Settings › Utilities, the healthy state (#103). Room schema 21 / backup format 20 unchanged; the MCP server unchanged (90 tools); release notes; the tag. The "Assigned future product-generation boundary" table unchanged.
- `docs/releases/1.7.1.md` (new): the 1.7.0 notes' shape, shorter — one paragraph that it is a maintenance release installing in place over 1.7.0 and over 1.6.0 and 1.5.0 (through 1.7.0's schema step); **Maintenance tab** (#103, the owner's words for the new layout and where Reminder health went); **Reminder health** (the healthy state); **Developer API** (#94: the one change a script could meet); **Upgrading** (nothing to do; data unchanged); **Known limits** carried from 1.7.0 unchanged, plus any P3 records.
- `README.md`: the current-release sentence names 1.7.1; the "Latest released baseline" and "Release details" lines move to 1.7.1 with the 1.7.0 link kept where `VersionAgreementTest` requires it.
- `docs/release-proofs.md`: one line after the "Cut as 1.7.0" line — 1.7.1 carries no schema or format step; R7 is the ordinary in-place install over 1.7.0 on both phones, after the same install on `emulator-5554` (plan.md §6 step 3).
- `docs/architecture/product-split-evidence.md`: one section in the 1.7.0 section's shape — what shipped, the proofs and the two phones recorded by the controller once each is done.

**Untouched:** every source file under `app/src/main`, `core`, `tools`; `tools/servicetag-mcp/pyproject.toml` (stays as 1.7.0 left it); the 1.7.0 rows and evidence; the workflows; the R1–R7 table.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain`: zero failures, zero skips (`VersionAgreementTest` and `ReleaseProofPolicyTest` among them).
- `git grep -nE 'versionCode = 21|versionName = "1\.7\.1"' app/build.gradle.kts` → 2; `git grep -nE '^\| 1\.7\.1 \| 21 \|' docs/versioning.md` → 1; `test -f docs/releases/1.7.1.md`; `git grep -nE '\]\(docs/releases/1\.7\.1\.md\)' README.md` → 1.
- `git diff <base> --stat -- app core tools .github` names exactly `app/build.gradle.kts` and `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt`.
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Must NOT

Touch production code; bump the MCP; edit a 1.7.0 row; change the R1–R7 table; create a tag (the tag is the owner's step, plan.md §6).
