# share-test-sender

A test-only Android application (#62). It exists so the connected suite can prove one fact that
no in-process test can: **Android delivers a share from another UID to ServiceTag's exported
share target**, with a real temporary read grant when the share carries a file.

It is not part of ServiceTag. `:app` does not depend on it, no release workflow builds it, and it
has no release variant.

- **Package / UID:** `com.loosecannon.servicetag.testsender` — its own package, so its own UID.
- **Installed by:** `:app:connectedDebugAndroidTest`, which depends on
  `:share-test-sender:installDebug`. Export `ANDROID_SERIAL=emulator-5554` first, or Gradle
  installs it on every attached device.
- **Built by CI:** the `build` job assembles `:share-test-sender:assembleDebug`.
- **Driven by:** `app/src/androidTest/.../share/TestSender.kt`, for `ShareBoundaryTest`.

## Commands

`SenderActivity` is exported (the test runs under ServiceTag's UID and must be able to start it),
has no intent filter and draws nothing. It reads three string extras, fires one explicit
`ACTION_SEND` at `com.loosecannon.servicetag/.share.ShareIntakeActivity` with
`FLAG_ACTIVITY_NEW_TASK`, and finishes.

| `command` | what it sends |
|---|---|
| `send_text` | `text/plain`, `EXTRA_TEXT` = the `text` extra (default `https://example-mower.invalid/xt1/manual.pdf`) |
| `send_file` | the fixture's type, `EXTRA_STREAM` = the fixture's `content://` URI, `ClipData` from that URI, `FLAG_GRANT_READ_URI_PERMISSION` |
| `send_bad_grant` | the same stream and `ClipData`, with no grant flag, so no read grant |

`fixture` is `mower-manual.pdf` (default, `application/pdf`) or `mower-shot.png` (`image/png`);
any other name sends nothing. Both are fictional. The fixture is copied out of `assets/` into
the app's files directory once and served by a non-exported `FileProvider` under
`com.loosecannon.sharetestsender.fixtures`.

### Why the authority is not under `com.loosecannon.servicetag`

The package is `com.loosecannon.servicetag.testsender`, but the provider's authority deliberately
is not. ServiceTag refuses a shared stream whose authority is its own application id **or anything
under it**: `StreamSourcePolicy` matches each own authority as a namespace
(`core/src/main/kotlin/com/loosecannon/servicetag/core/references/StreamSourcePolicy.kt:34`,
`host == it || host.startsWith("$it.")`), and the app feeds it `BuildConfig.APPLICATION_ID`
(`app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt:358-359`). An authority of
`com.loosecannon.servicetag.testsender.fixtures` was tried first and was refused as ServiceTag's
own ("That file cannot be accepted from the app that shared it.") before any grant was consulted.

### Why `send_file` sets the grant flag and `send_bad_grant` keeps its `ClipData`

On this API (37) the platform grants a flagless `ACTION_SEND` by itself when it has a stream and
no `ClipData`: `Instrumentation.execStartActivity` calls `Intent.migrateExtraStreamToClipData`,
which copies `EXTRA_STREAM` into `ClipData` and adds `FLAG_GRANT_READ_URI_PERMISSION`. The sender's
logcat says so:

```
E/Intent: Implicit URI grant for android.intent.action.SEND action will be discontinued from Android 18 onwards. Please set the grant explicitly in the app.
```

So `send_file` sets the flag explicitly instead of relying on a behaviour that is being retired,
and `send_bad_grant` sets `ClipData` from the URI without the flag: a share that already carries
`ClipData` is not migrated, so it really arrives ungranted. A "no flag, no `ClipData`" share would
carry a real grant and could never be refused.

### Why the bad-grant case shares with a grant first

ServiceTag cannot see this package (API 30+ package visibility) until this package has granted it
a URI. Until then an ungranted stream is not refused at all: the resolver cannot find the provider,
`query` answers null, and the intake draws a byte form with an empty Received line. So
`ShareBoundaryTest`'s bad-grant case runs `send_file` once, finishes that intake, and then runs
`send_bad_grant`, which the platform now denies ("Permission Denial") and the intake answers with
"Could not read what was shared". The fresh-sharer empty form is issue #63; when it lands, the
granted share goes.

To fire one by hand on the emulator:

```sh
adb -s emulator-5554 shell am start -n com.loosecannon.servicetag.testsender/.SenderActivity --es command send_file
```

## What it must not become

It holds no permission, never reads ServiceTag's data and has one exported component. It proves
delivery and the grant, nothing else: choosing an asset, naming, saving and every refusal
sentence are proved in process (see `docs/release-proofs.md`). A new command needs a new OS
boundary to demonstrate; a variation of an existing one is an in-process test.
