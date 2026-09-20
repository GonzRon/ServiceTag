# Product-split migration runbook — ServiceTag / NoteTag / nfc-tag-core

The ordered *how*. Companion to `docs/architecture/product-split-target.md` (the *what*) and
`docs/architecture/product-split-archaeology.md` (the evidence, cited as "arch §n"), with the
archaeology review's corrections cited as "review correction *n*" / "review addition *n*".
Binding: the brief at `.superpowers/split/brief.md` (cited as "§n") and the owner's rulings
**O1–O15** plus gate corrections **C1–C9** in `.superpowers/split/ledger.md`.

**How to read this.** Every step has **Do**, **Verify**, and **Rollback**. No step is complete until
its Verify passes; no step starts until the previous one's Verify has passed. §35's rule applies to
every consequential mutation: an exact rollback point, a backup set, the current signed APK, the old
signing key, the pre-split checkpoint, no deletion of old refs, no deletion of the attachment tree,
no silent tag rewrites, and **no uninstall of the old modern package until ServiceTag's migration is
independently proven**.

**Sequence (§5)** names the phases: A freeze/checkpoint · B archaeology · C target architecture ·
**D local ServiceTag identity conversion** · **E narrow-product reconstruction** · **F nfc-tag-core
extraction** · **G both apps consume** · **H data + attachment migration proof** · **I physical-tag
proof (now: coexistence of final products only)** · **J coexistence proof** · **K remote repository
changes** · **L independent CI** · **M canonical documentation** · **N final review/handoff**.

**THE CANONICAL MASTER ORDER — one order, stated once, obeyed by every table below:**

> **local D–G → remote K/L → phone H → physical and coexistence I/J → docs and handoff M/N**

with one documented exception inside it: **`nfc-tag-core` is created and pushed early** (§B.1), before
the rename and before G can be proved locally at all, because a git submodule needs a URL. That is
§27's own "most conservative reversible adjustment", and it is the only departure from §27's literal
step numbering.

Three things follow, and they are why the order is not the alphabet:

- **Remote before phone.** K/L produce the first green CI on a machine that is not this one, so the
  data migration in H runs against a ServiceTag whose build is already independently proven. §26 and
  gate 10 are otherwise unreachable, because the clean-clone-from-URL proof needs the remotes. K/L
  exercise **ordinary CI only**; the apps' `release.yml` workflows are installed at the tail of G and
  first execute at §G's product tags (owner ruling 2026-09-17).
- **Phone-data before physical-tag.** H ends with the old package uninstalled and NoteTag installed
  (§15 steps 7–8). I/J **cannot** run before that: they need both final products on the device, and
  NoteTag does not exist on the phone until H's last step.
- **Docs and handoff last**, because they record what the earlier phases observed.

**This runbook's sections map onto that order:** §A covers D–G · §B covers K–L · §C covers H · §D
covers I · §E covers J · §F–§H cover M · §I is rollback · §J maps the §34 gates. The sections appear
in the document in that order, which is also the order they are executed in.

**Standing constraints.**

- **The operating rule: vet everything possible on the emulator; the phone is used only where RF
  hardware or the real install is required.** Every proof that can be a JVM test is a JVM test;
  every proof that needs Android but not a radio runs on the emulator; the phone is reserved for the
  physical taps in §D and §E and for the real-install migration in §C.
- The attached phone holds the owner's **real data**. **No instrumented suite runs on it** (§15) —
  an instrumented run wipes app data. Automated suites go to the emulator; the phone gets only §C,
  §D and §E, and only once a fresh export exists.
- `adb` is always pinned by an `ANDROID_SERIAL` the operator exports once. Serials, phone model and
  codename, and phone folder names appear nowhere in this document.
- Naming: **noteNFC is a historical name only** (O1). Nothing named noteNFC exists after the split
  and nothing reuses `com.loosecannon.notenfc`.
- **[P*n*]** markers were used while drafting; every proposal is ratified as of `5d8ca77`, and §K is
  the closed register (none open).

**Preserved artifacts this runbook depends on** (Phase A, done):

| Artifact | Location | What it is |
|---|---|---|
| Rollback APK | `~/Documents/Projects/AndroidStudioProjects/noteNFC-releases/` | the signed 2.4 / versionCode 6 build of `ac523d7`, signer DN `CN=noteNFC, O=GonzRon`, certificate SHA-256 in the checkpoint ledger (arch §4.5) |
| Pre-split backup set | `~/Documents/Projects/AndroidStudioProjects/noteNFC-backups/pre-split-2026-09-16/` | a data archive at format 5 (9 419 B) and an artifacts archive at artifacts format 1 (13 651 544 B) sharing one `backupSetId`, 8 entries hash-verified, plus a database + prefs snapshot (arch §7, §7.5) |
| Pre-rewrite bundle | alongside the project directories | the history bundle taken before the 2026-09-14 `git filter-repo` rewrite (arch §4.1) |
| Recovery refs | on `origin` **and** locally | tag `pre-split-checkpoint` and branch `pre-split-master`, both at `ac523d7`. **Not deleted until all three repositories are green** (§27 step 12) |

---

## A. Local transition (sequence D → E → F → G)

Three local repositories are built and proven before anything remote moves. The library is extracted
*after* ServiceTag's identity conversion, so the extraction happens once against final code and the
provenance table in target §4.6 stays true.

### A.0 Pre-flight

**Do.**

```bash
cd ~/Documents/Projects/AndroidStudioProjects/noteNFC
git fetch --all --tags
git rev-parse pre-split-checkpoint^{commit} refs/heads/pre-split-master origin/master
git -C ../ServiceTag-split rev-parse HEAD        # the product-split worktree
```

**Verify.** All three refs resolve to `ac523d7`; the `product-split` worktree holds the docs package;
`git status` clean in both checkouts. **Rollback.** None needed — read-only.

### A.1 ServiceTag identity conversion (sequence D)

An SDD-style task list: one commit per task, each with its own verification, each independently
revertible. The scale is the reason for the list — `com.loosecannon.notenfc` occurs **2 028 times
across 236 files** (arch §4.6, §9 D4) — and §11 requires a deliberate inventory, not a blind
search-and-replace.

| # | Task | Files | Verify |
|---|---|---|---|
| **1** | `rootProject.name = "ServiceTag"` (an omission from the archaeology inventory — review correction 11) | `settings.gradle.kts:20` | `./gradlew projects` shows the new root name |
| **2** | `namespace` and `applicationId` → `com.loosecannon.servicetag`; keystore path → `~/.config/servicetag/keystore.properties`; `versionCode` 6 → **7**, `versionName` "2.4" → **"2.5"** (the ratchet above the preserved 2.4 rollback APK) | `app/build.gradle.kts` | `./gradlew :app:assembleDebug`; `aapt2 dump badging` shows the new package |
| **3** | Move the Kotlin package roots: `app/src/{main,debug,test,androidTest}/kotlin/com/loosecannon/notenfc/…` → `…/servicetag/…` and `core/src/{main,test}/kotlin/com/loosecannon/notenfc/core/…` → `…/servicetag/core/…`; rewrite every `package`/`import` | 233 `.kt` files + both manifests + `app/build.gradle.kts` | `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`, **plus a scoped check only**: no `package` or `import` declaration and no source path root still says `com.loosecannon.notenfc` — `git grep -nE '^\s*(package\|import)\s+com\.loosecannon\.notenfc' -- app core` empty, and `git ls-files app core \| grep -c 'com/loosecannon/notenfc/'` = 0. **The repository-wide zero-hit grep does NOT belong here**: tasks 4, 6 and 7 still legitimately hold `com.loosecannon.notenfc` in the NDEF type constants, the manifest filter path and the deep-link literals until they run, so that assertion is the whole-phase verification below |
| **4** | The **five FQN `android:name` literals that do not follow `namespace`** (review correction 4): `com.loosecannon.notenfc.NoteNfcApp` (application), `…MainActivity`, `…ShareActivity`, `…nfc.NfcDispatchActivity` in `app/src/main/AndroidManifest.xml`, and `…debug.DebugBackupActivity` in `app/src/debug/AndroidManifest.xml` | both manifests | the merged manifest contains no `notenfc` substring; the app launches |
| **5** | The **one Gradle-owned identity value** (C9, target §4.8): `ndefExternalDomain`, `ndefTypeName`, `aarPackage` in `app/build.gradle.kts`, feeding `manifestPlaceholders["ndefTagPath"]` and three `buildConfigField`s; the manifest's `android:path` becomes `${ndefTagPath}` (still an **exact** path, never `pathPrefix`); the app builds its `TagIdentity` from `BuildConfig` | `app/build.gradle.kts`, `AndroidManifest.xml:84-88`, the NFC wiring | **both** binding tests green: the JVM test on `TagIdentity`-from-`BuildConfig`, and the emulator test that `queryIntentActivities` on `vnd.android.nfc://ext/<externalType>` resolves to exactly this app's dispatch activity |
| **6** | **Drop legacy `md5_short` entirely** (O2/O3): the `LEGACY_TYPE`/`LEGACY_TYPE_NAME` constants, `legacyKeyPattern`, the `decodeLegacy` branch, `TagPayload.LegacyMd5`, `PayloadFormat.LEGACY_MD5`, `Resolution.UnknownLegacy`, the `Legacy` sheet, the `"LEGACY_MD5"` trampoline wire value, the second manifest filter, and the matching tests. Record it as **a deliberate reversal of D6's "kept permanently" promise** | `core/…/core/nfc/NdefCodec.kt:50-53,82-88`, `core/…/core/model/TagBinding.kt`, `core/…/core/usecase/ResolveTag.kt`, `app/…/ui/scan/{ScanViewModels,TagResultSheet}.kt`, `AndroidManifest.xml:89-93`, `core/src/test/…/NdefCodecTest.kt` | green suites; `git grep -i md5` over `app core` empty; the manifest declares exactly **one** `NDEF_DISCOVERED` filter |
| **7** | Deep-link scheme literals → `servicetag`; **`notenfc://` gone entirely** (O3) | `core/…/core/nfc/TagRoute.kt:10`, `core/…/core/links/DeepLinkRoute.kt:17`, `AndroidManifest.xml:56-58`, and the six `androidTest` files that hard-code `notenfc://` (arch §4.6) | `git grep -c 'notenfc://'` = 0; route tests green |
| **8** | `app_name` → `ServiceTag`; theme → `Theme.ServiceTag`; the `Application` subclass → `ServiceTagApp`; the nav-root composable → `ServiceTagRoot` (resolving the two-classes-one-name collision, arch §4.6); the debug manifest label | `res/values/{strings,themes}.xml`, both `NoteNfcApp.kt` files, both manifests | `./gradlew :app:assembleDebug :app:assembleRelease`; the label read back from the built APK |
| **9** | **A new ServiceTag launcher icon** replacing the inherited `ic_launcher` mipmap set — **a coexistence requirement, not cosmetics** (review addition 3): two apps that look identical on the launcher make every device observation and every NFC-allowlist entry ambiguous | `app/src/main/res/mipmap-*/`, the manifest's `android:icon` | the two apps are visually distinguishable on the launcher and in Settings → Apps |
| **10** | `DB_NAME` → `servicetag.db`; `AppPrefs` file name → `servicetag`; export prefixes → `ServiceTag-data-<stamp>.zip` / `ServiceTag-artifacts-<stamp>.zip`, and the three test fixtures that hard-code the old prefix | `app/…/di/AppGraph.kt:201`, `app/…/prefs/AppPrefs.kt:13`, `app/…/backup/SafBackupSetIO.kt:60-67`, `AppSmokeTest`, `AttachmentsDeviceProofTest`, `BackupViewModelTest` (arch §7.7 item 2) | JVM suites green. **A fresh install has no old database file to find, and the importer never reads a file name** (arch §7.3, §7.7 item 4) — none of this can affect restore correctness |
| **11** | Re-export the Room schema under the new `AppDatabase` FQN: `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/1.json`…`5.json`; delete the old directory; fix the migration-ladder tests' resource paths | `app/schemas/…` and the chain tests (arch §7.7 item 5) | every hop plus the full 1→5 run green; **`5.json`'s `identityHash` unchanged** — it is a structural hash independent of package (arch §7.1). **If it changes, stop**: the schema drifted and the phone's database will not open |
| **12** | Sibling isolation in ServiceTag's direction: a record typed `com.loosecannon.notetag:tag` decodes as `Foreign`, never as a recognised payload (target §4.3 invariant 1, C8) | `core/src/test/…/NdefEnvelopeIsolationTest.kt` (new) | green. This is the test that stops ServiceTag adopting a NoteTag tag |
| **13** | The **string-typed trampoline wire vocabulary** → enum names: `MainActivity.kt:84` hard-codes `Route.TagResult("V1", …)` instead of `PayloadFormat.V1.name`, and `ScanViewModels.kt:41` defines `FORMAT_NONE = "NONE"`; both, plus the `"LEGACY_MD5"` value being deleted in task 6, are replaced by `PayloadFormat` names (and a single named constant for the not-ours case) so the trampoline↔renderer contract stops being three loose literals (arch §4.6, §6.3) | `app/…/MainActivity.kt:84`, `app/…/ui/scan/ScanViewModels.kt:41` | a test that the extras `MainActivity` writes and reads round-trip through the enum, and that an unknown value short-circuits to the not-ours sheet **without** calling `ResolveTag` (arch §5.12) |
| **14** | Remaining identity carriers from review correction 11: `BuildConfig.VERSION_NAME` displayed in `ui/settings/SettingsScreen.kt:241`; the **three** files of `app/src/debug` (manifest, `DebugBackupActivity.kt`, `res/layout/activity_debug_backup.xml`), not one; `.gitignore` and `proguard-rules.pro` package references; the `<queries>` block reviewed and left unchanged (those schemes are link targets, not identity) | as listed | `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` |
| **15** | **README hygiene**: `README.md:121-125` reproduces the signer DN **and** the full colon-separated certificate SHA-256 in the repository (review correction 11). Reduce to a pointer; the fingerprint lives in the evidence file | `README.md` (the full rewrite is §H) | `git grep -c 'CN=noteNFC'` = 0 outside history |

