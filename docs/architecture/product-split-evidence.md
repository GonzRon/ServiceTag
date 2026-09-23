# Product-split evidence

Commits, builds, test runs and device observations, one section per phase. Fingerprints only —
no secrets, no physical-device ids, no owner paths.

*Note (2026-09-20): the sections below for ServiceTag 2.5, 2.6, 2.7 and 2.7.1 describe **pre-1.0 development releases**. Their GitHub Releases and tags were retired on 2026-09-20 and the supported lineage starts at 1.0.0 (see the last section and `docs/versioning.md`). The sections themselves are unchanged — they remain the engineering evidence for those builds.*

## Phase D — ServiceTag identity conversion (§A.1)

**Commits.** Phase D runs from `1a93b55` through `4c79418` on `product-split` — twenty-four
commits, printed in order by `git log --oneline 1a93b55^..4c79418` (this fix round and the
controller's plan amendments follow that endpoint). Of those, **six are the
controller's plan work** (`1a93b55` added the 2,418-line phase plan; `d17355c`, `4bbf9b4`,
`8241d07`, `8e83a36` and `094b023` amended it as runs contradicted it) and **eighteen are
implementation**. Of those eighteen, fifteen are §A.1's task commits, two are review fix rounds
(`3dd8b33` for Task 5, `8de824e` for Task 6) and one is this regression pass.

First task commit: `8af14eb` "root project name is ServiceTag". Last identity commit before the
regression pass: `cffe8b7` "readme points at the fingerprint instead of printing it". Last commit
of the phase: `4c79418`, "phase d regression pass on the emulator"; its review fix round `a0e4580`
and the controller's plan-amendment commits follow that endpoint. The eleven commits between the `pre-split-checkpoint` tag
(`ac523d7`) and `1a93b55` are the split's archaeology and target-architecture documents, not
Phase D.

**Task 16 made no commit.** Its whole-phase verification ran against `cffe8b7` and reported from
artefacts; the controller recorded the result and amended the plan in `094b023`. So Phase D has
fifteen task commits, two fix rounds, one regression pass, six plan commits — not the
"fifteen plus two verification commits" the plan's template guessed at.

**Identity, read off the built debug APK** (`aapt2 dump badging`, build-tools 36.0.0):
`package: name='com.loosecannon.servicetag' versionCode='7' versionName='2.5'`,
`application-label:'ServiceTag'`. The two launchable activities are `MainActivity` and the
debug-only `DebugBackupActivity` (`label='ServiceTag Backup (debug)'`); the third
`uses-permission` line, `com.loosecannon.servicetag.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is
AGP-injected and self-scoped to the new applicationId.

**Merged manifest.** One `NDEF_DISCOVERED` filter; `android:path="/com.loosecannon.servicetag:tag"`
resolved from the single Gradle-owned value; `android:scheme="servicetag"`; zero occurrences of the
retired package name. Read from
`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml` — the
`debugAndroidTest` merged manifest has no NFC filter and is the wrong file to grep (Task 16's
finding; the plan was amended for it).

**The C9 binding.** `TagIdentityBindingTest` (JVM, 4 tests / 8 assertions) and
`TagIdentityDispatchTest` (emulator, 3 tests) both green: the identity comes from one Gradle value,
the manifest carries only the placeholder, and `queryIntentActivities` for
`vnd.android.nfc://ext/com.loosecannon.servicetag:tag` resolves to exactly one activity — this
package's `NfcDispatchActivity`. The retired external type resolves to nothing of ours.

**Ambient NFC as the normal read path.** `NfcIdentityDeviceProofTest` (emulator, 4 tests). The
intent is **implicit** — action, `EXTRA_NDEF_MESSAGES` and the data URI
`vnd.android.nfc://ext/<externalType>` built from `BuildConfig.NDEF_EXTERNAL_DOMAIN` and
`BuildConfig.NDEF_TYPE_NAME`, with no component and no class — so the chain that actually ran is:

> `startActivity` → **the merged manifest's one `NDEF_DISCOVERED` filter** resolves it →
> `NfcDispatchActivity` → real `NdefCodec` → `ResolveTag` → real Room database → hand-off extras →
> `MainActivity` → `TagResultSheet`.

The filter link is therefore exercised here as well as asserted by `TagIdentityDispatchTest`. Two
limits of the model, stated plainly: the data URI is what the *filter* matches while the *records*
are what the codec judges (`NfcDispatchActivity.payloadOf` reads only `EXTRA_NDEF_MESSAGES` for
this action), so the two not-ours rows arrive **through our own filter carrying somebody else's
record** — the untrusted-input shape the trampoline is written for, not a claim that a sibling
*tag* can reach us. That a sibling tag cannot is `TagIdentityDispatchTest`'s claim.

| Test | What it proves |
|---|---|
| `ourTagOpensTheAssetItIsBoundTo` | a bound ServiceTag tag opens its asset with no chooser and no decision to make — there **is** a sheet: `TagResultSheet`'s `OpensAsset` branch names the asset and navigates in the same composition, and the nav root drops the sheet entry as it pushes the detail screen. The name alone would be satisfied by that transient sheet, so the row waits for the name **twice** (app-bar title + identity-plate model line), which only the detail screen carries |
| `ourTagWithNoRowSaysSo` | a ServiceTag tag with no row is named (`UNREGISTERED TAG` / "This ServiceTag tag is not in this phone's records."), not treated as damage |
| `aNoteTagRecordIsNotOurs` | a `com.loosecannon.notetag:tag` record is foreign **even carrying a byte-identical ServiceTag v1 body** (the body is our own `v1Record`'s payload, re-typed) — the type gate runs before any body parse (C8, invariant 1). Reason asserted: `not a ServiceTag tag: tnf=4 type=com.loosecannon.notetag:tag` |
| `ourTypeWithAShortBodyIsUnreadable` | our own type with an unparsable body is **ours**, and refused as unreadable rather than foreign. Reason asserted: `unreadable ServiceTag record: payload is 3 bytes, expected 18` |

Both not-ours rows assert the sheet's uppercased eyebrow (`NfcSheet` calls `eyebrow.uppercase()`,
G1 §1.4) and its shared sentence, because that is what the tree carries — and then the distinct
`QuietLine(result.reason)` prose, which is the only thing that tells "foreign" from "ours and
malformed" apart on screen. The JVM halves of those two claims are
`NdefEnvelopeIsolationTest.aSiblingRecordCarryingOurOwnPayloadIsStillForeign` and
`NdefEnvelopeIsolationTest.ourTypeWithASiblingBodyIsOursAndMalformed`.

