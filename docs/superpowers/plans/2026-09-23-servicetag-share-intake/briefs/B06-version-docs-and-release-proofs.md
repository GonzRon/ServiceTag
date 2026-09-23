# B06 — version 1.3.0 / 15, the documents, and the release proofs

**Read first:** the master plan's §1 (global constraints), §15 (the release gate), §16 (controller
proofs), §17 (strings), §18.6, §18.7 and §18.9. Those sections are the contract; this brief is the
work. **Spec:** `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` §4.3, §11, §12,
Ruling D-16. **Report:** `.superpowers/sdd/2026-09-23-servicetag-share-intake/B06-report.md`.

## Purpose

Make the release's numbers agree where they can fail in CI instead of in a review's eyes, amend the
three documents this release makes untrue — the versioning history, the security row and the
data-model version table, and write down the proofs the controller will run — including the two that
have no precedent in this repository: **the share flows driven on the emulator by `am start`, with
no second app**, and **the two-phone D-18 skip**. Last brief; it consumes every other. It is a
single lane and it holds `emulator-5554` for the proofs.

## Files

**Modify**

- `app/build.gradle.kts` — `versionName = "1.3.0"`, `versionCode = 15`. Nothing else in that file.
- `docs/versioning.md` — one new row in the supported-release table (see below). **The 1.0.0, 1.1.0, 1.2.0 and 1.2.1 rows are untouched**, and the forward-only clarification paragraph is untouched.
- `README.md` — one capability line for the share intake and a link to the committed spec, in the shape of the 1.2 lines `VersionAgreementTest` already asserts.
- `docs/design/09-security-privacy.md` — the Attachments row's two retired controls (§18.7).
- `docs/design/04-domain-data-model.md` — **one row** in the superseded §15 block's per-version table (`:600`–`:607`, which today runs `| v1 | 1.0.0 | … |` … `| v6 | **1.2.0** | … |`): a `| v7 | **1.3.0** | `asset_reference` |` row, in the shape of the v6 row. **Nothing else in that file** — in particular the block's superseded marker, its "attachments shipped at v5, not v4, and schedules land at v6, not v3" sentence and the **v6** row are untouched, because `VersionAgreementTest.theDataModelDocumentNamesTheShippedVersions` asserts all three and must keep passing. The paragraph below the table says supplies and projections "will take v7 upward"; correct that to **v8 upward**, dated, since v7 is now taken.
- `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt` — the four number cases and the versioning-document case, renamed off their old numbers, plus the three new document cases below.
- `docs/superpowers/plans/2026-09-23-servicetag-share-intake/master-plan.md` — **only** if a proof in §16 is found wrong while running it; the correction is recorded there dated, never rewritten away.

**Untouched:** every `:core` and `:app` source file — **this brief changes no behaviour**; every
document not named above, **including `docs/design/05-scheduling-semantics.md` and the rest of
`docs/design/`**; `docs/api/v1.md` (B05 owns it and has already merged); every tombstone; the
release workflow and every CI file; `libs/`, `tools/`.

## The version row

`docs/versioning.md`'s supported-release table gains, after the 1.2.1 row:

| versionName | versionCode | what |
|---|---|---|
| 1.3.0 | 15 | **share intake:** Android `ACTION_SEND` intake of a URL, a document, an image or an external note link into an asset; a new `asset_reference` table and a **References** section on asset detail with Open, Edit, Remove and "Add link"; a reference surface on the loopback API and MCP. Room schema **7**, backup format **7** — the new app reads every older archive and 1.2.x refuses a format-7 one with `BackupNewerFormat` rather than dropping rows, which is why this is a MINOR by the rule above. Contracts: `docs/api/v1.md`, `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`. Unit gate \<n\> tests / \<n\> suites … |

The test counts are **measured at the final tip and written then** — this brief does not guess them,
and a number written before the last merge is a defect. The row names the schema and the format as
`**7**` in the shape the 1.2.1 row names `**6**`, because `VersionAgreementTest` asserts that
pattern anchored.

Spec §12's closing sentence — "The seasonal and operational model moves to 1.4.0" — is a **roadmap**
statement, not a table row. It goes in the release evidence, **not** into `docs/versioning.md`,
unless the owner asks otherwise. Do not invent a reservation row: an unused reservation is exactly
what D-1 struck from this file at 1.2.0.

## The security-document amendment (§18.7)