**A.1.1 ServiceTag regression proof (§21) — the entry condition for gate 5.** ServiceTag must be
*functionally equivalent to pre-split except the deliberate identity changes*. The proof runs the
existing suites plus a device pass on the emulator over §21's list: the core maintenance product; NFC
and link behaviour (asset binding, ambient resolution, standalone links, intentional write, capacity,
read-back, foreign/malformed safety); attachments; backup (all-or-nothing export, data-only restore,
artifacts restore, set mismatch, missing/hash drift); and UX (Apollo theme, Dashboard, Assets,
two-tab navigation, Read/inspect tag, ambient NFC as the normal read path).

**Verify (whole phase).**

```bash
cd ~/Documents/Projects/AndroidStudioProjects/ServiceTag-split
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --console=plain

# The repository-wide assertion, run ONLY after the last identity task (7) has landed:
git grep -lE 'com\.loosecannon\.notenfc|notenfc://|md5_short|noteNFC|NoteNfc' \
  -- app core gradle settings.gradle.kts README.md
# expected: no output. Hits under docs/ are HISTORY and are left alone (§30, §H).
```

Run in task order this assertion is expected to fail after tasks 3 and 4 and to pass only from task
7 onward: the NFC type constants (tasks 4 and 6), the manifest filter path (task 6) and the deep-link
literals (task 7) each legitimately still carry the old string until their own task runs. Treating it
as a per-task gate would either block task 3 or invite someone to edit constants out of order.

Then a clean-checkout build from a directory that has never held the project (§26).

**Rollback.** Every task is one commit on `product-split`; revert the task, or `git reset --hard` to
`ac523d7`. `master`, `origin` and both recovery refs are untouched.

### A.2 NoteTag reconstruction (sequence E)

**Do.**

```bash
cd ~/Documents/Projects/AndroidStudioProjects
git clone --no-local noteNFC NoteTag
cd NoteTag
git remote remove origin                     # it gets its own remote in §B
git checkout -B master c84b881               # master IS the narrow semantic endpoint (O6)
git for-each-ref --format='%(refname)' refs/heads refs/tags \
  | grep -v '^refs/heads/master$' | xargs -r -n1 git update-ref -d
git reflog expire --expire=now --all && git gc --prune=now
```

Then the **first commit** (C6): delete the ServiceTag-only material at the new branch tip.

```bash
git rm -r docs                               # 70 inherited ServiceTag design files
git rm app/release/app-release.apk           # the 2024 shipped artifact; identical blob at 3a3c69a
git commit -m "start the narrow product from its own history"
```

**State plainly, in that commit message and in the README, what this does not do** (C6): it does
**not** scrub any of it from history, and **no second history rewrite will be performed**. What stays
reachable in the public history: the ServiceTag design documents; the owner's GitHub handle and
personal issue URLs in `docs/design/issues/applied.md` and one superpowers plan; the release-signer
certificate digests in `docs/design/phase-0-evidence.md`; and the author name plus personal email in
the commit metadata of **all 30 preserved commits** (review correction 10). None of it is a secret —
a public handle and public-key fingerprints — and all of it is already public in the repository this
branch came from. The repository is public by ruling, for exactly that reason.

Then the rewrite. **O6 is explicit: NoteTag is a substantial rewrite on modern engineering and
nfc-tag-core; historical technical debt is not preserved for authenticity.** `c84b881` supplies
provenance, intent, lessons and the share→write→tap evidence — not code to keep alive.

