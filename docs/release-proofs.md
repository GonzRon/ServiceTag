# Release proofs — the standing runbook

Every release's controller-proof section (§16 in a master plan) points here instead of restating
these steps. It follows the testing hierarchy in `docs/superpowers/planning-policy.md` and issue
#62: most proof runs in process, a few contract tests ask the framework, **five** tests in two classes
cross a real UID boundary, and one signed install proves the upgrade. Nothing here drives the screen from
the shell.

**The rule, in one sentence:** *a black-box test must name the OS boundary it uniquely
demonstrates, or it is not added.*

`<base>` below is the release branch's base commit. Every command is one physical line.

| # | proof | command | layer that owns it | budget |
|---|---|---|---|---|
| R1 | unit gate from scratch | `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` | JVM (includes `ReleaseProofPolicyTest`, the tripwire below) | — |
| R2 | connected suite, which includes the boundary and contract classes; the preserved set is staged first (below) | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` | instrumented Compose and framework contract (`ShareResolutionContractTest` among them) | zero skips |
| R3 | the Python suites | `root=$(git rev-parse --show-toplevel) && (cd "$root/tools/servicetag-mcp" && uv run --frozen pytest) && (cd "$root/tools/servicetag-bundle" && uv run --frozen pytest) && (cd "$root/tools/servicetag-schedules" && uv run --frozen pytest)` | each tool's own pytest | — |
| R4 | external boundary | **is R2's `ShareBoundaryTest` and `ContactGrantBoundaryTest`** — five cases, 20 s each; nothing else may be added to this row without naming a new OS boundary | external-boundary smoke, from the `:share-test-sender` UID | ≤ 100 s |
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

`ContactGrantBoundaryTest` (#72, R72-24) proves the second boundary: **a contact handed over by
another UID with a one-shot read grant is readable by ServiceTag, which holds no contacts
permission.** It grants the sender its own `READ_CONTACTS` and `WRITE_CONTACTS` through the
instrumentation's `UiAutomation` (no shell string names a permission), asserts ServiceTag's UID holds
neither, aims ServiceTag's own pick seam at the sender's explicit-only `PickerActivity` and calls
`launch()`; the sender seeds its fictional, account-less, organisation-only contact ("Example Rentals
Ltd") and answers. Its two cases:

1. the sender's real lookup URI **without** a grant reads as unreadable (P72-46), not a crash. This
   case runs first (`NAME_ASCENDING`), so every run shows the refusal before any grant is made;
2. the same pick **with** the grant reads as "Example Rentals Ltd", and the link the codec builds from
   the row's real `_ID` and `LOOKUP_KEY` is the URI the provider handed out.

Nothing is chosen, typed or pressed, and the Contacts app is never driven. The lend form, the
section, the relink, the return and every loan sentence are proved in process by
`PickedContactReaderTest`, `LoanEditViewModelTest`, `LoanReturnTest`, `AssetLoansStateTest` and
`LendingSectionDeviceTest`; the codec against the framework's own `Contacts.getLookupUri` is
`ContactLinkContractTest`.

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

**The first signed release carrying Room schema 16 / backup format 16 (#74's durable category catalog at schema 9, #67's document role at schema 10, #79's warranty reminder lead at schema 11 and its service cases at schema 12, #72's loans at schema 13, #77's transfer records at schema 14, #86's asset successions at schema 15, and #85's attachment provenance at schema 16)** must prove the direct **1.4.1 (schema 8) → schema 16** upgrade on `emulator-5554` before the production phone sees it, on the older-release path above: install the signed 1.4.1 and load the owner's data with 1.4.1's own tools; seed custom-category spelling variants (for example `Appliance`, ` appliance `, `APPLIANCE`) and built-in spelling variants (`hot tub`, `HOT TUB`) through the API; seed attachments of every kind (`PHOTO`, `LABEL_PHOTO`, `RECEIPT`, `MANUAL`, `WARRANTY`, `DOCUMENT`, `OTHER`, on an asset and on an entry) through an import merge of a data archive whose bytes are staged in the attachment folder first — 1.4.1's API has no attachment route — or use the owner's own attachments; make sure at least two assets carry a warranty date before the export — the owner's, or one seeded with 1.4.1's MCP `update_asset` (an overlay), or a `PATCH` that sends the asset's full command — because a lead needs one; take a pre-upgrade export; install the candidate in place; then verify that the migrations ran (`schemaVersion` 16, `backupFormatVersion` 16), that the catalog holds one row per custom key spelled as its oldest asset, that every asset's `category` is a catalog spelling, that `counts` are unchanged except for the new `assetCategories` key, the two new `serviceCases` and `serviceCaseEntries` keys, the new `assetLoans` key, the new `transferRecords` key and the new `assetSuccessions` key, each of those five 0 (the warranty and the loan reminders add no count: their delivery table is device-local), that a fresh post-upgrade export's attachments match the pre-upgrade export's — the pre-upgrade (format 8) export has no `role` key, the post-upgrade export carries `"role": null` on every attachment (the new `document_role` column arrives NULL on every row, and nothing is backfilled) and the four `sourceUri`, `sourceResolvedUri`, `sourceRetrievedAt` and `sourceName` keys as null on every attachment (#85: the four columns arrive NULL, nothing is backfilled), and every other attachment field is equal — and that its assets match too: the pre-upgrade export has no `warrantyReminderLeadDays` key, the post-upgrade export carries `"warrantyReminderLeadDays": null` on every asset (the new `warranty_reminder_lead_days` column arrives NULL on every row, and nothing is backfilled), and every other asset field is equal, `category` compared by its key — and that it carries `"serviceCases": []`, `"serviceCaseEntries": []`, `"assetLoans": []`, `"transferRecords": []` and `"assetSuccessions": []` (the pre-upgrade export has none of those keys; the five new tables arrive empty, and nothing is backfilled); that the pre-upgrade export re-plans **applicable with zero INSERT**, every row `IDENTICAL`, that a fresh export re-plans **applicable with zero INSERT** (a held key reads `SKIPPED`, never `CONFLICT`), and that R2's `PreservedSetRestoreTest` passes with the set staged; then give documents each of the three roles through the attachment sheet (the app's own path, which also moves each row's last-modified stamp), and set a warranty reminder lead on one dated asset in the asset editor (the app's own path, which also moves the asset's `updatedAt`) and on another through `POST /v1/assets/{id}/warranty-reminder` — in the editor, answer the notification question with "Not now" (this install has never been asked); open a service case on a dated asset through `POST /v1/service-cases` (the body carries its own `type`, `coverage`, `openedOn` and, with a cost, `currency` — the API applies no form default) and add a note entry and then a closing (`CLOSED`) status entry through `POST /v1/service-cases/{id}/entries`, and check that the case reads `CLOSED` with `closedOn` the closing entry's date and that the asset's condition did not move; lend asset A — a dated asset — through `POST /v1/loans` (the body carries its own `borrowerName` and `lentOn` — the API applies no form default) with a `dueOn` and `reminderMode` `ONCE`, and lend asset B, then return it through `POST /v1/loans/{id}/return`, and check that A's loan reads open and B's returned with its `returnedOn`, both `contactLinked` `false`, and that neither asset's condition moved (no contact is picked in the gate); re-plan the pre-upgrade (format 8) export again and prove it **applicable with zero INSERT**, every row `IDENTICAL` — the lead-bearing assets and the role-bearing attachments included, because an archive older than format 11 is compared without the lead, and without the stamp that setting it moved, as one older than format 10 is without the role, and the case, its entries and the two loans are rows the old archive does not name — and prove the format-16 round trip: a fresh export carries each role by name, each lead by number, the case with both entries and both loans, `"transferRecords": []` and `"assetSuccessions": []`, re-plans **applicable with zero INSERT**, every attachment, every asset, the case, both entries and both loans `IDENTICAL`, with every `transfers` and every `successions` count 0 (no record or succession to tally), and restores every role, every lead, the case, both entries and both loans — a replace restore, and a merge into an install without those rows with the bytes staged, whose report tallies `serviceCases` and `caseEntries` with a non-zero `insert`, `loans` with `insert` 2 and every `transfers` and every `successions` count 0. No Transfer Pack is made in the gate: making, importing, marking and withdrawing one are the phone's alone. No Replace is made in the gate either: replacing an asset is the phone's alone, and no route or tool does it. Master builds carry schema 16 under version name 1.4.1 until that release: they go on the emulator only, never on a phone. The production phone is not touched until this gate passes.

**Erratum — how 1.5.0 met this paragraph (2026-09-30; owner rulings S1 and the smoke qualifications).** The paragraph gates the production phone, not the tag. For 1.5.0 its requirements were met in three separately recorded proofs, and the rest is stated here rather than marked passed:

- **Proven on `emulator-5554` (the upgrade proof, `1.4.1 → candidate 1cfd6891`, in place):** the signed 1.4.1 installed fresh; the development phone's format-8 export loaded through 1.4.1's own `/v1/import-merge` (232 rows; the 8 attachment rows `SKIPPED` — no attachment folder on the emulator and a data archive carries no bytes); the category spelling variants seeded (1.4.1 trims category on write, so ` appliance ` cannot reach the migration — only case variants are testable) and two warranty dates; the candidate installed in place with the UID and `firstInstallTime` unchanged; `schemaVersion` 16 / `backupFormatVersion` 16; every shared count identical, `assetCategories` one row per custom key spelled as its oldest asset, the five new keys 0; every route both versions serve equal, `/v1/assets` equal once `warrantyReminderLeadDays` (null, nothing backfilled) is stripped; the format-8 export re-planned **applicable, 0 INSERT, 232 IDENTICAL, 8 SKIPPED**; the loader re-plan IDENTICAL.
- **Proven on the emulator after the upgrade (the post-upgrade writes):** a warranty lead through `POST /v1/assets/{id}/warranty-reminder` (the row moved only its lead and `updatedAt`); a service case opened, a note entry (the header untouched), a `CLOSED` entry (`closedOn` = its date), the asset's condition unmoved; loan A open with `dueOn` and `ONCE`, loan B returned, both `contactLinked` false, no condition moved; `/v1/status` moved by exactly the case, its two entries and the two loans; the format-8 export re-planned again **0 INSERT / 232 IDENTICAL / 8 SKIPPED**. The lead-bearing asset is a probe outside the archive (none of its 47 assets carries a warranty date), so "compared without the lead" on a row carrying one rests on the JVM merge tests. A case entry sent with `"note": null` is refused 400 (`note` is a string; `""` or omitted for none).
- **Proven on the development phone (R7):** the published asset installed in place over 1.4.1, the UID and `firstInstallTime` unchanged; 1.5.0 / 16 / 16; every shared count identical **including the 8 attachment rows**; 22 routes equal (the asset routes once `warrantyReminderLeadDays` is stripped); the loader re-plan IDENTICAL 47. **This is attachment-ROW continuity, not attachment-byte integrity and not an export round trip.**
- **Not exercised in 1.5.0's gate, by name (separate statuses, not passed):** the pre/post export comparison and the format-16 round trip (a fresh export, its re-plan, the replace restore, the merge into an install without the rows) — `/v1` has no export route and the emulator has no attachment folder; roles through the attachment sheet and the editor's warranty-lead path with "Not now" (UI-only); attachment bytes of every kind through a staged merge; the reminder sweeps (`warranty_reminders`, `loan_reminders`). Their evidence, when a release exercises them, belongs beside this erratum. The phone-side smoke of Save as document (two real materializations with their provenance, one refusal, one Review cancel) and of the notification bodies (two natural posts) is the release-acceptance record in `docs/architecture/product-split-evidence.md`.

**Note — the #92 routes (2026-09-30).** The two paragraphs above are 1.5.0's record and stay as written. From #92 on, three of the reasons a proof drove a screen or staged a merge have an API route, each over the phone's own use case (`docs/api/v1.md`, **Attachments (#92)**, **Save as document (#92)**, **Replacing an asset (#92)**): a gate may seed **an asset's** attachments of every kind with every role, or none, through `POST /v1/assets/{id}/attachments` and set a role through `PATCH /v1/attachments/{id}`, instead of a staged merge and the attachment sheet (an entry's attachment still arrives by a staged merge or the phone); it may save a reference as a document through `POST /v1/references/{id}/materialize`; and it may make a Replace — its tag moves included, as binding re-targets that write no tag — through `GET /v1/assets/{id}/replace-offer`, `POST …/replace-plan` and `POST …/replace`, where 1.5.0's gate made none because no route did. The rest of the schema-16 paragraph's screen and merge steps still have no route: the export and the format-16 round trip (`/v1` has no export route), and the editor's warranty-lead path with "Not now" (UI-only). A release whose older side predates #92 still seeds that side the old way. The routes' outcomes are **JVM-only proofs**: the upload's framing, authentication before the body, replay, strict 409 and derived id, the long-write lock across listener generations, save as document's refusal codes, its stop before the commit and its commit after, and the replace's order, digest and tag re-targets are proven over the production router and real loopback sockets (`AttachmentRoutesTest`, `AttachmentUploadRoutesTest`, `MaterializeRoutesTest`, `ReplaceRoutesTest`, `LoopbackApiServerTest`), with no new device class and no new device case. The sheets stay UI-proven (`ReplaceAssetFlowTest`, and the Materialize sheet's person-tapped smoke): the routes prove the outcomes, not the screens. Two things stay on a device: picking the attachment folder (the system's folder picker; an upload without one answers `ATTACHMENT_STORE_NOT_CONFIGURED`), and what no JVM test reaches — `adb forward` under a long body, the real folder's write, and a real https fetch started off-screen — which is the development-phone proof, never the production phone's.

**The first signed release carrying Room schema 17 / backup format 17 (#91's reference document role)** must prove the direct in-place upgrade from **each release a phone runs** when it is cut — today **1.5.0 (schema 16)** on the development phone and **1.4.1 (schema 8)** on the production phone — on `emulator-5554` before any phone sees it, each on the older-release path above (that release's signed asset, its own MCP and loader; the 1.4.1 path also crosses schemas 9–16, so the schema-16 paragraph's upgrade checks are its checks too). On the older side, seed references through that release's own `add_reference` or `POST /v1/references` (the route exists since 1.3.0): several `https` and `http` web links and a note link, or the owner's own; then take a pre-upgrade export. Install the candidate in place (the UID and `firstInstallTime` unchanged) and verify: `schemaVersion` 17 and `backupFormatVersion` 17; `counts` unchanged, with no new key (#91 adds no table); every pre-existing reference read through `GET /v1/assets/{id}/references` carries `"role": null` and every other field as before (the `document_role` column arrives NULL on every row, and nothing is backfilled or inferred, whatever a link's name says); and a post-upgrade export carries `"role": null` on every pre-existing reference, every other reference field equal to the pre-upgrade export's (which has no `role` key). Then give roles **after** the upgrade: each of the three to pre-existing web links through `PATCH /v1/references/{id}` (and `update_reference`), one on a new web link through `POST /v1/references`, and at least one through the Edit reference sheet (the app's own path; each moves the row's `updatedAt`). Re-plan the **pre-upgrade** export and prove it **applicable with zero INSERT, no `CONFLICT`, every reference `IDENTICAL`** (an emulator with no attachment folder reads its attachment rows `SKIPPED`, as 1.5.0's gate did) — the references that gained a role included, because an archive older than format 17 is compared without a reference's role and, when the row here carries one, without the `updatedAt` that giving it moved (the attachment role's rule since #67). Do not give a pre-existing reference a role and then clear it before that re-plan: such a row re-plans `CONFLICT`, the recorded limit of the same rule. Then prove the format-17 round trip: a fresh export carries each role by name and `"role": null` on the rest, re-plans **applicable with zero INSERT**, every reference `IDENTICAL`, and restores every role — a replace restore, and a merge into an install without those rows. R2's `PreservedSetRestoreTest` passes unchanged (format 5 carries no references). **The exports are a step on the device itself** — the app's own backup export, made by hand: `/v1` has no export route, the gap the 1.5.0 erratum names, unchanged by #91. **What the emulator does not observe here:** a role chosen on the share screen from a real sharing app (`ShareBoundaryTest` proves delivery only, and #91 adds no boundary); Save as document copying a reference's role into its attachment, which needs the attachment folder and a real https fetch (JVM-proven over the production router, `MaterializeRoutesTest`; on a device it is the development phone's smoke, never the production phone's); and the production phone itself. The rest of #91 — the migration, the codec, the merge rule, the routes and the MCP — is JVM-proven, with no new device class and no new device case. Master builds carry schema 17 under version name 1.5.0 until that release (no version bump and no release with #91, R91-11; the release vehicle is the owner's): they go on the emulator only, never on a phone. The production phone is not touched until this gate passes.

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
above stops naming exactly `ShareBoundaryTest` and `ContactGrantBoundaryTest`. Each of the two fails any
case that takes longer than 20 s. A future harness either edits those tests in the open or fails CI.
