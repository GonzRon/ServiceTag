# Release proofs — the standing runbook

Every release's controller-proof section (§16 in a master plan) points here instead of restating
these steps. It follows the testing hierarchy in `docs/superpowers/planning-policy.md` and issue
#62: most proof runs in process, a few contract tests ask the framework, **three** tests cross a
real UID boundary, and one signed install proves the upgrade. Nothing here drives the screen from
the shell.

**The rule, in one sentence:** *a black-box test must name the OS boundary it uniquely
demonstrates, or it is not added.*

`<base>` below is the release branch's base commit. Every command is one physical line.

| # | proof | command | layer that owns it | budget |
|---|---|---|---|---|
| R1 | unit gate from scratch | `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` | JVM (includes `ReleaseProofPolicyTest`, the tripwire below) | — |
| R2 | connected suite, which includes the boundary and contract classes; the preserved set is staged first (below) | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` | instrumented Compose and framework contract (`ShareResolutionContractTest` among them) | zero skips |
| R3 | the Python suites | `root=$(git rev-parse --show-toplevel) && (cd "$root/tools/servicetag-mcp" && uv run --frozen pytest) && (cd "$root/tools/servicetag-bundle" && uv run --frozen pytest) && (cd "$root/tools/servicetag-schedules" && uv run --frozen pytest)` | each tool's own pytest | — |
| R4 | external boundary | **is R2's `ShareBoundaryTest`** — three cases, 20 s each; nothing else may be added to this row without naming a new OS boundary | external-boundary smoke, from the `:share-test-sender` UID | ≤ 60 s |
| R5 | structural | inside R1: `ManifestContractTest.kt`'s two classes — `ManifestContractTest` (the source manifest and the exported set) and `MergedManifestContractTest` (the merged-manifest permission set) — and `VersionAgreementTest`; plus the exported-set parser line below | JVM structural | — |
| R6 | hygiene | the range greps below | the controller, over `<base>..HEAD` | — |
| R7 | signed-APK upgrade smoke | install the verified release over the previous one on the development phone, in place: `adb install -r <verified apk>`; same `firstInstallTime` and UID before and after; table counts unchanged | one install on the development phone | one install |

R3's projects each set `-q` in their own `pyproject.toml`; do not add another, or the summary
line is suppressed.

## Before R2: stage the preserved set

`PreservedSetRestoreTest` assume-skips unless the format-5 preserved set is on the emulator, and
R2's budget is zero skips. The controller holds that archive; it is owner data and never enters
the repository. Before R2:

`adb -s emulator-5554 push <the format-5 preserved set> /data/local/tmp/servicetag-proof-data.zip`

## R4 in full

`ShareBoundaryTest` asks the test-only sender (`share-test-sender/`, its own package and UID) to
fire a real `ACTION_SEND` at `ShareIntakeActivity`, waits for the intake to resume and reads its
Compose semantics. Its three cases are the whole of the external proof for share intake:

1. an external `EXTRA_TEXT` share from another UID reaches the intake;
2. an external `content://` stream with a genuine temporary read grant reaches the byte form;
3. the same stream without a grant, from a sharer that has never granted ServiceTag anything, is
   refused with "Could not read what was shared" at read time, not drawn as a form and not a
   crash. This case runs first (see the environment notes), so every run proves the shape #63
   fixed.

They choose no asset, type nothing, press nothing and count nothing. Choosing, naming, Save,
Cancel, every refusal sentence, the uri-list arm, the caps and process death are proved in process
by `ShareIntakeScreenTest`, `ShareIntakeViewModelTest`, `SharedItemReaderTest` and
`SharedItemLiftTest`. What the installed package manager resolves is `ShareResolutionContractTest`.

## R5's parser line

The manifest puts one attribute per line, so the exported set is parsed, never grepped. Exit 0
prints the three activities sorted and `other 0 []`; a fourth exported component exits 1.