| # | Commit | Verify |
|---|---|---|
| **1** | the deletion above | `git grep -lE 'GonzRon|github\.com/|SHA-?256:'` at the tip lists only `gradlew` (the Gradle wrapper's own upstream comments, present at `c84b881` — observed 2026-09-17), nothing else; `git ls-files | grep -c '\.apk$'` = 0; **the tree is 45 files** — 116 at `c84b881`, minus the 70 under `docs/`, minus the tracked release APK, which is itself one of the 46 non-docs files (arch §2.8) |
| **2** | identity: `applicationId`/`namespace`/Kotlin root → `com.loosecannon.notetag`; `rootProject.name = "NoteTag"`; label `NoteTag`; its own launcher icon (review addition 3). The historical mixed-case `com.looseCannon.noteNFC` root goes with the rewrite | `./gradlew :app:assembleDebug`; `aapt2 dump badging` shows the new package and label |
| **3** | **re-run CI from scratch.** `c84b881` itself never ran on a runner — the green Phase-0 evidence belongs to pre-rewrite twins `ccdb9d3`/`12e2c09` whose SHAs no longer exist (review correction 8). No green claim is inherited | a green run on the new repository's own workflow (after §B.4) |
| **4** | signing: `~/.config/notenfc/keystore.properties` via the same `Properties`-from-`user.home` mechanism — **the existing noteNFC key, unchanged, never rotated** (§12); `versionCode`/`versionName` past the historical 2 / `1.1` | `./gradlew :app:assembleRelease` produces a **signed** release build (§12 requires the proof); the certificate SHA-256 matches the existing key's, recorded not reproduced |
| **5** | the NoteTag application: share receiver, writer screen, ambient dispatch activity, minimal local store. **Delete the three 2024 activities** rather than modernise them (O6). Declare exactly **one** `NDEF_DISCOVERED` filter on the exact path `/com.loosecannon.notetag:tag`; **do not re-inherit the 2024 `TECH_DISCOVERED` catch-all or `res/xml/nfc_tech_filter.xml`** | `git grep -c nfc_tech_filter` = 0; exactly one NFC filter in the merged manifest |
| **6** | the **NoteTag v1 tag format** (O13/O14, target §4.9): one external record, `version|kind|flags|body`; kinds `0x01 JOPLIN_NOTE`, `0x02 URI`, `0x03 LOCAL_REF`; `0x04`+ reserved; **no AAR**; the automatic writer decision compact → URI-if-it-fits → LOCAL_REF, with "fits" decided by the exact encoded message against the **measured** `Ndef.maxSize`. **`JOPLIN_NOTE` is 32 lower-case hex on both sides, and the write side validates rather than assumes**: accept a candidate id only if it matches `^[0-9a-fA-F]{32}$`, normalise it to lower case before packing the 16 bytes, and on any other shape **fall through to `URI`** rather than truncating, mangling or refusing (target §4.9) | JVM tests per kind: encode/decode round-trip, malformed bodies, an unknown kind, an unknown version; a **mixed-case round-trip test** — encode from an upper- or mixed-case 32-hex id, decode, assert the reconstructed id is **lower case** and equal to the normalised input — and a non-conforming-id test asserting the **`URI` fallback** was chosen; **capacity selection driven by injected `maxSize` values** (§D.3) with the exact encoded message at `maxSize`, `maxSize - 1` and `maxSize + 1`, asserting compact → URI-if-it-fits → `LOCAL_REF` and that the fallback fires **only** when the message genuinely does not fit — `needed` is `toNdefMessage().toByteArray().size` with **no TLV allowance** (target §4.3 invariant 7); **no test asserts a character count** |
| **7** | the **minimal local store** (O14, ratified P19): a single atomically-replaced JSON file behind a small interface; `LOCAL_REF` targets plus convenience metadata; **never required to resolve a `JOPLIN_NOTE` or `URI` tag**; the writer tells the user when a tag will only work on this phone. **Plus the `LOCAL_REF` crash-consistency invariant (G2, as corrected by H1)**, two rules in priority order: **(a)** the mapping is durably stored **before** the physical tag is written, and a `LOCAL_REF` whose mapping has not committed is never written at all; **(b)** once a write has been **attempted**, the mapping is **RETAINED** unless it is provable that **no bytes reached the tag**. Sequence: allocate the UUID → **atomically persist** the mapping (temporary file, `fsync`, atomic rename) → write and verify → **retain on success, and also retain on any ambiguous failure** (tag lost mid-write, lost before the read-back completes, or any I/O error after the message was handed to the chip); remove **only** where no write can have happened — user cancellation, or a *pre-write* rejection (capacity refusal, foreign refusal, read-only, unsupported). The direction matters because `writeNdefMessage` can physically succeed and the tag then leave the field before the read-back confirms it: deleting the mapping there leaves **a live `LOCAL_REF` tag that resolves to nothing on the only phone that could resolve it**, while retaining it costs at worst an **orphan JSON entry** nobody sees and the next write of that UUID overwrites | a test that `JOPLIN_NOTE` and `URI` tags resolve with the store deleted; a test that a `LOCAL_REF` miss produces a message, not a crash; and the **named failure-injection deliverable, three cases**: *persist ok + **ambiguous** write failure → mapping **RETAINED***; *persist fails → **no** write attempted*; *cancellation or pre-write rejection → mapping **may be** removed* |
| **8** | a **copied, not shared** safe `ACTION_VIEW` launch policy: scheme allowlist, whitespace and control-character rejection, `ActivityNotFoundException` **and** `SecurityException` caught. The 2024 app passed stored text straight to `startActivity` with no `try/catch` and crashed on a missing Joplin (arch §2.5, §5.13) | a test per rejected scheme; a test that a missing handler is a message, never a crash |
| **9** | malformed/foreign wording: a ServiceTag tag is **not** interpreted as a note (§23); sibling isolation in NoteTag's direction | a test that `com.loosecannon.servicetag:tag` decodes as `Foreign`; the writer offers only **Write over it** / **Cancel** and names the other app (ratified P11) |
| **10** | adopt `nfc-tag-core` for reader mode, read-before-write, capacity, verified read-back. **Requires §A.3 and §A.4**, so this lands last | the §23 acceptance list, end to end |

**NoteTag acceptance (§23) — the entry condition for gate 6.** Share a supported Joplin note or link
→ NoteTag receives it → intentional write → nfc-tag-core safe write → read-back verified → ambient
tap → NoteTag resolves → safe `ACTION_VIEW` → the correct note or link opens. Plus: a malformed tag
does not launch unsafe content; a ServiceTag tag is not interpreted as a note; a clean checkout
builds; CI passes; the dependency on a pinned nfc-tag-core is real (§6.3's assertion step).

**Verify (whole phase).** `git rev-list --merges --count c84b881` = 0 and `git log --oneline | tail -1`
is `5fb6aed` — the 2023 root — proving the ancestry is real and ungrafted (arch §2.7). Clean-clone
build green. **Rollback.** Delete the directory and re-clone; `c84b881` is immutable history.

### A.3 nfc-tag-core extraction (sequence F)

**Do.** Create `~/Documents/Projects/AndroidStudioProjects/nfc-tag-core`, `git init`, and build the
layout at target §4.1: `nfc-core/` (pure Kotlin/JVM, with **zero third-party, application or
framework runtime dependencies — the Kotlin stdlib only**, added by the `kotlin.jvm` plugin),
`nfc-android/`
(the Android NFC adapter), its **own root build** so it builds and tests standalone (O15), its own
wrapper and its own catalog whose alias *names* match the two apps'. Then, file by file, follow the
**provenance table** at target §4.6: it names every library file's source file, line range,
`git log --follow` starting point, and the change made on extraction. Write `README.md` around that
table — for a repository with no inherited history, the table *is* the provenance (§9).

| # | Commit | Verify |
|---|---|---|
| **1** | skeleton: root `settings.gradle.kts` (`include(":nfc-core", ":nfc-android")`), root `build.gradle.kts`, catalog, wrapper, `.gitignore` | `./gradlew projects` lists both modules |
| **2** | `nfc-core`: `NdefRecordData`, `TagIdentity`, `TagContent`, `NdefEnvelope` (optional AAR builder), `UuidBytes`, `ExistingContent`, `OverwriteReason`, `OverwritePolicy` + the JVM suite at target §4.5 | `./gradlew :nfc-core:test`; `./gradlew :nfc-core:dependencies` shows **only the Kotlin stdlib** on the runtime classpath — no third-party, application or framework dependency |
| **3** | `nfc-android`: `NdefBridge` (+ the new `serialisedSize`), `NfcReaderModeSession` (doc comment carried across verbatim, including why the platform NDEF check stays on), `TagWriter`/`TagInspection`/`WriteResult` with the message-size capacity rule on both paths — `serialisedSize()` is exactly `toNdefMessage().toByteArray().size`, with **no TLV allowance** (target §4.3 invariant 7) — `TagHandle`/`TagIo`/`RealTagIo` | `./gradlew :nfc-android:testDebugUnitTest :nfc-android:assembleDebug`; **no dependency back into either app** (O15) |
| **4** | `tools/forbidden-scan.sh` + `forbidden-scan.allow`, wired as a `check` dependency and as the first CI step | the scan passes; every allow entry carries a reason, inspected not accepted (§22) |
| **5** | `README.md`: the provenance table, the invariant list, the public API, and the two *patterns* the library does not own — the reader-mode `LifecycleResumeEffect { start(); onPauseOrDispose { stop() } }` idiom, and the mint→write→verify→complete-else-abandon provisioning protocol (arch §6.1, §6.2). Plus an explicit note that `TagWriteSession` is **deferred** and on what criterion it would be promoted (target §4.7) | a reader who has never seen either app can tell where each file came from and what is deliberately absent |
| **6** | `.github/workflows/ci.yml` per target §9 | green once the remote exists (§B.1) |
| **7** | the emulator `androidTest` suite for the adapter | green on the emulator, **locally**; never in CI |

**Verify.** `./gradlew build` green from a clean clone; `tools/forbidden-scan.sh` exits 0; every
`git log --follow` starting point in the provenance table resolves in the ServiceTag repository.
**Rollback.** Delete the directory; nothing references it yet.

### A.4 Both apps consume the library (sequence G, O15)

**Task 1, before anything else: add the missing catalog alias.** Neither app's
`gradle/libs.versions.toml` has an `android-library` alias — the apps only ever needed
`android-application` **[code]** — so `nfc-android` cannot declare its plugin until each catalog, and
the library's own, gains:

```toml
android-library = { id = "com.android.library", version.ref = "agp" }
```

No `kotlin-android` alias is added: AGP 9.4 has Kotlin built in, `nfc-android` applies
**`com.android.library` only**, and `jvmTarget` is set inside
`android { kotlin { compilerOptions { … } } }` exactly as `app/build.gradle.kts` already does
**[code]** (target §3.1, §6.1). Verify by configuring both apps and the library standalone.

**Then**, in each app repository, once `GonzRon/nfc-tag-core` exists on the remote (§B.1):

```bash
git submodule add https://github.com/GonzRon/nfc-tag-core.git libs/nfc-tag-core
git -C libs/nfc-tag-core checkout nfc-tag-core-v0.1.0
git add .gitmodules libs/nfc-tag-core
```

Add the NEW block to `settings.gradle.kts` exactly as target §6.2 gives it (the `require` guard,
`include(":nfc-core", ":nfc-android")`, the two relocated `projectDir`s), the
`implementation(project(":nfc-android"))` line in `app/build.gradle.kts`, the CI checkout's
`submodules: recursive`, and the submodule-pin assertion step (target §6.3) — whose tag check is
`git describe --exact-match --match 'nfc-tag-core-v*' --tags HEAD`, an **exact product tag** rather
than any tag that happens to point at that commit — also kept as `tools/check-submodule-pin.sh` for
local runs. No branch is tracked: the submodule is deliberately detached at a tag, which makes
§19's "mutable HEAD not silently consumed" true by construction rather than by policy. Then delete from each app everything the library now
owns and replace it with the library's types — in ServiceTag that is `NdefBridge`,
`NfcReaderModeSession`, `TagWriter`, `TagIo`/`TagHandle`, the envelope half of `NdefCodec` and
`OverwritePolicy`, leaving the body codec, the row minting, the write protocol and every message
string behind in the app (target §4.7).

**Verify.**

1. `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug`
   in ServiceTag, and the equivalent in NoteTag.
2. The pin assertion passes in both, and **both apps are on the same library tag**.
3. **The four negative tests, demonstrated failing and then restored**: (a) `rm -rf libs/nfc-tag-core/*`
   → the `require` fires at configuration time with the fix command; (b)
   `git -C libs/nfc-tag-core checkout HEAD~1` → the pin assertion reports "not at an exact
   `nfc-tag-core-v*` tag";
   (c) `touch libs/nfc-tag-core/nfc-core/src/main/kotlin/x` → "submodule working tree is dirty";
   (d) remove a catalog alias the library uses → a configuration-time failure naming the alias.
4. **Clean-checkout proof, from the local path at this stage.** Nothing has been pushed yet except
   the library (§B.1), so the app repositories have no remote to clone from: clone **the local
   working repository** into a never-used directory and build there —
   `git clone --recurse-submodules ~/Documents/Projects/AndroidStudioProjects/<app> <tmp>` — which
   still proves what matters here: no uncommitted sibling file, no developer-local Gradle state and no
   absolute home path is load-bearing (§26). The **URL clone and the second-workstation proof** need
   the remotes and therefore happen at **§B.6**, after the pushes.

**Rollback.** `git submodule deinit -f libs/nfc-tag-core && git rm -f libs/nfc-tag-core`, revert the
settings and dependency commits. Each app's own NFC layer is still in its history and can be
un-deleted.

**Closing note (Phase G, 2026-09-17).** Phase G ended with the four verifications above done in
both apps (evidence file, Phase G section), both apps pinned to the same `nfc-tag-core-v0.1.0`
gitlink, the two tag-only `release.yml` workflows and their `tools/release-dry-run.sh` installed
and dry-run locally — never triggered, since no product tag exists (target §8) — and one
structural fact recorded in target §6.2 as amended: in both apps **`:core` depends on `:nfc-core`**
(Phase G ruling G-1), so the dependency order is `:app → :nfc-android → :nfc-core` and
`:app → :core → :nfc-core`.

---

## B. Remote transition (sequence K → L)

§27's order, *only after local proofs*: 1 checkpoint pushed · 2 rename `GonzRon/noteNFC` →
`GonzRon/ServiceTag` · 3 update local origin · 4 verify redirects/CI/webhooks · 5 create
`GonzRon/nfc-tag-core` · 6 push library history · 7 create `GonzRon/NoteTag` · 8 push the
reconstructed lineage · 9 configure CI independently · 10 move/recreate the explicit narrow-product
issues · 11 verify all three from clean clones · 12 **do not delete recovery refs until everything is
green.**

**§27 step 1 is already done**: the checkpoint was pushed in Phase A — tag `pre-split-checkpoint` and
branch `pre-split-master`, both at `ac523d7` — so the renumbered order below starts from step 5 and
the checkpoint step appears only as a pre-flight assertion in §B.2.

**One documented deviation, accepted by the controller.** Steps 5–6 (create and push the library) run
**before** step 2 (the rename). A git submodule needs a URL, so §A.4 — a *local* proof, which §27's
own preamble requires to come first — cannot complete until the library repository exists. §27 itself
provides for this: *"If hosting capabilities differ, make the most conservative reversible adjustment
and document it."* Creating a new repository mutates nothing that exists and is reversible by rename,
while the rename of the live repository is the first step that changes something people already
depend on. §27 steps actually run, in this order: **(1 done in Phase A) → 5–6 → 2 → 3 → 3a → 4 → 7 →
8 → 9 → 10 → 11 → 12** — at the **remote K/L** position of the canonical master order (front
matter), after §A's local proofs and before §C's phone work, save for steps 5–6 (§B.1), which run
early so that §A.4 can pin a real library URL, as noted above. Nothing in §B is strictly
irreversible — the rename reverses, the transfers reverse, and the new repositories can be renamed
aside — so "point of no return" language is avoided deliberately;
**the one genuinely irreversible step in this whole runbook is C.9**, the uninstall.

Capability note: the active `GonzRon` token holds `gist, read:org, repo, workflow` — enough to rename,
create and transfer — and **cannot delete a repository**; `delete_repo` lives only on the second,
inactive account (arch §4.3). **No step below requires a delete, and none may be designed to.**

### B.1 (§27 steps 5–6) Create and push `nfc-tag-core`

**Do.**

```bash
# GonzRon/nfc-tag-core already exists on GitHub — created EMPTY and public on 2026-09-17 (owner
# authorization, split ledger); no repo-create step here. The library's branch is `master` (F-4).
cd ~/Documents/Projects/AndroidStudioProjects/nfc-tag-core
git remote add origin https://github.com/GonzRon/nfc-tag-core.git
git push -u origin master
git tag -a nfc-tag-core-v0.1.0 -m "extracted from the ServiceTag tree; provenance in README"
git push origin nfc-tag-core-v0.1.0
# The empty repository carried GitHub's placeholder default_branch = main. After the first push,
# verify the ACTUAL default branch is master; if GitHub kept the placeholder, set it and check again,
# so ServiceTag, NoteTag and nfc-tag-core all end on the same convention.
gh api repos/GonzRon/nfc-tag-core --jq .default_branch            # expected: master
# if it printed main:
#   gh api -X PATCH repos/GonzRon/nfc-tag-core -f default_branch=master
#   gh api repos/GonzRon/nfc-tag-core --jq .default_branch          # expected: master
```

**Verify.** `gh repo view GonzRon/nfc-tag-core --json name,visibility,defaultBranchRef` (the default branch reads `master`);
`gh api repos/GonzRon/nfc-tag-core/tags` lists the tag; the Actions run is green (§B.6 enables it if
it is not on by default).

**The tag is created here, and accepted later.** `nfc-tag-core-v0.1.0` is cut **once the standalone
library is green** — it has to exist before either app can add a submodule pointing at it — but it is
**final only once both consuming apps are green against that exact tag** (§A.4, §B.6, target §10.3).
Until then it is provisional: if integration forces a library change, the tag is **deleted and
re-cut** rather than consumed as-is, because a tag two apps have already built against must never
move. Practically, that means the window between this step and §B.6 is the only window in which
deleting that tag is legitimate. **Rollback.** The token cannot delete: make it private and rename it
aside
(`gh repo rename nfc-tag-core-abandoned`), which frees the name; record the abandoned name in the
ledger.

### B.2 (§27 step 2) Rename the existing repository to ServiceTag

**Do.**

```bash
# Pre-flight (§27 step 1): the recovery refs must already be on the remote.
gh api repos/GonzRon/noteNFC/git/refs/tags/pre-split-checkpoint --jq .object.sha   # ac523d7
gh api repos/GonzRon/noteNFC/git/refs/heads/pre-split-master     --jq .object.sha   # ac523d7

gh repo rename ServiceTag --repo GonzRon/noteNFC
```

**Verify.** `gh repo view GonzRon/ServiceTag --json name,visibility,isFork,hasIssuesEnabled,defaultBranchRef`.
The rename must preserve: visibility PUBLIC, not-a-fork, issues enabled, default branch `master`, all
36 issues, the one git tag, the 2023 draft release, and the CI history (arch §4.1, §4.8). Check each.
**Rollback.** `gh repo rename noteNFC --repo GonzRon/ServiceTag` — reversible in both directions, with
GitHub redirecting either way. The recovery refs stay in place throughout and are never deleted. This
is the first step that changes something other people or other checkouts already resolve, which is
why it waits for every local proof — not because it cannot be undone.

### B.3 (§27 steps 3–4) Update local origins and verify redirects, CI and webhooks

**Do.**

```bash
cd ~/Documents/Projects/AndroidStudioProjects/noteNFC
git remote set-url origin https://github.com/GonzRon/ServiceTag.git
git fetch --all --tags && git rev-parse origin/master pre-split-checkpoint
git -C ../ServiceTag-split remote -v          # the worktree shares the repository's remote
curl -sI https://github.com/GonzRon/noteNFC | grep -i '^location'   # the old path redirects
gh api repos/GonzRon/ServiceTag/hooks --jq '.[].config.url'         # webhooks, if any
gh run list --repo GonzRon/ServiceTag --limit 3
```

**Verify.** The local remote resolves; the old path redirects; CI still triggers on a push; any
webhook still points somewhere valid. **Note**: the redirect from `noteNFC` lasts only until a new
repository claims that path — **nothing will**, because the narrow product is now `NoteTag` (O1), so
the redirect is permanent. **Rollback.** Rename back and reset the remote URLs.

### B.3a Land and push ServiceTag's converted code

Without this step the rename produces a repository whose `master` is still the **pre-split**
application: the identity conversion, the library submodule and the new workflow would exist only on
a local branch, so §26's clean-clone-from-URL proof and gate 10 could never pass. Only §A's
verifications gate it — the converted code is proven locally before it becomes ServiceTag's `master`.

**Do.**

```bash
cd ~/Documents/Projects/AndroidStudioProjects/noteNFC
git fetch origin && git switch master && git status --porcelain     # must be empty
git rev-parse master                                                # expect ac523d7
git merge --no-ff product-split -m "product split: ServiceTag identity, shared NFC library, docs"
git push origin master
```

`--no-ff` is deliberate: the conversion stays one reviewable merge rather than dissolving into
master's first-parent line, which keeps `ac523d7` findable as the last pre-split commit without
relying on the recovery refs.

**Verify.**

1. `git log --first-parent --oneline -3` shows the merge on top of `ac523d7`.
2. `gh run list --repo GonzRon/ServiceTag --limit 1` is **green on the new `ci.yml`** — the one with
   `submodules: recursive`, `fetch-depth: 0` and the pin assertion (target §6.3). A green run here is
   the first proof that the submodule mechanism works on a machine that is not this one. `release.yml`
   is present and has not run: no product tag exists yet.
3. The submodule is initialised at the pinned tag in the runner's checkout (the assertion step says
   so), and the catalog `agp`/`kotlin` diff is empty.
4. A fresh `git clone --recurse-submodules` of the **URL** builds — deferred to §B.6 with the others.

**Rollback.** `git push --force-with-lease origin pre-split-master:master` restores master to
`ac523d7` from the recovery branch, which is exactly what it was kept for;
`--force-with-lease` refuses if anyone else has moved master in the meantime. The merge commit stays
in the local repository and on `product-split`, so nothing is lost and the push can be retried once
the cause is fixed. The recovery refs are **not** deleted here or anywhere in §B (§27 step 12).

### B.4 (§27 steps 7–8) Create `GonzRon/NoteTag` and push the reconstruction

**Do.**

```bash
gh repo create GonzRon/NoteTag --public \
  --description "Attach a note or a useful link to a physical NFC tag." --disable-wiki
cd ~/Documents/Projects/AndroidStudioProjects/NoteTag
git remote add origin https://github.com/GonzRon/NoteTag.git
git push -u origin master
```

*Executed 2026-09-18 with one factual correction: `GonzRon/NoteTag` had been reserved empty on 2026-09-17, so the
`gh repo create` line was superseded by `git remote add origin` + `git push -u origin master`; GitHub had left the
placeholder default branch `main`, patched to `master` and re-read, as §B.1 did for the library; the description,
wiki-off and topics were applied with `gh repo edit` at the push.*

**Verify.** `git log --oneline origin/master | tail -1` → `5fb6aed`, proving the 2023 root travelled;
the repository carries **no** `docs/` directory and **no** `.apk`; the first `ci.yml` run is green (it
is the first run this lineage has ever had — review correction 8); `release.yml` is present and has
not run. **Rollback.** Cannot delete; rename
aside (`gh repo rename NoteTag-wip`) and re-push once fixed.

### B.5 (§27 step 10) Move the explicit narrow-product issues

**Do.** §10 and O8: only the explicit future-narrow-product work moves, by source semantics, never by
keyword. Transfer if the tooling supports it, else recreate with backlinks and mark the originals
moved.

```bash
# 1. Backlink FIRST, while the issue still has its original URL.
gh issue comment 6  --repo GonzRon/ServiceTag \
  --body "Moving to the standalone NoteTag product: https://github.com/GonzRon/NoteTag — see docs/architecture/product-split-migration.md."
gh issue comment 36 --repo GonzRon/ServiceTag --body "<same>"

# 2. Transfer.
gh issue transfer 6  GonzRon/NoteTag
gh issue transfer 36 GonzRon/NoteTag

# 3. Retitle under the NoteTag name (O8) and backlink the other way.
gh issue edit <NEW#> --repo GonzRon/NoteTag --title "Generalize external note/deep-link support beyond Joplin"
gh issue comment <NEW#> --repo GonzRon/NoteTag \
  --body "Transferred from GonzRon/ServiceTag (formerly GonzRon/noteNFC), where this product's history lives."
```

*Executed 2026-09-18: `gh issue transfer` worked (the capability is now exercised, not merely assessed): #6 → NoteTag #1,
#36 → NoteTag #2, backlinks both ways, bodies intact, old URLs redirect; ServiceTag holds 34.*

**Verify.** Both issues resolve in `GonzRon/NoteTag` with their bodies intact — in particular #36's
ownership statement — and the old URLs redirect; `gh issue list --repo GonzRon/ServiceTag --state all`
= 34. If transfer fails (the capability was assessed but never exercised — arch §4.3), fall back to
§10's alternative: recreate in NoteTag with the verbatim body, and close the original **marked moved**
with a pointer. **Rollback.** `gh issue transfer <NEW#> GonzRon/ServiceTag`; the backlinks stay and
become the audit trail.

### B.6 (§27 steps 9, 11–12) CI, clean clones, and the recovery refs

**Do.** Confirm Actions is enabled on both new repositories
(`gh api repos/<owner>/<repo>/actions/permissions`) and that each has a green run. Then verify all
three **from clean clones of their URLs** (§26, §27 step 11) — the proofs §A.4 could only run against
a local path, because the app remotes did not exist yet: for each repository,
`git clone --recurse-submodules <url> <tmp> && cd <tmp> && ./gradlew <that repo's CI task list>` in a
never-used directory, **and once more from a second workstation** for the two apps. The URL clone is
what proves the submodule's recorded URL and pinned commit are fetchable by someone who is not the
author; the second workstation is what proves no developer-local Gradle state was load-bearing.

**Verify.** Three repositories, three green CI runs, three clean-clone builds. **No secrets in any
`ci.yml`, no absolute home path, no developer-local Gradle state, no device id, no private signing
material in any source tree** (§26). The `release` environment exists in both app repositories with
its four secrets and the `RELEASE_CERT_SHA256` variable set (owner manual step; values never printed;
target §8), and it is **protected** — required reviewers or an equivalent protection rule — so GitHub
withholds its secrets until the rule passes; a tag-protection rule on `servicetag-v*` / `notetag-v*`
limits who can push a release tag (owner ruling 2026-09-17). *Executed 2026-09-18 from this machine: the three URL clean clones built with `--no-build-cache` (evidence file, K/L
section); the `release` environments exist and are protected in both app repositories, NoteTag's with its four secrets
and the public variable, ServiceTag's awaiting the ServiceTag key; the second-workstation proof for the two apps is the
owner's, on another machine, and is the one §B item still open.* **Only then**, and not before, may anyone consider the recovery refs
`pre-split-checkpoint` and `pre-split-master` retired — and this runbook does not retire them (§27
step 12, §35). **Rollback.** Disable Actions on the affected repository; no code change.

---

## C. Phone transition and the data migration proof (sequence H, §13–§15, O10)

**§15's on-phone order, with its withdrawn step marked as withdrawn:** **1** keep modern 2.4
installed · **2** export and verify the backup set · **3** install ServiceTag (new package)
alongside · **4** restore into ServiceTag · **5** prove ServiceTag's data and attachments ·
**6** *(§15's tag-proof step — **withdrawn** by O2, and gate 8 folded into gate 9; nothing happens
here)* · **7** only after ServiceTag is independently proven, uninstall the modern old-package app ·
**8** install NoteTag (new package; nothing reuses `com.loosecannon.notenfc`) · **9** coexistence
tests, **which are §D and §E of this runbook and run after this whole section completes**.

Step 6 is called out rather than quietly renumbered, because its old content was a *legacy* tag
migration and reading it as "the physical-tag proof happens here" would invert the canonical order:
the physical and coexistence work (**I/J** — §D and §E) requires **both final products installed**,
and NoteTag does not reach the phone until step 8. So §C runs to completion, including the uninstall
at C.9 and the NoteTag install at C.10, and only then does §D begin.

The order is not stylistic. Until step 5 passes, the old package is the **only** holder of the data
and the **only** holder of the SAF grant — a persisted grant is scoped to the calling application and
cannot cross a package boundary, even for the same signing key (arch §7.4).

**Two prohibitions that hold throughout** (§13, §35): **never delete, relocate or rewrite the live
attachment folder**, and no instrumented suite runs on the phone.

**The owner's four on-phone UI actions in this section**, which are **outside** the 12-action
tag/coexistence budget of §E.1 and are counted here instead: **(1)** *Settings → Backup → Export set*
(C.1); **(2)** *Settings → Attachment storage* → pick the SAF folder (C.3); **(3)** *Settings →
Backup → Import (replace)* (C.4); **(4)** *Settings → Backup → Restore files* (C.5). Four taps
through the phone's own UI — the first in the modern old-package app, the other three in ServiceTag —
each of which only the owner can perform because each needs the real
install and, for (2), the system document picker. Everything else in §C — pulls, hash verification,
the JSON comparison, the emulator observations, the installs and the uninstall — is
workstation-driven.

### C.1 (§15 steps 1–2) Snapshot before

**Do.**

Before anything else, read the preserved data archive once, off the phone: `unzip -p <data zip> data.json` and confirm its `nfcTags` array holds no row whose `payloadFormat` is anything but `V1`. ServiceTag's restore refuses an unknown enum name for the whole archive (`BackupFormat.enumOrCorrupt`), so a `LEGACY_MD5` row would abort §C.4 cleanly but completely. The set exported on 2026-09-16 has zero `nfcTags` rows (checked 2026-09-16 during Phase D), so this is a guard for a re-export, not a known problem.


```bash
export ANDROID_SERIAL=<the phone>      # set once, never printed into a document
STAMP=$(date +%Y%m%d-%H%M%S)
OUT=~/Documents/Projects/AndroidStudioProjects/noteNFC-backups/transition-$STAMP
mkdir -p "$OUT"
```

1. In the modern app: **Settings → Backup → Export set**. It writes both archives or neither — a set
   whose halves disagree cannot exist (arch §7.2).
2. Pull both archives to `$OUT`.
3. Capture the database + prefs snapshot the same way Phase A did (the installed build is debuggable).
4. Record the installed app's `versionName`/`versionCode` and its certificate SHA-256, so §I knows
   what it is restoring to.

**Verify.**

```bash
cd "$OUT"
unzip -p <data>.zip manifest.json | jq '{formatVersion, schemaVersion, appVersion, backupSetId, counts, artifactCount, artifactBytes, dataSha256}'
# formatVersion 5, schemaVersion 5, backupSetId non-empty (a blank one at format 5 is refused as
# corrupt, because it could never pair with an artifacts file -- arch §7.2)
unzip -p <data>.zip data.json | sha256sum                     # must equal manifest.dataSha256
unzip -p <artifacts>.zip manifest.json | jq '{artifactFormatVersion, dataFormatVersion, backupSetId, entries: (.entries|length)}'
# same backupSetId as the data manifest; entries == artifactCount
```

Verify every artifact entry's SHA-256 against its manifest row. The pre-split baseline (arch §7.5, and
O10's list) is assets **5**, nfcTags 0, externalLinks 1, measurementDefinitions 13, eventProfiles 15,
profileFields 22, profileConsumables 14, assetEvents **26**, measurements **68**, consumableUsages 4,
attachments **8**. A count that has moved *up* since 2026-09-16 is fine — this export is the baseline
from here. A count that has moved *down* is a stop.

**Rollback.** Nothing changed; re-export.

### C.2 (§15 step 3) Install ServiceTag alongside

**Do.** Build the ServiceTag debug APK from the converted tree and install it. **Uninstall nothing.**
The applicationIds differ, so the OS treats them as unrelated apps and both sit on the launcher — with
**distinct icons and labels**, which is why task A.1/9 exists (review addition 3).

**Verify.** Both packages present; both launch; the modern app's data untouched (open it and see the
five assets); ServiceTag's database empty — a fresh package gets a fresh database, never a copied file
(arch §7.6). **Rollback.** Uninstall the ServiceTag package; the old package is untouched.