`docs/design/09-security-privacy.md`'s Attachments row states "MIME sniffed on import; size cap
configurable" as shipped controls. **Neither is true:** `AttachmentKinds.inferFrom` and
`AttachmentLocator.extension` both read the **declared** type and nothing sniffs, and the cap is the
`MAX_ATTACHMENT_BYTES` constant. Spec §4.3 retires both claims. Amend the row in this release rather
than letting a stated control quietly diverge, and record in the same row **why the declared type is
defensible**: bytes are never rendered, executed or interpreted, and are never opened by an embedded
WebView. Add the share path's own control to the same row in one clause: a share's stream URI must
be `content://` and must not name one of ServiceTag's own authorities, checked before the stream is
opened (I-9). **The row is amended, not deleted**, and the amendment is dated, like every other
superseded line in `docs/design/`.

## `VersionAgreementTest` — what changes and what does not

| case | change |
|---|---|
| `theReleaseIdentityIs121AndCode14` | renamed to name 1.3.0 and 15; asserts `"1.3.0"`, `15`, and `"servicetag-v1.3.0"` |
| `theSchemaAndTheFormatAreBothSix` | renamed; asserts `AppGraph.SCHEMA_VERSION == 7`, `BackupCodec.FORMAT_VERSION == 7`, and that the two agree |
| `theRoomDatabaseCarriesTheSameVersionAsTheGraphConstant` | **no source change** — it reads `SCHEMA_VERSION` and derives the rest, so it starts asserting `version = 7,` and `7.json` on its own. Its passing is the proof that B01's `7.json` was committed |
| `statusEchoesTheVersionsTheBuildCarries` | asserts `"1.3.0"`, `7`, `7` |
| `theProductionHandlersAreWiredToTheBuildsOwnConstants` | **unchanged** |
| `versioningRecordsThisReleaseAndNoLongerReservesItsCode` | renamed; asserts a `1.3.0 \| 15` row exists, that it names `schema **7**` and `format **7**`, that the forward-only clarification is still present verbatim, and — **kept** — that no `1.1.1` reservation row has come back |
| `theSchedulingDocumentMarksItsSupersededRules`, `theDataModelDocumentNamesTheShippedVersions` | **unchanged**. They are 1.2's and must keep passing |
| `theDocumentsDescribeThreePrimaryDestinations` | **unchanged** — this release adds no primary destination |
| **new:** `theReadmeNamesTheShareIntakeAndLinksItsSpec` | the README names the capability and links `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` |
| **new:** `theSecurityDocumentNoLongerClaimsMimeSniffing` | anchored: `docs/design/09-security-privacy.md` no longer contains "MIME sniffed on import" or "size cap configurable", and does carry the dated amendment |
| **new:** `theDataModelDocumentNamesV7` | anchored: `docs/design/04-domain-data-model.md`'s superseded §15 block carries a `v7` row naming **1.3.0**. **The block does carry a per-version table** (`:600`–`:607`), so there is no escape hatch and the row is added — the file is on this brief's Modify list for exactly this reason |

## Test matrix

One test per hazard class. Each must fail without the change it names.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| the tag and the APK disagree | `BuildConfig.VERSION_NAME` is `1.3.0` and `"servicetag-v${VERSION_NAME}"` is the tag the workflow checks | a `versionName` bumped in one place makes the release workflow refuse to publish, late |
| the code is reused or reset | `BuildConfig.VERSION_CODE` is `15`, one more than 1.2.1's 14 | a reused code makes an in-place upgrade impossible on the phone |
| the schema and the format drift | `AppGraph.SCHEMA_VERSION`, `BackupCodec.FORMAT_VERSION` and `AppDatabase`'s annotation are all 7, and `7.json` exists and declares 7 | a schema bumped in one place makes an import refuse an archive it could read, or accept one it cannot |
| the wire lies about the build | `GET /v1/status` echoes `1.3.0`, `7`, `7` through the **production** constants | a workstation cannot tell what it is talking to before it sends |
| the release history loses a row | `docs/versioning.md` carries a `1.3.0 \| 15` row naming schema 7 and format 7, and no reservation row has returned | a release with no row is a release nobody can date later |
| a retired control is still claimed | the security document no longer claims MIME sniffing or a configurable cap, and carries the dated amendment | a stated control that does not exist is worse than an absent one |
| the README goes stale | the README names the share intake and links the committed spec | a released document describing the wrong capability is a support cost |

## The release proofs this brief writes down and the controller runs

These are §16's, restated here as a runbook so the controller has one file to work from. **Nothing
in this brief runs on the production phone.**

