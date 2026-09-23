# B03 — the share target, the intake screen, and the manifest's third exported name

**Read first:** the master plan's §1 (global constraints), §5 (the policies this brief calls), §6
(the share intake contract), §8 (the intake screen), §9 (invariants), §13 (waves — **this brief
holds `emulator-5554` for the whole of wave 3**), §17 (strings). Those sections are the contract;
this brief is the work. **Spec:** `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` §2,
§4.1, §4.3, §4.4, §7, §10, Rulings D-6, D-13, D-14, D-20. **Report:**
`.superpowers/sdd/2026-09-23-servicetag-share-intake/B03-report.md`.

## Purpose

Land the exported `ACTION_SEND` target and the screen behind it: one activity with the exact
fully-qualified name `ManifestContractTest` asserts, its filter over exactly ten MIME types, a
stream reader that consults B02's I-9 predicate **before it constructs the `ByteSource`**, and a
one-column intake screen with the asset chooser, Name, Description, a Type control on the byte path,
and the four refusal states. **Cancel, back, and every refusal write nothing** (I-8). The manifest's
exported set grows from two names to three and stays an exact set (D-14). **No References section,
no "Add link", no route, no MCP tool, no Documents-row change** — those are B04's and B05's.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/share/ShareIntakeActivity.kt` — **this exact
  fully-qualified name is the string the manifest and `ManifestContractTest` both carry; it may not
  be renamed, moved or repackaged by any later brief.** A `ComponentActivity` that builds the graph
  as `MainActivity` does, hosts the screen, and calls `finish()` on Save, Cancel, Close and back.
- `app/src/main/kotlin/com/loosecannon/servicetag/share/SharedItem.kt` — the intent reader (see
  Interfaces). Plain functions over an `Intent` and a `ContentResolver`, so the whole of it is
  testable with Robolectric-free fakes where possible and in `androidTest` where a real resolver is
  needed.
- `app/src/main/kotlin/com/loosecannon/servicetag/share/ShareIntakeViewModel.kt` — the state
  machine.
- `app/src/main/kotlin/com/loosecannon/servicetag/share/ShareIntakeScreen.kt` — the Compose screen,
  built from the shipped section primitives (`SectionHeader`, `QuietLine`, the app's text fields).
- Tests: `app/src/test/kotlin/.../share/SharedItemReaderTest.kt`,
  `app/src/test/kotlin/.../share/ShareIntakeViewModelTest.kt`,
  `app/src/androidTest/kotlin/.../share/ShareIntakeScreenTest.kt`.

**Modify**

- `app/src/main/AndroidManifest.xml` — the activity and its filter; the five new `<queries>`
  entries.
- `app/src/test/kotlin/com/loosecannon/servicetag/reminders/ManifestContractTest.kt` — **that file
  holds two top-level classes and this brief edits only the first.** In **`ManifestContractTest`**
  (`:23`): rename `theExportedComponentSetIsExactlyTheTwoShippedActivities` (`:84`) off
  "TheTwoShippedActivities" (for example `theExportedComponentSetIsExactlyTheThreeNamedActivities`),
  add the share activity to the expected **set**, **widen what it reads from `elements("activity")`
  to `elements("activity") + elements("activity-alias")`** — an exported alias is a fourth exported
  component and the shipped assertion does not see one — and update the class KDoc (`:13`–`:14`).
  **The assertion stays an exact set and is never made a count of three** — that KDoc (`:16`,`:19`)
  records that the merged manifest carries library-injected components. Add the two new `@Test`s
  named in the matrix. While in that KDoc, also correct its pre-existing drift "exactly four
  receivers" → **six**, which is what `everyReceiverIsNonExportedAndThereAreSix` (`:57`) has
  asserted since 1.2; the report names it as a drive-by comment fix.
- `app/.../di/AppGraph.kt` — nothing new is constructed here by this brief unless the activity needs
  a graph accessor B02 did not expose; if it does, it is one `val` and the report says so. **Wave 3
  merge order: B05 merges first, this lane rebases once onto the merged tip and re-runs its whole
  gate** (master plan §10).

**Untouched — read this one carefully, it is the finding a review round was spent on.**
`app/src/test/.../reminders/ManifestContractTest.kt` contains **two** classes:
`ManifestContractTest` (`:23`), which this brief edits, and **`MergedManifestContractTest`
(`:197`)**, which holds `neitherExactAlarmPermissionInTheMergedManifestEither` (`:208`) and
**`theMergedManifestPermissionSetIsExactly` (`:237`)** and **is left completely untouched** — not
renamed, not folded into the first class, not moved to its own file, its expected permission set not
edited (master plan §1.8, §18.5). Also untouched: `everyReceiverIsNonExportedAndThereAreSix`'s count
of six; the file-scoped helpers below `:262`; `MainActivity`; `nfc/NfcDispatchActivity`; the six
receivers; `AttachmentProblem`, `AttachmentKinds`, `AttachmentLocator`, `AddAttachment` and every
other attachment use case; `MimeTypes.EXTENSIONS`; `ui/attachments/**`, `ui/asset/**` and
**`ui/components/**`** — `SectionHeader` and `QuietLine` are consumed by this brief and by B04 and
neither may tweak a shared primitive (B04); `api/**`, `tools/` and every document; every `:core`
file (B01's and B02's); every tombstone.

## Interfaces

**Consumes:** B01's `AssetReference`, `ReferenceKind`, `ReferenceRepository`; B02's
`LinkLaunchPolicy`, `LinkDecision`, `ShareTextParser`, `ParsedShare`, `StreamSourcePolicy`,
`ReferenceText`, `MAX_REFERENCE_*`, `AddReference`, `AddReferenceCommand`, `ReferenceProblem`; the
shipped `AddAttachment`, `AddAttachmentCommand`, `AttachmentProblem`, `AttachmentKinds.inferFrom`,
`AttachmentStorage.state()`, `ByteSource`, `LogEvent`/`EventCommands` for the `NOTE` path, and
`AssetRepository` for the chooser.

**Produces** (internal to `:app`, but B04 and B05 must not duplicate any of it):

```kotlin
// app/.../share/SharedItem.kt
sealed interface SharedItem {
    /** A URI-only share: EXTRA_TEXT or EXTRA_STREAM that parsed to a link. */
    data class Link(val uri: String, val suggestedName: String?) : SharedItem
    /** A byte share whose stream passed I-9. `size` is null when the provider reports none. */
    data class Bytes(
        val uri: Uri, val suggestedName: String, val mimeType: String, val size: Long?,
    ) : SharedItem
    /** Text that holds no URI (#43 AC 5, D-5). */
    data class PlainText(val text: String) : SharedItem
    /** Refused before anything was opened, or unreadable. */
    data class Refused(val reason: IntakeRefusal) : SharedItem
}