### C.3 (§15 step 4a) Acquire ServiceTag's own SAF grant — the same tree first

A grant cannot be inherited: a persisted grant is scoped to the *calling* application, so a different
`applicationId` holds none of another app's grants even for the same signing key, and ServiceTag must
drive its own `OpenDocumentTree` → `takePersistableUriPermission`. **This is a
**[platform-doc]** claim, not an observation** — arch §7.4 records it as such, and
`archaeology-data.md` notes it was written from public API documentation rather than a byte-for-byte
fetch. It is load-bearing (it is why §15 installs ServiceTag alongside and uninstalls the old package
last), so it is **observed on the emulator before C.9** rather than taken on trust: step C.8a below.

**Do.** ServiceTag → **Settings → Attachment storage** → pick **the same folder the modern app already
uses**. The take is wrapped in `runCatching`; only a successful take updates the pref, and the screen
re-reads the store state so it shows what is actually true — some cloud-backed providers refuse a
lasting grant (arch §7.4).

**Same-tree discovery via stable locators** — the optimisation §13 allows, not a substitute for proof.
Every attachment locator is **store-relative**: `assets/<asset-id>/<attachment-id>.<ext>` or
`events/<event-id>/<attachment-id>.<ext>`, with no drive letter, authority, tree-URI fragment or
package name anywhere in it, confirmed against the real set's 8 rows (arch §7.4). The right tree is
simply the one that already contains those relative paths:

```bash
unzip -p "$OUT"/<data>.zip data.json | jq -r '.attachments[] | .storageLocator'
```

Confirm in ServiceTag's store-health readout that all 8 resolve **before any restore runs**. 8 of 8
means the same tree.

**If the grant is refused** (a cloud-backed provider that will not persist one), do not fight it: this
becomes the empty-tree case, which §13 requires to be proved independently anyway (C.7). **Never
delete, relocate or rewrite the live folder to make a grant work** (§13).

**Verify.** `StoreState` is `Ready(displayName, authority)`, not `NotConfigured`, not `AccessLost`;
the ServiceTag package holds exactly one persisted grant — a successful take of a *different* folder
releases the previous one, and grants otherwise accumulate (arch §7.4, spike S5). **Rollback.** Revoke
in system settings or pick again; picking is idempotent and the pref is written only on success.
Nothing in the tree has been written.

### C.4 (§15 step 4b) Restore the data

**Do.** ServiceTag → **Settings → Backup → Import (replace)** → the pulled `<data>.zip`.

The importer decodes **outside** the transaction — "refuse before touching data" — then, inside one
write transaction, deletes in reference-clearing order and inserts in reference order: assets
parents-first, definitions ENTERED-then-DERIVED, profiles, links, tags, events, attachments last
(arch §7.2). `validateGraph()` re-checks every FK-shaped reference and every locator shape *before*
the transaction opens; a format-5 file with a blank `backupSetId` is refused as corrupt.

**Why this needs no code change.** The backup content carries **zero package or product identity**:
grepping the real pulled `manifest.json`, `data.json` and the artifacts `manifest.json` for `notenfc`,
`loosecannon`, `com.loosecannon`, `NoteNFC`, `noteNFC` returns **zero hits in any of the three files**,
`appVersion` is a bare semver string, and the import path never inspects a file name or extension for
meaning (arch §7.3). A renamed build with an unmodified codec layer imports the preserved files
unchanged.