**R1 — the unit gate from scratch** (master plan §16, R1) and **R2 — the connected suite on
`emulator-5554`** (R2), zero skips, `ANDROID_SERIAL` pinned. **R3 — the three Python suites** (R3),
with `servicetag-bundle` and `servicetag-schedules` **unchanged**.

**R4 — the share flows, by intent, on the emulator** (R4). The point worth stating plainly, because
it is the one a reader will doubt: **no second app is needed to drive a share.** `adb shell am start` constructs an `ACTION_SEND` intent exactly as a sharing app would, `--es` / `--eu` carry the
extras, and `--grant-read-uri-permission` reproduces the grant flag. Two commands per flow, one to
prove the **filter** and one to drive the **screen**:

- the filter, with no `-n`, so the manifest and not the code answers: `adb shell cmd package query-activities -a android.intent.action.SEND -t application/pdf` → a ServiceTag entry; repeat for `text/plain` and `image/jpeg`; then the same with `-a android.intent.action.SEND_MULTIPLE -t image/*` → **no ServiceTag entry**, proving D-7;
- the screen, with `-n com.loosecannon.servicetag/com.loosecannon.servicetag.share.ShareIntakeActivity`.

**Ten flows — master plan §16's R4 row is the contract and this list must match it one for one**,
each with its expected sentence from §10 and its expected row delta:

1. **URL** — `--es android.intent.extra.TEXT 'https://example-mower.invalid/xt1/manual.pdf'` → saved as a `WEB_URL` reference.
2. **document** — `adb push` a fictional PDF, then a `content://` URI with `--grant-read-uri-permission` → one attachment row, the description on the D-19 line.
3. **image** — `content://media/external/images/media/<id>` → one attachment row.
4. **note-link** — `'[Mower maintenance](joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978)'` → the name prefilled "Mower maintenance", saved as a `NOTE_LINK`.
5. **blocked scheme** — `'javascript:alert(1)'` → "ServiceTag will not save that kind of link.", nothing written.
6. **unknown scheme** — `'zotero://select/items/0'` → "Save this link?", then saved as `OTHER`.
7. **not a link** — `--es android.intent.extra.TEXT 'just some words'` → **"That is not a link."** with **"Save as a note"**, which writes an `EventKind.NOTE` journal event and **no reference**. This is #43 AC 5 and D-5, and the **only** end-to-end proof of the NOTE path; an earlier revision of this runbook omitted it.
8. **refused stream** — `--eu android.intent.extra.STREAM 'file:///sdcard/Download/x.pdf'` → "That file cannot be accepted from the app that shared it.", nothing written and nothing opened.
9. **no assets** — after `pm clear` → "Add an asset in ServiceTag first, then share this again." and Close only.
10. **no attachment folder** — tree grant revoked → the **byte** share refused with the ratified no-folder sentence and Save disabled, and the **URL** share on the same phone still saving.

Plus a **cancelled** run of each, every one leaving the counts unchanged. Every flow records the row
counts before and after; no flow reports a serial.

**R5 — the archives** (R5): the format-5 preserved-set restore still green (**the controller stages
the archive**; no implementer handles owner data); a format-6 archive imports with `assetReferences: []`; a **format-7 archive handed to a 1.2.1 build is refused through `BackupNewerFormat`**; and a
format-7 export re-planned against the phone that produced it is **all `IDENTICAL`**, references
included.

**R6 — the two-phone D-18 proof** (R6). Two installs — a second AVD, or two `pm clear` generations
of one install with an archive kept between them. Each side saves **the same URL on the same asset
under a different display name**; the archive from one is planned into the other. **PASS is:
`references` reporting `skipped: 1`, `applicable: true`, the apply succeeding, and the local row
unchanged afterwards.** Then one same-id/different-content case, which must be a **blocking
`CONFLICT`**. Both halves are required: the first proves D-18 C, the second proves references did
not become incapable of blocking.

**R7 — the API/MCP e2e**, the sequence in master plan §16's R7 row.

**R8 — the structural greps.** A list and not a table on purpose: an ERE alternation contains a `|`,
which a table cell would require escaping, and a pattern that has to be un-escaped before it runs is
exactly the silently-matching-nothing failure the anchored-pattern rule exists to prevent.
**`<base>` here is the release branch's base commit** — R8 asks what the whole release changed,
which is the one place that token is right. **It is not `$BASE`**, the lane base every per-brief
gate uses, including this brief's own (master §1.15). Run each line as written.