enum class IntakeRefusal { STREAM_NOT_ACCEPTED, UNREADABLE, URI_TOO_LONG, SCHEME_BLOCKED }

fun readSharedItem(
    intent: Intent,
    resolver: ContentResolver,
    streamPolicy: StreamSourcePolicy,
    linkPolicy: LinkLaunchPolicy,
): SharedItem
```

**The precedence rule is the declared type first, the extra second, and it applies to exactly one
type.** `text/uri-list` is a list of URIs by definition — it has no other content — so a sharer may
legitimately deliver one as a stream and an unconditional `EXTRA_STREAM`-first rule would copy it
into the attachment folder as a document, against spec §4.4. `text/plain` is **not** in that
position: it is an ordinary document type, `MimeTypes.EXTENSIONS` maps it to `txt`
(`core/.../core/model/Attachment.kt:92`) for exactly the case of a `text/plain` attachment needing a
locator extension, and spec §4.1's remark that the table "does not carry `text/markdown`, `text/csv`
or `application/msword`" is only meaningful if the other seven of D-6's ten **do** reach
`AttachmentLocator.extension` — the attachment path. A shared `.txt` maintenance log is a file the
owner means to keep, not prose to be offered as a note. See master plan §18.15.

1. **If `intent.type` normalises to `text/uri-list`**, the item is **text**, whichever extra carries
   it. `EXTRA_TEXT` is used when present; otherwise the stream is read — after
   `streamPolicy.accepts` — under **two separate caps** (master §18.20), because a uri-list is a
   *list* and may legitimately be longer than any one URI in it: **at most 64 KiB is read** and
   decoded as UTF-8; the **first line that is neither blank nor a `#` comment** is taken; and
   **`MAX_REFERENCE_URI_CHARS` (2,048; B02's constant) is applied to that extracted URI alone**. A
   stream longer than 64 KiB, or not decodable as UTF-8, is `Refused(UNREADABLE)`; an extracted URI
   over 2,048 is `Refused(URI_TOO_LONG)`. **`text/uri-list` is the only type that takes this arm**,
   and it never reaches the attachment path.
2. **Otherwise, if `EXTRA_STREAM` is present**, the item is **bytes** — including a **`text/plain`**
   stream, which is a document.
3. **Otherwise `EXTRA_TEXT`** is parsed as text. This is the arm a `text/plain` share takes when it
   carries no stream, which is the URL-share case D-6 put `text/plain` in the list for.

**Inside the bytes arm the order is the contract.** Take the `Uri` with the API-33+ typed
`getParcelableExtra(name, Uri::class.java)` — **never the deprecated overload, which returns
whatever a hostile parcel names**; call `streamPolicy.accepts(uri.scheme, uri.authority)`; **only if
it is true** query the resolver for the display name and size and hand back `Bytes`. A `false`
answer returns `Refused(STREAM_NOT_ACCEPTED)` **without any resolver call at all**, so nothing is
opened.

**Inside the text arm:** `ShareTextParser.firstUri`; `null` → `PlainText`; a URI over the cap →
`Refused(URI_TOO_LONG)`; a `LinkDecision.Blocked` → `Refused(SCHEME_BLOCKED)`; otherwise `Link`. The
name suggestion comes from the Markdown label, then `EXTRA_SUBJECT`, then `EXTRA_TITLE`, then the
stream's display name — **each capped to 200 characters and sanitised through `ReferenceText` before
it becomes a display name**, and a filename through `ReferenceText.sanitiseFilename`. **Only these
four extras are read; every other extra is ignored.**

The view model exposes one state with: the parsed item, the asset list, the chosen asset, the name,
the description, the chosen `AttachmentKind` (byte path only), `saveEnabled`, and the current
refusal line. `saveEnabled` is true only when an asset is chosen **and** the sanitised name is
non-blank **and**, on a byte share, `AttachmentStorage.state()` is `Ready`.

## Invariants this brief must hold

**I-8** (cancel, back, and every refusal write nothing: no row, no bytes, no grant, no journal
event); **I-9**'s **call site** (the predicate runs before the `ByteSource` is constructed); **I-2**
and **I-7** are inherited — this brief never re-implements a refusal B02 owns, it only chooses which
§10 sentence to show. **I-3**: a `Link` never reaches `AddAttachment`; a `Bytes` never reaches
`AddReference`. **I-4**: nothing here binds a tag. **I-5**: no tombstone is touched.

**The grant.** `ACTION_SEND`'s `FLAG_GRANT_READ_URI_PERMISSION` lives until the receiving **task**
finishes, so a process recreation mid-intake either still reads it or fails cleanly.
`takePersistableUriPermission` **is never called** — it throws on this grant regardless — so **a
share always copies** through the shipped `AddAttachment` path and ServiceTag stays clear of the 512
persisted-grant cap.

**No new permission.** Nothing in this brief adds a `<uses-permission>`; the release gate asserts
the merged permission set is unchanged.

## The four dead-end states, verbatim from spec §10

| state | what is drawn | what is offered |
|---|---|---|
| no assets (D-13) | "Add an asset in ServiceTag first, then share this again." | **"Close"** only. No asset-creation flow runs inside the share task |
| no attachment folder, **byte** share (D-20) | "Choose an attachment folder in ServiceTag Settings, then share this again." | Save **disabled**, **"Close"**. Nothing is staged and nothing is copied |
| no attachment folder, **URI** share | nothing at all about storage | the normal Save path — a reference needs no folder |
| refused stream (I-9) | "That file cannot be accepted from the app that shared it." | Close. Distinct from "Could not read what was shared", which is a read *failure* |

The other §10 sentences this brief owns: the screen's "Save to ServiceTag", "Received", "Attach to",
"Choose asset", "Name", "Description (optional)", "Type", "Save", "Cancel", "Saved to \<asset\>";
the refusals "That file is empty", "Could not read what was shared", "That link is already on this
asset", "That link is too long to save."; the shipped "That file is larger than 256 MB"; the
unknown-scheme confirmation "Save this link?" / "ServiceTag does not recognise \"\<scheme\>\" links.
It will be saved as written and opened with whatever app claims it." / "Save" / "Cancel"; the
blocked-scheme "ServiceTag will not save that kind of link."; and the not-a-link "That is not a
link." / "Save as a note" / "Cancel". The Type control's seven labels are the shipped
`AttachmentKind.label()` values and are reused, not re-spelled. **No refusal string this brief draws
may name a URI, a scheme, an authority or a path** — the unknown-scheme confirmation's `<scheme>`
placeholder is the one ratified exception.

## Test matrix

One test per hazard class. Each must fail without the change it names. Reader and view-model cases
are JVM; the screen cases are `androidTest`.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| a hostile parcel names a class | the reader uses the typed `getParcelableExtra(name, Uri::class.java)` — asserted structurally over `share/*.kt`: every `getParcelableExtra(` call site passes `Uri::class.java` | the deprecated overload returns whatever the parcel names |
| a `file://` stream is opened | a `file:///sdcard/…` `EXTRA_STREAM` yields `Refused(STREAM_NOT_ACCEPTED)` | without the predicate `ContentResolver.openInputStream` resolves it with ServiceTag's own uid |
| our own provider is read back | a `content://com.loosecannon.servicetag.files/…` stream yields `Refused(STREAM_NOT_ACCEPTED)` | a non-exported FileProvider becomes readable through the share path |
| any other scheme is accepted | an `http://` `EXTRA_STREAM` yields `Refused(STREAM_NOT_ACCEPTED)` | a predicate that only denies `file` lets a third scheme in |
| **the refusal happens after the stream is opened** | a fake `ContentResolver` that **throws on every call** still yields `Refused(STREAM_NOT_ACCEPTED)` for a refused URI — proving the predicate ran first and nothing was opened | a reader that queries the name and size before checking the scheme opens a URI it then refuses |
| a legitimate provider is refused | a `content://com.android.providers.downloads.documents/…` stream yields `Bytes` with the provider's name, type and size | an allow-list breaks every real document provider |
| the two text extras are unbounded | a 400-character `EXTRA_SUBJECT` and a 400-character `EXTRA_TITLE` each yield a 200-character sanitised suggestion with no control characters | an uncapped extra puts 400 characters into a 200-character column |
| a filename escapes | a stream whose display name is `../../etc/passwd`, or carries a NUL or a newline, yields a path-free basename | a raw provider name becomes part of a locator |
| a non-URI share is guessed at | `EXTRA_TEXT` holding prose yields `PlainText` and the screen offers **"Save as a note"** with "That is not a link." — saving writes an `EventKind.NOTE` journal event and **no reference** | a fallback that stores prose as a URI violates #35 and D-5 |
| a blocked scheme reaches the screen | `javascript:alert(1)` shows "ServiceTag will not save that kind of link." and Save is not offered | a screen that relies only on the use case shows a generic failure after the fact |
| an unknown scheme saves silently | `zotero://select/items/0` shows "Save this link?" with the scheme in the ratified sentence, and only after Save does `AddReference` run with `confirmedUnknownScheme = true` | a screen that passes `true` unconditionally removes the confirmation the policy exists for |
| an over-long URI | a 2,049-character URI shows "That link is too long to save." | an uncapped path writes an unbounded column |
| a duplicate link | saving a URI already on the chosen asset shows "That link is already on this asset" and writes nothing | mapping `DuplicateUri` to the generic failure hides a recoverable state |
| an empty file is accepted | a zero-length stream shows "That file is empty" **from the intake layer** and `AddAttachment` is never called | adding a member to `AttachmentProblem` would change the shipped camera and picker paths and the exhaustive `when` — spec §4.3 puts this refusal here for that reason |
| an over-cap file is copied first | a declared size over `MAX_ATTACHMENT_BYTES` shows the shipped "That file is larger than 256 MB" **before the copy** | checking only after the copy writes 256 MiB+ into the owner's folder first |
| a read failure looks like a refusal | a resolver that throws on `openInputStream` shows "Could not read what was shared", **not** the I-9 sentence | one message for two different facts hides a security refusal behind a transient error |
| **a `text/uri-list` stream is filed as a document** | an intent whose `type` is `text/uri-list` carrying **`EXTRA_STREAM` and no `EXTRA_TEXT`** yields `Link`, not `Bytes`, and nothing is copied into the attachment folder; the same type with `EXTRA_TEXT` present also yields `Link`; and the two caps hold independently (master §18.20): a **65 KiB** uri-list stream, and one not decodable as UTF-8, are each `Refused(UNREADABLE)`, while a **3 KiB** uri-list whose first non-comment line is a 300-character URI **succeeds**, and one whose first non-comment line is a **2,049**-character URI is `Refused(URI_TOO_LONG)` | an unconditional `EXTRA_STREAM`-first rule copies a URI list in as an attachment, against spec §4.4, and the type is in D-6's ten so a real sharer can produce it |
| **a shared `.txt` file is turned into a note** | an intent whose `type` is `text/plain` carrying **`EXTRA_STREAM`** yields **`Bytes`**, is copied through `AddAttachment`, and the row's locator ends `.txt` (`MimeTypes.EXTENSIONS` maps `text/plain`); the same type carrying only `EXTRA_TEXT` yields `Link` or `PlainText` as the text arm decides | widening the uri-list arm to `text/plain` decodes the file, finds no URI, and offers "Save as a note" — the document is never stored and the owner loses it silently (master §18.15) |
| **a failure mid-copy leaves a row or a partial file** | a `ByteSource` that yields some bytes and **then throws** leaves **no attachment row**, nothing at the locator in the fake store, and shows "Could not read what was shared" | the shipped `AddAttachment` only `deleteBestEffort`s on the **over-size** arm (`AddAttachment.kt:62`–`66`), not on a throw from the source, so without this case a half-written file can outlive the failure. Spec §8's Grants row names this hazard and no other brief carries it |
| **the description is collected and dropped** | on the byte path, Name → `AddAttachmentCommand.displayName` and Description → `AddAttachmentCommand.notes`, asserted on the command the view model hands `AddAttachment` (both fields exist at `AttachmentCommands.kt:22`,`:28`) | a screen that collects a description and never puts it on the command makes #43 AC 9 unreachable on the byte path, and B04's rendering test would pass against a `notes` nothing ever writes |
| **the no-assets state saves** | with zero assets the screen shows "Add an asset in ServiceTag first, then share this again." and **"Close"**, offers no Save and no asset-creation flow, and writes nothing | a chooser over an empty list with an enabled Save writes a row with no owner |
| **the no-folder state stages bytes** | with `StoreState.NotConfigured` a **byte** share shows the ratified no-folder sentence, Save is **disabled**, nothing is staged and nothing is copied; **a URI share on the same phone saves normally and the screen says nothing about storage** | a single storage gate on the screen blocks the reference path, which needs no folder (D-20) |
| a blank name is savable | with a non-blank asset chosen and the name cleared, Save is **disabled** — the blank-name sentence is never drawn on this screen | an enabled Save produces a refusal the screen has no ratified sentence for here |
| the Type control is offered on a link | a `Link` share draws no "Type" control; a `Bytes` share draws it, prefilled with `AttachmentKinds.inferFrom` | one screen for both paths offers a kind the reference model has no column for |
| **cancel writes something** | one case per step — before choosing an asset, after choosing one, after typing a name, at the confirmation dialog — Cancel and system back each leave the asset's reference count, attachment count and event count unchanged, and no file in the attachment folder (I-8) | a view model that saves on dispose, or an activity that commits in `onPause`, leaves a row |
| the sharing app is not returned to | after Save and after Cancel the activity is finished and `MainActivity` was not started | a screen that navigates into the shell breaks the finish-back-to-the-sharer contract |
| the running shell is disturbed | with `MainActivity` already running, a share leaves its back stack, its current destination and its scroll untouched — `taskAffinity=""` and `launchMode="standard"` asserted on the manifest element | an activity inside `MainActivity`'s `singleTask` stack recreates or renavigates the shell |
| cold and warm starts differ | the same share, performed with the process cold and with it warm, produces the same row field for field | a graph built differently in the two paths writes different defaults |
| a process recreation loses the grant | the view model, restored from a saved state with the same intent, either reads the stream or yields `Refused(UNREADABLE)` — and in the refusing case writes nothing | state held only in memory turns a recreation into a half-written row |
| **the exported set grows silently** | `ManifestContractTest`'s exported set — **`<activity>` and `<activity-alias>` together**, the second added by this brief — is exactly the three fully-qualified names of master plan §1, and no other component kind carries `exported="true"` | a fourth exported component, or a renamed share activity, fails the set comparison (D-14). **`<activity-alias>` is the hole this closes**: the shipped assertion reads `elements("activity")` only, so an exported alias — a fourth exported component by any definition — passes today, and so did master §16's parser before this round |
| the receivers move | `everyReceiverIsNonExportedAndThereAreSix` still passes | a receiver added here breaks the shipped count |
| a permission is added | `theMergedManifestPermissionSetIsExactly` is unchanged and still passes | `ACTION_SEND` intake needs none, and asserting that is the point |
| the filter drifts | a new `@Test` parses the source manifest and asserts the share activity's filter declares `ACTION_SEND`, `category.DEFAULT`, **exactly the ten MIME types of master plan §6**, and **no** `ACTION_SEND_MULTIPLE`, `ACTION_VIEW`, `PROCESS_TEXT` or `BROWSABLE` | a type added or dropped by hand changes what the sheet offers with nothing noticing |
| package visibility is missing | a new `@Test` asserts `<queries>` carries one `ACTION_VIEW` entry per allowed scheme plus the shipped `content` + `*/*` entry | without them `ACTION_VIEW` silently finds no handler on API 30+ |
| **a URI, a filename or shared text is logged** | a structural `@Test` over `app/src/main/kotlin/com/loosecannon/servicetag/share/**`: no `Log.i(`, `Log.w(`, `Log.e(`, `println(` or `System.out` call site anywhere in the package (§4.4) | a debug line left behind puts a person's URL in logcat, where every app with `READ_LOGS` on an old ROM can read it — the one §4.4 control with no other check at any level |

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` — green, with `SharedItemReaderTest`,
  `ShareIntakeViewModelTest` and the amended `ManifestContractTest` counted.
- Connected, **holding `emulator-5554` with `ANDROID_SERIAL` pinned**, requested from and released
  to the controller: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.share.ShareIntakeScreenTest`
  → green; then the whole suite once before hand-back.
- **The exported set, parsed not grepped.** The manifest puts **one attribute per line**, so no
  `<activity …android:exported="true"` single-line pattern can match it — it returns 0 today and
  would keep returning 0 if a fourth exported activity were added, which §1.10 forbids. Run master
  plan §16's XML-aware `python3 -c` check: exit 0, printing the **three** fully-qualified activity
  names sorted and `other 0 []`. It must **fail** if a fourth exported activity or any exported
  receiver, service or provider is present — verify that by running it once against a scratch copy
  of the manifest with a fourth added, and report both results.
- `grep -c 'com.loosecannon.servicetag.share.ShareIntakeActivity' app/src/main/AndroidManifest.xml`
  → **1**; `grep -c 'android.intent.action.SEND_MULTIPLE' app/src/main/AndroidManifest.xml` → **0**;
  `grep -c 'android.intent.category.BROWSABLE' app/src/main/AndroidManifest.xml` → **0**; `git diff $BASE..HEAD -- app/src/main/AndroidManifest.xml | grep -c '^+.*uses-permission'` → **0**.
  **`$BASE` is this lane's base commit**, recorded by the controller at dispatch (master §1.15) —
  never the release branch's base, which would make a later wave's gate fail against its
  predecessors' merged work.
- `grep -rn 'takePersistableUriPermission' app/src/main/kotlin/com/loosecannon/servicetag/share` →
  no output.
- `grep -rnE 'Log\.[iwe]\(|println\(|System\.out' app/src/main/kotlin/com/loosecannon/servicetag/share` → no output.
- `git diff --stat $BASE..HEAD -- app/src/main/kotlin/com/loosecannon/servicetag/ui core/src/main` →
  empty.
- Report the `:app` unit and connected deltas, and the drive-by KDoc receiver-count fix.

## This brief must NOT

Draw a References section, an "Add link" action, an edit sheet or a remove confirmation (B04).
Change `DocumentsSection` (B04). Add a route, a DTO or an MCP tool (B05). Add a member to
`AttachmentProblem`, or a type to `MimeTypes.EXTENSIONS`. Declare `ACTION_SEND_MULTIPLE`,
`ACTION_VIEW`, `PROCESS_TEXT` or `BROWSABLE` on the share activity. Add a `<uses-permission>`. Call
`takePersistableUriPermission`. Re-implement a refusal B02 owns. Invent a string: anything §10 does
not list is a **finding for the controller**. Touch a tombstone, `MainActivity`,
`NfcDispatchActivity` or any receiver. Change `app/build.gradle.kts` or any document. Log a URI, a
filename or shared text at INFO or above.