**Verify.** The import reports the same eleven counts as the manifest; `AppPrefs`'
`last_restored_backup_set_id` now holds the set id (it was **absent** before — no import had ever run
against this data, arch §7.5). **Rollback.** All-or-nothing inside one transaction; a failure leaves
ServiceTag's empty database as it was. If it succeeds but proves wrong, uninstall ServiceTag — the old
app still holds everything.

### C.5 (§15 step 4c) Restore the artifacts

**Do.** ServiceTag → **Settings → Backup → Restore files** → the pulled `<artifacts>.zip`.

`RestoreArtifacts.run(archive, expectedSetId)` throws `ArtifactsSetMismatch` **before touching any
entry** if the archive's `backupSetId` disagrees with the data restore's
`lastRestoredBackupSetId`, and is idempotent: bytes that already hash correctly are `alreadyPresent`,
never rewritten. Each entry is verified **twice** — the manifest's `sha256` against the local row
before anything is opened, and the store's own streaming digest after the write (arch §7.2).

**Verify (same-tree case).** **8 `alreadyPresent`, 0 written, 0 skipped** — the files are already
there and already hash correctly. That is the same-tree proof: idempotence, not a copy. A single
`written` means the tree was not the one in use; a `skipped` means a hash mismatch and is a stop.
**Rollback.** Nothing is written in the same-tree case. In the empty-tree case the restore deletes any
stale document at a locator first and deletes the partial document on failure, so a failed entry
leaves no debris (arch §7.4).

### C.6 (§15 step 5, §24) The data proof — stable-id comparison

**The principle** (arch §7.4): every id in the backup format is a verbatim UUID string, every
relationship is by id, **no id is package-, device- or install-qualified**, so *an id that exists
pre-split must exist, unchanged, post-split*. That sentence is the proof.

**Do.** Export a fresh set from the **restored ServiceTag**, pull it, and compare its `data.json`
against the preserved one. `BackupCodec.encode()` sorts every list by `id`, and children by
`sortOrder` within parent, so the same input always produces the same bytes (arch §7.2) — a
`data.json` differing only in the fields listed in C.8 is a pass.

```bash
unzip -p "$OUT"/<preserved-data>.zip data.json > /tmp/before.json
unzip -p "$OUT"/<servicetag-data>.zip data.json > /tmp/after.json
diff <(jq -S . /tmp/before.json) <(jq -S . /tmp/after.json)   # expect empty, or only C.8 fields
```

Then the per-table assertions. **Compare, for every one of the eleven tables, the id set and every
relationship field** (arch §7.1, §7.4, §7.6, with the event rows corrected per review correction 17):

| Table / DTO list | Ids | Relationship and identity fields to compare |
|---|---|---|
| `asset` / `assets[]` | `id` set, exactly | `parentAssetId` (nullable, self-referencing) — and rebuild the tree from ids and diff it against the pre-restore tree; the importer's `AssetTree.parentsFirst` re-validates no-cycle on the way in. Plus every metadata field O10/§13 names: manufacturer, model, serial, purchase and in-service dates, price + currency, vendor, location, warranty, **retirement**, **season window**, status, category |
| `nfc_tag` / `nfcTags[]` | `id` set | `assetId` **xor** `linkId`, `payloadFormat`, `payloadKey`, `status`, `physicalUid`. **Empty in this dataset** (`nfcTags = 0`) — asserted empty, and the assertion stays for whenever tags exist |
| `external_link` / `externalLinks[]` | `id` set | `assetId` (nullable), `kind`, `uri`. **The row most at risk**: a single `JOPLIN` link with `assetId = NULL` — unowned, referenced by nothing, therefore the one most easily lost (arch §7.5) |
| `measurement_definition` / `measurementDefinitions[]` | `id` set | `assetId`, `kind`, and for `DERIVED` rows `formula`, `sourceAId`, `sourceBId` (self-referencing, RESTRICT) |
| `event_profile` / `eventProfiles[]` | `id` set | `assetId` |
| `profile_field` / `eventProfiles[].fields[]` | nested `id` set | `definitionId`, and the `(profileId, definitionId)` uniqueness |
| `profile_consumable` / `eventProfiles[].consumables[]` | nested `id` set | parent `profileId` (implicit in nesting), values |
| `asset_event` / `assetEvents[]` | `id` set | **per-field equality, not just ids** (review correction 17): `assetId`, `profileId` (nullable), **`occurredOn`**, **`tzId`**, **`createdAt`**, and the **`(source, source_ref)`** pair with its uniqueness constraint. A timestamp or zone that shifts is a migration failure, not a cosmetic difference |
| `measurement` / `assetEvents[].measurements[]` | nested `id` set | `definitionId` (RESTRICT), parent `eventId` (implicit), value and unit |
| `consumable_usage` / `assetEvents[].consumables[]` | nested `id` set | parent `eventId` (implicit), amount and unit |
| `attachment` / `attachments[]` | `id` set | owner = **exactly one of** `assetId` / `eventId` (XOR, enforced in `toDomain()` and by `validateGraph()`), plus `sha256`, `sizeBytes`, `storageLocator`, `mode`, and the `(storageProvider, storageLocator)` uniqueness |

**The attachment byte proof — three independent hashes per row, eight rows** (§24). For each
attachment, assert:

1. `AttachmentDto.sha256` in the restored export **==** the same field in the preserved export;
2. **==** the artifacts manifest's per-entry `sha256` for that `attachmentId`;
3. **==** `sha256sum` of the **original file** — read back at its locator after the restore, and
   independently out of the preserved artifacts archive entry `artifacts/<attachment-id>.<ext>`, whose
   extension is taken from the *locator* so entry name and locator can never disagree (arch §7.2).

Also assert `sizeBytes` on all three sides and `artifactCount` == the attachment-row count (8 = 8).
This is the same check `RestoreArtifacts` performs twice at runtime; doing it a third time from the
workstation is what makes it *evidence* rather than a claim.

**Verify (the gate).** Eleven tables with identical id sets; every relationship and per-field
comparison equal; 8×3 hashes equal; `5.json`'s `identityHash` unchanged and matching the new package's
live `room_master_table.identity_hash`; `PRAGMA user_version` = 5 (arch §7.1). Record the whole result
as a table in `docs/architecture/product-split-evidence.md` (§29).

**Rollback.** On any failure: **do not uninstall anything.** The old package still holds the live data.
Uninstall ServiceTag, fix, reinstall, restore again — replace-mode restore is idempotent from the same
archive.

### C.7 The empty-tree restore, proved independently (§13)

§13 is explicit: same-tree discovery is *an optimisation, not the only proof*; the set **must
independently prove portability to an EMPTY attachment tree**. The same-tree case proves idempotence;
it does not prove the restore can write bytes.