`python3 -c 'import sys,xml.etree.ElementTree as E; A="{http://schemas.android.com/apk/res/android}"; r=E.parse("app/src/main/AndroidManifest.xml").getroot(); f=lambda t:sorted(e.get(A+"name") for e in r.iter(t) if e.get(A+"exported")=="true"); act=f("activity")+f("activity-alias"); oth=f("receiver")+f("service")+f("provider"); print("activities",len(act),sorted(act)); print("other",len(oth),oth); sys.exit(0 if sorted(act)==["com.loosecannon.servicetag.MainActivity","com.loosecannon.servicetag.nfc.NfcDispatchActivity","com.loosecannon.servicetag.share.ShareIntakeActivity"] and not oth else 1)'`

## R6's greps

Each prints nothing, or the count stated.

- no e-mail in added lines: `git diff <base>..HEAD | grep -nE '^\+.*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'` (fixture URLs carry none)
- no home path: `git diff <base>..HEAD | grep -nE '^\+.*/hom[e]/[a-z]'`
- no serial but the emulator's: `git diff <base>..HEAD | grep -nE '^\+.*(adb -s |ANDROID_SERIAL=)' | grep -v 'emulator-5554'`
- no attribution: `git log --format=%B <base>..HEAD | grep -inE 'co-authored|generated[- ]by|claude|anthropic'`
- one author: `git log --format='%an %ae' <base>..HEAD | sort -u | wc -l` → 1
- empty bodies: `git log --format=%b <base>..HEAD | grep -c .` → 0
- the shared library's pin: `bash tools/check-submodule-pin.sh` → `submodule pin ok`
- no Kotlin `assert` in connected tests, beside the retired-token tripwire below: `grep -rnE '(^|[^.[:alnum:]_])assert\(' app/src/androidTest` → no output (ART runs with assertions disabled, so `assert(` there checks nothing in any release; use `assertTrue`/`assertEquals` or `check`. The pattern also catches an inline `runOnIdle { assert(` and leaves Compose's `.assert(` alone)
- lint: `./gradlew :app:lintDebug` → 0 errors, nothing suppressed (a real error is a real finding; 1.4's gate found an API-26 crash this way)

## R7 in full

Set `ANDROID_SERIAL` to the development phone for this step only; it is never written down.
Before and after `adb install -r` of the verified APK: `adb shell pm list packages -U --show-versioncode com.loosecannon.servicetag` (UID and version code) and `adb shell pm dump com.loosecannon.servicetag | grep -m1 firstInstallTime` (the package manager's own record, not the screen). The UID and `firstInstallTime` must not change and the version code must be the new one. The table counts come from the Developer API's `GET /v1/status` (`counts`, `docs/api/v1.md`) before and after and must be identical. The development phone is told apart by its installed version code, never by its model. The production phone is never part of a proof.

**When the release bumps the Room schema or the backup format**, the development phone's data survival is proven three ways, with **no export and no owner action**: **(i)** before and after the install, `GET /v1/status`'s `counts` and a per-table hash of every list route, read through the loopback API (the Developer API screen opened by one `adb shell am start`), each object hashed over the **previous release's key set**, with keys the release adds or normalises compared through **the release's documented mapping** and every other key verbatim — for 1.4.0 a schedule's pre-install `seasonBehavior` / `seasonReentry` / `seasonReentryOffsetDays` triple passes through `toLegacy(toPolicy(…))` before comparison — so neither an added key nor a normalised one false-fails; the counts and hashes are identical and each new table's count is reported; **(ii)** the schedules loader's re-plan against the upgraded phone (`servicetag-schedules plan <the owner's manifest> --code <code>`) exits 0 with every entry IDENTICAL; **(iii)** R2's `PreservedSetRestoreTest`, which restores the format-5 preserved set onto the new schema on the emulator.

**When the production phone runs an older release than the development phone**, the direct path from its installed release is proven on `emulator-5554` before the production install: the older signed release asset is downloaded and its checksum verified against that release's evidence; the debug app is uninstalled; the older release is installed and the owner's data loaded with the MCP and loader **of that release's tag** (a whole-tree worktree — the loader imports the MCP in process, and newer tools refuse an older schema); the candidate APK (`tools/release-dry-run.sh`, which needs the local signing material) is installed over it in place; the release's own data proofs run with the current tools; the emulator's debug install is restored afterwards. First used for 1.4.1 (1.3.0 → 1.4.1 while the development phone went 1.4.0 → 1.4.1).

**Before any connected run on `emulator-5554`, run `tools/emulator/prepare-emulator.sh`.** The AVD starts with
`-no-snapshot-save`, so its settings reset on every restart; the script turns off Gboard's stylus-handwriting
onboarding, which otherwise captures the focused field and swallows injected text (first seen 2026-09-23).
Every gate script and every implementer brief calls it first.

**The first signed release carrying Room schema 12 / backup format 12 (#74's durable category catalog at schema 9, #67's document role at schema 10, #79's warranty reminder lead at schema 11 and its service cases at schema 12)** must prove the direct **1.4.1 (schema 8) → schema 12** upgrade on `emulator-5554` before the production phone sees it, on the older-release path above: install the signed 1.4.1 and load the owner's data with 1.4.1's own tools; seed custom-category spelling variants (for example `Appliance`, ` appliance `, `APPLIANCE`) and built-in spelling variants (`hot tub`, `HOT TUB`) through the API; seed attachments of every kind (`PHOTO`, `LABEL_PHOTO`, `RECEIPT`, `MANUAL`, `WARRANTY`, `DOCUMENT`, `OTHER`, on an asset and on an entry) through an import merge of a data archive whose bytes are staged in the attachment folder first — 1.4.1's API has no attachment route — or use the owner's own attachments; make sure at least two assets carry a warranty date before the export — the owner's, or one seeded with 1.4.1's MCP `update_asset` (an overlay), or a `PATCH` that sends the asset's full command — because a lead needs one; take a pre-upgrade export; install the candidate in place; then verify that the migrations ran (`schemaVersion` 12, `backupFormatVersion` 12), that the catalog holds one row per custom key spelled as its oldest asset, that every asset's `category` is a catalog spelling, that `counts` are unchanged except for the new `assetCategories` key and the two new `serviceCases` and `serviceCaseEntries` keys, both 0 (the warranty adds no count: its delivery table is device-local), that a fresh post-upgrade export's attachments match the pre-upgrade export's — the pre-upgrade (format 8) export has no `role` key, the post-upgrade export carries `"role": null` on every attachment (the new `document_role` column arrives NULL on every row, and nothing is backfilled), and every other attachment field is equal — and that its assets match too: the pre-upgrade export has no `warrantyReminderLeadDays` key, the post-upgrade export carries `"warrantyReminderLeadDays": null` on every asset (the new `warranty_reminder_lead_days` column arrives NULL on every row, and nothing is backfilled), and every other asset field is equal, `category` compared by its key — and that it carries `"serviceCases": []` and `"serviceCaseEntries": []` (the pre-upgrade export has neither key; the two new tables arrive empty, and nothing is backfilled); that the pre-upgrade export re-plans **applicable with zero INSERT**, every row `IDENTICAL`, that a fresh export re-plans **applicable with zero INSERT** (a held key reads `SKIPPED`, never `CONFLICT`), and that R2's `PreservedSetRestoreTest` passes with the set staged; then give documents each of the three roles through the attachment sheet (the app's own path, which also moves each row's last-modified stamp), and set a warranty reminder lead on one dated asset in the asset editor (the app's own path, which also moves the asset's `updatedAt`) and on another through `POST /v1/assets/{id}/warranty-reminder` — in the editor, answer the notification question with "Not now" (this install has never been asked); open a service case on a dated asset through `POST /v1/service-cases` (the body carries its own `type`, `coverage`, `openedOn` and, with a cost, `currency` — the API applies no form default) and add a note entry and then a closing (`CLOSED`) status entry through `POST /v1/service-cases/{id}/entries`, and check that the case reads `CLOSED` with `closedOn` the closing entry's date and that the asset's condition did not move; re-plan the pre-upgrade (format 8) export again and prove it **applicable with zero INSERT**, every row `IDENTICAL` — the lead-bearing assets and the role-bearing attachments included, because an archive older than format 11 is compared without the lead, and without the stamp that setting it moved, as one older than format 10 is without the role, and the case and its entries are rows the old archive does not name — and prove the format-12 round trip: a fresh export carries each role by name, each lead by number, and the case with both entries, re-plans **applicable with zero INSERT**, every attachment, every asset, the case and both entries `IDENTICAL`, and restores every role, every lead, the case and both entries — a replace restore, and a merge into an install without those rows with the bytes staged, whose report tallies `serviceCases` and `caseEntries` with a non-zero `insert`. Master builds carry schema 12 under version name 1.4.1 until that release: they go on the emulator only, never on a phone. The production phone is not touched until this gate passes.

## Environment notes that cost a day

- **Pin the emulator.** A physical phone may be attached. `ANDROID_SERIAL=emulator-5554` must be
  exported for every Gradle and `adb` command here except R7's, or `:share-test-sender:installDebug` and the
  connected suite reach every attached device. A fresh clone has no `local.properties`, so export
  `ANDROID_HOME` too.
- **Gboard's stylus handwriting swallows text sent through adb.** The emulator's
  `stylus_handwriting_enabled` must be 0 for any test that types through adb — none of these do:
  every typed value is `performTextInput` inside an instrumented test.
- **The shell cannot delegate a documents-provider grant.** A shell-built share of a SAF URI
  arrives ungranted and correctly reads as "Could not read what was shared". That is why a real
  sender with its own `FileProvider` exists: it holds the grant it passes on.
- **The platform grants a flagless share by itself.** On API 37 an `ACTION_SEND` with a stream,
  no grant flag and no `ClipData` is migrated by `Intent.migrateExtraStreamToClipData`, which adds
  `FLAG_GRANT_READ_URI_PERMISSION`; the sender's logcat reads "Implicit URI grant for
  android.intent.action.SEND action will be discontinued from Android 18 onwards. Please set the
  grant explicitly in the app." So the sender's `send_file` sets the flag explicitly, and
  `send_bad_grant` carries `ClipData` without the flag so that it genuinely arrives ungranted.
- **ServiceTag cannot see a sharer's provider until that sharer has granted it a URI.** API 30+
  package visibility hides the sender's package from ServiceTag (`AppsFilter ... BLOCKED`), so for
  an ungranted stream from a sharer ServiceTag cannot see, the resolver logs "Failed to find
  provider info" and `query` returns null without throwing. Since #63 that is a read failure at
  read time (share-intake spec, I-11): the intake draws "Could not read what was shared" as its
  first loaded frame, before any form, and nothing is opened. Once the sharer has granted one URI,
  the provider is visible and an ungranted read is denied by the platform ("Permission Denial")
  instead, which lands on the same dead end. `ShareBoundaryTest` shares nothing first and runs its
  bad-grant case first (`NAME_ASCENDING`, its name sorts first), so every run, from the fresh
  install the connected task makes, proves the null-query shape rather than the denial.
- **The sender's authority is outside ServiceTag's namespace.** `StreamSourcePolicy` refuses
  ServiceTag's application id and every authority under it, so the sender serves its fixtures as
  `com.loosecannon.sharetestsender.fixtures`, not under `com.loosecannon.servicetag.`
  (`share-test-sender/README.md`).

## The tripwire

`ReleaseProofPolicyTest` (JVM, inside R1 and CI) fails if any file git tracks, or would not ignore, under `tools/`,
`app/src/androidTest/`, `share-test-sender/` or `.github/` names a screen-driving tool, or if R4
above stops being `ShareBoundaryTest` alone. `ShareBoundaryTest` fails any case that takes longer
than 20 s. A future harness either edits those tests in the open or fails CI.