**Record size (H2).** The `:tag` record is 51 B (3 + 30 + 18) and the AAR 44 B (3 + 15 + 26): a
**95 B** NDEF message, pinned byte for byte by `NdefCodecV1Test`. Capacity is compared
message-size to message-size against `Ndef.maxSize`, with no TLV framing (G1). The NTAG213
limits-test constant is `137` (`NdefCodecV1Test.NTAG213_MAX_MESSAGE_BYTES`, and
`TagWriteControllerTest`'s default `maxSize`) — **`[unobserved]` provisional seed**, to be
re-pinned from the measured `Ndef.maxSize` in §D Session 1 (H8).

**Legacy format.** `md5_short` is gone: no constant, no decoder, no manifest filter, no sheet, no
enum member (`PayloadFormat` has one member, `V1`). `git grep -i md5 -- app core gradle
settings.gradle.kts README.md` returns exactly one line — `TagResultWireTest`'s negative case
`assertNull(TagResultWire.payloadOf("LEGACY_MD5", "63b37acf"))`, which asserts that an unknown
format word resolves to nothing. The string `md5_short` itself survives only in
`docs/architecture/product-split-archaeology.md`, as history. Recorded as a **deliberate reversal
of D6's "kept permanently" promise** under O2/O3. The live database carries `nfc_tag = 0` rows
(arch §7.5, archaeology §"nfc_tag = 0"), so no row of the owner's data named it.

**Room.** Schemas re-exported under `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/`
by `git mv` plus a re-export — that is the only schema directory present. `5.json`'s
`identityHash` is `157988f1aada363f37590a6735a3be36`, **unchanged**. Every migration hop and the
full 1→5 run green. One archaeology artefact to know about: `AppDatabase.kt` is only **41 %**
similar across Task 3's package move, so at git's default 50 % rename threshold `abd0df1` shows it
as a delete plus an add (`git show --stat abd0df1`) rather than as a rename; the history is intact
and `git log --follow -M20%` walks it back through `aa6ef97` (room v5) to `3f6e2ec` (room v2).

**Launcher icon.** The owner's finished pack, staged at
`~/Documents/Projects/AndroidStudioProjects/split-assets/ServiceTag/` and copied into the module:
an ivory `#F7F5EF` ground and a text-free mark in `#1F4E78` with `#52606A` and `#FFFFFF` detail
(the Apollo Service Binder palette), adaptive foreground/background under `mipmap-anydpi-v26/`
(`ic_launcher.xml` and `ic_launcher_round.xml`), a dedicated `<monochrome>` layer for Android 13+
themed icons, and PNG rasters at five densities for pre-adaptive launchers. The inherited
`mipmap-anydpi/` XML pair and all ten `*.webp` rasters are deleted — the APK contains no `.webp`
under `ic_launcher*` (`unzip -l | grep -ci 'ic_launcher.*\.webp'` = 0) — and `<application>` now
declares `android:roundIcon` as well as `android:icon`. No placeholder: the coexistence requirement
is met with shipped artwork. Design masters (`source/`, `preview/`) stay in the staging area and
are not tracked here.

**Inventory addition found in this phase.** `ui/settings/SettingsScreen.kt:64`
`PROJECT_URL = "https://github.com/GonzRon/ServiceTag"` — an identity carrier absent from arch
§4.6 and from §A.1. It points at the post-rename name, which resolves from §B.2 onward (this code
is pushed at §B.3a, after it).

**Suites** (this pass, after `./gradlew clean`): `:core:test` **350** tests in 34 classes;
`:app:testDebugUnitTest` **223** in 34 classes; `:app:connectedDebugAndroidTest` **66** in 13
classes, on the emulator (`ANDROID_SERIAL=emulator-5554`) — 0 failures, 0 errors, 0 skipped in all
three. The instrumented total is Task 16's 62 plus this phase's four new ambient rows. **No
instrumented run touched the phone in this phase**: a physical device was attached throughout and
every `adb` call and every connected/install task was pinned to `emulator-5554`. Both APKs build;
the release APK is `app-release-unsigned.apk`, because the signing config's
`~/.config/servicetag/keystore.properties` does not exist yet — the key is generated in a later
phase and §12's signed-release proof is discharged there. The **retired 2024 signing key's
SHA-256 fingerprint is recorded once**, in `docs/design/phase-1a-evidence.md`; the README points
there rather than reprinting it, and nothing in this file reproduces it.

**After the reviews.** The Task 17 fix round (`a0e4580`) and the whole-branch review's fix round (`b4b016b`) added tests without changing any identity fact above: at `b4b016b` the suites are `:core:test` 354, `:app:testDebugUnitTest` 225 and `:app:connectedDebugAndroidTest` 66 in 13 classes, all 0 failures / 0 errors / 0 skipped, the connected suite re-run in full on the emulator at that commit.

**Clean checkout.** `git clone --no-local --branch product-split` into a directory that never held
the project, then `:core:test :app:testDebugUnitTest :app:assembleDebug` — green (Task 16, against
`cffe8b7`; the clone was deleted afterwards). One caveat worth carrying: `local.properties` is
intentionally gitignored, so a fresh clone has no SDK path and needs `ANDROID_HOME` supplied out of
band. That is not something Phase D introduced; it will reproduce for any fresh clone, and §B.6's
URL clone and second-workstation build must expect it.

**Repository-wide identity grep** (Task 16, `git grep -lIE
'com\.loosecannon\.notenfc|notenfc://|md5_short|noteNFC|NoteNfc' -- app core gradle
settings.gradle.kts README.md`). The only **text** hits are two deliberate negative cases:
`app/src/test/.../ui/backup/BackupViewModelTest.kt` (the `noteNFC-*` archive names inside
`aPreservedRetiredPrefixArchiveStillImports`, which proves the owner's pre-split export set is
still importable) and `app/src/androidTest/.../nfc/TagIdentityDispatchTest.kt:46` (the retired
external type `vnd.android.nfc://ext/com.loosecannon.notenfc:tag` inside
`theRetiredExternalTypeResolvesToNothingOfOurs`, which asserts nothing of ours still claims it,
O3). The only **binary** hit is the kept 2024 `app/release/app-release.apk`, untouched. There is
no remaining live reference to the retired identity.

**§21 regression — where every row is discharged.** §21's list, with the suite that answers it.
All of the JVM and emulator suites named below ran green in this pass; the physical rows are named
as not attempted and why.

| §21 row | Discharged by |
|---|---|
| the core maintenance product (assets, journal, measurements, profiles, templates) | `:core:test` + `:app:testDebugUnitTest` + `AssetModelDeviceProofTest`, `JournalDeviceProofTest`, `EditorsDeviceProofTest` |
| NFC asset binding, ambient resolution, standalone links | `NfcIdentityDeviceProofTest` + `TagUseCasesRoomTest` + `ResolveTagTest.boundToALinkLaunchesIt` (`:core:test`) |
| foreign / malformed safety | `NfcIdentityDeviceProofTest` + `NdefEnvelopeIsolationTest` + `NdefCodecTest` |
| intentional write, capacity, read-back | `TagWriteControllerTest` off-device with the capacity seed; the physical rows are §D Session 1 |
| attachments | `AttachmentsDeviceProofTest`, `SafTreeAttachmentStoreContractTest` |
| backup: all-or-nothing export, data-only restore, artifacts restore, set mismatch, missing/hash drift | `BackupViewModelTest`, `RestoreProofTest`, `BackupUseCasesTest`, `AttachmentsDeviceProofTest` |
| UX: Apollo theme, Dashboard, Assets, two-tab navigation, Read/inspect tag, ambient NFC as the normal read path | `NavigationSmokeTest`, `AppSmokeTest`, `ComponentsSmokeTest`, `ContrastTest`, and the ambient row by `NfcIdentityDeviceProofTest` |

**Caveat, stated once.** Everything above is **debug-build evidence**. §12's signed-release
requirement is discharged separately and only as a build-verified claim; no device row in this
file comes from a release build.

**Not attempted in this phase, and why.** Formatting, the measured `Ndef.maxSize`, capacity
refusal, read-back and lock-last need a radio and a physical NTAG213: §D Session 1's four owner
taps. Coexistence needs two installed products: §E Session 2's eight. The emulator has no NFC
radio, so the ambient path is proved with synthetic `NDEF_DISCOVERED` intents through the real
dispatch activity, codec and database (G4's reassignment).

## Phase E — NoteTag reconstruction (§A.2)

**Commits.** Phase E is the whole of the NoteTag repository's own work: `c84b881..9ff1d65` on `master`
— **17 commits**, printed in order by `git log --oneline c84b881..9ff1d65`, **0 merges**
(`git rev-list --merges --count HEAD` = 0). The ancestry is the original product's, not a fresh
history: the root commit is `5fb6aed` "working!" and `git rev-list --count HEAD` is **47**, so the
30 commits before `c84b881` are the noteNFC line, untouched. First commit of the phase: `8865990`
"start the narrow product from its own history"; last: `9ff1d65` "review round: an unverified format
is not a write, and the claims match the code". The tree was **45 files** after that first commit
(the deletion of the sibling's documents and the 2024 APK) and is **85** now
(`git ls-files | wc -l`). This repository has no remote yet and nothing is pushed in this phase.

**After the whole-branch review.** The reconstruction was reviewed as a whole once it was finished,
and the seven things it found that mattered are fixed in one commit, `9ff1d65` — the last of the
phase, and the tree every number below was measured at. (1) An **unverified `Written` is no longer a
write**: the interim adapter's `NdefFormatable` path returns `Written(verified = false)` after a
format, and the controller used to confirm the store row, report "Written" and set `done`, which
dropped the very next tap — the one that measures, capacity-checks, writes and verifies; it now says
"Formatted the tag. Hold it to the phone again to finish writing the link.", confirms nothing, keeps
a `LOCAL_REF` mapping persisted-unconfirmed exactly as for any other ambiguous outcome, and leaves
the task open. (2) The writer's read failure says **one fixed NoteTag sentence** — "Could not read
the tag. Hold it still and try again." — instead of leaking a platform exception's message, with the
single-flight guard proved released by the tap that follows. (3) `WritePlanner` **refuses at plan
time** a scheme that is neither blocked nor on the allowlist (`WritePlan.Refused("NoteTag does not
open <scheme> links.")`), because `ResolveTap` answers such a link with a sentence and would never
open the tag it had just written. (4) A tag list this phone **cannot read** is no longer reported as
"written on another phone" — a false statement about the tag — but as "This phone's tag list could
not be read.", with `CancellationException` rethrown. (5) The manifest names its three components
**namespace-relatively** (`.NoteTagApp`, `.MainActivity`, `.nfc.NfcDispatchActivity`) and
`MainActivity.EXTRA_MESSAGE` is built from `BuildConfig.APPLICATION_ID`, so the binding test's claim
of no second copy of the identity *including in the manifest* is now literally true and asserted
(`theManifestNamesNoComponentByItsPackageRoot`). (6) `tags.json` is **excluded from Android's cloud
backup and from device-to-device transfer** by `res/xml/data_extraction_rules.xml`, because
`allowBackup="false"` alone would have let a transfer carry the local map to a new phone and
contradict what the README says a `LOCAL_REF` tag is. (7) `FsyncRename` **fsyncs the containing
directory** after the atomic rename (target §4.9, amended in the same round): without it the rename
itself can be lost to a power cut although the bytes were durable. Six smaller items travelled with
them: the toast removed from the copied `LinkLauncher` (NoteTag speaks on the card, not in a toast),
"49 bytes as an NDEF message" in the README, no `!!` on `writtenAt` in the list row,
`CancellationException` rethrown before the pre-write persist's catch-all, a `values-night`
platform theme so a dark cold start does not flash the light ground, and the two list chips made
non-interactive labels so a screen reader does not announce them as buttons — the words unchanged.

The scoped re-review of that round found every item addressed and one residual worth recording:
on the interim adapter's `NdefFormatable` path the format tap persists a `LOCAL_REF` row that the
second tap never confirms or forgets, because that tap plans a fresh uuid. The row is unconfirmed,
so it is invisible in the list and irrelevant to resolution; it is parked to Phase G, which
replaces that adapter (ledger ruling R1).

**Identity, read off the built debug APK** (`aapt2 dump badging`, build-tools 36.0.0):
`package: name='com.loosecannon.notetag' versionCode='3' versionName='2.0'`,
`application-label:'NoteTag'`, and **one** launchable activity,
`com.loosecannon.notetag.MainActivity`. The version numbers are past the 2024 app's 2 / "1.1", so
an installed build updates rather than collides.

**Merged manifest.** Read from
`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`:
exactly **one** `NDEF_DISCOVERED` filter, with `android:path="/com.loosecannon.notetag:tag"`
resolved from the single Gradle-owned value; **no** `TECH_DISCOVERED` filter and no
`nfc_tech_filter` resource; **no** `android:scheme="notetag"` anywhere (ratified P4 — the scheme is
reserved for #6/#36 and declares nothing); zero occurrences of the retired identity. The only
extra `uses-permission` is AGP's `com.loosecannon.notetag.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
self-scoped to this applicationId.

**The C9 binding.** `TagIdentityBindingTest` (JVM, 6 tests) and `TagIdentityDispatchTest` (emulator,
4 tests) both green. The identity is typed once in `app/build.gradle.kts`, the manifest carries only
the placeholder, and `BuildConfig.NDEF_AAR_PACKAGE` is **null** — NoteTag writes no Application
Record (O13), asserted in the binding test and in the build script's own text.
`queryIntentActivities` for `vnd.android.nfc://ext/com.loosecannon.notetag:tag` resolves to exactly
one activity, this package's `NfcDispatchActivity`
(`ourExternalTypeResolvesToOurDispatchActivity`); the retired
`com.loosecannon.notenfc:md5_short` and the sibling `com.loosecannon.servicetag:tag` resolve to
nothing of ours (`theRetiredExternalTypeResolvesToNothingOfOurs`,
`theSiblingExternalTypeResolvesToNothingOfOurs`), and `notetag://` resolves to nothing of ours
either (`thereIsNoNoteTagUrlSchemeToResolve`).

**Ambient NFC as the normal read path.** `AmbientDispatchDeviceProofTest` (emulator, **6 tests**).
The intent is **implicit** — the action, `EXTRA_NDEF_MESSAGES` and the data URI
`vnd.android.nfc://ext/<externalType>` built from `BuildConfig`, with no component and no class —
so the chain that actually ran is:

> `startActivity` → **the merged manifest's one `NDEF_DISCOVERED` filter** resolves it →
> `NfcDispatchActivity` → real `NoteTagCodec` → `ResolveTap` → real `JsonFileTagStore` →
> `MainActivity`'s result card.

The six rows, each asserting one sentence verbatim: a `JOPLIN_NOTE` tap reaches the launcher and is
told no app can open `joplin://x-callback-url/openNote?id=…`, with the id in the 32 lower-case hex
the codec re-renders; a sibling record carrying a plausible 18-byte ServiceTag body is answered
"This tag belongs to ServiceTag, not NoteTag."; a `LOCAL_REF` with no mapping on a fresh install is
answered "This tag was written on another phone, so this phone cannot open it."; a `javascript:`
link is refused by `LinkLaunchPolicy` before the launcher, with an `ActivityMonitor` proving **no**
`ACTION_VIEW` left the process (and the same monitor then counting a probe `ACTION_VIEW`, so the
zero is a refusal and not a blind spot); our own type with a one-byte body is "This NoteTag tag is
unreadable (payload is 1 bytes, header needs 3)."; and an intent through our own filter carrying
**no** `EXTRA_NDEF_MESSAGES` is "Nothing to resolve." — the trampoline's null-records path, added
with `26baa46`, the same commit that makes the outcome guard rethrow `CancellationException` so a
cancelled tap stays cancelled instead of being reported as an unreadable tag.

**Record size.** A `JOPLIN_NOTE` message is **49 B** — 3 B of record framing, the 27-byte external
type `com.loosecannon.notetag:tag`, and a 19-byte body (`version | kind | flags` + the note's 16 id
bytes) — pinned in `:core` by `NdefSizeTest` and `NoteTagCodecTest.aJoplinNoteRoundTripsAsOneRecordOf49Bytes`.
`NdefSizeDeviceTest` (emulator, 4 tests) is the pin that matters: `NdefSize.serialisedSize` is
compared against `NdefMessage.toByteArray().size` for the 49 B message, for a 255-byte payload and
for a 300-byte payload — across the short-record boundary where the length field grows from one
byte to four — and for a two-record message. Green on the emulator, so the planner's off-device
arithmetic is the platform's. Capacity is message bytes against the tag's measured `Ndef.maxSize` —
**for the `Ndef` path**: no TLV allowance, no character count. On the interim `NdefFormatable` path
there is **no measured `maxSize` and so no comparison at all**: the planner treats that first tap as
unmeasured and the writer's own pre-write check is the only refusal, until Phase F/G's
`format(null)` two-step measures the tag before writing it. The controller now treats that path's
unverified result as "hold it again", never as a write.

**The store and the LOCAL_REF invariant.** `JsonFileTagStore` is one JSON file rewritten whole
behind a `Mutex` and replaced atomically (temp file → `fsync` → `ATOMIC_MOVE` rename → `fsync` of
the **containing directory**, so the rename has its own durability barrier, with the temp file
deleted if any step fails and the directory sync wrapped because some filesystems refuse to open a
directory as a channel); P19 holds — no Room. The file is `tags.json` in `filesDir`, and it is
**excluded from Android's cloud backup and from device-to-device transfer** by
`res/xml/data_extraction_rules.xml`: `allowBackup="false"` governs only the cloud on targetSdk 36,
and a transfer that carried the map to a new phone would contradict the README's statement that a
`LOCAL_REF` tag does not survive a move. `TagEntry.writtenAt` is nullable and
carries the invariant: a `LOCAL_REF` mapping is persisted **before** the write, so `get` resolves a
retained entry (a live tag may exist), while `list` — the write history the UI shows — returns only
confirmed entries. **Retained is not written.** The three failure injections, in
`NoteTagWriteControllerTest` (JVM): `aFailedWriteRetainsTheLocalRefMappingUnconfirmed` (the write
fails after the mapping was persisted: the entry stays, `writtenAt` stays null, and it is absent
from the history), `aStoreThatCannotKeepTheMappingWritesNothingToTheTag` (the store refuses first:
0 write attempts, nothing on the tag, no entry) and
`aTooSmallTagRemovesTheMappingNoBytesCanHaveReachedIt` (the writer's own pre-write capacity check
refuses: the mapping is removed, because no bytes can have reached the tag). Portable kinds —
`JOPLIN_NOTE` and `URI` — never require the store at all.

**Sibling isolation, both ways.** ServiceTag's direction is `NdefEnvelopeIsolationTest`
(`core/src/test/kotlin/com/loosecannon/servicetag/core/nfc/NdefEnvelopeIsolationTest.kt`, Phase D).
NoteTag's direction is `NoteTagCodecTest.aServiceTagRecordIsForeignEvenWithAPlausibleBody`: a
`com.loosecannon.servicetag:tag` record whose body would parse perfectly as a ServiceTag body is
`Foreign("tnf=4 type=com.loosecannon.servicetag:tag")` **before any body parse happens**, and the
description names the type rather than guessing at damage. On the device the same claim is
`AmbientDispatchDeviceProofTest.aServiceTagRecordIsNamedAsServiceTags`, whose sentence is "This tag
belongs to ServiceTag, not NoteTag." (§23: named, not called damage), with
`TagIdentityDispatchTest.theSiblingExternalTypeResolvesToNothingOfOurs` proving a sibling *tag*
never reaches us through the filter in the first place.

**Suites** (at `9ff1d65`, after `./gradlew clean`): `:core:test` **78** tests in 9 classes —
`LinkLaunchPolicyTest` 11, `NdefEnvelopeTest` 7, `NdefSizeTest` 5, `OverwriteWordingTest` 9,
`ResolveTapTest` 14, `JsonFileTagStoreTest` 12, `JoplinIdTest` 4, `NoteTagCodecTest` 10,
`WritePlannerTest` 6; `:app:testDebugUnitTest` **26** in 3 classes — `TagIdentityBindingTest` 6,
`MainViewModelTest` 4, `NoteTagWriteControllerTest` 16; `:app:connectedDebugAndroidTest` **19** in
5 classes on the emulator (`ANDROID_SERIAL=emulator-5554`) — `AmbientDispatchDeviceProofTest` 6,
`NdefSizeDeviceTest` 4, `TagIdentityDispatchTest` 4, `AppSmokeTest` 3,
`WriteScreenDeviceBoundTest` 2. **0 failures, 0 errors, 0 skipped** in all three. The same command
line also built `:app:assembleDebug`, `:app:assembleRelease` and `:app:compileDebugAndroidTestKotlin`.
**No instrumented run touched the phone in this phase**: every connected task was pinned to
`emulator-5554`.

**Clean checkout.** `git clone --no-local` from the repository into a scratch directory that never
held the project; the clone's `git rev-parse --short HEAD` is **`9ff1d65`**, the same commit every
number above was measured at. Then, with the Gradle build cache disabled so the suites really ran
there, `:core:test :app:testDebugUnitTest :app:assembleDebug` — 78 and 26 tests green in the clone
and `app-debug.apk` produced. The clone was deleted afterwards. The Phase D caveat reproduces:
`local.properties` is gitignored, so a fresh clone needs `ANDROID_HOME` supplied out of band.

**Signing.** `:app:assembleRelease` produces `app-release.apk` — **signed**, not
`app-release-unsigned.apk` — because the signing config's `~/.config/notenfc/keystore.properties`
exists on this workstation with all four values. Release signed with the existing key —
fingerprint **matches** the record in `docs/design/phase-1a-evidence.md`, compared without
printing, not reproduced. The comparison wrote both digests to files, normalised them and returned
one word; neither digest was printed, echoed or passed as an argument, and the NoteTag README points
at that record rather than reprinting it.

**The look.** The owner's blue palette, read off the finished icon pack and approved 2026-09-17
(derived from Joplin's palette, not a copy of it). Seven tokens come off the pack — **Frost**
`#F4F7FB`, **Paper** `#FFFFFF`, **Azure** `#1F5FA8`, **Navy** `#0B3A6E`, **Slate** `#2B3038`,
**Mist** `#8A96A6` and **Sky** `#5DA6F5` — with four derived from them for contrast (`Steel`,
`SkyTint`, `Ink`, `Haze`). Both Material 3 schemes, **light and dark**, are built from those
tokens, with no dynamic colour; `res/values/colors.xml` names six of them for the platform side
(the window background). The launcher mark itself appears as a **watermark**: the adaptive icon's
foreground layer, at 7 % alpha, bottom-end aligned and clipped behind both screens, decorative
only. The first attempt (`5687fae`, ivory/amber/umber) was replaced by the owner's revision
(`9e30bb5`) in the same task; no screen and no sentence changed in either.

**Caveat, stated once.** Everything above is **debug-build and emulator evidence** — the only
release-build claim in this section is the signing one, and it is a build-and-verify claim, not a
device row. **No physical tag was touched**: the emulator has no NFC radio, so every tap is a
synthetic `NDEF_DISCOVERED` intent through the real filter, activity, codec, store and screens.
The NDEF adapter under the codec (`NdefBridge`, `TagWriter`, including `NdefFormatable.format(message)`
at `TagWriter.kt:98`) is an **interim copy of ServiceTag's**, and that shape is superseded by
`nfc-tag-core` in Phase F/G. The two screens' visual check — palette, watermark, light and dark —
was made by screenshot during Task 9b; the screenshots were deleted and are not evidence here.

**Not attempted in this phase, and why.** Adopting `nfc-tag-core` is §A.2 row 10 and belongs to
**Phase G**, so the format, the codec and the NDEF adapter live in this repository for now. CI on a
runner is **§B.4** — there is no remote yet, and the same Gradle line that CI will run gated every
task locally. The physical session is still owed: a real tap, the measured `Ndef.maxSize`, a
capacity refusal, a read-back and a lock all need a radio and an NTAG213.

**Phase E local reconstruction complete; Gate 6 pending its deferred prerequisites (row 10 /
Phase G, §B.4 CI, the physical session).**

## Phase F — nfc-tag-core extraction (§A.3)

Same header discipline as Phases D and E — fingerprints only, no secrets, no physical-device ids,
no owner paths. There is nothing to fingerprint in this phase: no release build was made and no
signing key was touched anywhere in it; the only device id below is `emulator-5554`.

**Commits.** The library repository, `~/Documents/Projects/AndroidStudioProjects/nfc-tag-core`,
branch `master` (F-4, re-read at FINAL), stands at FINAL `4ca1a1e` — its whole history,
**11 commits** (`git rev-list --count HEAD`). Composition: eight task commits (`d5b76f1`
skeleton, `08e80a7` nfc-core envelope/identity/records, `cdb223d` nfc-core uuid bytes/overwrite/size
arithmetic, `419a129` nfc-android bridge/reader mode/writer, `243cefd` the forbidden scan,
`a24aded` the README, `3e4a5c1` CI, `cdd86c6` the emulator suite), one review-fix round that landed
inside Task 6's own close (`8355d45`, correcting the README's invariant-proof overclaim), and one
fix round after the whole-branch review of the finished library (`fb45aa0`), and one README-only
correction after the scoped re-review of that round (`4ca1a1e`, the two lines the re-reviewer named:
the `TagWriter.kt` provenance row and invariant 8's proof line), which is FINAL. FINAL differs from
`fb45aa0` by that one commit alone — `git diff --stat fb45aa0..4ca1a1e` prints `README.md | 7 ++++---`
and nothing else — so the suites, the clean clone and the connected run recorded below, all measured
at `fb45aa0`, stand for `4ca1a1e`; the in-repo gates (`./gradlew build` with lint, the scan, both
provenance loops) were re-run at `4ca1a1e` and are green.
`git ls-files | wc -l` is **43**. No remote, no tag: `git remote | wc -l` and `git tag | wc -l` are
both `0`; nothing has ever been pushed. `GonzRon/nfc-tag-core` exists on GitHub, empty and public,
created 2026-09-17 by owner authorization, and was not touched in this phase.

**Layout as built.** Two Gradle modules under one root (`rootProject.name = "nfc-tag-core"`):
`nfc-core` — `org.jetbrains.kotlin.jvm`, package `com.loosecannon.nfc.tagcore`, toolchain 17, no
Android plugin, 7 main files — and `nfc-android` — `com.android.library` **only**, with no
`kotlin-android` plugin declared anywhere in the build scripts or the catalog (AGP 9.4 carries
Kotlin itself), `namespace = "com.loosecannon.nfc.tagcore.android"`, `compileSdk = 37`,
`minSdk = 26`, 5 Kotlin files (6 tracked, with the manifest), taking `api(project(":nfc-core"))`
and having no other runtime dependency. `nfc-core`'s runtime classpath is stdlib-only:
`./gradlew :nfc-core:dependencies --configuration runtimeClasspath` prints one top-level entry,
`\--- org.jetbrains.kotlin:kotlin-stdlib:2.4.20`, with nothing beneath it but the stdlib's own
`org.jetbrains:annotations:13.0`. Its `src/main`'s only imports, across all seven files, are
`java.nio.ByteBuffer` and `java.util.UUID`.

**Suites at FINAL.** `nfc-core` — **50** tests across six JVM classes: `EnvelopeLimitsTest` 5,
`NdefEnvelopeTest` 20, `NdefSizeTest` 5, `OverwritePolicyTest` 8, `TagIdentityTest` 5,
`UuidBytesTest` 7. `nfc-android` unit — **10** across three classes: `TagReadTest` 2,
`TwoTapFakeTest` 2, `WriteRouteTest` 6. `nfc-android` connected — **10** on `emulator-5554` across
two classes: `NdefBridgeDeviceTest` 9, `NfcReaderModeSessionDeviceTest` 1. **0 failures, 0 errors,
0 skipped** everywhere. FINAL moved in this round, so the connected suite was re-run against it;
its XML is `nfc-android/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 17.xml`
(the file name carries a literal space), written 47 seconds after the `fb45aa0` commit itself.

**Clean clone.** `git clone --no-local` into a throwaway scratch directory that had never held the
project; `git rev-parse --short HEAD` there is `fb45aa0`, FINAL. `ANDROID_HOME` supplied out of
band (a fresh clone has no `local.properties`). `./gradlew build --no-build-cache --console=plain`
reports `BUILD SUCCESSFUL`, with no `FROM-CACHE` anywhere in the log — every task, the test tasks
included, really ran there. `bash tools/forbidden-scan.sh` there reports `forbidden-scan: clean`.
The clone's test-result XML reproduces the same 50-and-10 totals, class for class, `failures="0"`
on every file. The clone was deleted afterwards.

**The forbidden scan.** `tools/forbidden-scan.sh`'s `WORDS` pattern is target §4.4's list,
verbatim, with one word appended and nothing removed or loosened: `FLAG_READER_SKIP_NDEF_CHECK`,
so invariant 4 is a standing build gate rather than a one-off grep. The scanned paths are now
`nfc-core/src`, `nfc-android/src`, `settings.gradle.kts`, the root and both module
`build.gradle.kts` files and `gradle/libs.versions.toml` — proven in scope by planting one
forbidden word in each of the four newly covered files and watching the scan name it and exit 1.
`tools/forbidden-scan.allow` carries **0** entries — nothing has ever been allow-listed — and its
matching rule is now the `path:fragment` form its header always documented: an entry is split at
its first colon, the path must match a hit's path exactly, and the fragment must appear in the hit's
line as a fixed substring. Both halves were proven with throwaway fixtures (a hit; the entry
suppressing it; a wrong path and an absent fragment both failing to suppress it) and the fixtures
were deleted before the commit. The scan is wired into `check` only in the library's own root
`build.gradle.kts` (a `forbiddenScan` task, `tasks.named("check") { dependsOn(forbiddenScan) }`,
extended to subprojects) — invisible to a consumer app whose own root includes `:nfc-core` and
`:nfc-android` as subprojects of itself. `.github/workflows/ci.yml` is authored (scan, then both
unit suites, then `assembleDebug`) but has never run: there is no remote to push it to.

**Provenance.** The README's provenance table carries **24 rows**: the eighteen files carried out of
the two consumers, plus six with no antecedent — `WriteRouteTest.kt`, `TwoTapFakeTest.kt`,
`FakeTagIo.kt`, `TagReadTest.kt`, `NfcReaderModeSessionDeviceTest.kt` and `TestActivity.kt`.
Re-running the hash-resolution loop from Task 6 Step 2 against the finished tree —
`git cat-file -e "$h^{commit}"` against every 7-hex-digit hash the README quotes, in the ServiceTag
worktree, falling back to the NoteTag repository — printed nothing: every hash in the table resolves
in one of the two repositories. The file loop, as amended in the plan on 2026-09-17 to read only the
table's NEW-file column (the "Copied from" columns name ServiceTag and NoteTag files by design, and
are never in this tree), also printed nothing: every library file the table's 24 rows name (25 files; one row carries two)
exists. The unscoped form of that loop, which Task 6's report explained, is retired.

**The four amendments, as ruled — and as verified at FINAL.** F-1 **accepted**: `WriteResult.Failed`
carries the folded `cause`, read at FINAL as `Failed(reason: String, cause: Throwable? = null,
attempted: Boolean = true)` — the third parameter is this round's A2, below. F-2 **accepted as
amended**: `NdefSize` is in `nfc-core`, and its `require(records.isNotEmpty())` and refusal test both
ran green at FINAL (`NdefSizeTest` 5). F-3 **ownership accepted, API revised to two-stage**:
`TagInspection.route()` (no message size) → `WriteRoute.Format` / `ReadOnly` / `Writable(maxSize)`,
then `Writable.fit(needed)` → `CapacityVerdict.Write` / `TooSmall`, all six cases green at FINAL
(`WriteRouteTest` 6). F-4 **accepted**: `git rev-parse --abbrev-ref HEAD` at FINAL is `master`, and
the post-push default-branch verification is recorded at runbook §B.1. The design/runbook amendment
landed on `product-split` as `9d92ffd`; a later docs correction, `5300f1d`, brought target §2 into
agreement that the branch is `master`.

**After the whole-branch review.** One fix round, `fb45aa0`, closed the review's actionable findings
(its scoped re-review passed every item and named two README lines, corrected in `4ca1a1e`; an
empty-fragment allow entry that would blanket-suppress a file is parked to v0.2.0 in the README);
it is FINAL and every number above is measured at it. **A1 (C1 + I8)** replaced `TagInspection`'s
`existingRecords: List<NdefRecordData>` plus `unreadable: String?` with one field, `read: TagRead`,
a sealed interface of `Readable(records)` and `Unreadable(reason, cause)`, so a consumer's `when`
cannot read an unreadable tag as an empty one and the platform exception survives for its log; the
new `TagReadTest` asserts that shape and `WriteRouteTest` shows an `Unreadable` inspection still
routes `Writable(maxSize)`, since `route()` never consulted readability. **A2 (I1)** added
`Failed.attempted`: `false` on the two pre-radio refusals (`write` on a tag that still needs
formatting, `format` on one already formatted), meaning nothing on the tag can have changed; `true`
— the default every folded exception keeps — meaning the radio was reached and the effect is
indeterminate. **A3 (I2)** made the lock unblind: `TagWriter.lock(tag, expected)` re-reads the tag
and compares it structurally before `makeReadOnly()` is reachable at all, which turns invariant 9
from a KDoc promise into code; `TagIo.lock` and `RealTagIo` follow, and `FakeTagIo` now records
`lastLockExpected` and `lastWriteLock`. **A4 (I4/P4, I5, I7)** strengthened the scan: the new word,
the documented `path:fragment` allow matching, and the widened paths, all as described above.
**A5 (M1)** gave `NdefRecordData` a `toString` that prints its type as text and its payload as a
length instead of two array identities, with one JVM case asserting the exact string. **A6 (M4)**
made `FakeTagIo`'s default `writeResult` a refusal no real writer returns
(`Failed("fake: no write result configured", attempted = false)`) instead of a `Written` it could
never produce. **A7 (M8)** states in `TagWriter.write`'s KDoc that an empty record list throws
`IllegalArgumentException` out of `toNdefMessage()` before any tag I/O — "never throws" is about tag
I/O only. **A8 (I3, I4, M5, M6)** rewrote the README: the invariants renumbered to target §4.3's
1–9 and 13 exactly, with 9 restored to "lock last, never blind" and its proof now the code, 6 stated
as a consumer-side invariant the library cannot test, 4 proven by the source flag set plus the scan's
standing gate rather than by the device test, `TwoTapFakeTest` described as the template a consumer's
fake follows rather than as a proof, the new API shapes and the `Failed`-as-map-key warning, the six
NEW provenance rows, target §6.2's settings snippet, and a `Deferred to v0.2.0` section.

**A1–A3 change ratified §4.2 API shapes.** `TagInspection`'s field list and constructor order,
`WriteResult.Failed`'s parameter list, and `TagWriter.lock`/`TagIo.lock`'s signature are all
ratified text in target §4.2, so the three are recorded here as **controller amendments pending the
owner's ratification**, not as silent drift. **Zero consumers are affected**: neither app has taken
the library yet (Phase G, §A.4), so the amendment lands before the first consumer compiles against
any of the three.

**Parked by the review, deferred to v0.2.0 and listed in the README.** I9 a `presenceCheckDelayMs`
/ extras parameter on `NfcReaderModeSession`; M9 `check` and `lint` in the CI task list; M10
`NfcAdapter.getDefaultAdapter`'s deprecation; I6 anchoring the prose-prone scan words such as
`compose` and `navigation` (the design forbids weakening the pattern and names the allow file as the
remedy, which is why A4 made that file's matching rule work as documented); M7 the root script's
`subprojects { … }` configuration of sibling `check` tasks, against isolated projects; P6 the empty
manifest's omission of the NFC permission; and P7 the two library-shaped needs underneath
`TagWriteSession` — refusal-versus-indeterminate and a lock that captures what it verified — which
A2 and A3 have now met, so the session that eventually arrives will not have to invent either.

**Phase E residuals.** R1–R4 are dispositioned in the README's "Consumer obligations carried from
Phase E" section — each made representable or visible by the library, with the acting on it still
owed by each consumer. Its fifth bullet no longer asks a consumer's fake to capture what the
library's own fake would not: `FakeTagIo` records `lastWriteLock` and `lastLockExpected`, and the
bullet says a consumer's fake should record the same two.

**Not attempted in this phase, and why.** No consumer repository was touched — ServiceTag-split and
NoteTag are unchanged except this evidence commit. No tag was cut: `nfc-tag-core-v0.1.0` waits for
§B.1. No push and no `gh` command ran in either repository; the empty, owner-authorized
`GonzRon/nfc-tag-core` remote stayed untouched. The physical rows of runbook §D are untouched —
everything above is emulator and JVM evidence.

**Post-close, pre-publish (owner verdict 2026-09-17: Phase F ACCEPTED; A1–A3 RATIFIED as the v0.1.0
API; two hardening items before §B.1).** The Phase F endpoint stays `4ca1a1e`; the nine tasks are not
reopened. One owner-directed hardening commit follows it on `master` — `15f1cd5` "hardening: bad allow
entries fail loud, a self-test proves it, ci runs the whole build" (six files, +197/−15, **no Kotlin
file touched**, so the public API is byte-identical to `4ca1a1e`): `.github/workflows/ci.yml` now runs
`bash tools/forbidden-scan.sh`, then `bash tools/forbidden-scan-selftest.sh`, then **`./gradlew build
--console=plain`** — the same standalone build (check, lint, both unit suites, the debug aar) the local
acceptance proof ran, so remote CI is no longer weaker than the local gate; still no connected step.
`tools/forbidden-scan.sh` validates the allow file *before* scanning and fails loudly with **exit 2**
on an empty path, an empty fragment or a colon-less entry (`forbidden-scan: bad allow entry <n>:
'<entry>' (<why>)`, then `forbidden-scan: allow file invalid`), so a `path:` entry can no longer
blanket-suppress a file; the `WORDS` pattern is unchanged and the allow file still has **0** entries.
`tools/forbidden-scan-selftest.sh` (committed, executable) proves the contract in a `mktemp -d`
scratch tree with eight cases — no entries → 1; exact `path:fragment` → 0; empty fragment → 2 with no
`clean` line; empty path → 2; no colon → 2; wrong path → 1; a `FLAG_READER_SKIP_NDEF_CHECK` fixture
→ 1; no fixtures → 0 — and is wired into Gradle `check` as `forbiddenScanSelfTest`, a root-script-only
`Exec` task beside `forbiddenScan`, so `./gradlew build` runs both. The README's deferred section is now
`## Deferred` and follows the owner's rulings: "prose false positives" is not a deliverable (the pattern
is never weakened; a concrete case gets the narrowest `path:fragment` allowance); the NFC permission
stays consumer-owned and is off the list (intent filters, dispatch identity and product exposure are the
consumer's, unconditionally); the presence-check delay is deferred on evidence, not scheduled; the CI
and empty-fragment items are done. Gates at `15f1cd5`, re-run by the controller: scan clean; self-test
8/8; a live empty-fragment probe → exit 2 (file restored); 12 commits; clean tree; 0 remotes; 0 tags.
Scoped review of this commit alone precedes §B.1.

**§B.1, executed 2026-09-17 (owner authorization after the hardening review).** The hardening review
(Approved) named one Important — self-test case 7 could not tell the flag word apart from the word
fixture — and one Minor — a missing allow file gave a raw shell error; both were fixed by the
controller in `7e0377a` "selftest: case 7 stands on the flag alone, and a missing allow file is an
error" (case 7 now fails against a scanner copy with the flag word stripped; a tree without the allow
file exits 2), and `./gradlew build` was green locally at that commit before anything left the
machine. Then, in the runbook's order: `origin` added; `master` pushed (`7e0377a`, 13 commits from the
empty tree); GitHub's default branch read **`master`** without a PATCH (the placeholder `main` was
dropped on the first push); the first Actions run — https://github.com/GonzRon/nfc-tag-core/actions/runs/35265501042 — completed **success** on every step:
checkout, JDK 17, setup-android, setup-gradle, `forbidden-knowledge scan`, `forbidden-scan
self-test`, `standalone build (check, lint, both unit suites, the debug aar)`, upload-artifact. Only
then was the tag cut: **`nfc-tag-core-v0.1.0`**, annotated, on `7e0377a`, pushed; `git ls-remote
--tags origin` and the tags API both list it at that commit, and `git describe --exact-match --match
'nfc-tag-core-v*' --tags HEAD` resolves. The tag is **provisional** (target §10.3, runbook §B.1):
final only once both Phase G consumers are green against exactly it; until then it may be deleted
and re-cut. Not done: no noteNFC rename, no app push, no issue migration, no phone, no physical NFC.
The programme stops here at the **Phase G planning gate**.

**Phase F local extraction complete; §B.1 done — `nfc-tag-core-v0.1.0` is cut and pushed, provisional until Phase G.**

## Phase G — both apps consume nfc-tag-core (§A.4)

Same header discipline as Phases D, E and F — fingerprints only, no secrets, no physical-device
ids, no owner paths. Nothing is fingerprinted below: no release build was published, no signing key
was generated, touched or rotated, and the only device id anywhere in this phase is
`emulator-5554`. Two release **dry runs** ran locally; they are quoted by their verdict lines and
exit codes alone, which is all either script prints about signing identity by design.

**The two FINALs**, as of the end of the 2026-09-18 fix round. The plan's release `7cf0e1a` is the
**base** of this phase, not one of its commits, so it is counted from and never counted in.
ServiceTag stands on `product-split` in `~/Documents/Projects/AndroidStudioProjects/ServiceTag-split`
at **the fix round's closing evidence commit** — the one carrying this paragraph, which is the tip
and so cannot name its own sha — **twenty-five commits after the base**; its **last code commit is
`2a19226`**, and only plan, design and evidence text follows it, so every suite below stands for `2a19226`
as well. NoteTag stands at **`01d001f`** on `master` in
`~/Documents/Projects/AndroidStudioProjects/NoteTag` — **nine commits after Phase E's FINAL
(`9ff1d65`)**, all implementation. ServiceTag's eleven implementation commits are `773e356`
(wiring), `490e72e` (`:core`), `556b859` + `a9afc32` (`:app` and its review fix round), `1d5c27e`
(CI and the pin script), `b7ca574` + `b596254` + `6896562` (the release workflow, its dry-run
hardening, the action pins), `836f1b3` (WS-1, the consent-wording pin), `dee0ee5` (the whole-branch
fix round) and `2a19226` (the owner's wording rulings); its fourteen docs commits are six plan
amendments (`1e5a6a9`, `82b059f`, `01f75e7`, `2064b19`, `4872f1c`, `9fe1756`), four design/runbook
amendments (`753550b`, `1fc423f`, `08447d6`, `63f8d46` — the last also carrying the fix round's
evidence), one plan-and-design catch-up after the fix round (`677e9f1`) and three evidence commits
(`5a0998a`, `13af50a` and this one). NoteTag's eight are `b055028`
(wiring), `7458229` (`:core`), `804f560` + `32eaec1` (`:app` and its fix round), `62f6e37` (CI),
`fb68a4c` (the release workflow), `0928422` (the whole-branch fix round), `6749d0a` (the
UNREADABLE wording ruling) and `01d001f` (the format-failure log made unconditional, the K/L
pre-flight fix the owner ordered on 2026-09-18 so both apps log the reason the same way). ServiceTag's `origin` is
still `GonzRon/noteNFC` and nothing was pushed; NoteTag has **no remote at all** (`git remote |
wc -l` is `0`) and no tags; ServiceTag's only tag is still `pre-split-checkpoint`.

**One tag, two apps.** Both repositories pin the library at the same gitlink —
`git ls-tree HEAD libs/nfc-tag-core` names `7e0377ac99d7a4fee95ca6b88551daaa6330e52f` in **both** —
and that commit is `nfc-tag-core-v0.1.0`: `git describe --exact-match --match 'nfc-tag-core-v*'
--tags HEAD` inside `libs/nfc-tag-core` resolves to the tag in both, and `git submodule status`
prints ` 7e0377ac99d7a4fee95ca6b88551daaa6330e52f libs/nfc-tag-core (nfc-tag-core-v0.1.0)` with no
`+`/`-` marker in both. Each `.gitmodules` names the read-only fetch URL
`https://github.com/GonzRon/nfc-tag-core.git` and carries **no branch line**, so the working tree is
detached at the tag and can never drift onto a branch. `bash tools/check-submodule-pin.sh` prints
`nfc-tag-core-v0.1.0` then `submodule pin ok: nfc-tag-core-v0.1.0` and exits `0` in both apps and in
both clean clones. The `agp`/`kotlin` catalog diff against the library's own catalog is empty in
both. Nothing under `libs/` was modified in either app at any point in the phase, and
`git -C libs/nfc-tag-core status --short` is empty in both even after four emulator builds wrote into
`libs/nfc-tag-core/nfc-android/build/` — the submodule's own `.gitignore` covers it.

**How the library is wired (G-1).** One settings block per app includes the two library modules as
subprojects of the app build; `:core` takes `implementation(project(":nfc-core"))` and `:app` takes
`implementation(project(":nfc-android"))` — never `api` — and the root build script declares
`alias(libs.plugins.android.library) apply false` beside the application plugin, because AGP's
application and library plugins must share the root plugin classpath. That root line is the one
design defect this phase found: target §6.2 showed only the settings side, and §6.2 now carries both
the `:core → :nfc-core` edge and the root declaration (`08447d6`). No `repositories { }` block and
no `includeBuild` anywhere; the library is source, pinned by commit, not an artifact.

**ServiceTag — what was deleted and what replaced it.** `:core` lost
`core/…/core/nfc/TagIdentity.kt` and `core/…/core/nfc/OverwritePolicy.kt` (the library owns both
types now) and two test classes, `TagIdentityTest.kt` and `OverwritePolicyTest.kt`, plus
`NdefCodecTest.kt`, whose three envelope-gate cases are the library's own behaviour and whose two
body cases (`onlyFirstRecordMatters`, `uriRecordIsForeign`) moved into `NdefCodecV1Test`.
`NdefCodec.kt` was rebuilt over the library's `NdefEnvelope`/`UuidBytes` — it keeps the body layout
(`version | flags | UUID`) and `TagPayload` with `V1`/`NewerVersion`/`Foreign`/`Malformed`/`Empty`,
and no longer defines `NdefRecordData` or the type gate — and a new
`core/…/core/nfc/OverwriteReasons.kt` carries the product's sentences over the library's
`OverwritePolicy`. `:app` lost all three of its NFC adapter files —
`app/…/servicetag/nfc/NdefBridge.kt`, `NfcReaderModeSession.kt` and `TagWriter.kt` — so
`app/…/servicetag/nfc/` now holds exactly one file, `NfcDispatchActivity.kt`, and `:app` owns no NFC
adapter code at all. `TagWriteController.kt` was rewritten onto the library's
`TagIo`/`TagHandle`/`NfcTagHandle`/`RealTagIo` (an `object`, taking no codec) and its
`TagInspection`/`TagRead`/`WriteResult`; `WriteState.Verifying`, `awaitingVerify`, the `PendingWrite`
holder and `verify()` are gone with the unverified-format path that needed them. The constraint
greps at FINAL: `git grep -nE 'class (NdefBridge|TagWriter|TagInspection|NfcReaderModeSession|RealTagIo)|interface (TagIo|TagHandle)|object (NdefSize|OverwritePolicy)' -- 'app/**' 'core/**'`
→ **0**; `git grep -n 'servicetag.nfc.\(NdefBridge\|TagWriter\|NfcReaderModeSession\|TagInspection\|WriteResult\)' -- 'app/**' 'core/**'`
→ **0**; `git grep -n 'Verifying\|awaitingVerify' -- 'app/**'` → **0**.

**NoteTag — what was deleted and what replaced it.** `:core` lost the four interim copies
`core/…/core/nfc/NdefEnvelope.kt`, `NdefRecordData.kt`, `NdefSize.kt` and `TagIdentity.kt` and two
test classes, `NdefEnvelopeTest.kt` (7 cases) and `NdefSizeTest.kt` (5); `NoteTagCodec.kt` now calls
the library's `NdefEnvelope.decode`/`encode` and moves `LocalRef` bytes through `UuidBytes`, dropping
its `ByteBuffer` and `UUID` imports with its parse and body logic otherwise unchanged;
`OverwriteWording.kt` keeps every sentence and routes the decision through the library's
`OverwritePolicy.decide` via a new `existingContent(c: NoteTagContent): ExistingContent` mapping;
`WritePlanner.kt` and `ResolveTap.kt` only retarget imports. `:app` lost all four interim adapter
files — `app/…/notetag/nfc/TagIo.kt`, `TagWriter.kt`, `NdefBridge.kt` and
`NfcReaderModeSession.kt`, each of which had carried the `Interim copy of ServiceTag's …; Phase G
replaces it with nfc-tag-core` header — so `app/…/notetag/nfc/` also holds exactly one file,
`NfcDispatchActivity.kt`. `NoteTagWriteController.kt` rides the library seam, and `SMALL` moved
8 → 60 bytes because `fit()` now runs before consent rather than inside the writer. The constraint
greps at FINAL: `git grep -n 'Interim copy'` → **0** (the interim era is over);
`git grep -nE 'class (NdefBridge|TagWriter|TagInspection|NfcReaderModeSession|RealTagIo)|interface (TagIo|TagHandle)' -- 'app/**' 'core/**'`
→ **0**; `git grep -n 'nfcTag()' -- 'app/src/main/**'` → **0**; `git grep -n 'UNMEASURED' -- 'app/**'`
→ **0**. Every Phase E binding sentence is still in place, each in its own file — the device-bound
warning, "Written · This phone only", "Saved as a this-phone-only tag.", "This phone only", and the
two sibling refusals ("This tag belongs to ServiceTag, not NoteTag." in `ResolveTap`, "This tag
belongs to ServiceTag." in `OverwriteWording`) — and retained is still not written.

**The four Phase E residuals, dispositioned in code, with the test names as written.** **R1** (an
orphan mapping per formatable tag) is closed by construction in both apps: the `WriteRoute.Format`
branch returns before any planning or provisioning. NoteTag's
`aFormatableTagIsFormattedAndNothingIsPlannedOrPersisted` asserts `formatCount == 1`,
`writeAttempts == 0`, `minted == 0` and an untouched store on the format tap; ServiceTag's
`aFormatableTagIsFormattedOnTapOneAndWrittenOnTapTwo` asserts `provision.begun == 0` then `1` — the
row is provisioned on the **first writable tap**, never on a format-only tap (owner correction 2) —
and `aTagThatIsNotNdefAtAllIsRefusedAndProvisionsNothing` asserts zero rows in the database. **R2**
(the fakes model the second tap) is the same two cases' second halves, run over the library's own
`FakeTagIo` with a mutable inspection flipped from `needsFormat` to `Writable(maxSize)`; NoteTag's
second tap plans against `maxSize = 60`, falls to `LOCAL_REF`, mints exactly one uuid and stops at
`Confirm([DEVICE_BOUND], "Write")`, and the case now runs on through `confirm()` and a third tap.
**R3** (an honest sentence) is G-5's wording, asserted character for character as a whole state
value in each app — **ServiceTag as a whole `WriteState.Idle` value**, "Formatted. Lift the tag off
and hold it again to write."; **NoteTag as a whole `WriteState.Waiting` value**, "Formatted the tag.
Hold it to the phone again to write the link." (the two apps park a formatted tag in different
states, which is why the assertion is against a different type in each). **R4** (the read exception) is
`catch (e: CancellationException) { throw e }` ahead of the broad catch in both controllers, with
`Log.w` on the caught exception, on `WriteResult.Failed.cause` on both the write and the format path
and on `TagRead.Unreadable.cause` — five such sites in NoteTag — pinned by
`aTagThatCannotBeReadIsOneFixedSentenceAndTheNextTapIsStillHandled` (NoteTag) and
`aTagThatCannotBeReadIsOneSentenceAndTheNextTapStillWorks` (ServiceTag). The library's two
Phase F amendments are consumed as intended: **C1** — `existingOn()` maps `TagRead.Unreadable` to
the product's `Malformed(reason)`, never `Empty`, so an unreadable tag is a question
(`anUnreadableTagIsAQuestionNotAnEmptyTag` in NoteTag, `anUnreadableTagAsksBeforeItIsOverwritten` in
ServiceTag, both with `writeAttempts == 0`), and ServiceTag applied the same mapping to
`ScanViewModel.classify` so the read path cannot call an unreadable tag empty either; **I1** —
`Failed.attempted`, never the reason text or the exception class, decides retain-versus-remove in
NoteTag and the wording in both, pinned by `aRefusedWriteRemovesTheMappingAnIndeterminateOneRetainsIt`
(NoteTag) and `aRefusedWriteAndAnIndeterminateWriteAreWordedDifferently` (ServiceTag), whose two rows
differ **only** in `attempted` and whose `attempted = false` reason text is deliberately one a reader
might have pattern-matched on.

**Suites at the two FINALs — JVM, per module.** ServiceTag: `:nfc-core:test` **50**,
`:nfc-android:testDebugUnitTest` **10**, `:core:test` **349** in 33 classes,
`:app:testDebugUnitTest` **235** in 34 classes, of which `TagWriteControllerTest` is **16**. NoteTag:
`:nfc-core:test` **50**, `:nfc-android:testDebugUnitTest` **10**, `:core:test` **70** in 7 classes
(`LinkLaunchPolicyTest` 11, `OverwriteWordingTest` 11, `ResolveTapTest` 14, `JsonFileTagStoreTest`
12, `JoplinIdTest` 4, `NoteTagCodecTest` 11, `WritePlannerTest` 7), `:app:testDebugUnitTest` **29**
in 3 classes (`TagIdentityBindingTest` 6, `MainViewModelTest` 4, `NoteTagWriteControllerTest` 19).
**0 failures, 0 errors, 0 skipped** in every XML file in both apps. Against the baselines:
ServiceTag's `:core` moved 354 → 349 and its `:app` unit suite 225 → 235 (Phase D at `b4b016b`);
NoteTag's `:core` moved 78 → 70 (`78 - 12 + 4`: the two deleted library-owned classes' 12 cases out,
four new cases in) and its `:app` unit suite 26 → 29 with the controller class 16 → 19 (Phase E at
`9ff1d65`). The library's own **50 + 10** run identically under each app's catalog, which is what
"one tag, two apps" means at the suite level.

**Suites at the two FINALs — connected, on `emulator-5554` only.** ServiceTag
`:app:connectedDebugAndroidTest` is **68 tests in 14 classes** against the **Phase D baseline of 66
in 13** — the difference is exactly WS-1's new 2-test class. NoteTag is **19 tests in 5 classes**,
**equal to the Phase E baseline of 19 in 5** (`AmbientDispatchDeviceProofTest` 6,
`NdefSizeDeviceTest` 4, `TagIdentityDispatchTest` 4, `AppSmokeTest` 3,
`WriteScreenDeviceBoundTest` 2). The library's own emulator suite ran under **each** app root as
well: `:nfc-android:connectedDebugAndroidTest` is **10 tests in 2 classes**
(`NdefBridgeDeviceTest` 9, `NfcReaderModeSessionDeviceTest` 1) under ServiceTag and the same 10
under NoteTag. All four runs were serial, ServiceTag first, at the final commits, with 0 failures,
0 errors and 0 skipped everywhere; the XML timestamps are 2026-09-18T00:22–00:23Z. G-7 kept
NoteTag's `NdefSizeDeviceTest` as the product's own 49-byte pin rather than leaning on the library's
generic one, and it is 4 of the 19 above.

**The ratified sentences are asserted on the device (WS-1).** Correction 1 made ServiceTag's
`confirmOverwrite()` record consent and perform no tag I/O, which made two screen strings wrong:
they still told the user to keep holding the tag while answering. The owner ratified their
replacements exactly as written — "Answer here, then hold the same tag to the phone again." and
"After you confirm, hold the same tag to the phone again to write." — and, on the owner's
recommendation, `836f1b3` added `WriteTagScreenConsentWordingTest`, an emulator Compose class whose
two cases (`theConfirmStateTellsTheUserToLiftAndRetap`,
`theConfirmStateNeverAsksTheUserToKeepHoldingTheTag`) assert both production strings are rendered in
the Confirm state and that "Hold the tag to the phone while" is rendered **zero** times. Its only
production change was two `private` → `internal` words on sibling composables; the sentences
themselves were not touched by the test. At FINAL, `git grep` finds each ratified sentence in
`WriteTagScreen.kt` and in that test, and the superseded string nowhere under `app/src/main/`.
Consent itself is pinned by three JVM cases: `confirmingRecordsConsentAndPerformsNoTagIo`
(`writeAttempts == 0`), `theNextTapWithTheSameContentWritesThroughTheFreshHandle`
(`io.lastWriteHandle === fresh`) and `theNextTapWithDifferentContentAsksAgain` (consent consumed and
discarded, the question re-asked, then honoured on the next matching tap).

**G-6 in code: the lock rides the write.** ServiceTag's only lock is `io.write(tag, intended,
wantLock)`; there is no `io.lock` call site in `:app` at all. `lockIsAppliedByTheWriteItself`
asserts `lastWriteLock == true` **and** `lastLockExpected == null`, and
`formatThenWriteRecordsTheUidAndNeverLocksBlind` asserts `lockCalls == 0` across a format tap and a
locking write tap. NoteTag proves the same negative in `aWrittenLocalRefIsRetainedAndConfirmed`
(`io.lockCalls == 0`, `lastLockExpected` null). The library's `lock(tag, expected)` stays available
and unused by either app. Invariant 7 is `writable.fit(NdefSize.serialisedSize(records))` before any
consent, pinned by `aTagTooSmallEvenForTheLocalRefIsRefusedBeforeAnything` (NoteTag) and
`aTagTooSmallForTheMessageIsRefusedWithoutWriting` (ServiceTag); single flight by
`twoTapsBeforeTheFirstCompletesInspectOnce` and `aThirdTapAfterAWrittenResultIsDropped`.

**The pin script, proven by four negative tests in each app.** `tools/check-submodule-pin.sh` is
byte-identical in both repositories and each app demonstrated all four failures and restored the
tree afterwards. **(a) missing submodule**: `libs/nfc-tag-core is missing or uninitialised.` /
`Clone with --recurse-submodules, or run:  git submodule update --init --recursive`, from
`./gradlew projects` with one library build script moved aside. **(b) wrong commit**: with the
submodule at `HEAD~1`, `submodule is at 15f1cd5b0327bbb8cee4212ffbf497c3711d981f but this commit
pins 7e0377ac99d7a4fee95ca6b88551daaa6330e52f`, exit `1` — the sha-equality check speaks before the
tag-exactness check, which is the plan's amended expectation (`82b059f`) rather than the message its
first draft named. **(c) dirty submodule**: `submodule working tree is dirty`, exit `1`, after one
stray file. **(d) catalog alias removed**: a configuration-time script compilation error naming the
alias, `Line 3:     alias(libs.plugins.android.library) apply false` / `Unresolved reference
'library'`. After all four, in both apps, `git -C libs/nfc-tag-core status --porcelain` was empty and
`git submodule status` was back at ` 7e0377ac99d7a4fee95ca6b88551daaa6330e52f libs/nfc-tag-core
(nfc-tag-core-v0.1.0)`, every time.

**Clean clones — twice, in each app.** At Tasks 4 and 8, `git clone --recurse-submodules` of each app
into a session scratch directory fetched the submodule from its GitHub URL, checked it out at
`7e0377ac…` = `nfc-tag-core-v0.1.0`, passed the pin script, and ran the CI task list with
`--no-build-cache`: ServiceTag `BUILD SUCCESSFUL`, `78 actionable tasks: 78 executed`; NoteTag
`BUILD SUCCESSFUL`, `76 actionable tasks: 76 executed`; `grep -c 'FROM-CACHE'` **0** in both, so
every task, tests included, really ran there, and the clones' XML sums reproduced the in-place
totals module for module (ServiceTag 50/10/349/235; NoteTag 50/10/70/29, `159` in total).
**Repeated at this phase's close, at the two FINALs**: the same clone of `08447d6` and of `fb68a4c`
into `<scratch>/task12/`, `submodule pin ok: nfc-tag-core-v0.1.0` in each, then
`./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest
:app:assembleDebug --no-build-cache --console=plain` → ServiceTag `BUILD SUCCESSFUL`, `78 actionable
tasks: 78 executed`; NoteTag `BUILD SUCCESSFUL`, `76 actionable tasks: 76 executed`; `FROM-CACHE` **0**
in both; XML sums again 50/10/349/235 and 50/10/70/29 with 0 failures, 0 errors, 0 skipped; a debug
APK built in each. A fresh clone of either app, plus `ANDROID_HOME`, is a working build.

**The release workflows: authored, validated, dry-run, never triggered.** Both apps now carry
`.github/workflows/release.yml` beside the unprivileged `ci.yml`. Each runs **only** on its product
tag namespace — `'servicetag-v*'` in ServiceTag, `'notetag-v*'` in NoteTag — under the `release`
environment, with `contents: write` and nothing more, and the two files differ in exactly three
marked lines (the tag pattern, `APP_DIR`, `APK_BASENAME`). Re-validated at the two FINALs by loading
each file with a YAML parser: the top-level keys are `name`, `on`, `permissions`, `jobs`, the `on`
key is the **string** `on` and not the boolean YAML 1.1 would make of a bare `on:` (owner
correction 4 quotes it as `'on':`), `on.push.tags` is `['servicetag-v*']` and `['notetag-v*']`
respectively, and the job's `environment` is `release` in both. All four external actions are
**pinned to commit shas** with their version in a trailing comment (`actions/checkout@11d5960a…`
v4.4.0, `actions/setup-java@cf277c60…` v4.9.1, `android-actions/setup-android@9fc6c4e9…` v3.2.2,
`gradle/actions/setup-gradle@ed408507…` v4.4.3), on the owner's ruling that a privileged workflow
must not ride mutable tags; `ci.yml` was deliberately left on its tags. The tag-at-HEAD check is the
robust form the owner ruled, `git tag --points-at HEAD --format='%(refname:short)' | grep -Fxq --
"$GITHUB_REF_NAME"`, not `describe --exact-match`, so a doubly-tagged HEAD cannot slip past it. The
job fails closed before the release build on missing signing material, and after it on an unsigned
APK, a certificate that differs from the expected fingerprint, a submodule not at an exact
`nfc-tag-core-v*` tag, a failing gate, or a tag whose version differs from the built `versionName`
(G-4: NoteTag ships `versionName = "2.0"`, so its product tag is **`notetag-v2.0`**, not the
runbook's earlier `notetag-v1.0`, amended everywhere in Task 11). `umask 077` runs before any key
file is created, and the keystore is written outside every cached path and removed in an
`if: always()` cleanup. **Neither workflow has ever run**: no product tag exists in either
repository — ServiceTag's only tag is `pre-split-checkpoint` and NoteTag has none — and neither app
has been pushed.

**The two dry runs, as the scripts printed them.** `tools/release-dry-run.sh` is the local,
no-secrets equivalent, and it prints `matches`/`differs`/`BLOCKED` and never a fingerprint, a
password or a keystore path on any path. NoteTag, at `fb68a4c`: exit **0**, `version: 2.0 matches
2.0`, and the verdict line `RELEASE DRY RUN: PARTIAL — signing identity not independently checked` —
**PARTIAL, never PASS** (owner correction 4), because no expected fingerprint is configured on this
machine, so the compare was skipped rather than passed. ServiceTag, at `08447d6`: exit **3**, verdict
`BLOCKED: no signing material (target §8)` — the expected result, since the ServiceTag signing key
does not exist yet; generating, backing up and restore-testing it is the owner's manual task 1 and
the only thing standing between ServiceTag and the same PARTIAL. Both verdicts are what the plan
predicted for this phase; neither is a finding.

**What the owner still has to provision, and when.** G-3 fixes the names now so the workflows can be
written before the secrets exist: the GitHub environment **`release`**, holding the four secrets
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`,
plus the **public repository variable** (not a secret) `RELEASE_CERT_SHA256`, the certificate
fingerprint the post-build identity check compares against. None of the five was provisioned in this
phase, in either repository. They are the owner's manual task 2, **at K/L**, and by a later owner
ruling that `release` environment is **protected** — required reviewers and a tag-protection rule —
so its secrets stay withheld until the protection rule passes (runbook §B.6, target §8, amended in
`1fc423f`).