**Do.** Install ServiceTag on the emulator, point its store at an empty folder, and restore data then
artifacts from the same two archives. (If C.3's grant was refused on the phone, this is also the
phone's path — into a new, empty folder, never the live one.)

**Verify.** **8 written, 0 alreadyPresent, 0 skipped**; every file lands at its store-relative locator
under the new root, proving locators are store- and package-agnostic by construction (arch §7.4); all
8×3 hashes equal; the same eleven-table comparison passes. Note that `documentIn()` falls back from an
exact name match to "the first child whose name starts with `<id>.`", because some SAF providers
append or normalise extensions — a restored file whose extension differs is a pass, provided its hash
matches. **Rollback.** Wipe the emulator; the phone was not touched.

### C.8 Fields that legitimately differ (§24)

A difference in any of these is **not** a migration failure. Anything *not* listed must be identical,
because every remaining field is domain data (arch §7.6).

| Field | Why it differs |
|---|---|
| `manifest.backupSetId` | minted fresh per export — a pairing token between one data archive and one artifacts archive, not a stable identity |
| `manifest.createdAt` | the new export's timestamp |
| `manifest.appVersion` | `BuildConfig.VERSION_NAME`; carries no package identity, and ServiceTag's version was bumped |
| Export file names (`noteNFC-data-*` → `ServiceTag-data-*`) | cosmetic; the importer never reads a file name |
| The Room database **file** and its name (`notenfc.db` → `servicetag.db`) | a fresh package gets a fresh, empty database populated by the *data* restore, never a copied file (§13: no private-database copy migration) |
| Schema export directory (`<old FQN>/` → `<new FQN>/`) | a build artefact derived from the `AppDatabase` FQN, not runtime state |
| `attachment_tree_uri` pref | **must** be re-chosen: a persisted SAF grant is scoped to the calling application and cannot cross a package boundary, even for the same signing key |
| `last_backup_at` pref | device-local, never in a backup |
| `last_restored_backup_set_id` pref | absent before (no import had ever run), set after |
| `appearance_mode` pref | never explicitly set, so it falls back to its `SYSTEM` default and is never written |
| Thumbnail cache (`<cacheDir>/thumbs/…`) | purely derived and regenerable, in a directory that "may vanish at any time" |
| `shared_prefs/storage_spike.xml` | one `tree_uri` key outside the `AppPrefs` contract — residue from spike S5; device-local, not preserved (arch §7.5). Clearing it is optional |
| SAF `displayName` / `authority` in `StoreState` | derived from the newly granted tree |
| `external_link.lastOpenedAt` | changes if the link is launched during the proof; compare it before launching anything |
| `nfc_tag.lastScannedAt` / `writtenAt` / `physicalUid` | a read scan performs a write (arch §5.9), so any tag row touched during §D or §E moves. Not applicable before §D: `nfcTags = 0` |
| Launcher icon, label, theme, package-derived authorities | the deliberate identity changes of §11 |

### C.8a Observe the per-package grant claim on the emulator — before the uninstall

The whole point of §15's ordering rests on a **[platform-doc]** claim (§C.3). Before acting on it
irreversibly, watch it happen somewhere harmless. Workstation-driven; **no owner action**.

**Do.** On the emulator, install **two** packages that both use a SAF tree — the ServiceTag build and
a throwaway variant of the ServiceTag build with `applicationIdSuffix = ".spike"` (so C.8a runs before C.10 and does not depend on NoteTag existing). Point **both** at the **same** folder, each through its own
`OpenDocumentTree` flow. Then, from the workstation, read each package's persisted-permission list
and uninstall one.

**Verify.** Three things, each recorded as an evidence row:

1. Each package's `getPersistedUriPermissions()` returns **only its own** grant — one folder, two
   independent grants, neither visible to the other.
2. Uninstalling one package leaves the other's grant intact and its files readable, which is exactly
   the property C.9 depends on.
3. A package that has *not* run its own picker holds **no** grant on that folder, however the other
   package's grant was obtained — the claim that makes "ServiceTag must take its own" true.

If any of the three does not hold, **stop before C.9**: the ordering assumption is wrong and the
phone's only copy of the data is still behind the old package. **Rollback.** Wipe the emulator;
nothing on the phone was touched.

### C.9 (§15 step 7) Uninstall the old package — only now

**Do.** Only after C.6 passes and its evidence table is written: uninstall `com.loosecannon.notenfc`.

**Verify.** ServiceTag still shows all five assets, 26 journal events and 8 attachments with
thumbnails, and the attachment files still open in the system viewer — the SAF tree belongs to the
folder, not to the uninstalled app, and ServiceTag holds its own grant. That last clause was
**[platform-doc]** until C.8a; it is an observation by the time this step runs, which is the only
reason this step is safe to take. **Rollback.** Reinstall the
preserved signed 2.4 / vc6 APK from `~/Documents/Projects/AndroidStudioProjects/noteNFC-releases/` and
import the preserved set into it. **This is the only genuinely irreversible step in the runbook**,
which is why C.6's proof is its precondition (§35).

### C.10 (§15 step 8) Install NoteTag

**Do.** Build and install NoteTag's debug APK. Nothing reuses `com.loosecannon.notenfc`, so there is
no collision and no ordering hazard (O1) — unlike a design that kept the old identity, this step has
no precondition beyond C.9.

**Verify.** Both `com.loosecannon.servicetag` and `com.loosecannon.notetag` are installed with
**distinct icons and labels**; each launches; neither sees the other's data. **Rollback.** Uninstall
NoteTag; ServiceTag is unaffected.

---

## D. Physical tags — Session 1: writer mechanics (sequence I)

Tag *migration* is withdrawn (O2; §34 gate 8 folded into gate 9; §5's "physical-tag proof — now:
coexistence of final products only"). What remains on the phone is the irreducible minimum: the
mechanics that need a real radio and a real chip. **Everything that can be proved without RF has
been moved off the phone** — see §D.3.

### D.1 Physical tags — five required, one optional

| # | Tag | Prepared how | Used for |
|---|---|---|---|
| **T1** | blank, **unformatted** NTAG213 (`NdefFormatable`, not yet `Ndef`) | out of the packet; becomes a NoteTag tag in Session 1 and ends read-only | the format path **and** lock-last in one tag, in the H3 shape: **tap 1 is `format(null)` — format only, empty and unlocked, no payload**; **tap 2** takes the tag as `Ndef` and does everything that needs a capacity figure — read `maxSize`, capacity-check, write, verify the read-back, then lock. It is the **only** tag ever locked. Tap 2 is also where the **real `Ndef.maxSize` of a physical NTAG213 is captured** and written to the evidence file, replacing the **[unobserved]** provisional 137 B seed of `NTAG213_MAX_MESSAGE_BYTES` (target §4.9) — so every JVM capacity fake is pinned to a measured number, never to a vendor datasheet figure |
| **T4** | **NoteTag `JOPLIN_NOTE`** canonical tag | written in Session 1 | the canonical NoteTag write; then Session 2's cold ambient tap, ServiceTag's sibling-refusal read, and the dispatch-spike tap |
| **T2** | **ServiceTag asset** canonical tag | written in Session 1 | the canonical ServiceTag write; then Session 2's ambient tap and NoteTag's sibling-refusal read |
| **T3** | **ServiceTag standalone-link** tag | written in **Session 2** | §25 row 3, kept as a physical row because §25 says **observe, don't infer**: the no-UI launch path and the `last_opened_at` stamp are what the row is about, and a synthetic intent would only re-prove the wire shape the asset tag already proves |
| **T7** | a commercial **URL sticker** | any retail NFC sticker | §25 row 6, "unrelated/foreign tag → no unsafe action" — and the one row where the expected result is *a platform notification*, not silence (**[platform-doc] PD10**) |
| *(opt)* | a tag with an unrelated **external type** | a generic third-party NFC writer | optional observation: the `Foreign` branch with a type string that is neither product's. Not a gate — the sibling reads already exercise that code path |

All NTAG213 or larger: NTAG213 is the minimum supported tag (O14). Message sizes, in the unit the
write check actually uses (target §4.3 invariant 7, §4.9): NoteTag `JOPLIN_NOTE` **49 B**;
ServiceTag's record with its AAR **95 B** under the **new** identity — `(3 + 30 + 18) + (3 + 15 + 26)`,
not the 89 B the archaeology computed against the shorter old identity — both comfortably inside what
an NTAG213 reports through `Ndef.getMaxSize()`, which Session 1 tap 2 measures.

### D.2 Session 1 — four owner taps

Each row is one deliberate tap in an explicitly-opened writer screen (§25: "intentional writes only
via explicit writer UI"). Installs, force-stops, `logcat` and `dumpsys` are workstation-driven.

| # | Owner action | Pass condition |
|---|---|---|
| **1** | **T1**, first tap, in **NoteTag's writer** | **`format(null)` only: the tag is formatted, left EMPTY and UNLOCKED, and no payload is offered to it.** The screen says to lift it off and hold it again. This is not a stylistic choice — `NdefFormatable.format(firstMessage)` formats *and* writes in one operation and there is no `Ndef`, hence no `maxSize`, until afterwards, so handing it the intended message would let a too-large message fail *inside* the format call with no capacity verdict possible (target §4.3 invariant 7, H3) |
| **2** | **T1**, second tap, with the lock armed | the tag now arrives as `Ndef`. In one tap: **read `maxSize`** — *this* is the Session-1 measurement, the first real capacity figure that exists, **recorded in the evidence file** and the number every JVM capacity fake is pinned to (§D.3) — then capacity-check the intended message against it, write it, compare the read-back structurally (record count, order, TNF, full type, full payload), and **only then** apply the lock, whose success is asserted from the **`makeReadOnly()` return value** on this same tap. **This one tag exercises format → measure → capacity-check → write → verify → lock-last**, which is why no separate lock tag exists and why no thirteenth interaction is needed to confirm the lock |
| **3** | **T4**, one tap, NoteTag's writer | a canonical `JOPLIN_NOTE` tag: **one external record, no AAR** (O13); read-back verified; `needed` was the exact encoded message size (49 B) |
| **4** | **T2**, one tap, ServiceTag's writer | a canonical ServiceTag asset tag: read-back verified; the `nfc_tag` row's `writtenAt` and `physicalUid` stamped |

**Four owner taps.** Nothing else in this session requires the owner.

### D.3 What moved off the phone, and where it went

Each of these was a candidate physical row and is now cheaper, faster and more repeatable elsewhere.
The operating rule drove every reassignment: *vet everything possible on the emulator; the phone only
for RF hardware or the real install.* What did **not** move is listed in §D.1 and §E — §25's
"observe, don't infer" wins wherever a row is about observed platform behaviour rather than about
arithmetic or a decision table.

| Former physical row | Now proved | How, and under what condition |
|---|---|---|
| URI-vs-`LOCAL_REF` **capacity selection** | **JVM tests with fake capacities** | drive the writer's decision with injected `maxSize` values around the boundary: an exact encoded message at `maxSize`, `maxSize - 1` and `maxSize + 1`. **Condition (controller ruling): the fake's baseline is the REAL `Ndef.maxSize` captured from the physical NTAG213 in Session 1 tap 1 and recorded in the evidence file** — a fake pinned to a datasheet number would not be evidence about the tags the owner actually uses. Asserts compact → URI-if-it-fits → `LOCAL_REF`, and that the fallback fires **only** when the message genuinely does not fit, which is the failure mode G1's arithmetic correction exists to prevent |
| `LOCAL_REF` **missing-map** behaviour | **JVM / app test** (condition: stays a JVM/app test, not a device row) | resolve a `LOCAL_REF` body with the local store empty or the entry removed: a clear message, no crash, no silent nothing |
| `LOCAL_REF` **crash consistency** | **JVM failure-injection test** (§A.2 task 7, G2 as corrected by H1) | three cases: persist ok + an **ambiguous** write failure → the mapping is **RETAINED**, because the write may physically have landed and an orphaned live tag is far worse than an orphan JSON entry; persist fails → **no write is attempted**; cancellation or a **pre-write** rejection (capacity, foreign, read-only, unsupported) → the mapping **may be** removed |
| Per-package **SAF grant** behaviour | **emulator observation, C.8a** | two packages, one folder, one grant each; the claim behind §15's ordering stops being **[platform-doc]** before C.9 acts on it |
| Separate **lock** test | **folded into T1**, taps 1–2 | one tag now carries format, verify and lock |
| Separate **foreign-record write** test | **folded into the sibling reads**, §E checks 5–6 | a sibling's tag is a foreign record as far as the overwrite decision is concerned, so the same confirmation path is exercised by a tap that also proves a §25 row |
| **Warm / from-recents / screen-off** dispatch states | **optional, workstation-logged observations** | logged from `logcat`/`dumpsys` while the owner is already tapping in §E; never separate owner actions. **P21 is the spike's default outcome** — ServiceTag keeps its AAR, NoteTag ships without one — and only a surprise in these logs would reopen it |

### D.4 How each observation is recorded

One row per observation in `docs/architecture/product-split-evidence.md` (§29), in the same shape as
the existing `docs/design/phase-*-evidence.md` files, so it is comparable to the 1B/1C rows this
design leans on. Schema: `id | question | precondition (which apps installed, which build, which tag)
| action | expected (with its claim tag) | observed | verdict | date | build`.

The operator captures `adb logcat` and the relevant `dumpsys` output for the whole session, writes the
observed column verbatim including surprises, and marks PASS / FAIL / INCONCLUSIVE. **An INCONCLUSIVE
row stays INCONCLUSIVE** — 1B row 13 is the precedent (arch §5.10). A claim tag in the target document
moves from **[unobserved]** to **[device-observed]** only for rows that passed, and an observation
that contradicts a **[platform-doc]** expectation is recorded as a contradiction in both documents,
exactly as arch §5.10 does for stopped-state dispatch. §29 forbids secrets and device ids in the
evidence file; certificate fingerprints only.

**One caveat the evidence file must state in its own words.** Everything observed in §D and §E is
**debug-build evidence** — debug builds are what §15 installs and what the emulator runs. The
signed-release requirement of §12 is discharged separately and only as a **build-verified** claim: a
release APK is produced and signed and its certificate fingerprint is recorded, but **no device row
is collected from a release build**. Nobody may later read a debug observation as a release
guarantee, and §29's evidence file says so on its first page.

**Rollback (this session).** Any tag can be rewritten **except T1 once locked**, which is the point of
locking it and the reason no other tag is locked. No app data changes beyond the rows the writers
create, and a cancelled write leaves no row behind (`abandonIfUnwritten`). The eight legacy field tags
are untouched — §D.5.

### D.5 The eight legacy tags — inventory facts only

Eight physical `md5_short` tags exist on the owner's equipment, one of them targeting a standalone
note rather than an asset; the census is `docs/design/g1/00-source-data-inventory.md` (review
correction 3, addition 2). **The historical package is not installed. Its old private lookup map is
not part of any supported migration path and is intentionally abandoned.** (Evidence: an
attached-phone `pm list packages` listing showed only the lower-case current package; the exact
`pm path` check is scheduled for the next time the phone is attached, so nothing here claims the old
data is proven gone.)

Their lifecycle is not a migration: an old tag on a machine is currently useless; when convenient, the
owner opens that machine in ServiceTag and writes the canonical ServiceTag tag; done. The normal
writer's read-before-write step already presents a foreign or unreadable record as a one-confirmation
overwrite, offering **Write over it / Cancel**. **No wizard, no resolver, no compatibility mode, no
acceptance row, no migration code** (O2, O11).

---

## E. Session 2 — coexistence acceptance (sequence J, §25 as revised by O9)

Preconditions: both apps installed (C.2 and C.10), both on the **same** library tag, Session 1's three
tags written, `adb logcat` capturing from the workstation throughout.

| # | Owner action | §25 row | Pass condition |
|---|---|---|---|
| **1** | in **ServiceTag's writer**, write **T3** as a **standalone-link** tag | row 3 (write half) | read-back verified; the row's target is the link, not an asset. Kept physical because §25 says observe, don't infer, and the row that matters is the *launch* behaviour in check 4 |
| **2** | **cold ambient tap of T4**, apps closed, from the lock or home screen. **The first-use NFC permission dialog for NoteTag, if it appears, is confirmed on this tap** | row 1 | NoteTag resolves and the correct note opens in Joplin through a safe `ACTION_VIEW`. **No chooser.** No ServiceTag window. This tap is also **dispatch-spike S1**: it answers whether an AAR-less external-type-only tag reliably reaches an installed app (O13). Warm, from-recents and screen-off repeats are optional workstation-logged observations (§D.3) |
| **3** | **ambient tap of T2**. **ServiceTag's first-use dialog, if any, is confirmed here** | row 2 | ServiceTag opens that asset. No chooser, no NoteTag window. The same spike question for the app that still carries an AAR (ratified P21), so this tap is what S1 compares against |
| **4** | **ambient tap of T3** | row 3 (launch half) | the link launches with **no app screen at all**, and `last_opened_at` is stamped (arch §5.11). This is the row a synthetic intent could not honestly prove |
| **5** | with **NoteTag's writer** open, read **T2**, then **Cancel** | row 4 | the tag is **named** as another app's — the reason carries the full type string from `Foreign.description` — and exactly two actions are offered, **Write over it** and **Cancel**. **Cancel** leaves the tag byte-identical; no NoteTag record is created; no cross-product action of any kind is offered (ratified P11) |
| **6** | with **ServiceTag's Read / inspect tag** open, read **T4** | row 5 | named as another app's tag; **Write over it / Cancel** only; no row created, no lookup performed, no transaction opened (arch §5.12) |
| **7** | **ambient tap of T7**, the commercial URL sticker | row 6 | **no unsafe action**: neither app draws a screen, neither writes to its database. A platform notification is the *expected* result, not a failure — **[platform-doc] PD10**: `ACTION_VIEW` from Android 16, an "open link" notification from Android 17 |
| **8** | **uninstall NoteTag** (workstation), then **one tap of T4**, then **reinstall** (workstation) | — | **dispatch-spike S2**: with no app claiming the external type and **no AAR to fall back to**, the expected result is nothing at all — `NDEF_DISCOVERED` no match, `TECH_DISCOVERED` no filter, stop. **To observe on-device.** The uninstall and reinstall are workstation commands; only the tap is the owner's |

**One further tap, ServiceTag's alone and pre-existing** — outside the 12-action coexistence budget
because it is neither a coexistence row nor anything the split introduced. It is here because the
physical gate is the only place that can see it:

| # | Owner action | Pass condition |
|---|---|---|
| **R1** | with **ServiceTag** open, move between **Read / inspect tag** and the **write tag** screen — both directions — and tap **T2** on the screen that survives the transition | ServiceTag used to compose **two** reader-mode sessions (`ScanScreen` and `WriteTagScreen`); on a nav transition that overlapped both, one session's stop could leave the app with no reader mode. **From 2.7 this is structural**: one session belongs to the activity (`app/…/ui/nfc/ReaderMode.kt`), the two screens install a tag sink instead of a session of their own, and the hold spans both routes — so a transition makes no `enableReaderMode`/`disableReaderMode` call at all, proved on the JVM by `ReaderModeTest` and on the emulator by `ReaderModeHoldTest`. The tap is what remains: only the phone can show that the platform agrees, and that the surviving screen really does read the tag. Pre-existing ServiceTag behaviour, **ServiceTag only** — NoteTag has a single writer screen and no overlap to lose **Retired at 2.7 (owner ruling 2026-09-18): non-diagnostic as written — its outcome is contaminated by the inspect screen's auto-open of a bound tag and does not isolate session continuity; the invariant is proven by `ReaderModeHoldTest` on the emulator; kept for history, not required for a release.** |
| **R2** | with **ServiceTag** open on **Read / inspect tag**, hold **T4** (NoteTag's tag) against the phone and **leave it there** until the answer appears; then take it away. Then, back on **Read / inspect tag**, hold **T2** (a tag bound to a ServiceTag asset) the same way, leave it there until the answer appears, take it away, tap **Open asset**, and press **back** once | issue **#37**'s acceptance, and check 6 of §E repeated with the tag left in the field — which is what the 2026-09-17 gate observed going wrong (§E check 6's note, evidence P5): ServiceTag released reader mode ~200 ms after its read and the platform dispatched the tag to NoteTag, which opened the note. Pass: the inspect screen names the tag as another app's and **nothing else opens** — no NoteTag window, no note, no chooser, no second dispatch — and the answer appears on the inspect screen itself rather than on a screen pushed over it. 2.7 holds the activity's one session for as long as a tag-reading screen is on top and draws the answer in place, so the tag stays ServiceTag's until the owner leaves the screen. For **T2**, since issue **#41** (every build from **1.0.0** on): the asset **does not open by itself**. The answer names it — the asset's name, its `id · v1` line, an **Open asset** action and **Cancel** — and the phone stays on the inspect screen for as long as the owner leaves the tag there, so the hold is never released with a tag in the field. **Open asset** then opens that asset **once**, and **back** returns to **Read / inspect tag** showing READY TO SCAN. Pass: one asset screen, no duplicate, no chooser, and the inspect screen underneath it when back is pressed. The duplicate this row used to record as an acceptable outcome is gone by construction — there is no auto-open left to release the hold — so a second copy of the asset screen is now a **failure**, not a variant. **ServiceTag only**, two owner actions, outside the 12-action budget for the same reason R1 is |

**Read from the logs, with no tap** — §25's remaining rows:

| §25 row | How it is established |
|---|---|
| **row 7** — ambient read never enters write mode | observed on checks 2, 3, 4 and 7: no writer UI, no reader mode, no `Tag` handle obtained on the ambient path. Structurally guaranteed too — the trampoline never calls `nfcTag()` (arch §5.10) |
| **row 8** — intentional writes only via explicit writer UI | established by Session 1's four taps and check 1: every write came from a writer screen the owner opened |
| **row 9** — no unpredictable chooser from overlapping identity | zero chooser dialogs in the logs across checks 2, 3, 4 and 7 |

### E.1 The owner-action count — 12

| Session | Owner actions | What they are |
|---|---|---|
| **1 — writer mechanics** (§D.2) | **4** | T1 tap 1 (format only), T1 tap 2 (measure → capacity-check → write → verify → lock), T4 write, T2 write |
| **2 — coexistence** (§E) | **8** | T3 write, T4 cold ambient, T2 ambient, T3 ambient, T2 in NoteTag's writer, T4 in ServiceTag's inspector, T7 ambient, and the uninstall/tap/reinstall spike tap |

**Total: 12 owner actions, in two sessions — and 12 is the *tag and coexistence* budget, not the
whole of the owner's involvement.** The pre-existing ServiceTag reader-mode rows **R1** and **R2**
above are two further rows, **three further taps** (R1 one, R2 two), and are deliberately outside
this budget: they prove nothing about the split, only that ServiceTag's own reader mode survives a
nav transition (R1) and holds a tag through an inspect (R2 — issue #37, fixed in 2.7). §C's data
migration needs **four** further on-phone UI actions
(Export set; pick the SAF folder; Import data; Restore files), named and counted in §C's intro, which
sit outside this budget because they belong to a different phase and a different gate. The two
first-use NFC permission confirmations are **folded into checks 2 and 3** and are not counted
separately (nor is at most one retap if Android consumes a scan while a permission dialog is up — a
mechanical accident, not an action); §25 rows 7, 8 and 9 cost nothing because they are read from the
logs. Everything else here is workstation-driven: installs, uninstalls, reinstalls, force-stops,
`dumpsys`, `logcat`, the emulator observations, and every optional dispatch-state observation.

**Verify (the gate).** Eight checks recorded in §E plus the three log-read rows; from the logs, with
no owner action: zero unexpected activity starts, zero chooser dialogs on checks 2–4 and 7, and no
database write in either app on checks 5–7. §25's closing instruction governs throughout: **observe
actual Android behaviour; do not infer from manifests.**

**Rollback.** Uninstall either app; neither holds the other's data or grant. A failure on checks 2–4
means a filter is wrong and is fixed in source, not on the phone. A failure on 5–7 or on §25 row 7 is
the serious one: the type gate or the ambient/write separation is broken, which stops the split and
sends the library back to §A.3.

---

## F. Issue and backlog partition (§10, O8, C7)

From the classification in arch §4.7 — **36 issues, 35 open / 1 closed, no labels on any of them** —
done by reading each title and, for the eleven ambiguous ones, the full body, because most ServiceTag
issues still say "noteNFC". §10's rule: **use source semantics, not keywords.**

| Disposition | Issues | Action |
|---|---|---|
| **Moves to NoteTag** (2) | **#6** "Generalize external note/deep-link support beyond Joplin"; **#36** "First-class deep-link support for Joplin, Obsidian, Logseq, Evernote, Notion, OneNote and Todoist" — explicit future-narrow-product work (§10, O8) | transfer per §B.5, **retitled under the NoteTag name**, with backlinks both ways. If transfer is unavailable, recreate with the verbatim body and close the original marked moved |
| **Stays in ServiceTag — ServiceTag proper** (27) | #2–#5, #7–#18, **#19**, **#20**, #21, #22, #24–#28, #33, #34 | nothing moves. **#20** (Apollo Service Binder design system) is **ServiceTag's, not shared** (C7). **#19** ("`notenfc://` deep-link contract") is entirely about `notenfc://asset|link|tag`, i.e. ServiceTag domain objects despite the legacy scheme name — its scheme literal is retitled to `servicetag://` inside the issue, not by transfer. **#34** ("Todoist deep-link actions") says noteNFC in the title but its body is about landing on a maintenance operation. §10 forbids moving Todoist/asset-link/maintenance/schedule/attachment work merely because it mentions links |
| **Stays in ServiceTag — genuinely dual-purpose NFC** (4) | **#1** "[EPIC] Evolve noteNFC into an NFC-first maintenance tracker"; **#30** "Bind, rebind, revoke, and unknown-tag flows"; **#31** "tag payload format v1 and legacy md5_short resolver"; **#35** "Untrusted input policy: tag payloads, deep links, stored URIs" | stay, each with **a note**. **"Shared" does not mean "move to nfc-tag-core"** (C7): a mixed policy/mechanism issue is split or stays in ServiceTag as history. #31 additionally records that the `md5_short` half is **withdrawn, not moved** (O2), and that this reverses D6's "kept permanently" promise |
| **Stays in ServiceTag — build/test infra** (2) | **#23** "Phase 0: clone-buildable repo, AGP 9 toolchain, `:core` module, CI"; **#32** "Testing pyramid and CI gates" | stay. #23 gets a note that NoteTag inherited its *outcome* by branching `c84b881` but **must re-run CI from scratch** (review correction 8); #32 a note that the gates now exist in three repositories |
| **Closed, with a pointer** (1) | **#29** "[MVP] Investigate the installed APK's signing certificate and upgrade path" | stays closed, in ServiceTag, with a comment pointing at target §8. Not reopened, not transferred: it is why a new identity had to be minted at all |
| **New, in NoteTag** | — | one issue for the `LOCAL_REF` export/import of the local map (O14, explicitly not split scope). The `notetag://` `VIEW` filter is **not** a separate issue: ratified P4 reserves the scheme and defers any filter to #6/#36, where it belongs. The UI toolkit is **not** an issue either: ratified P20 settles it (Compose, one activity, two tiny screens) |
| **New, in nfc-tag-core** | — | one issue for `TagWriteSession`'s promotion criterion (target §4.7), so the deferral is tracked rather than forgotten; one for any residue of the `NdefFormatable` capacity gap if ratified P8 — as corrected by G1's message-size arithmetic — does not fully close it |

*Executed 2026-09-18 after the first releases, owner-approved: the two transfers (34 left), then the delivered-phase reconciliation — 15 issues closed with evidence comments (Phase 0: 2, Phase 1: 7, Phase 2: 5, Phase 4: 1; #31 closed as "payload v1 delivered, md5_short resolver intentionally withdrawn by the split"), #1 and #34 retitled under the ServiceTag name, #29 annotated with the new signing identity, milestones Phase 0/1/2/4 closed, and three new issues (ServiceTag #38 harness fold-in, NoteTag #3 LOCAL_REF map export/import, nfc-tag-core #1 TagWriteSession criterion evaluated and not met). Final: ServiceTag 36 issues, 20 open / 16 closed; NoteTag 3; nfc-tag-core 1.*

**Verify.** `gh issue list --repo GonzRon/ServiceTag --state all` = 34;
`gh issue list --repo GonzRon/NoteTag --state all` = 2 transferred + the new ones; every
"stays with a note" issue has its note. **Rollback.** Transfers reverse (§B.5); notes and new issues
are additive.

---

## G. Releases and tags (§28)

§28: inspect, do not mechanically copy or delete; ServiceTag keeps the complete modern history;
old-name tags may remain as history; NoteTag gets historically meaningful narrow-product tags **where
cleanly reconstructable**; never claim a maintenance release was a narrow release; document ambiguous
ownership.

| | Disposition |
|---|---|
| **The 2023 draft release** | **Stays in ServiceTag as history. Not published, not deleted, not re-anchored.** It is: name `initial working`, `draft: true`, `prerelease: true`, `tag_name: ""` (**empty — never tagged**; GitHub minted only a synthetic placeholder for its `html_url`), `target_commitish: "master"`, `published_at: null`, no assets. The commit the repository stood at two minutes before it was created is `707ca3f` "adding a debug apk" — an **Evernote-era** commit (package `com.looseCannon.evernotenfc`, key `UUID.randomUUID()…substring(0, 8)`, record type `com.loosecannon.evernotenfc:uuid8_link`), predating MD5 keying, the `md5_short` type, the project rename and the Joplin conversion by over a year (arch §2.10). **Because it has no tag and targets the branch ref rather than a SHA, it is unanchored — publishing it today would tag whatever master then is.** This paragraph is the documented ambiguous ownership §28 asks for: it is neither a ServiceTag release nor a NoteTag release, and it is left exactly as it is |
| **`pre-split-checkpoint`** | stays in ServiceTag at `ac523d7`; a ServiceTag-state marker, not a narrow-product marker (arch §4.8). Not deleted (§27 step 12) |
| **`pre-split-master`** (branch) | stays in ServiceTag at `ac523d7`. Recorded because the name reads like a tag and is not — `git tag` lists only `pre-split-checkpoint` (arch §4.1) |
| **ServiceTag's first tag** | **`servicetag-v2.5`** on the commit that passes gate 9, annotated with the split's completion date, the library tag both apps consume, and the certificate SHA-256 of the ServiceTag key. Product-prefixed because the repository's history contains a differently-named product and a bare `v2.5` would be ambiguous across the rename (ratified P17) |
| **ServiceTag's second tag** | **`servicetag-v2.6`** on the commit that passes the eight 2.6 proofs: the note-link product removed from the app at the behavioural level (Option B, owner ruling 2026-09-18), `versionCode` 8 / `versionName` 2.6, `external_link` and the format-5 `externalLinks` field kept as compatibility tombstones — **no schema bump, no format bump, no cleanup migration**. Published by the same tag-only `release.yml` as 2.5, which still refuses to publish unless the tag's version equals the built `versionName` (G-4) |
| **NoteTag's first tag** | **`notetag-v2.0`** — the tag follows the built `versionName` 2.0 (G-4, 2026-09-17; earlier drafts said `notetag-v1.0`) — on the commit that passes gate 9, annotated with the boundary commit `c84b881` it descends from and the library tag it consumes. **This is the first tag this lineage has ever had** — there is no 2023 or 2024 tag, locally or on origin (arch §4.1). No earlier narrow-product tag is "cleanly reconstructable", so none is invented |
| **nfc-tag-core's first tag** | **`nfc-tag-core-v0.1.0`**, created in §B.1, annotated with the source commit `ac523d7` and a pointer to the provenance table |
| **Releases on the new repositories** | none at creation, and none during K/L. At the final product tags (`servicetag-v2.5`, `notetag-v2.0`) each app's tag-only `release.yml` builds, signs from the `release` environment's secrets, verifies the certificate against the public `RELEASE_CERT_SHA256` and the version against the tag, and publishes the signed APK with its SHA-256 as the GitHub Release (target §8; owner ruling 2026-09-17). `nfc-tag-core` publishes nothing: its tag is the release |

**Rollback.** Tags are deletable locally and on the remote and carry no data. The draft release is
never touched.

---

## H. Documentation plan (§29, §30)

### H.1 Untouched — history

§30: **never rewrite the Phase 0–4A documents as if they said ServiceTag.** Everything under
`docs/design/` and `docs/superpowers/` stays: `docs/design/README.md`, D1–D13, the eight
`phase-*-evidence.md` files, the `g1/` pair (including the eight-tag census this design cites), the
two `spikes/` reports and the `issues/` package (arch §4.9; the new-issue drafts are **19**, not 17 —
review correction 12). Rewriting a phase-evidence file would destroy the evidence this split is built
on: the 1B and 1C device rows are cited a dozen times across these two documents. Where a historical
document is now out of date, the correction goes in a *new* document with a pointer, exactly as D13
corrected D6.

`docs/architecture/product-split-archaeology.md` is likewise final once its C1/C2/C6/C7 and review
corrections land.

### H.2 Changed — the canonical surfaces (§29)

| Surface | Change |
|---|---|
| **`README.md`** (ServiceTag) | currently opens `# noteNFC` and describes the **merged** scope (arch §4.9). Rewritten for ServiceTag only: title, what it is, "What it does today" with the note-utility framing removed **and schedules/reminders stated as Phase 3 future work** (C1), the identity block (`applicationId`, NDEF type, `servicetag://`), **Building** gaining `--recurse-submodules` and the submodule/subproject mechanism (O15), **Signing** pointing at `~/.config/servicetag/` and **replacing the reproduced signer DN and full SHA-256 at lines 121-125 with a pointer** (review correction 11), and the "Cutover from the old package" section replaced by a pointer to this runbook. A "Related projects" block links NoteTag and nfc-tag-core |
| **`README.md`** (NoteTag, new) | written fresh: what the product is (attach a note or a useful link to a physical NFC tag) and is not; the v1 tag format and its three kinds; that `LOCAL_REF` tags are device-bound; its true ancestry from 2023 with `c84b881` named as the semantic endpoint; that the inherited ServiceTag design package was deleted at the branch tip and **is still in history, deliberately, with no second rewrite** (C6); `--recurse-submodules`; signing from `~/.config/notenfc/` with the note that the directory keeps its historical name because the key must not be touched (§12) |
| **`README.md`** (nfc-tag-core, new) | the provenance table (target §4.6), the invariant list (§4.3), the public API, the forbidden-knowledge rule and its scan, the two patterns the library does **not** own, and the explicit statement that `TagWriteSession` is deferred with its promotion criterion (§4.7) |
| **`docs/design/README.md`** (ServiceTag) | one **new paragraph at the top**, no table rows edited: this package is the ServiceTag line; the split happened on <date>; the narrow product now lives at `GonzRon/NoteTag` and the shared mechanism at `GonzRon/nfc-tag-core`; every document below is historical and its "noteNFC" means "this app before the rename". Its terminology note — which defines "noteNFC tag payload format v1" as record type `com.loosecannon.notenfc:tag` — gains a sentence saying the type is now `com.loosecannon.servicetag:tag` and that the old string belongs to nobody |
| **D3 (`03-target-architecture.md`)** | append a short **"After the product split"** section: the NFC mechanism moved to `nfc-tag-core`; D3 §9's rules are now library invariants, with the mapping; the `md5_short` filter is **withdrawn** (a deliberate reversal of D6, recorded as such); `DISPATCH_NFC_MESSAGE` at targetSdk 37 restated against the new identity. The existing §9 text stays, because the 1B/1C evidence rows reference it by section number |
| **D6 (`06-legacy-compatibility.md`)** | append one note: its still-live promise that the `md5_short` filter is "kept permanently" is **withdrawn by O2/O3**; the eight field tags are inventory facts whose lifecycle is a normal ServiceTag write (§D.5). The original text is not edited |
| **D7 (`07-implementation-sequence.md`)** | the "Product separation — deferred convergence operation" section is the one place in the historical corpus describing this operation prospectively. It gains a closing block — *executed on <date>; see `docs/architecture/product-split-{archaeology,target,migration,evidence}.md`* — and a tick against its own eight conditions: archaeology before mutation ✓, durable checkpoint and rollback ✓, remote rename only after local proof ✓, both apps installable together with the dispatch proof ✓ (§E), identity-preserving data migration through the canonical backup format ✓ (§C.6), explicit-user-intent tag writes ✓ (§D), distinct ServiceTag key with the original preserved ✓ (target §8), independent green CI for all three ✓ (target §9). Its roadmap lines gain "Phase 3 begins after the split completes" and the §31 order. **Nothing else in D7 is edited** |
| **D13 (`13-compatibility-policy.md`)** | one appended note: its anticipated chooser window ("Both apps match `md5_short`; Android shows a chooser until the old app is removed") **does not arise**, because no product declares that type at all |
| **This package** | at completion, both documents gain the observed values for every "to observe on-device" marker (or the recorded contradiction), the ratified status of every **[P*n*]**, and the three first tags from §G |
| **`docs/architecture/product-split-evidence.md`** | **created** (§29): commits, CI results, builds, clean-clone proof, the id comparison, the attachment hashes, the device results from §D and §E, and certificate fingerprints only — **no secrets, no device ids** |
| **`.superpowers/split/ledger.md`** | one line per completed phase, as for A and B |

**Verify.** `git diff --stat` over `docs/design/` and `docs/superpowers/` shows **additions only**, and
only in the five files named. Every cross-reference in the new READMEs resolves. A reader who opens
ServiceTag's `README.md` cannot mistake it for the note utility, and a reader who opens `docs/design/`
is told in the first paragraph what era they are in — §30's stated purpose, so that future maintenance
work points to ServiceTag.

---

## I. Rollback, end to end (§35)

Read from the row you are in, upward: the earlier the failure, the cheaper it is.

| Failed in | What has changed | Recovery |
|---|---|---|
| **§A.1** ServiceTag conversion | commits on `product-split` in a worktree | `git reset --hard ac523d7`, or revert the task commit. `master`, `origin` and both recovery refs untouched |
| **§A.2** NoteTag reconstruction | one local directory | delete it and re-clone from `c84b881`, which is immutable history |
| **§A.3** library extraction | one local directory | delete it; nothing references it yet |
| **§A.4** both consume | submodule + settings commits in two local repositories | `git submodule deinit -f libs/nfc-tag-core && git rm -f libs/nfc-tag-core`, revert the commits. Each app's own NFC layer is still in its history |
| **§B.1** library repo created | a new remote repository exists | the token **cannot delete**: make it private, rename it aside, record the abandoned name |
| **§B.2** rename | `GonzRon/noteNFC` is now `GonzRon/ServiceTag` | `gh repo rename noteNFC` — reversible both ways, with redirects either way. Reset local remotes. **The recovery refs stay throughout and are the anchor for everything below** |
| **§B.3a** ServiceTag's converted master pushed | `origin/master` now carries the identity conversion, the submodule and the new workflow | `git push --force-with-lease origin pre-split-master:master` restores master to `ac523d7` from the recovery branch — exactly what that branch was kept for — and `--force-with-lease` refuses if anyone else moved master meanwhile. The merge commit survives locally and on `product-split`, so nothing is lost and the push can be retried once the cause is fixed. The recovery refs are **not** deleted (§27 step 12) |
| **§B.4** NoteTag pushed | a second new remote repository exists | rename it aside, fix locally, re-push |
| **§B.5** issue transfer | two issues moved | `gh issue transfer` back; comment history and redirects survive, and the backlinks become the audit trail |
| **§C.1–C.8** phone restore and proof | **the old package is still installed and still holds the live data** — the whole point of §15's ordering | uninstall the ServiceTag package and keep using the modern app. The SAF tree is untouched in the same-tree case (8 `alreadyPresent`, 0 written), and §13 forbids ever deleting, relocating or rewriting it |
| **§C.9** old package uninstalled | the old app's data is gone | reinstall the preserved signed **2.4 / versionCode 6** APK from `~/Documents/Projects/AndroidStudioProjects/noteNFC-releases/` — built from `ac523d7`, signer DN `CN=noteNFC, O=GonzRon`, certificate SHA-256 in the checkpoint ledger — re-grant its SAF tree, and import the preserved set. **The only genuinely irreversible step in the runbook**, which is why C.6's proof is its precondition (§35) |
| **§C.10 / §D / §E** NoteTag installed, tags written, coexistence | tags written; two apps installed | uninstall either app freely. Any tag can be rewritten **except T1 once locked** — T1 is the only tag ever locked, which is precisely why the format → verify → lock-last sequence is exercised on it and on nothing else (§D.2). **T4** and **T2** stay rewritable. The eight legacy field tags were never touched |
| **The whole split** | everything above | `git checkout pre-split-checkpoint` in a fresh clone of the renamed repository; rename the repository back to `noteNFC`; transfer the two issues back; rename the two new repositories aside; reinstall the 2.4 APK and import the preserved set. The pre-rewrite history bundle beside the project directories covers the one case none of this does — a corrupted or force-pushed history — because it predates the 2026-09-14 rewrite |

**Two rules that make all of it hold.** No recovery ref is ever deleted (§27 step 12, §35), and no
step is designed to require a repository delete — which the active token's capabilities enforce
anyway (arch §4.3).

---

## J. Reviews — the ten gates of §34

§34's list, quoted rather than inferred. Gate 8 (physical-tag migration) is **withdrawn and folded
into gate 9** (O2, §5). §34's standing rule: *important compatibility findings are fixed before
proceeding; minor cleanup is recorded and deferred.*

| Gate | §34 | Phase(s) | Entry condition |
|---|---|---|---|
| **1** | 4A final | — *(done)* | Phase 4A closed, CI green, the checkpoint taken |
| **2** | archaeology | B *(done — PASS with corrections)* | the archaeology document, its §9 discrepancy log, and the 19 corrections + 8 additions from the independent review |
| **3** | **split architecture before mutation** | C *(this package)* | target + migration documents revised under O1–O15 and C1–C9; every named module/package/file exists or is marked NEW; every unobserved platform claim marked; proposals separated from rulings. **Mutation stays on HOLD until this passes** |
| **4** | **nfc-tag-core boundary** | F, G | the forbidden-dependency scan green with a reasoned allow file; every library invariant tested; the provenance table complete with resolving `git log --follow` starting points; **what is deferred and why stated explicitly** (target §4.7) — a boundary claiming more than two consumers have proven is what this gate exists to catch |
| **5** | **ServiceTag identity / regression** | D | §A.1's **fifteen** tasks complete; §A.1.1's §21 regression pass green; the identity table true of the **built APK**; `5.json`'s `identityHash` unchanged; clean-checkout build green |
| **6** | **NoteTag narrow scope** | E | §23's acceptance list end to end; the v1 format's per-kind tests; the local store never required to resolve `JOPLIN_NOTE`/`URI`; true ancestry (30 commits, zero merges, root `5fb6aed`); CI green **from scratch** (review correction 8); no `docs/`, no tracked APK, no legacy decoder, no tech catch-all |
| **7** | **data / artifact migration** | H | §C.6 and §C.7 pass: eleven tables with identical id sets and per-field equality (events including `tzId`, `occurredOn`, `createdAt`, `(source, source_ref)`); 8×3 attachment hashes; the empty-tree restore proved independently; every difference accounted for by §C.8 and nothing else. **This gate precedes §C.9's irreversible uninstall** |
| **8** | *withdrawn* | — | folded into gate 9 (O2; §5's "physical-tag proof — now: coexistence of final products only") |
| **9** | **final coexistence / device** | I + J | Session 1's four writer taps recorded (§D.2, including format → measure → capacity-check → write → verify → lock-last on one tag, and the **measured `Ndef.maxSize`** written to the evidence file); Session 2's eight taps and three log-read rows passed (§E), with **dispatch spike S1 folded into checks 2–3 and S2 as check 8**; **owner actions exactly 12** (§E.1; a retap swallowed by a permission dialog does not count); zero chooser dialogs on checks 2–4 and 7; no database write on checks 5–7; sibling refusals offering **Write over it / Cancel** and nothing else; the per-package SAF grant observed on the emulator at C.8a before C.9 acted on it; every reassigned proof (§D.3) green off-device with the capacity fakes pinned to the measured NTAG213 budget; both apps on the same library tag; every observation in the evidence file with a verdict, and the **debug-build caveat** recorded |
| **10** | **final three-repository convergence** | K + L + M + N | three repositories, three green CI runs, **ServiceTag's converted `master` pushed and green on the new workflow (§B.3a)**, three clean-clone builds **from URLs** plus one per app from a second workstation (§B.6); the two `release.yml` workflows installed and dry-run locally (not executed until the product tags); issues moved with backlinks and the notes added; the three first tags created; documentation changed only where §H says and additively where it touches history; every **[P*n*]** ratified or superseded; every "to observe on-device" marker resolved or explicitly deferred; and the §36 handoff assembled — checkpoint SHA, the historical split SHA and why, three repo names/URLs/canonical commits, what the original repository became, what moved into nfc-tag-core, what stayed app-specific, the dependency/version mechanism, both applicationIds and namespaces, NFC record and deep-link ownership, AAR behaviour, signing fingerprints only, the migration backup format, the ID-preservation and attachment-hash proofs, the SAF grant procedure, the coexistence proof, CI status ×3, issue movements, releases/tags, rollback, and remaining debt. **Next operation after handoff: ServiceTag Phase 3** |

**No gate is self-certified**; each is reviewed against the artifact it names, by someone who did not
produce it — the pattern already used for gates 1–3.

---

## K. Proposal ledger — all ratified, none open

Gate 3 ratified the last five proposals, and each now lives in the design text rather than in a list:
**P4** (no `notetag://` `VIEW` filter at reconstruction), **P11** (a sibling's tag is named and offers
exactly **Write over it / Cancel**, normalised throughout both documents — no "keep" wording and no
cross-product action anywhere), **P19** (the local store as a single atomically-replaced JSON file)
**together with the G2 crash-consistency invariant and its failure-injection deliverable** (§A.2 task
7), **P20** (Compose, one activity, two tiny screens) and **P21** (ServiceTag keeps its AAR pending
the spike now folded into §E checks 2–3).

Everything this runbook once proposed has been accepted or superseded: the composite-build choice by
**O15**, the Migrate-tag tool and all legacy handling by **O2/O11**, the reconstructions of §22's word
list, §27's order and §34's gates by the brief now being on disk, and the remote-ordering deviation by
the controller's acceptance recorded in §B.
