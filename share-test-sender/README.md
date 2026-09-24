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
| `send_bad_grant` | the same stream with no read grant |

`fixture` is `mower-manual.pdf` (default, `application/pdf`) or `mower-shot.png` (`image/png`);
any other name sends nothing. Both are fictional. The fixture is copied out of `assets/` into
the app's files directory once and served by a non-exported `FileProvider` under
`com.loosecannon.servicetag.testsender.fixtures` — not ServiceTag's own authority, so the share
target's own-authority refusal does not apply.

To fire one by hand on the emulator:

```sh
adb -s emulator-5554 shell am start -n com.loosecannon.servicetag.testsender/.SenderActivity --es command send_file
```

## What it must not become

It holds no permission, never reads ServiceTag's data and has one exported component. It proves
delivery and the grant, nothing else: choosing an asset, naming, saving and every refusal
sentence are proved in process (see `docs/release-proofs.md`). A new command needs a new OS
boundary to demonstrate; a variation of an existing one is an in-process test.
