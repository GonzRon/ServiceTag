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

A single-screen Compose app — Dashboard, Assets and Maintenance along the bottom, everything else
one push deep — around the one-tap flow that is still the spine: tap a tag anywhere and the phone
opens the right place. Read / inspect tag, for the rare deliberate look, lives under Settings, and
it keeps NFC for as long as you are on it: what a tag turns out to be is shown on the screen you are
already on, so a tag left against the phone is not handed back to the system mid-look. An inspect
inspects: a tag already bound to an asset is named there, with an Open asset action, and opening it
is what hands NFC back — the ambient tap still opens a bound tag straight away.

- **Dashboard** — the assets in service, and, until the first export succeeds, a card that says
  there is no backup yet and offers to take one. The list is the systems themselves: a component of
  another asset is listed on that asset and not again here. A search box above the list filters as
  you type — over the name, the category, the make, the model, the serial and the location — and a
  component that matches comes back with the system it is part of named under it. The sections for
  what needs attention and what is coming up are drawn from maintenance schedules, and a filter
  above them narrows the list by category and by maintenance status.
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
- **A local automation API** — a workstation can drive this phone's records without a screen.
  Settings > Utilities > Developer API shows a port and an eight-character pairing code; while that
  screen is open, and only while it is open, the app answers JSON requests on the phone's own
  loopback address that carry the code. There is one endpoint per thing the app can already do —
  read and write assets, components, readings, quick actions and journal entries, read tag bindings
  — and nothing that bypasses one. A wipe, a replace-restore, an export, an NFC write and an
  attachment's bytes have no endpoint at all. Two more endpoints merge a backup's data archive into
  this phone *additively*: one returns a **plan** and writes nothing, the other applies it — and
  only when the plan has no conflicts, because a row that is already here is never overwritten and
  nothing is ever deleted. `tools/servicetag-mcp/` is the workstation side, an MCP server with one
  tool per operation, and `docs/api/v1.md` is the contract.
- **Maintenance schedules, maintenance groups and local reminders** — a Maintenance destination
  holding due work, the schedules themselves, maintenance groups and the health of the reminders.
  A schedule repeats on a date rule, on a meter rule, or on whichever comes first, and it can be
  attached to one asset or to a group of assets that are serviced together, where a round is a
  checklist of members and can be closed when the rest will not be done. Reminders are delivered
  by this phone alone — a daily digest at an hour you choose, with Done, Snooze and Open on the
  notification — and nothing leaves the device. A tag tap while something is due offers the
  completion there on the sheet, and each tag can carry a label saying where on the thing it is
  stuck. The design contract is
  [`docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`](docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md).
- **Share a link, a document, a photo or a note into an asset** — ServiceTag is in Android's share
  sheet. Send it one item from anywhere — a page from the browser, a PDF or a photo from Files or the
  gallery, a note link from a notes app — pick the asset it belongs to, give it a name and an optional
  description, and save. Bytes land as an ordinary attachment in the folder you already chose; a link
  is saved as a *reference*, which a References section on the asset lists, opens, renames and removes,
  and which an "Add link" action on that same section can write without any share at all. Nothing is
  guessed at: text that is not a link is offered as a journal note instead, a scheme ServiceTag does
  not recognise is saved only after you say so, and a scheme it refuses is neither saved nor opened.
  The design contract is
  [`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`](docs/superpowers/specs/2026-09-23-servicetag-share-intake.md).
- **Operating seasons, maintenance service policy, operational condition and derived health** — an
  asset can be in use all year, between two calendar dates or for a season started and ended by
  hand, and can have a yearly maintenance break; each schedule says whether its work is done
  whenever it is due, when the season starts or before it starts, so seasonal work waits out of
  season, or deferred past the break, instead of turning overdue; an asset keeps a dated history of
  whether it is operational, degraded or down; and its health — nominal, warning or critical, with
  the reasons — is derived when it is read, from the age of what it tracks and from how overdue its
  linked maintenance is, never stored and never changing the condition, as the design contract
  [`docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md`](docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md)
  sets out.
- **Settings** — appearance (system / light / dark), the palette's name, the attachment folder and
  the provider behind it, Read / inspect tag, Developer API, the build's version and a link to the
  project.

### Note links are NoteTag's

