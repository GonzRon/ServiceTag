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
| R2 | connected suite, which includes the boundary and contract classes | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` | instrumented Compose and framework contract (`ShareResolutionContractTest` among them) | zero skips |
| R3 | the Python suites | `cd tools/servicetag-mcp && uv run --frozen pytest`, then the same in `tools/servicetag-bundle` and `tools/servicetag-schedules` | each tool's own pytest | — |
| R4 | external boundary | **is R2's `ShareBoundaryTest`** — three cases, 20 s each; nothing else may be added to this row without naming a new OS boundary | external-boundary smoke, from the `:share-test-sender` UID | ≤ 60 s |
| R5 | structural | inside R1: `ManifestContractTest` (the source manifest, and the merged-manifest permission set), `VersionAgreementTest`; plus the exported-set parser line below | JVM structural | — |
| R6 | hygiene | the range greps below | the controller, over `<base>..HEAD` | — |
| R7 | signed-APK upgrade smoke | install the verified release over the previous one on the development phone, in place: `adb install -r <verified apk>`; same `firstInstallTime` and UID before and after; table counts unchanged | one install on the development phone | one install |

R3's projects each set `-q` in their own `pyproject.toml`; do not add another, or the summary
line is suppressed.

## R4 in full

`ShareBoundaryTest` asks the test-only sender (`share-test-sender/`, its own package and UID) to
fire a real `ACTION_SEND` at `ShareIntakeActivity`, waits for the intake to resume and reads its
Compose semantics. Its three cases are the whole of the external proof for share intake:

1. an external `EXTRA_TEXT` share from another UID reaches the intake;
2. an external `content://` stream with a genuine temporary read grant reaches the byte form;
3. the same stream without a grant (after one granted share has made the sender's provider
   visible, see the environment notes) is refused with "Could not read what was shared", not a
   crash.

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

## R7 in full

Set `ANDROID_SERIAL` to the development phone for this step only; it is never written down.
Before and after `adb install -r` of the verified APK: `adb shell pm list packages -U --show-versioncode com.loosecannon.servicetag` (UID and version code) and `adb shell dumpsys package com.loosecannon.servicetag | grep -m1 firstInstallTime` (package state, not the screen). The UID and `firstInstallTime` must not change and the version code must be the new one. The table counts come from the Developer API before and after and must be identical. The production phone is never part of a proof.

## Environment notes that cost a day

- **Pin the emulator.** A physical phone may be attached. `ANDROID_SERIAL=emulator-5554` must be
  exported for every Gradle and `adb` command here, or `:share-test-sender:installDebug` and the
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
  package visibility hides the sender's package from ServiceTag (`AppsFilter ... BLOCKED`). An
  ungranted stream from a sharer ServiceTag cannot see is not refused: the resolver logs "Failed to
  find provider info", `query` returns null without throwing, and the intake draws a byte form
  with an empty Received line (Save then fails with "Could not read what was shared" and writes
  nothing). Once the sharer has granted one URI, the provider is visible and an ungranted read is
  denied by the platform ("Permission Denial"), which the intake answers with the dead end. So
  `ShareBoundaryTest`'s bad-grant case sends one granted share first; the fresh-sharer behaviour
  is a product gap, not something these proofs paper over.
- **The sender's authority is outside ServiceTag's namespace.** `StreamSourcePolicy` refuses
  ServiceTag's application id and every authority under it, so the sender serves its fixtures as
  `com.loosecannon.sharetestsender.fixtures`, not under `com.loosecannon.servicetag.`
  (`share-test-sender/README.md`).

## The tripwire

`ReleaseProofPolicyTest` (JVM, inside R1 and CI) fails if any tracked file under `tools/`,
`app/src/androidTest/`, `share-test-sender/` or `.github/` names a screen-driving tool, or if R4
above stops being `ShareBoundaryTest` alone. `ShareBoundaryTest` fails any case that takes longer
than 20 s. A future harness either edits those tests in the open or fails CI.
