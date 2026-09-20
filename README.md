# ServiceTag

An open-source, local-first Android app that turns NFC tags into durable handles for the physical
things you maintain — the hot tub, the generator, the well pump, the bike: service history,
measurements and upcoming maintenance, per asset.

Stick a tag on the thing. Scan it and the phone opens that asset's own record — what it is, what it
measures, and everything that has been done to it — with no screen to hunt for. The tag carries only
a random identifier; everything it means lives in a small SQLite database on the phone, which you
can back up and restore with identities intact, so a tag keeps working across phone replacement,
reinstall and restore.

No accounts, no backend, no telemetry. Reminders are local first; Todoist is an optional, later
projection that never becomes the source of truth.

## What it does today

A single-screen Compose app — Dashboard and Assets along the bottom, everything else one push deep —
around the one-tap flow that is still the spine: tap a tag anywhere and the phone opens the right
place. Read / inspect tag, for the rare deliberate look, lives under Settings, and it keeps NFC for
as long as you are on it: what a tag turns out to be is shown on the screen you are already on, so a
tag left against the phone is not handed back to the system mid-look. An inspect inspects: a tag
already bound to an asset is named there, with an Open asset action, and opening it is what hands
NFC back — the ambient tap still opens a bound tag straight away.

- **Dashboard** — the assets in service, and, until the first export succeeds, a card that says
  there is no backup yet and offers to take one. The list is the systems themselves: a component of
  another asset is listed on that asset and not again here. A search box above the list filters as
  you type — over the name, the category, the make, the model, the serial and the location — and a
  component that matches comes back with the system it is part of named under it. The sections for
  what needs attention and what is coming up are drawn from schedules, which are Phase 3, so today
  the dashboard draws the current assets and the backup nudge.
- **Assets** — a list you can filter to include archived ones, an asset screen built around the
  identity plate (category, name, description and tags), a create/edit form, and archive
  rather than delete.
- **Describe equipment fully: make, model, serial, purchase and warranty, location, parts of a
  larger system, seasons** — a grouped asset editor behind the plate and a DETAILS section that
  renders what is filled in; a component names the system it is part of and the system lists its
  components; a season window says OUT OF SEASON while today falls outside it; and retirement is a
  date you pick, independent of archiving, with a service event offered afterwards rather than
  demanded.
- **A journal of events and typed measurements** — log maintenance events with typed readings and
  materials used, and the asset screen reads back its current readings and its service record. An
  entry is logged against a date, puts one row per field under a READING / VALUE / TARGET header,
  keeps the materials that went in apart from the readings, and takes a note. A stored entry can be
  reopened, edited or deleted. Five starter templates (hot tub, power equipment, UPS, RO water,
  generic) seed the readings and the quick actions; nothing about them is hard-coded.
- **Define your own readings and actions per asset; derived readings such as RO rejection** — a
  Readings & actions screen per asset with an editor for each. A reading has a label, unit, target,
  decimals and a meter flag; an action names the readings it asks for, the kind of entry it logs and
  the materials it suggests; and a derived reading is computed from two of the asset's own readings
  on the same entry rather than entered. Once measurements exist, the three controls that decide how
  they are read back are locked and the form says so, instead of refusing the save later.
- **Scan a tag** — tapping a tag opens it whether the app is running or not. A tag bound to an asset
  opens that asset. A tag that has a record here but no assignment, one that was retired or marked
  lost, and a ServiceTag tag this phone holds no record of are each named as what they are and
  offered a bind. A tag that is empty, holds another product's content, cannot be read, or was
  written by a newer ServiceTag is reported as exactly that and offered a rewrite — never an error,
  and never silently overwritten.
- **Write a tag** — an asset's screen writes a tag for it. The writer reads the tag first and asks
  before overwriting anything except an empty tag or that same tag again, naming what it found —
  another ServiceTag tag, a ServiceTag tag written by a newer app, foreign NDEF content, unreadable
  NDEF content. It checks the tag has room before it asks, reads the tag back to verify what it
  wrote, and applies the lock as part of the write rather than as a blind second step.