- `grep -cE '^\s*REFERENCES,?\s*$' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **1**, and the review reads the member list against master plan §4's order
- `grep -c 'ten canonical tables' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **0**
- `grep -cE '^[[:space:]]*UPDATE[,[:space:]]*$' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **0**
- `grep -rlE '^import (android|androidx)\.' core/src/main` → no output
- **no `provenance` column, field or argument** — `grep -rn '"provenance"' core/src/main app/src/main tools/servicetag-mcp/src docs/api/v1.md` → no output, and `grep -rnE '\bprovenance\s*[:=]' core/src/main app/src/main tools/servicetag-mcp/src` → no output. **Not the bare word**: it appears in seven shipped prose and comment lines (`SeedTemplates.kt`, `Journal.kt`, `ContentHash.kt`, `MainActivity.kt`, `JournalEntities.kt`, `server.py`, `v1.md`'s 1.2 code table), none of them a field and none of them this release's to reword — a bare-word grep could never pass and would invite an unowned mutation in every module at once
- tombstones untouched — `git diff --stat <base>..HEAD -- core/src/main/kotlin/com/loosecannon/servicetag/core/model/ExternalLink.kt app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/ExternalLinkEntity.kt app/src/main/kotlin/com/loosecannon/servicetag/data/room/dao/ExternalLinkDao.kt` → empty
- tombstones unread — `grep -rn 'externalLink\|ExternalLink\|LinkKind' core/src/main/kotlin/com/loosecannon/servicetag/core/references core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/AddReference.kt app/src/main/kotlin/com/loosecannon/servicetag/share app/src/main/kotlin/com/loosecannon/servicetag/ui/references` → no output
- **the exported component set — a parser, not a grep.** `AndroidManifest.xml` puts **one attribute per line**, so `grep -cE '<activity[^>]*android:exported="true"'` matches **nothing** in this file and its receiver/service/provider sibling **passes even when a receiver is exported** — a security check that cannot fail, which §1.10 forbids. Run master plan §16's XML-aware check instead: `python3 -c '…xml.etree…'` → exit 0, printing the **three** fully-qualified activity names sorted and `other 0 []`. It counts **`<activity-alias>` as an activity**: an exported alias is a fourth exported component and a check that ignored the tag would exit 0 on one. B03's gate proves it **fails** on a fourth; this gate records that it still passes on three. `grep -c 'com.loosecannon.servicetag.share.ShareIntakeActivity' app/src/main/AndroidManifest.xml` → **1** beside it
- `git diff <base>..HEAD -- app/src/main/AndroidManifest.xml | grep -c '^+.*uses-permission'` → **0**, and `MergedManifestContractTest.theMergedManifestPermissionSetIsExactly` green with its expected set unedited
- `grep -c 'android.intent.action.SEND_MULTIPLE' app/src/main/AndroidManifest.xml` → **0**; `grep -c 'android.intent.category.BROWSABLE' app/src/main/AndroidManifest.xml` → **0**
- `grep -c 'android:mimeType=' app/src/main/AndroidManifest.xml` → **11** — the ten new filter types plus the one shipped `<data>` in `<queries>`. **Not** `<data android:mimeType=`, which requires `android:mimeType` to be the first attribute on its element and returns 0 today (the shipped `<data>` at `:27` puts `android:scheme` first). The real check is B03's filter `@Test`, which parses; this is the cheap sentinel beside it
- every `getParcelableExtra` call site in `share/**` passes the typed overload — a **scalar** comparison, because `grep -rc` over a directory prints one `path:count` line per file and two such outputs cannot be compared by a gate: `grep -rl 'getParcelableExtra(' app/src/main/kotlin/com/loosecannon/servicetag/share | wc -l` equals `grep -rl 'Uri::class.java' app/src/main/kotlin/com/loosecannon/servicetag/share | wc -l`. The assertion that actually holds the invariant is B03's structural `@Test`; this is the sentinel beside it
- `grep -rn 'takePersistableUriPermission' app/src/main/kotlin/com/loosecannon/servicetag/share` → no output
- **§4.4's two prohibitions, which have no other check at any level** — `grep -rnE 'Log\.[iwe]\(|println\(|System\.out' app/src/main/kotlin/com/loosecannon/servicetag/share app/src/main/kotlin/com/loosecannon/servicetag/ui/references` → no output. **Both packages**: B04's section renders the same URIs B03's screen does, so a prohibition checked over only one of them is checked over half the surface. B03 also carries its half as a structural `@Test`. And `grep -rn 'WebView' app/src/main core/src/main` → no output
- the four numbers, **anchored** (§1.12) — `grep -cE '^[[:space:]]*version = 7,$' app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt`, `grep -c 'SCHEMA_VERSION = 7' app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt`, `grep -c 'FORMAT_VERSION = 7' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt`, `grep -c 'versionCode = 15' app/build.gradle.kts`, `grep -c 'versionName = "1.3.0"' app/build.gradle.kts` → **1** each
- the schemas — `test -f app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/7.json` → present; `git diff --stat <base>..HEAD -- app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/1.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/2.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/3.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/4.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/5.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/6.json` → empty
- `grep -c '^@mcp\.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → **41**
- `grep -rl 'ten canonical tables' core/src/main` → **no output** (`grep -rl`, not `-rc`, which would print a count line for all ~90 files; it names the two live ones today, `MergePlan.kt:18` and `BuildBackupMergePlan.kt:27`); `grep -c 'Ten tables since format 6' app/src/main/kotlin/com/loosecannon/servicetag/api/ApiDtos.kt` → **0**
- stale prose gone — `grep -c 'ten tables' docs/api/v1.md` → **0**; **`grep -c '1–6' docs/api/v1.md` → 0** (the bare string: `v1.md` spells the emphasis two ways, at `:143` and `:455`, so a pattern pinned to one placement leaves the other stale and still reports 0); `grep -c 'MIME sniffed on import' docs/design/09-security-privacy.md` → **0**; `grep -c 'size cap configurable' docs/design/09-security-privacy.md` → **0**; `grep -cE '^> \| v7 \| \*\*1\.3\.0\*\* \|' docs/design/04-domain-data-model.md` → **1**
- master plan §1.14's untouched list — `git diff --stat <base>..HEAD -- libs/ tools/servicetag-bundle/ tools/servicetag-schedules/ core/src/main/kotlin/com/loosecannon/servicetag/core/nfc/ core/src/main/kotlin/com/loosecannon/servicetag/core/schedule/ app/src/main/kotlin/com/loosecannon/servicetag/reminders/ app/src/main/kotlin/com/loosecannon/servicetag/ui/theme/` → empty

**R9 — hygiene** (master plan §16's R9 row). **R10 — the dev-phone install** (§15.8): over 1.2.1 in
place, code 14 → 15, the Stage-B estate's counts unchanged and `assetReferences: 0` before anything
is added; then one URL share, one document share, and **the `joplin://` round trip — the only step
in this release that needs hardware**, because it needs Joplin installed with real notes. **The
production phone is untouched.**

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` — green, with `VersionAgreementTest` **fully** green for the first time since wave 1.
- `grep -c 'versionName = "1.3.0"' app/build.gradle.kts` → **1**; `grep -c 'versionCode = 15' app/build.gradle.kts` → **1**; `grep -cE '^\|\s*1\.3\.0\s*\|\s*15\s*\|' docs/versioning.md` → **1**; `grep -c 'MIME sniffed on import' docs/design/09-security-privacy.md` → **0**.
- `grep -cE '^> \| v7 \| \*\*1\.3\.0\*\* \|' docs/design/04-domain-data-model.md` → **1**.
- `git diff --stat $BASE..HEAD -- core/src/main app/src/main` → **empty**. **`$BASE` is this lane's base commit** (master §1.15) — B06 runs last, so against the release branch's base this diff would carry every source change in the release and the gate would be a false FAIL. This brief changes no behaviour; `app/build.gradle.kts` is outside `app/src`.
- The report carries the measured test counts that go into the versioning row, and a PASS/FAIL line per proof R1–R10 **with no serial, no owner noun and no home path**.

## This brief must NOT

Change any production source file. Guess a test count before the final tip. Add a reservation row to
`docs/versioning.md`, or touch the 1.0.0, 1.1.0, 1.2.0 or 1.2.1 rows. Delete a superseded line from
any design document — amend and date it; in particular leave `04-domain-data-model.md`'s superseded
marker, its attachments-at-v5 sentence and its **v6** row exactly as they are, since
`theDataModelDocumentNamesTheShippedVersions` asserts all three. Rewrite a proof in the master plan
to match what happened; a proof found wrong is corrected there **dated**, and the report says so.
Run anything on the production phone. Stage or handle an owner archive itself — R5's format-5
archive is the controller's. Push, tag, or approve the release environment: §15.6 stops at the
owner's click.