**The seven rulings, as ruled.** **G-1 ACCEPT**: each app's `:core` takes `:nfc-core` as well as
`:app` taking `:nfc-android`, since both body codecs, the overwrite consumers, `WritePlanner` and
`ResolveTap` live in the pure-JVM `:core` modules — real in both apps' `core/build.gradle.kts` and
now in target §6.2. **G-2 ACCEPT as amended**: the release job materialises the existing local
signing mechanism on the runner rather than changing Gradle, and a runner without the secrets
**fails before the release build starts**. **G-3 ACCEPT, provisioned at K/L**: the names above.
**G-4 ACCEPT**: `notetag-v2.0`, with every target and runbook occurrence of `notetag-v1.0` amended
and the historical Phase E/F evidence text annotated rather than rewritten. **G-5 ACCEPT, pinned by
test**: the two formatted sentences quoted above. **G-6 ACCEPT in principle with correction 1**:
ServiceTag locks only through `write(lock = true)`, and consent is recorded, never written through
the sheet's stale handle. **G-7 ACCEPT**: NoteTag keeps its own 49-byte device pin. The four owner
corrections that reshaped the plan before Task 1 are all in the code: consent-only
`confirmOverwrite` (three tests), the row on the first writable tap and never on a format-only tap
(prose and two tests), `umask 077` before any key file with missing secrets failing ahead of the
build, and the quoted `'on':` with a YAML assertion on the string key — together with the rule that
the dry run reports PARTIAL and never PASS when the fingerprint compare is skipped. The later owner
rulings — sha-pinning the privileged workflow's actions, `git tag --points-at HEAD`, the two
ServiceTag write-screen sentences ratified verbatim with an on-device assertion, and the protected
`release` environment at K/L — are each in place as described above.