- **Attach photos, manuals and receipts** — pick a folder once in Settings (any folder a document
  provider exposes, so a sync tool can replicate it) and a DOCUMENTS section on any asset or ledger
  entry takes files from the picker or the camera, keeps the bytes in that folder as ordinary
  documents, shows thumbnails for images, opens anything with the system viewer, and lets you
  rename, re-kind, date or delete each one. Nothing is hidden inside the app.
- **Back up and restore, identities intact** — a Backup screen (from the dashboard's nudge, or the
  backup action on any asset) exports a backup *set* into a folder you pick: one ZIP holding every
  asset, tag binding and attachment record with its original id, and a second holding the
  attachment bytes. A restored phone resolves the same tags; restoring the data alone works and
  marks the files "not on this device" until you restore the second ZIP, which adds files and
  deletes nothing. Restoring the data replaces everything on the phone and makes you type `REPLACE`
  first — unless the phone has no records yet, in which case there is nothing to replace and it only
  asks you to confirm.
- **Settings** — appearance (system / light / dark), the palette's name, the attachment folder and
  the provider behind it, Read / inspect tag, the build's version and a link to the project.

### Note links are NoteTag's

Sharing a note or a web link to an NFC tag is not part of ServiceTag. That utility lives in
[NoteTag](https://github.com/GonzRon/NoteTag), and ServiceTag removed it from this app before its 1.0.0 baseline: there is
no Links screen, no share target, no way to point a tag at a link, and no outbound-link allowlist.

Old data is kept, not discarded. A backup written by any earlier version still carries its
`externalLinks` rows and restores them unchanged — the format is untouched at 5 — but nothing in
ServiceTag creates, shows or opens one. A tag written by an older version to point at a link reads
as a tag from before the split and does nothing else.

## Building

```bash
git clone --recurse-submodules <this repo> && cd ServiceTag
./gradlew :app:assembleDebug
```

The NFC mechanism lives in a shared library, `nfc-tag-core`, which is a git submodule at
`libs/nfc-tag-core` pinned to an exact `nfc-tag-core-v*` tag. Its two Gradle modules are included as
ordinary subprojects of this build, `:nfc-core` and `:nfc-android`; there is no Maven coordinate and
nothing to publish. A checkout without the submodule fails at configuration time, from the `require`
in `settings.gradle.kts`, and prints the fix:

```
libs/nfc-tag-core is missing or uninitialised.
Clone with --recurse-submodules, or run:  git submodule update --init --recursive
```

`tools/check-submodule-pin.sh` asserts the rest and exits `1` naming the first thing that is not so:
the submodule is at the commit this commit pins, that commit is an exact `nfc-tag-core-v*` tag, the
library's version catalog pins the same AGP and Kotlin as this app's, and the submodule working tree
is clean. CI runs it before the build.

The gate CI runs, and the one to run locally, is:

```bash
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug
```

Needs a `local.properties` with `sdk.dir` pointing at an Android SDK (compileSdk 37, build-tools
36.0.0). Everything else — Gradle 9.7.1, AGP 9.4.0 with its built-in Kotlin, KSP, Room 3 — comes down
through the wrapper and the version catalog. Every module compiles against JDK 17, provisioned by the
Gradle toolchain through the foojay resolver in `settings.gradle.kts`, so no particular local JDK has
to be installed; CI uses Temurin 17.

Instrumented tests run on an emulator, not on a phone holding real data — they wipe app data. Pin the
target rather than letting `adb` choose:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

The library's own emulator suite, `:nfc-android:connectedDebugAndroidTest`, runs from this root the
same way.

**The debug backup harness.** Backup and restore are product features — the dashboard's nudge, or the
backup action on an asset, opens a real screen. What stays debug-only is the harness beside it: from
`app/src/debug/`, a second launcher icon, **ServiceTag Backup (debug)**, with four buttons — Seed
sample, Export, Import (replace), Wipe — and a live count. **Wipe is the reason it still exists**:
emptying the database without touching the tags is how the restore proof stands in for a second
phone, and it is deliberately not offered anywhere in the app. It is a harness, not product UI, and
the release APK contains neither the activity nor its manifest entry (see
`docs/design/phase-1a-evidence.md` §7 and `docs/design/phase-1c-evidence.md` §9).

## Signing

Release builds pick up `~/.config/servicetag/keystore.properties` if it exists; when it is absent the
release build is simply unsigned and everything else still works. The file is plain `storeFile` /
`storePassword` / `keyAlias` / `keyPassword` and points at a keystore outside the repository. Neither
file is ever in the repo (`.gitignore` covers `keystore.properties`, `*.jks`, `*.keystore`).

The release certificate's SHA-256 fingerprint is recorded once, in
`docs/design/phase-1a-evidence.md`. It is not reproduced here: a fingerprint is a public key, but a
repository's front page is not where a signer's identity belongs.

Back the keystore up somewhere outside the repo. Lose it and the app can never be updated in place
again — a new key means a new install for every user.

## Releases

A release is a tag of the form `servicetag-v<versionName>` (e.g. `servicetag-v1.0.0`) pushed to GitHub. The version itself follows semantic `MAJOR.MINOR.PATCH` versioning, classified before the number is chosen — see `docs/versioning.md`.
That tag alone triggers `.github/workflows/release.yml`, which checks out the exact commit under the
`release` environment, runs the full test gate, builds the signed APK from that environment's four
secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`), verifies the built APK's certificate against the public repository variable
`RELEASE_CERT_SHA256` and its `versionName` against the tag, and only then publishes the signed APK
and its SHA-256 checksum as a GitHub Release. Ordinary CI (`.github/workflows/ci.yml`) never sees any
of that signing material — it stays unprivileged and runs on every push. `tools/release-dry-run.sh`
is the local, no-secrets equivalent: it runs the same checks against whatever signing material is on
this machine and reports `PASS`, `PARTIAL — signing identity not independently checked`, or `BLOCKED`
without ever printing a fingerprint, password or keystore path.

## Where it is going

The design package under [`docs/design/`](docs/design/README.md) lays out the whole system and the
phase sequence: assets with a journal of events and typed measurements (Phase 2), provider-neutral
maintenance schedules with local reminders and a health screen (Phase 3), attachments on a pluggable,
cloud-agnostic store (Phase 4), an optional Todoist projection (Phase 5), supplies and parts
(Phase 6). Phases 0, 1A–1C, 2A, 2B-1, 2B-2 and 4A are merged; Phase 3 — schedules, reminders and the
dashboard sections that depend on them — is next, and begins once the product split completes.
Progress is tracked in the GitHub issues, one milestone per phase.

The 2026 product split is what produced the shape below: it moved the note utility out into NoteTag
and the NFC mechanism down into the shared `nfc-tag-core` library, leaving ServiceTag to be the
maintenance product alone.

Code shape: `:core` is pure Kotlin (domain model, payload body codec, scheduling engine, backup
format, policies, ports) and is tested on the JVM; `:app` is the Android shell (Room 3, NFC reader
mode, Compose + Material 3 + Navigation 3); and beneath both, `:nfc-core` and `:nfc-android` are the
shared library's modules — the NDEF envelope, the byte-to-UUID helper, tag I/O and the overwrite
policy. No DI framework, no plugin system.

## Where this app came from

This repository was `GonzRon/noteNFC`, a combined note-utility and maintenance product, until the
2026 product split. The maintenance product kept the history and became ServiceTag; the note utility
is reconstructed as its own project, NoteTag; and the NFC layer became the `nfc-tag-core` library,
whose NDEF format is the library's own and product-neutral — what a record's type names, and what its
body means, stays each app's.

What moved, what stayed, what the identities are now and how the data is migrated are all in
`docs/architecture/product-split-migration.md`. Everything under `docs/design/` predates the split
and is history.