Sharing a note or a web link to an NFC tag is not part of ServiceTag. That utility lives in
[NoteTag](https://github.com/GonzRon/NoteTag), and ServiceTag removed it from this app before its 1.0.0 baseline: there is
no Links screen, no share target, no way to point a tag at a link, and no outbound-link allowlist.

**Amended 2026-09-23 (1.3.0). The paragraph above is kept for the record; two of its four clauses no
longer hold.** ServiceTag now declares a share target and carries an outbound-scheme allowlist again,
because a link can be saved *to an asset* — see the share-intake capability above. The two clauses
that still hold are the ones the split was about: there is no Links screen, and **no way to point a
tag at a link**. A tag still resolves to an asset and nothing else; NoteTag remains the tag-to-note
product.

Old data is kept, not discarded. A backup written by any earlier version still carries its
`externalLinks` rows and restores them unchanged — the format moved to 6 for schedules and groups,
and left those rows alone — but nothing in ServiceTag creates, shows or opens one. A tag written by
an older version to point at a link reads as a tag from before the split and does nothing else.

### The automation API is loopback-only and lives as long as its screen

The API exists so an agent or a script on a workstation can do in one call what would otherwise be
an afternoon of tapping: load a season's worth of readings, rename every component of a system,
check what the phone actually holds. It is deliberately the narrowest thing that can do that.

- **It answers on `127.0.0.1` and nowhere else**, on port 17337, and it checks the peer's address a
  second time on every accepted connection. There is no server, no service and no background work:
  the listener is started by the Developer API screen and stopped when that screen pauses or goes
  away, so the honest answer to "when is my phone accepting commands?" is "while you are looking at
  the screen that says so".
- **Every request must carry `Authorization: Bearer <code>`** with the code on that screen. The code
  is eight characters from a 32-character unambiguous alphabet, drawn from the platform's CSPRNG, and
  **new every time the screen opens** — it is never saved, never persisted and never logged. Anything
  without it gets a 401 with an empty body: no code, no message, no hint about what exists.
- **The workstation reaches it with `adb forward tcp:17337 tcp:17337`**, which the MCP server runs
  itself. The app declares `android.permission.INTERNET` because Android gates TCP socket *creation*
  on it even for a loopback address. `INTERNET` gives the process network capability, but ServiceTag
  1.1.0 introduces no outbound networking and the Developer API listens only on localhost; future
  cloud features may make intentional outbound use of the permission under their own designs.
- **Every endpoint is one of the app's own use cases.** None of them reaches past one, and the
  destructive ones have no endpoint: no wipe, no replace-restore, no export, no NFC write, no NFC
  bind, no attachment bytes, and nothing at all for the pre-split link tombstones.
- **The additive merge import is planned before it writes.** `POST /v1/import-merge/plan` reads an
  ordinary format-5 data archive, compares it with what this phone holds, and answers with a plan —
  writing nothing. Per row: not here → **insert**, with the UUID preserved; here and identical →
  **no-op**; here and different → **conflict**. NFC tags are matched on their payload identity as
  well as their row id, so the same physical tag cannot end up bound to two assets. `POST
  /v1/import-merge/apply` writes the plan, and **only when it has no conflicts** — one conflict
  anywhere and nothing at all is written, with the conflict list returned instead. So a merge can
  never overwrite or delete a row this phone already had. An attachment row is written only when its
  bytes are already in the attachment folder. Resolving conflicts, and mapping an imported asset
  onto a local one, are a later release; there is no screen for any of it in 1.1.0.

The full contract — endpoints, request and response shapes, status codes and limits — is
[`docs/api/v1.md`](docs/api/v1.md). The workstation side is
[`tools/servicetag-mcp/`](tools/servicetag-mcp/README.md). Building an importable archive from a
private inventory file is [`tools/servicetag-bundle/`](tools/servicetag-bundle/README.md)'s job.

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

1.4.0 is this release: operating seasons, maintenance service policy and a maintenance break,
operational condition and derived health, on top of the schedules, reminders, attachments, local
automation API and share intake that earlier releases shipped. Next comes a shared catalog of parts
and supplies with stock and reorder readiness (#15), then tracking which parts are installed in which
assets and what material each maintenance needs (#47), then merging a backup set into an existing
ServiceTag database (#44). Once those are in, the production phone converges onto the same release
and data as the development phone. The design package under [`docs/design/`](docs/design/README.md)
still describes the whole system, and progress is tracked in the GitHub issues.

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