**The caveat this phase shares with D, E and F.** Everything above is **debug-build, JVM and
emulator evidence**. No physical tag was touched, no `adb` command addressed anything but
`emulator-5554`, and the phone was never a target — the instrumented suites wipe app data, which is
why. No release APK was built or published, no signing key was generated, and no GitHub secret or
variable was set. Nothing was pushed: ServiceTag's `origin` still points at the pre-rename
`GonzRon/noteNFC` and NoteTag has no remote at all. The rename (§B.2), the `--no-ff` merge of
`product-split` into `master` (§B.3a), NoteTag's first push (§B.4), the issue moves (§B.5) and the
URL clean clones with the second-workstation proof (§B.6) are owner-authorized steps after this
phase, as is the first execution of either `release.yml`. **Gate 6 (NoteTag §23 end to end) and
gate 10 are not claimed here**: both need K/L, the phone and the physical sessions, and nothing in
this section should be read as evidence for either.

**Fix round after the whole-branch review (2026-09-18).** The whole-branch review of the finished
phase returned **no blocking finding** — the branch stands as released — and a short list of
correctness, hardening, test-strength and documentation items; **not one of them changes a sentence
shown to a user**. **A1**: ServiceTag's single-flight flag became an `AtomicBoolean` claimed with
`compareAndSet` instead of a `@Volatile` read-then-write, so two binder threads tapping at once can
no longer both enter (`sheetOwnsBusy` unchanged — the sheet still owns the flag until
`confirmOverwrite`/`keepIt`). **A2**: NoteTag's `confirm()` and `cancel()` open with
`val p = pending ?: return`, so a stray answer after a verified `Written` is a no-op instead of an
emission. **A3**: both controllers' broad `onTag` catch is `catch (e: Exception)` — NoteTag's
`Throwable` narrowed, the `CancellationException` rethrow still first — logging
`Log.w(TAG, "tap failed", e)`, because the catch covers the whole tap and not just the inspect.
**A4**: both `release.yml` files and both `tools/release-dry-run.sh` scripts now require
`apksigner verify --print-certs` to yield **exactly one** `SHA-256 digest` line before the
fingerprint compare, so a multi-signer APK cannot pass on its first signer, and `certs.txt` joins
the `if: always()` cleanup. **A5**: both checkouts carry `persist-credentials: false`
(`submodules: recursive` and `fetch-depth: 0` unchanged). On the documentation side: §6.3 no longer
claims the pin assertion is a Gradle `check` dependency and records the ruling that it must not
become one (`check` has to run without the network the script's tag fetch uses); the §6.3 snippet
points at `tools/check-submodule-pin.sh`, the script the apps actually call; §9's opening now says
**the three `ci.yml` workflows** and points at §8 for `release.yml`'s SHA pins; invariant 11 records
**both** ratified single-flight mechanisms (ServiceTag transfers busy-ownership to the sheet, NoteTag
releases the flag and re-asks) with the identical outcome that is the invariant's point; §4.7 records
that the `TagWriteSession` promotion criterion was evaluated at the first side-by-side diff and not
met; and the runbook's physical gate gains one pre-existing, **ServiceTag-only** row — the two
reader-mode sessions across a nav transition — explicitly outside the 12-action budget.

**The fix round re-proved, at `dee0ee5` (ServiceTag) and `0928422` (NoteTag).** Both gates —
`:nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug
:app:compileDebugAndroidTestKotlin` — are green with **0 failures, 0 errors, 0 skipped** in every
XML file. Every JVM figure in "Suites at the two FINALs" above was re-measured and stands unchanged
except NoteTag's, where the new M2 case lifts `:app:testDebugUnitTest` **29 → 30** and
`NoteTagWriteControllerTest` **19 → 20**; that case fails on the pre-fix controller (it is the one
that proved A2 red before green) and passes after it. The wording rulings below then moved
ServiceTag's `:app:testDebugUnitTest` **235 → 237** in **34 → 35** classes —
`TagWriteControllerTest` **16 → 17** for E1 and a new one-case `ScanViewModelTest` for E2 — while
`:core:test` stayed **349** in both apps' shared shape and NoteTag's `:core:test` stayed **70**
(E3 rewrote an existing assertion rather than adding one). The connected suites were re-run on
`emulator-5554` only, serial, ServiceTag first: ServiceTag **68 tests in 14 classes**
(2026-09-18T09:39–09:40Z) and NoteTag **19 tests in 5 classes** (2026-09-18T09:43Z), both 0/0/0 and
both equal to the figures above. Because those runs preceded the three wording commits, both suites
were run once more at the final code commits (ServiceTag `2a19226`, NoteTag `6749d0a`): ServiceTag
**68 in 14** at 2026-09-18T10:00:32Z and NoteTag **19 in 5** at 10:00:49Z, both 0/0/0, `emulator-5554`
only, both trees and submodules clean afterwards. The two dry runs are unchanged in verdict: ServiceTag exit **3**,
`BLOCKED: no signing material (target §8)`; NoteTag exit **0**, `version: 2.0 matches 2.0` and
`RELEASE DRY RUN: PARTIAL — signing identity not independently checked`. ServiceTag's new one-signer
assertion was additionally exercised through the script's self-test hook against the debug APK,
which has exactly one signer and passes it. The YAML assertion still loads each `release.yml` with
`yaml.safe_load` and reads the quoted `'on':` key as the string it is: `['push']` in both, with the
tag globs `servicetag-v*` and `notetag-v*`.

**Parked to the owner, not done here** (each raised by the whole-branch review and each left exactly
as it was): **M4**, **M11** and **M12**; the Task 2 message; the Task 3 minor arms; the Task 6 test;
and the Task 10 fetch flag. They are recorded so nobody reads their absence as an oversight.

**The owner's three wording rulings (2026-09-18), on the principle that user-facing text states what
we know and what the user should do while technical diagnosis goes to the logs.** **E1**: ServiceTag's
format failure said "Could not format the tag (${r.reason}). Hold it still and try again." and now
says NoteTag's exact text, "Could not format the tag. Hold it still and try again.", with the library
reason and the cause logged unconditionally as `Log.w(TAG, "format failed: ${r.reason}", r.cause)`
(a null cause is fine). **E2**: ServiceTag's scanner said "Couldn't read that tag
(${e.javaClass.simpleName}). Hold it still and try again." and now says "Couldn't read that tag.
Hold it still and try again.", with `Log.w(TAG, "read failed", e)` beside it carrying the class name
and the stack. **E3**: NoteTag's overwrite question said "This tag holds unreadable NoteTag content
(${d.detail})." and now says "This tag holds unreadable NDEF content (${d.detail}).", because
unparseable NDEF establishes nothing about whose content it is. Each is pinned by a JVM test — a new
failed-format case, the new `ScanViewModelTest`, and `OverwriteWordingTest`'s updated assertion — and
**no connected test asserts any of the three**, checked by grep over both apps' `androidTest` trees,
so the device numbers above stand unchanged and no emulator run was repeated for E. The gates for
these three commits were ServiceTag `:core:test :app:testDebugUnitTest :app:assembleDebug
:app:compileDebugAndroidTestKotlin` and NoteTag `:core:test :app:testDebugUnitTest
:app:assembleDebug`, both green with 0 failures, 0 errors and 0 skipped.

**Phase G local consumption complete; both apps green on nfc-tag-core-v0.1.0; K/L pending owner authorization**

## K/L — remote transition (§B), 2026-09-18

**Pre-flight (the rename gate), all held before any remote mutation.** ServiceTag `product-split` clean at
`cd6de14` (last code commit `2a19226`); NoteTag `master` clean at `01d001f` — one commit after Phase G's
FINAL, the owner-ordered logging-consistency fix (both apps log the format-failure reason unconditionally;
plan `7796f67`, file == block, `NoteTagWriteControllerTest` 20/0); both gitlinks `7e0377a` =
`nfc-tag-core-v0.1.0`; recovery refs on the remote — the annotated tag `pre-split-checkpoint` and the branch
`pre-split-master` — both resolving to `ac523d7`, which was also the remote `master`; no product release tag
anywhere; the pre-rename snapshot of `GonzRon/noteNFC`: public, not a fork, issues on, default `master`,
36 issues, one untagged draft release, no webhooks, one workflow, last run green.

**§B.2 rename.** `GonzRon/noteNFC` → `GonzRon/ServiceTag`. Preserved and verified under the new name:
visibility, not-a-fork, issues, default branch, all 36 issues, the one tag, the draft release, 33 CI runs,
both recovery refs at `ac523d7`; the old path redirects on the web and through the API. The About became the
owner's sentence for the product ("…turns NFC tags into durable handles for the physical things you
maintain…"), topics gained `asset-management` and lost the three note-product names.

**§B.3/§B.3a.** Local origins repointed; `product-split` merged into `master` with `--no-ff` as `ed83fb5`
(parents `ac523d7` and `cd6de14`) and pushed. **The first run on the runner was red, and is kept as
evidence:** run 35333943779 checked the submodule out recursively, asserted the exact pin (`submodule pin ok:
nfc-tag-core-v0.1.0`), set up the build, and did not run `release.yml`; one unit test of 237 failed with
`UncompletedCoroutinesError` after its 60 s budget — a Phase D-era ViewModel test whose Room instance ran its
queries on real `Dispatchers.Default` threads while Main was an unconfined test dispatcher. The fix was
test-side only, in four rounds on `master`: `dfcffd1` (the ordering test saves once; the double-save guard is
its own case, `aSecondSaveWhileTheFirstIsStillInFlightIsDropped`, `save(); save()` with no scheduler
advancement between them, gated so the second provably lands in flight, proving one persisted profile and one
emission — the +1 that makes 238), `080fd80` (the shared test database takes the test's context: Main stays
`UnconfinedTestDispatcher(scheduler)`, Room runs on `StandardTestDispatcher(scheduler)` on the **same**
`TestCoroutineScheduler`, so a launched save runs eagerly to its first database hop, suspends there
deterministically, and completes only while the test awaits; Room 3.0.3's query context governs suspend DAO
calls and both transaction helpers), `4aace2c` (the last fixture seeds its asset inside the test scope
instead of a `runBlocking` in setup). Nine of nine Main-setting fixtures share the model; 238/0 three times
under one CPU; no production code changed; an independent review verdicted the owner's ten criteria. With
the approved README rewrite (`b8aa26f`, the product as it is now, the note links carried over and named as
NoteTag's future, Signing and Releases unchanged) the tip `4aace2c` was pushed once: run 35339701578
**green**.

**§B.4 NoteTag.** The reserved repository received `master` at `01d001f` (root `5fb6aed`); the placeholder
default `main` was patched to `master`; description, wiki off and topics set at the push. Run 35339904692 —
the first this lineage has ever had — **green** on a cold runner (2 m 44 s), pin assertion passing.

**Steps 9–11.** The `release` environment exists in both repositories: NoteTag's holds
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` and the
public repository variable `RELEASE_CERT_SHA256` (all piped from the local signing material; no value was
printed or recorded anywhere); ServiceTag's holds no secret, because no ServiceTag key exists (owner manual
task 1). Both environments require the owner's review and limit deployments to the product tag pattern;
both repositories carry an active ruleset restricting creation, update and deletion of `servicetag-v*` /
`notetag-v*` to repository admins. Verified on the remotes: each `ci.yml` is the pushed blob, names no
`secrets.*` and no environment; `release.yml` has run **zero** times in either repository; no product tag
exists.

**§B.5 issues.** `gh issue transfer` worked: #6 → NoteTag #1 (retitled "Generalize external note/deep-link
support beyond Joplin"), #36 → NoteTag #2 (retitled under the NoteTag name), backlinks both ways, bodies
intact, old URLs redirecting; ServiceTag holds 34.

**§B.6 clean clones, from the URLs, `--no-build-cache`, on this machine.** `nfc-tag-core` at `7e0377a`:
scan clean, self-test 8/8, `build` 73/73 executed, 50 + 10 tests. ServiceTag at `4aace2c`: pin ok, 78/78
executed, 50 / 10 / 349 / 238. NoteTag at `01d001f`: pin ok, 76/76 executed, 50 / 10 / 70 / 30. Nothing
served from cache; no absolute home path in any clone; the only device identifier in code is
`emulator-5554` inside NoteTag's smoke-test guard, which exists to keep the suite off the phone. **Open:**
the second-workstation proof for the two apps is the owner's, on another machine.

**What did not happen.** No `servicetag-v2.5`, no `notetag-v2.0`, no release workflow run, no phone
install, no physical NFC write, no uninstall of `com.loosecannon.notenfc`, no change to
`nfc-tag-core-v0.1.0`, no deletion of a recovery ref, no secret value in any file or report. One process
note carried from Phase G: the Task 12 reviewer's read-only breach (contained) is on record.

**K/L remote transition complete; ordinary CI green on all three repositories from GitHub; release workflows dormant; the second-workstation proof and the ServiceTag key are the owner's; next is the phone/data migration (§C).**

## §C — phone transition and the data migration proof (sequence H), 2026-09-18

**Builds and devices.** Phone: one physical device, Android 17 / API 37 (its identity is not recorded here). Emulator: `emulator-5554`, API 37, no NFC. Old package: `com.loosecannon.notenfc` 2.4 / versionCode 6, the debuggable build. ServiceTag: debug build of `master` — `4aace2c` for C.2–C.6, updated in place to `7390e44` (the Settings entry for Backup and restore; CI green) before C.9 with all data preserved. NoteTag: debug build of `master` `01d001f`. Everything in this section is **debug-build evidence**; the signed-release requirement is discharged separately, as a build-verified claim, in the release section that follows.

**The owner's on-phone actions**, outside the tag budget: Export set (through the old package's debug-only launcher entry — the same codec and SAF writer as the in-app screen), pick the folder, Restore data, Restore files, and one Export backup set from ServiceTag for C.6. Two runbook wordings corrected on the way: the in-app Backup screen is reached from the **Dashboard**, not Settings (and, from `7390e44`, also from Settings → Utilities → Backup and restore); `GonzRon/NoteTag` had been reserved empty, so B.4's create step was superseded.

| id | question | precondition | action | expected | observed | verdict |
|---|---|---|---|---|---|---|
| C.1 | is the exported set consistent and complete? | old package installed, data live | Export set; pull; verify | format 5 / schema 5; data sha == manifest; artifacts manifest paired, 8 entries hashed; counts ≥ the 2026-09-16 baseline | all true; counts equal the baseline except `nfcTags` 1 (was 0; one tag written on 2026-09-17, format V1) — accepted as the new baseline; set preserved in `noteNFC-backups/transition-20260918-075440/` with checksums and a read-only database + prefs snapshot | PASS |
| C.2 | can ServiceTag install beside the old package? | C.1 | install the debug APK | both packages present, both launch, old data untouched, ServiceTag's database empty | true (old database byte-size unchanged; `servicetag.db` fresh) | PASS |
| C.3 | does ServiceTag take its own grant on the same tree? | C.2 | owner picks the same folder | `attachment_tree_uri` equal to the old app's; exactly one persisted grant for the new package; 8 of 8 locators resolve | true; ServiceTag held **no** grant until its own picker ran | PASS |
| C.4 | does the data restore reproduce the eleven counts? | C.3 | Restore data (in-app path) | eleven counts; `last_restored_backup_set_id` = the set id; identity hash of schema 5; user_version 5 | all eleven exact (5 / 1 / 1 / 13 / 15 / 22 / 14 / 26 / 68 / 4 / 8); pref set; identity hash matches `5.json`; user_version 5. Note: the debug harness's importer does not record the pref and its file restore skips the set-id check — the proof used the in-app path | PASS |
| C.5 | is the same-tree artifacts restore idempotent? | C.4 | Restore files (in-app, pairing check active) | 8 already present, 0 written, 0 skipped | the 8 files hash exactly to the manifest and none was modified on the day (mtimes 2026-09-16) — nothing written, nothing skipped | PASS |
| **C.6** | **is the restored data the same data?** | C.5 | export from ServiceTag; compare with the preserved export | `data.json` equal but for the C.8 fields; eleven id sets equal; every relationship field equal; 8×3 attachment hashes equal | **`data.json` IDENTICAL** under `jq -S` (only `manifest.backupSetId`, `createdAt` and `appVersion` 2.4→2.5 differ, all C.8 fields); eleven id sets EQUAL; every field of every table EQUAL; the named relationship fields EQUAL (parent tree; tag asset-xor-link, format, key, status, uid; link asset, kind, uri; definition asset, kind, formula, sources; profile asset; profile fields' definitions; event asset, profile, occurredOn, occurredTime, tzId, createdAt, source, sourceRef; measurements; usages; attachment owner-xor, sha256, size, locator, provider, mode); XOR and both uniqueness constraints hold; **8 rows × 3 hash sides equal** (restored export = preserved export = artifacts manifest = the bytes at each locator; the ServiceTag artifacts archive's entries equal too) | **PASS** |
| C.7 | does the restore write bytes into an EMPTY tree? | emulator, ServiceTag installed | point the store at an empty folder; Restore data; Restore files; export; compare | 8 written, 0 already present, 0 skipped; every file at its store-relative locator; hashes equal; eleven tables equal | "Imported: 5 assets, 1 tags, 1 links · 8 attachments listed"; "Restored 8 files, skipped 0"; 8/8 sha256 equal with no extension normalisation; the export's `data.json` IDENTICAL to the preserved one | PASS |
| C.8a-1 | is a persisted grant scoped to the calling package? | emulator; ServiceTag + a `.spike` variant, one folder | each runs its own picker | each package lists only its own grant | observed | PASS |
| C.8a-2 | does one package's grant survive the other's uninstall? | C.8a-1 | uninstall the variant | ServiceTag's grant intact, files readable | observed | PASS |
| C.8a-3 | does a package that never ran its picker hold a grant? | C.8a-1, before the variant's picker | read its grants | none | observed: none | PASS |
| C.9 | can the old package be removed now? | C.6 and C.8a PASS | uninstall `com.loosecannon.notenfc` | ServiceTag still shows 5 assets, 26 events, 8 attachments; its grant survives | true: all rows present, all 8 files in the tree, exactly one package holds a grant on the tree and it is ServiceTag; the app launches | PASS — the only irreversible step, taken |
| C.10 | do both final products coexist? | C.9 | install NoteTag | both installed with distinct ids and labels; each launches; neither sees the other's data | true | PASS |

**Suites at the physical-gate builds, on the emulator.** ServiceTag `7390e44`: `:app:connectedDebugAndroidTest` **69 in 15 classes** (Phase G's 68 plus the Settings entry test), 0 failures; NoteTag `01d001f`: **19 in 5**, 0 failures. **No instrumented suite ran on the phone.** The live attachment folder was never deleted, relocated or rewritten. Rollback artefacts stayed in place throughout: the release-signed 2.4 / vc6 APK and the verified set.

## §D/§E (simplified by owner ruling) and the first releases, 2026-09-18

**The physical gate, as ruled on 2026-09-18.** The owner reduced the release-blocking physical gate to: one real tag written and read by each product, each product handling its own tag and refusing the sibling's with both installed, and the migrated data intact. Waived as blockers and recorded here as **unperformed, non-blocking** evidence items: a physically unformatted tag for the format-first branch (the owner's NTAG213 stock arrives factory NDEF-formatted, whose capability container is one-time programmable; the branch stays proven by the JVM and emulator tests); a third-party capacity measurement of `Ndef.maxSize` (the 95-byte ServiceTag record and the 49-byte NoteTag record both wrote and verified, which is the capacity evidence this release rests on); exact transcription of every tap's sentence; and the second-workstation clone proof (GitHub's clean runners already cloned recursively, resolved the exact pin, built and passed).

| id | question | precondition | action | expected | observed | verdict |
|---|---|---|---|---|---|---|
| T1 | does ServiceTag write, verify and lock a real tag? | ServiceTag 7390e44 debug, lock armed | hold a blank NTAG213 | write → read-back → lock | one activation; the row provisioned on the first activation (the tag was factory NDEF-formatted, so the Writable route, not Format); written after the lock's own re-read; no warning, no crash; the owner reports success | PASS |
| P1 | does NoteTag write a real tag? | NoteTag 01d001f debug | share a Joplin external link; hold T4 | written and verified | pass (owner) | PASS |
| P2 | does NoteTag's tag resolve ambiently? | both apps closed | hold T4 from the lock screen | NoteTag resolves; Joplin opens the note; no chooser | `NfcDispatcher: matched NDEF` → `com.loosecannon.notetag` → `ACTION_VIEW joplin://…`; no chooser | PASS |
| P3 | does ServiceTag write a second real tag? | a different asset | hold T2 | written and verified | a second `nfc_tag` row today on a second asset, written | PASS |
| P4 | does ServiceTag's tag resolve ambiently? | both apps closed | hold T2 | ServiceTag opens the asset; no chooser | `matched AAR to NDEF` → `NfcDispatchActivity` → `MainActivity`; no chooser | PASS |
| P5 | does each product refuse the sibling's tag? | both installed | T2 in NoteTag's writer; T4 in ServiceTag's inspect | a refusal naming the other product; Write over it / Cancel only; nothing written | both refusals as designed (owner); **observation:** ServiceTag's inspect screen released reader mode ~200 ms after its read with the tag still in the field, so the platform re-discovered the tag and dispatched it — correctly — to NoteTag, which opened the note. Not a wrong claim; a ServiceTag inspect-screen defect, filed as issue #37, non-blocking by ruling | PASS |
| P6 | is the migrated data intact after the tag work? | after P1–P5 | read the database and the tree | 5 / 26 / 68 / 8 / 1; 8 files; the grant held | true; `nfc_tag` now 3 rows, all written | PASS |

**The releases.** Tags cut at the exact green commits after the repository gate held (both masters equal to their remotes and green, both submodules at `nfc-tag-core-v0.1.0`, no prior product tag, both `release` environments complete and protected, both dry runs PASS against the GitHub fingerprint variables). Each `release.yml` run waited for the owner's environment review, then completed:

| product | tag → commit | run | asset | APK SHA-256 | signer SHA-256 (public) |
|---|---|---|---|---|---|
| ServiceTag 2.5 | `servicetag-v2.5` → `92d4123` | 35346929008, success | `ServiceTag-2.5.apk` 9,659,057 B + `.sha256` | `ce08aac0b5657880ba92a07be42aa8351235ddd7a67961d994208f484f72f7a0` | `894AFA766433B7DF28EAE3242694327EA21F24639A39EBCBE1C88A96EBF2F73C` |
| NoteTag 2.0 | `notetag-v2.0` → `01d001f` | 35346930915, success | `NoteTag-2.0.apk` 8,547,362 B + `.sha256` | `34ab847e52db61b1708ef4fdda5827def90a70a11d1a1549961cbf6354defda4` | `0902D3B0F826381905C6D8254FDAF36756924D80DA7C19B90A08930B33277A9F` |

Verified from a fresh download of each release: `apksigner verify` ok; **exactly one signer**; the signer's certificate equals the repository's public `RELEASE_CERT_SHA256`; `versionName` equals the tag; the published checksum equals the download; the ServiceTag signer is the new ServiceTag key, generated by the owner on 2026-09-18 and never the noteNFC key; the NoteTag signer is the existing noteNFC key. The job logs carry only masked secret values, no base64 blob, no keystore path contents. The library is unchanged at `7e0377a`; the recovery refs stand at `ac523d7`. The published APKs were **not** installed on the phone: the phone carries the debug builds the physical gate was run on, and the release install is an optional follow-up, not part of this proof.

**Both first product releases are published, cryptographically verified, and stand on a phone-proven migration and a phone-proven tag path.**

## ServiceTag 2.6 — the note-link functionality removed, the data shape kept (Option B), 2026-09-18

**Ruling.** Remove the legacy note-link product functionality from ServiceTag at the behavioural level; keep the `external_link` table and the format-5 `externalLinks` field as compatibility tombstones (no schema bump, no format bump, no row discard, no cleanup-only migration); one new user-facing sentence for a pre-split link tag; one label change ("Bind to asset" replaces "Bind to asset or note"); everything else byte-identical. Plan `docs/superpowers/plans/2026-09-18-servicetag-2.6-remove-note-links.md`.

**Commits on `master`**: `4b04720` (plan), `d41634a` (`:core`: a link target is a dead end; `OpenLink`/`SaveLink`/`DeleteLink`/`LinkLaunchPolicy` deleted; `LinkRepository` narrowed to what backup needs), `14ad4cb` + `1afd412` (`:app`: `ShareActivity`, the share flow, the Links screens, the `servicetag://link` host, the scan/dispatch launch branch and every link surface removed; the pre-split sheet; new tests), `6a25589` (README "Note links are NoteTag's", `versionCode` 8 / `versionName` 2.6, target §2/§5 and runbook §G), `5e38320` (`PreservedSetRestoreTest`), `769e879` (four review minors). Each task independently reviewed; one fix round; whole-branch review: every ruling item holds, no Critical, no Important. The release tag `servicetag-v2.6` points at `769e879`.

| proof | how | result |
|---|---|---|
| 1 no user-facing link/note feature reachable | greps over `app/src/main` + `core/src/main`; every entry point traced in the whole-branch review | 0 hits; no screen, action, sheet, menu item or route reaches a link |
| 2 no share intent or link deep-link handler registered | `RemovedSurfacesTest` on `emulator-5554` asks `PackageManager` for this package's handlers | 0 `ACTION_SEND text/plain`, 0 `servicetag://link`, 1 `servicetag://asset` |
| 3 ServiceTag cannot create a link-bound tag | `requireTargetExists` throws before any write; `Route.WriteTag("link", …)` refused at the nav boundary | `bindingALinkTargetIsRefusedAndWritesNothing`, `provisioningALinkTargetIsRefusedAndWritesNothing`, `aLinkKindedWriteRouteIsNotSupported` green |
| 4 old link-bound records/tags fail safely | `Resolution.PreSplitLink` → one sheet with the sentence and Cancel; both entry points | `ResolveTagTest` (an asset seeded under the link's id still yields the pre-split outcome), `TagResultViewModelTest` (the sentence byte-exact), `PreSplitLinkTagSheetTest` on the emulator |
| 5 existing data and attachments on the phone unchanged | baseline before the release install: 7 assets, 26 events, 68 measurements, 8 attachments, 3 tags, 1 tombstone link row; the release build's own export compared with the 2.5 export | `data.json` IDENTICAL; 8/8 attachment hashes; tree files unmodified; `externalLinks` still 1 |
| 6 format-5 backup/restore still passes | `PreservedSetRestoreTest` restores the real 2026-09-18 pre-split set on the emulator | every count equal to the preserved set's, `external_link` 1, `user_version` 5 |
| 7 ordinary CI green | run 35365353758 on `769e879` | success |
| 8 release signing dry run | `tools/release-dry-run.sh` against the public variable | `version: 2.6 matches 2.6`, `RELEASE DRY RUN: PASS` |

**Release.** `servicetag-v2.6` → `769e879`; run 35365619774 success after the owner's environment review; `ServiceTag-2.6.apk` 9,609,605 B, SHA-256 `053854d4e494b120763fe177057d7c0598cf392b191a7a283a157916a7f5de5c`; one signer, `894AFA766433B7DF28EAE3242694327EA21F24639A39EBCBE1C88A96EBF2F73C` == `RELEASE_CERT_SHA256`; `versionName` 2.6; checksum equals the download. **Smoke on the phone:** the 2.5 debug build exported its set, was uninstalled, the published 2.6 was installed and took its own folder grant, restored the data (7 assets, 3 tags, 8 attachments) and the files (all already present), and its own export is identical to the 2.5 export — the phone now runs the signed release build with the owner's data. Recorded, not fixed: the debug-only backup harness stays as a developer tool (#38 closed); `ExternalLinkDao.observeAll`/`observeForAsset` and `NfcTagDao.observeForLink`/`forLink` are unexercised tombstone queries to be named in the later data-contract cleanup; the pre-split sheet shares the "Tag detected" eyebrow with the asset sheet by the one-string budget; a typed-REPLACE prompt on an empty store is #40.

**ServiceTag 2.6 released; the note-link product is NoteTag's alone; the data contract of the migration proof stands unchanged.**

## ServiceTag 2.7 — the dashboard lists systems, the inspect screen holds reader mode, 2026-09-18

**Ruling.** Two owner requests after 2.6, nothing else: (#39) the dashboard hides component assets under their parent and gains a quick search that reaches components; (#37) one activity-owned reader-mode session, held while a tag-reading screen is on top, with the read answer drawn on the inspect screen so a read never disposes the screen that owns NFC. Four new user-facing sentences, ratified verbatim ("Search assets and components" / "Components are listed on the asset they belong to. Search to find one." / "Nothing matches that." / "Clear search"); every other string byte-identical to 2.6; `libs/nfc-tag-core` at `7e0377a`; no schema or format bump; version 2.7, code 9. Plan: `docs/superpowers/plans/2026-09-18-servicetag-2.7-dashboard-and-inspect.md` (`40b0a3c`).

**Commits on `master`**: `d9dc940` + `dedc4a7` (dashboard: `DashboardRow`, top-level-only with a blank query, `Asset.matches` over name/category/make/model/serial/location, `SearchBox`, the search field bound to a synchronous holder), `b568a00` + `040e5ff` + `6f0cec3` (one `ReaderMode` per activity in `ServiceTagRoot`, `Route.readsTags()`, the newest sink receives the tag, `TagResultSheet` drawn on `ScanScreen` under a per-read `ViewModelStore`, the binder-thread callback behind an `AtomicReference`), `616cf1a` (version, README, runbook rows), `6643d93` (back with an answer showing dismisses the answer, as 2.6 did). Every task had an independent review and a scoped re-review of its fixes; the whole branch had a final independent review (READY TO TAG; three should-fixes, fixed and re-reviewed). #37's root cause, from the code: the only two `stop()` sites were the two screens' `onPauseOrDispose` blocks, so pushing `Route.TagResult` ended `ScanScreen`'s `LifecycleResumeEffect` and released reader mode with the tag still in the field.

| proof | how | result |
|---|---|---|
| 1 unit gate from scratch | `--rerun-tasks` at `6643d93` | 648 tests, 0 failures (app 37 classes/256, core 30/332, nfc-core 6/50, nfc-android 3/10); `DashboardViewModelTest` 12/12, `ReaderModeTest` 6/6, `RouteTest` 3/3 |
| 2 connected suite on `emulator-5554` | preserved pre-split set staged so the format-5 restore executed | 79 tests, 0 failures, 21 classes; `DashboardSearchTest` 2/2, `ReaderModeHoldTest` 3/3, `ReadScopedSheetOwnerTest` 1/1, `InspectBackDismissesTheAnswerTest` 1/1, `PreservedSetRestoreTest` 1/1; every pre-existing class at its previous count |
| 3 structural facts | greps over `app/src`; guard-path diff; submodule status; `tools/check-submodule-pin.sh` | one `NfcReaderModeSession` construction site; zero adapter calls in `:app`; no navigation to `Route.TagResult`; `core`, `libs`, `app/schemas`, manifest unchanged; gitlink `7e0377a`; pin ok |
| 4 built artifact | `aapt2 dump badging` on the debug APK | `versionCode='9' versionName='2.7'` |
| 5 release signing dry run | `tools/release-dry-run.sh` with the public fingerprint | `RELEASE DRY RUN: PASS`, `fingerprint compare: matches`, `version: 2.7 matches 2.7` |
| 6 hygiene | the whole diff `40b0a3c..6643d93` (18 files, +1414/−84) | no device id, e-mail, home path, tag UID or note id; seven single-line commit subjects |
| 7 ordinary CI green | run 35377058891 on `6643d93` | success |
| 8 data on the phone unchanged across the upgrade | the owner's export on 2.6 before the install and on 2.7 after it, compared table by table and by attachment hash | `data.json` IDENTICAL; eleven tables EQUAL (7 assets, 3 tags, 1 tombstone link, 21 definitions, 19 profiles, 26 events, 8 attachments); 8/8 attachment hashes; manifest `appVersion` 2.6 → 2.7 |

**Release.** `servicetag-v2.7` (annotated, `9c168e5`) → `6643d93`; run 35378053882 success after the owner's environment review; `ServiceTag-2.7.apk` 9,609,605 B, SHA-256 `d5b4f704c6189e6276bbcbfab6faa41d031af91a05f74fb697236aded71f0891`; download checksum == published checksum; one signer; certificate `894AFA766433B7DF28EAE3242694327EA21F24639A39EBCBE1C88A96EBF2F73C` == the ServiceTag release signer of 2.5/2.6 (the repository variable was not readable by the workstation token at verification time; the release workflow's own comparison against it passed on the runner); `versionName` 2.7 == tag. Installed over the 2.6 release on the phone with `adb install -r` (same key, in place); the installed APK pulled back matches the verified download by checksum and signer.

**Physical rows (owner, 2026-09-18, on the 2.7 release):**

- **R1 — INVALID / NON-DIAGNOSTIC AS WRITTEN.** The activity-owned reader-session continuity is emulator-proven (`ReaderModeHoldTest`: the hold spans the tag flow and ends only when it is left). The physical procedure does not independently demonstrate that invariant: its outcome is contaminated by the inspect screen's handling of a bound tag. Retired in the runbook's §E row. Incidental finding: a ServiceTag-bound tag scanned in Read / inspect tag auto-opens the asset — undesirable inspect-mode behaviour, filed as **#41** for 2.8 (inline decoded tag, explicit "Open asset" action, ambient behaviour unchanged).
  *Supersession note (2026-09-18, added at 2.7.1): resolved by #41 in ServiceTag 2.7.1 — the inspect screen now names a bound tag and waits for "Open asset", so the hold is no longer released by an auto-open; the sentence above stays as the record of the released 2.7 behaviour.*
- **R2 — foreign-app coexistence: PASS.** While ServiceTag's Read / inspect tag was active, a NoteTag-written tag was read by ServiceTag and the app remained on the inspection screen; NoteTag did not launch and no chooser appeared. This is the scenario #37 described; #37 closed.

**ServiceTag 2.7 released; the dashboard lists systems and finds their parts by search; a deliberate inspect keeps NFC until the screen is left; the data contract of the migration proof stands unchanged.**

## ServiceTag 2.7.1 — an empty phone only confirms, and a deliberate inspect names a bound tag, 2026-09-18

**Ruling.** Two corrective changes released together: (#40) on a genuinely empty store — no assets, tags, events, attachments or tombstone link rows — Restore data asks a plain confirm instead of the typed REPLACE word, which stays unchanged whenever any row exists or the emptiness read fails; (#41) on Read / inspect tag a tag bound to a ServiceTag asset is named on the sheet with an explicit "Open asset" action instead of auto-opening, so the inspect screen stays on top and the activity-owned reader mode is held for the whole look; the ambient tap is unchanged. Six ratified strings and no other user-visible change: `Restore this backup?` / `This phone has no records yet, so there is nothing to replace.` / `Restore` / `Cancel` / `ServiceTag tag` / `Open asset`. **Versioning ruling (owner, before publication):** the repository adopts semantic `MAJOR.MINOR.PATCH` versioning (`docs/versioning.md`); this corrective release is **2.7.1** with `versionCode` 10, not 2.8 — the waiting 2.8 run was cancelled and its unpublished tag deleted before any GitHub Release existed; 2.5, 2.6 and 2.7 stand as 2.5.0, 2.6.0 and 2.7.0 without their tags being rewritten. Plan: `docs/superpowers/plans/2026-09-18-servicetag-2.8-restore-prompt-and-inspect.md` (`b91f275`, its header notes the rename).

**Commits on `master`**: `37e29e1` + `7eefcf1` (`core/usecase/StoreIsEmpty` over the five existing ports; `AppGraph.storeIsEmpty`; `BackupViewModel.isStoreEmpty()`; the plain dialog branch in `BackupScreen`, failing reads falling back to the typed dialog), `57ec88a` + `d2af3d2` (`TagResultSheet(inspecting = false)`, `ScanScreen` passing true, the inspect arm of `OpensAsset`; comments rescoped), `fc70783` (version, README, runbook R2), `584f99a` (2.8 → 2.7.1, `docs/versioning.md`). Every task had an independent review and a scoped re-review of its fixes; the whole branch had a final independent review (READY TO TAG, no blocking or should-fix findings); the rename commit had its own scoped review (clean).

| proof | how | result |
|---|---|---|
| 1 unit gate from scratch | `--rerun-tasks` at `584f99a` | 656 tests, 0 failures (app 37 classes/258, core 31/338, nfc-core 6/50, nfc-android 3/10); `StoreIsEmptyTest` 6/6, `BackupViewModelTest` 22/22 |
| 2 connected suite on `emulator-5554` | preserved pre-split set staged so the format-5 restore executed | 83 tests, 0 failures, 23 classes; `EmptyStoreRestorePromptTest` 2/2, `InspectNamesABoundTagTest` 2/2 (inspect names it; the default still auto-opens), `PreservedSetRestoreTest` 1/1; every 2.7 class at its previous count |
| 3 structural facts | greps; guard-path diff; submodule status; pin script | `TagResultSheet(` 3 production lines, `inspecting = true,` only in `ScanScreen`, none in the nav shell; `REPLACE_WORD` 4 and `Replace everything?` 1 in `BackupScreen.kt`; `StoreIsEmpty(` wired once; `libs`, the repository ports, schemas, manifest, `nfc/`, `MainActivity`, `ui/nfc` unchanged; `ScanViewModels.kt` comment-only; gitlink `7e0377a`; pin ok |
| 4 built artifact | `aapt2 dump badging` | `versionCode='10' versionName='2.7.1'` |
| 5 release signing dry run | `tools/release-dry-run.sh` with the published fingerprint | `RELEASE DRY RUN: PASS`, `fingerprint compare: matches`, `version: 2.7.1 matches 2.7.1` |
| 6 hygiene | the whole diff `b91f275..584f99a` (18 files, +775/−59) | no device id, e-mail, home path, tag UID or note id; six single-line commit subjects |
| 7 ordinary CI green | run 35394773906 on `584f99a` | success |

**Release.** `servicetag-v2.7.1` (annotated, `86d340f`) → `584f99a`; run 35394937562 success after the owner's environment review; `ServiceTag-2.7.1.apk` 9,609,605 B, SHA-256 `1d4b09c55be03bdc5a2a8c869c316633fea199815e3caf653c72e238eb328bf7`; download checksum == published checksum; one signer; certificate == `RELEASE_CERT_SHA256` (read from the repository at verification); `versionName` 2.7.1 == tag. Installed over the 2.7 release on the phone with `adb install -r` (same key, in place; the owner's 2.7 export of the same day is the pre-upgrade backup, no new export taken by the owner's ruling); the installed APK pulled back matches the verified download by checksum and signer; the phone reports 2.7.1 / 10.

**Physical rows.** None required as a gate: the foreign-tag coexistence (R2's first half) was proven on hardware at 2.7 and 2.7.1 does not touch that path. **R2, bound-tag half — PASS on hardware (owner, 2026-09-18, on the installed 2.7.1):** with Read / inspect tag open, a tag bound to a ServiceTag asset was read; nothing launched; the sheet named it as a ServiceTag tag with the asset listed and the Open asset button shown. Issues #40 and #41 closed.

**ServiceTag 2.7.1 released; the first release under the semantic-versioning policy; the data contract of the migration proof stands unchanged.**

## Version lineage reset — ServiceTag 1.0.0, 2026-09-20

**Ruling (owner, 2026-09-20).** With no external users, the 2.x public release numbers used during the product split and release-pipeline bring-up were development-era numbering. The supported public lineage restarts at **1.0.0** under the semantic-versioning policy (`docs/versioning.md`); Android's `versionCode` stays monotonic (the installed 2.7.1 is code 10, so 1.0.0 is **11**) so the upgrade is in place. Git history, schema and backup-format history, issue history, the signing identity and this evidence file are not rewritten; the 2.x sections above are historical evidence for pre-1.0 development releases.

**Inventory before deletion** (recorded, then archived beside the checkout in `noteNFC-releases/retired-servicetag-2.x/` — APKs, checksum files, release metadata, the annotated tag objects and their commits, with a manifest; every checksum verified):

| retired tag | tag object | commit | APK SHA-256 | published |
|---|---|---|---|---|
| `servicetag-v2.5` | `38caf04` | `92d4123` | `ce08aac0b5657880ba92a07be42aa8351235ddd7a67961d994208f484f72f7a0` | 2026-09-18 12:56Z |
| `servicetag-v2.6` | `6888819` | `769e879` | `053854d4e494b120763fe177057d7c0598cf392b191a7a283a157916a7f5de5c` | 2026-09-18 16:02Z |
| `servicetag-v2.7` | `9c168e5` | `6643d93` | `d5b4f704c6189e6276bbcbfab6faa41d031af91a05f74fb697236aded71f0891` | 2026-09-18 18:14Z |
| `servicetag-v2.7.1` | `86d340f` | `584f99a` | `1d4b09c55be03bdc5a2a8c869c316633fea199815e3caf653c72e238eb328bf7` | 2026-09-18 21:11Z |

No `servicetag-v2.8` tag or release existed (cancelled unpublished on 2026-09-18). The four GitHub Releases and their tags were deleted, remote then local, each by exact name; the four commits remain reachable on `master`. Untouched: the tag `pre-split-checkpoint` (→ `ac523d7`), NoteTag's `notetag-v2.0`, `nfc-tag-core-v0.1.0`, and a tagless 2023 draft release ("initial working", no assets, noteNFC-era) left for the owner's decision.

**Commit on `master`**: `6764d1e` — `versionCode` 10 → 11, `versionName` "2.7.1" → "1.0.0"; `docs/versioning.md` rewritten (the historical note verbatim, the supported history from 1.0.0); README's release example and its note-links sentence; the runbook's R2 row ("since issue #41, every build from 1.0.0 on"). No file under `app/src`, `core/src` or `libs` changed: **1.0.0 is byte-identical in code to the retired 2.7.1 build**. Independently reviewed (clean).

| proof | how | result |
|---|---|---|
| 1 unit gate from scratch | `--rerun-tasks` at `6764d1e` | 656 tests, 0 failures (app 37 classes/258, core 31/338, nfc-core 6/50, nfc-android 3/10) |
| 2 connected suite on `emulator-5554` | preserved pre-split set staged so the format-5 restore executed | 83 tests, 0 failures, 23 classes; every class at its 2.7.1 count |
| 3 structural facts | `git diff --stat 584f99a HEAD -- app/src core/src libs`; submodule status; pin script; version history | code diff empty; gitlink `7e0377a`; pin ok; `versionCode` 7, 8, 9, 10, 11 for 2.5, 2.6, 2.7, 2.7.1, 1.0.0 |
| 4 built artifact | `aapt2 dump badging` | `versionCode='11' versionName='1.0.0'` |
| 5 release signing dry run | `tools/release-dry-run.sh` with the published fingerprint | `RELEASE DRY RUN: PASS`, `fingerprint compare: matches`, `version: 1.0.0 matches 1.0.0` |
| 6 hygiene | the diff `584f99a..6764d1e` | no device id, e-mail, home path, tag UID or note id; single-line commit subject |
| 7 ordinary CI green | run 35538188301 on `6764d1e` | success |

**Release.** `servicetag-v1.0.0` (annotated, `7b907cb`) → `6764d1e`; run 35538277476 success after the owner's environment review; `ServiceTag-1.0.0.apk` 9,609,605 B, SHA-256 `202540d885cf272141258342f4950124da94b7de3531f997d12c7b9dfcd212ec`; download checksum == published checksum; one signer; certificate == `RELEASE_CERT_SHA256` (read from the repository) and identical to the retired 2.7.1 build's certificate — the signing identity is unchanged across the reset; `versionName` 1.0.0 == tag; `versionCode` 11.

**Phone.** Pending at the time of this entry: the phone was not attached when the release was verified. The install is `adb install -r` of the verified APK over the phone's 2.7.1 (code 10 → 11, same key, in place); recorded below when done.

**ServiceTag 1.0.0 published: the first supported release; the 2.x development line retired and archived; the signing identity and the data contract unchanged.**

## ServiceTag 1.1.0 — local automation API, workstation MCP server, conflict-safe backup merge (issue #46), 2026-09-21

**What shipped.** A loopback JSON API inside the app (`127.0.0.1:17337`, alive only while the Developer API screen is resumed, a fresh pairing code per visit as a bearer token); a workstation MCP server at `tools/servicetag-mcp/` (21 tools; unknown arguments refused centrally; edits overlay the current row; `retire_asset` monotonic); an additive backup import that plans before it writes and answers INSERT / IDENTICAL / CONFLICT / SKIPPED, never an update or a delete. `android.permission.INTERNET` added, with the owner's sentence on what it does and does not mean in the manifest, README and `docs/api/v1.md`. Classified MINOR under `docs/versioning.md`: 1.0.0 → 1.1.0, `versionCode` 11 → 12. Range `f588da3..b466e93`, 20 commits on `master`, each reviewed (six task reviews, scoped re-reviews per fix round, a whole-branch release review). The contract `docs/api/v1.md` had roughly 250 statements independently checked against the committed implementation, so it is a verified compatibility artifact rather than prose. Plan defects recorded, not papered over: the plan's own grep expected two occurrences of each import path in the contract (the correct text has three and four); the plan expected two `uses-permission` lines in the built artifact (there are three — the third is AndroidX's app-private receiver permission, already present in the 1.0.0 APK; the only new capability is `INTERNET`).

| proof | how | result |
|---|---|---|
| 1 unit gate from scratch | `--rerun-tasks` at `8eae323`, re-run at `b466e93` | 770 tests / 84 classes, 0 failures (core 33/394, app 42/316, nfc-core 6/50, nfc-android 3/10); Python 113 passed (21 tools) |
| 2 connected suite on `emulator-5554` | preserved pre-split set staged so the format-5 restore executed; at `8eae323`, carried to `b466e93` by tree identity of `app/src/main`, `core/src/main`, `libs` and the build files | 87 tests / 24 classes, 0 failures, 0 skipped (`DeveloperApiListenerTest` 3, `SettingsBackupEntryTest` 2, `PreservedSetRestoreTest` 1) |
| 3 structural facts | 41 anchored greps (one `ServerSocket(`, one quoted loopback literal, no outbound call site, no client socket, permissions NFC + INTERNET only, the merge sources free of delete/`updatedAt`/upsert, apply's guard order, five unique indices, the six ratified strings, format 5 / schema 5); ten untouched paths; submodule | all at their expected counts; the ten paths unchanged across the range; gitlink `7e0377a`, pin ok |
| 4 built artifact | `aapt2 dump badging` | `versionCode='12' versionName='1.1.0'` |
| 5 release signing dry run | `tools/release-dry-run.sh` with the published fingerprint | `RELEASE DRY RUN: PASS`, `fingerprint compare: matches` |
| 6 hygiene | the diff `f588da3..b466e93` and every commit message | no device id, e-mail, home path, UUID or real pairing code; single-line subjects; one author identity; one real fixture brand replaced by a fictional one before the push |
| 7 end-to-end on `emulator-5554` | clean install of the final debug build; Settings → Developer API by `uiautomator`; the code read from under its label; a connected MCP client through the server's own `adb forward` | 19/19: schemas strict on 21 tools; round trip (status, create → read back); a one-field edit, an all-`null` edit and a category change preserve every other field and the parent; `clear_fields` clears exactly one text or nullable field; unpaired, wrong-code and 422 refusals actionable; merge on the preserved set — plan writes nothing (61 insert / 8 skipped for attachments without a store), apply grows the store by exactly 61, re-plan all identical, a locally edited overlap is one CONFLICT and the apply then returns the report as data with zero writes |
| 8 contract, live | HTTP checks through the forward | 9/9: 401 empty (no token, wrong token), 404 envelope, 405, wrong verb on a sub-resource 404, 413 empty, a read row sent back verbatim as an edit → 400, the same row minus the five documented keys → 200, wrong content type on import → 415 |
| 9 fail-closed | three unknown-argument calls with a sensitive-looking value | refused; the value absent from every result and exception chain; the phone's own "Requests this session" counter 0 → 0; no forward created; leaving the screen closes the port and the tool says how to recover |
| 10 ordinary CI green | run 35670900969 on `b466e93` | success, jobs `build` + `mcp`, 0 annotations |

**Release.** `servicetag-v1.1.0` (annotated) → `b466e93`; run 35671147026 success after the owner's environment review; `ServiceTag-1.1.0.apk` 9,707,937 B, SHA-256 `c2a4460cdf9332f4f362f9e8fbf7729eb3a711d78e089a1af440b68a55984727`; download checksum == published checksum; `apksigner` verifies (v2), one signer; certificate == `RELEASE_CERT_SHA256` == the 1.0.0 signer; badging `1.1.0` / `12`; not debuggable.

**Development phone.** The published APK installed on the clean development phone (Android 17, NFC-capable, nothing of ours installed before); the APK pulled back from the phone has the release checksum. Proofs 7, 8 and 9 re-run there against the signed build through the real `adb forward`: **28/28**, the same results as on the emulator, including the merge sequence on the preserved set and the recovery message after leaving the screen. The app stays installed with its store cleared, so the Stage-A migration starts from empty. The production phone was not attached; its next install is this 1.1.0 (code 12 over its 10), which supersedes the pending 1.0.0 install.

**Issues.** #46 closed as completed; #44 updated (the plan-before-write core shipped; the Backup screen's merge mode, owner conflict resolution and duplicate review stay open); follow-ups #51 (Developer API screen: recents thumbnail, dead listener), #52 (readable 422 details), #53 (MCP polish), #54 (CI action pinning).

**ServiceTag 1.1.0 published: the automation bridge is in place; the data contract (format 5, schema 5) and the signing identity are unchanged.**

## ServiceTag 1.2.0 — operational maintenance: schedules, groups and rounds, local reminders, a Maintenance tab, scan-to-maintain, API/MCP (issues #4 #5 #11 #21 #24 #27 #28 #49 #50 #55), 2026-09-23

**What shipped.** Room schema 6 / backup format 6 / the merge over ten tables, with closures as immutable facts and derived state non-canonical (B01); the scheduling engine — fixed-calendar, completion-relative, meter-based and time-or-meter rules, `DUE_SOON`/`DUE`/`OVERDUE`, pause, archive and season handling, postpone kept apart from snooze, `ScheduleRecompute.rebuild` pure in (config, events, closures, membership, today, zone) (B02); maintenance groups with per-member truth, the open-instant membership basis and "Close this round" that never claims work was done (B03, B15); the provider-neutral `ReminderProvider` port and reconcile contract (B04); the platform plumbing — `POST_NOTIFICATIONS` requested on first schedule creation, no exact alarms, boot / time / time-zone receivers (B05); the local provider — inexact daily digest, WorkManager backstop, three-day overdue re-notify, persisted nonces, device-local snooze (B06); Done / Snooze / Open quick actions on notifications (B07); primary navigation Dashboard · Assets · Maintenance with due work, schedules, groups and health (B08); the scan sheet that lands on actionable maintenance for a bound tag (B09); reminder health with idempotent repair (B10); per-tag placement labels (B11); the API's maintenance surface (19 new routes, 40 documented) and the MCP server at 38 tools (B12); version 1.2.0 / `versionCode` 13 with the docs (B13); the schedule editor and the five operations (B14). Fifteen briefs, each implemented from its brief, reviewed on its real diff and merged fast-forward with CI green on the exact tip; the plan and briefs are at `docs/superpowers/plans/2026-09-22-servicetag-1.2/`, the spec at `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` (rev 4.1).

| proof | how | result |
|---|---|---|
| 1 unit gate from scratch | `--rerun-tasks` at `12ef30f`; carried to `84a3244` by code identity (the two commits after it are `.md`-only; the two test classes that read the docs re-run there) | 1,190 tests / 133 classes, 0 failures (core 533/49, app 597/75, nfc-core 50/6, nfc-android 10/3); Python 197 (MCP, 38 tools) + 158 (bundle), all passing |
| 2 connected suite on `emulator-5554` | every `androidTest` package or class in its own invocation (22 invocations); the owner's preserved format-5 set staged so the restore proof executed; at `12ef30f`, carried by code identity | 131 tests / 35 classes — every `@Test` in the tree — 0 failures, 0 skipped; run inside the after-20:00-local window that used to break two of them |
| 3 structural facts (§16.4) | anchored greps at the tip | no `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`; `POST_NOTIFICATIONS` 1; `RECEIVE_BOOT_COMPLETED` 1; 6 receivers, none exported; one channel-creation site; no `PendingIntent` without `FLAG_IMMUTABLE`; the forward-only migration row; 1.2.0 / 13 / schema 6 / format 6; the 2.6 tombstones present; `androidx.work` absent from `:core`; gitlink `7e0377a` |
| 4 format-5 restore (§16.5) | `PreservedSetRestoreTest` on the owner's real 2026-09-18 export, on the emulator only | restores through `importBackupReplace` onto schema 6; link counts equal; the re-exported `externalLinks` byte-identical across the 5 → 6 round trip |
| 5 end-to-end on `emulator-5554` (§16.6) | clean install of the tip's debug build; Settings → Developer API by `uiautomator`; a connected MCP client through the server's own `adb forward`; then the Backup screen's export into a SAF folder and that export planned back | 26/26: pair; status 1.2.0; two assets → a group of two → a group-targeted QUICK schedule due today, one `list_due` item, 2 required / 0 complete; one member completes (1 of 2, still due); the same member again 409; `closedOn` tomorrow and before the open date 422 and nothing written; close → 201 and one closure; a repeated close closes the newly opened next round (two closures, distinct occurrences — spec §2.4); a completion after the closes lands on the round now current; `clear_fields`; the argument guard; archive → gone from due work, closures kept. Export format 6; `import_merge(plan_only)`: applicable, 0 conflicts, every row IDENTICAL (assets 2, events 2, groups 1, schedules 1, closures 2); status unchanged; `pm clear` |
| 6 hygiene (§16.7) | the diff `1f963f8..84a3244` and its 89 commit messages | one author identity; single-line subjects; no attribution; no e-mail, home path, device id, pairing code or private noun |
| 7 badging (§16.8) | `aapt2 dump badging` on the debug build, repeated on the artifact below | `versionCode='13' versionName='1.2.0'`, `targetSdk` 36; exactly the eight permissions of the `RECEIVE_BOOT_COMPLETED` ruling (NFC, INTERNET, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, WAKE_LOCK, the AndroidX app-private receiver permission), no exact-alarm permission |
| 8 reviews | whole-branch release review at `7813614` and a scoped re-review of the fix range `7813614..12ef30f`, both on the most capable model | RELEASE / RELEASE, 0 blocking; every brief reviewed on its real diff, fix rounds scoped and re-reviewed |
| 9 ordinary CI green | run 35819156892 on `84a3244` (and 35817352402 on `4b9e01e`; every merged tip before it green in turn) | success, jobs `build` + `mcp` + `bundle`, 0 annotations |

**Found at the gate.** The connected sweep, run after 20:00 local, exposed a latent defect rather than a merge fault: D-27's pin floor read `updated_at` at UTC while today and the anchor are the owner's dates, so an evening-created schedule floored on tomorrow and its first occurrence moved a whole interval out. Ruled a product defect and fixed on lane `1.2-fix-shell` (the zone is a required pure input of `rebuild`; `RecomputeSchedules` supplies the device zone), reviewed twice, spec §2.1/§6 and the plan amended. The plan's §16.6 text then over-specified two refusals: a repeated `close_round` closes the *next* round because spec §2.4 opens it the moment the previous one is closed, so the proof asserts that and the API doc says it; a first `servicetag-v1.2.0` tag on `4b9e01e` was retired unpublished while that was settled and re-cut on `84a3244` with no code change.

**Release.** `servicetag-v1.2.0` (annotated) → `84a3244`; run 35819328082 success after the owner's environment review (a first tag on `4b9e01e` was retired unpublished, see above); `ServiceTag-1.2.0.apk` 10,615,429 B, SHA-256 `027c28cca454b89af31fc592f50e027864012de94781510391257f41eb486081`; download checksum == published checksum; `apksigner` verifies (v2), one signer; certificate == `RELEASE_CERT_SHA256` == the 1.1.0 signer; badging `1.2.0` / `13`, `targetSdk` 36; exactly the eight permissions; not debuggable.

**Development phone.** The verified APK installed over the development phone's signed 1.1.0 **in place** (`adb install -r`; `versionCode` 12 → 13 with the same `firstInstallTime` and UID); the APK pulled back from the phone has the release checksum. The Stage-A data survived the schema 5 → 6 migration: every 1.1.0 table keeps its row count (assets 41, definitions 23, events 26, profiles 67, tags 3, links 1), `list_assets` and `list_tag_bindings` are identical before and after, and the 26 events are identical in every shared field — the only difference is the three new event fields (`scheduleId`, `occurrenceOn`, `detailsPending`) at their defaults on every row; the new tables start empty. The group flow re-run there through the real `adb forward` against the signed build: **27/27**, the same results as on the emulator, the two proof assets archived afterwards. Hazard 9 on a real Stage-A asset bound to two tags: one schedule, listed exactly once. The groups UI, driven by `uiautomator` on a seeded group: the Maintenance tab shows the due schedule with "1 of 2 complete" and the group under "Maintenance groups" (archived groups listed with an ARCHIVED chip); the group screen shows both members, "1 of 2 complete", Complete selected / Complete all and Close this round; the schedule screen adds Postpone and Snooze — all ratified strings; the seed archived afterwards. The S4 probe's log was pulled (next-day re-arm and the first backstop run recorded) and the probe uninstalled; the phone stays on 1.2.0 with its data. The production phone was not touched; its install is the owner's separate decision.

**Issues.** #4, #5, #11, #21, #24, #27, #28, #49, #50 and #55 closed as completed, each with a closing comment naming what shipped, the briefs, the release facts and the proofs; #24 carries the `RECEIVE_BOOT_COMPLETED` reading of its AC 3 and the S4 follow-up; #44 updated (the merge now spans format 6's ten tables; the Backup screen's merge mode, conflict resolution and duplicate review stay open).

**Known limits and owner flags.** The D-27 floor now uses the device zone (reversible by ruling). `close_round` is not idempotent by spec — a retried close skips the newly opened round, immutably; tightening options are recorded for 1.2.1. `ZoneId.of("America/New_York")` names the negative-offset zone in four JVM test files. `lifecycleChangedAt` is deferred to 1.3. Post-1.2 tidies: `HealthScreenTest` inherits its neighbour's store; a mis-cited invariant in `DashboardAttentionTest`; the device zone declared explicitly at the `RecomputeSchedules`/`AppGraph` seam.

**ServiceTag 1.2.0 published: one maintenance subsystem — engine, groups, reminders, Maintenance tab, scan sheet, health, API/MCP — on schema 6 / format 6, with the signing identity unchanged.**

## Stage B — the deferred maintenance cadences loaded onto the development phone, 2026-09-23

**What shipped.** `tools/servicetag-schedules` (master `ac6fc3e`; CI job `schedules`, 92 tests): a workstation tool that reads a private schedule manifest, resolves every asset, profile and group reference against the phone through the MCP client, computes a plan (`CREATE` · `IDENTICAL` · `CONFLICT` · `ERROR`) and applies only a clean plan — groups before schedules, never editing, archiving or deleting an existing row — then re-plans and asserts everything `IDENTICAL`. Plan and brief: `docs/superpowers/plans/2026-09-23-servicetag-stage-b/`. Reviewed on its real diff (two blocking findings fixed and re-reviewed; a third round relaxed one invariant the first real plan proved stricter than the product: a QUICK schedule on an asset may carry a profile, only a group target forbids one).

| proof | how | result |
|---|---|---|
| 1 unit tests | `uv run --frozen pytest`, locally and in CI (run 35874098527) | 92 passed; the mutation-checked tests for the eight resolution invariants |
| 2 synthetic manifest on `emulator-5554` | clean install, three fictional assets, one group + three schedules | plan `CREATE` 4 → apply → re-plan `IDENTICAL` 4; a mutated copy (one anchor changed) → `CONFLICT` 1 and `apply` exits 1 writing nothing |
| 3 the real manifest on the development phone | the owner's classified Todoist cadences (private manifest; plan outputs kept in the owner's folder) | plan `CREATE` 47 → apply created 4 groups + 43 schedules → re-plan `IDENTICAL` 47; a first attempt had refused 23 entries on the over-strict invariant and wrote nothing |

**The estate afterwards.** 43 live schedules (39 asset-targeted, 4 group-targeted; 37 `FIXED`, 6 `COMPLETION`; 13 `FORM`), 4 live groups, season behaviour `IGNORE` on every row (the seasonal and pre-season cases are tagged as the design examples for #14 and #60); the due list shows 43 items, 3 `DUE SOON` and 40 `OK`. Eleven items stayed out with reasons recorded in the owner's classification; one is the concrete fixture for #61. The production phone was not touched.

