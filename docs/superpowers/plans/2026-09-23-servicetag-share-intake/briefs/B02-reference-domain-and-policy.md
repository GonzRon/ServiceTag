# B02 — the reference domain: policy, limits and the three use cases

**Read first:** the master plan's §1 (global constraints), §5 (the reference domain and the three
policies), §9 (invariants), §10 (file map), §17 (strings), §18.1 and §18.2. Those sections are the
contract; this brief is the work. **Spec:**
`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` §3.2, §3.3, §4.2, §4.3, §4.4, §7,
§10. **Report:** `.superpowers/sdd/2026-09-23-servicetag-share-intake/B02-report.md`.

## Purpose

Land every **rule** over B01's shapes, in `:core`, with **no Android type anywhere**: the three-tier
scheme policy reintroduced as new code (#35's lists verbatim), the share-text parser, the I-9
scheme-and-authority predicate, the four constants, the scheme→kind inference, and the three use
cases that are the only way a reference is ever created, amended or removed. Because the refusals
live here, the share screen, the "Add link" sheet, the API and MCP all inherit them and **no caller
can smuggle a blocked scheme past them** (I-2). **No Android, no Room, no UI, no route, no MCP
tool.**

## Files

**Create** — a new package `core/src/main/kotlin/com/loosecannon/servicetag/core/references/`:

- `LinkLaunchPolicy.kt` — the three tiers and `LinkDecision`.
- `ShareTextParser.kt` — `ParsedShare` and `firstUri`.
- `StreamSourcePolicy.kt` — I-9's predicate, strings only.
- `ReferenceLimits.kt` — the four constants and the two sanitisers.
- `ReferenceKinds.kt` — `inferFrom`.

and in `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/`:

- `ReferenceCommands.kt` — `AddReferenceCommand`, `UpdateReferenceCommand`, `ReferenceResult`,
  `ReferenceProblem`.
- `AddReference.kt`, `UpdateReference.kt`, `RemoveReference.kt`.

Tests: `core/src/test/kotlin/.../references/LinkLaunchPolicyTest.kt`,
`.../references/ShareTextParserTest.kt`, `.../references/StreamSourcePolicyTest.kt`,
`.../references/ReferenceLimitsTest.kt`, `core/src/test/kotlin/.../usecase/AddReferenceTest.kt`,
`.../usecase/UpdateReferenceTest.kt`, `.../usecase/RemoveReferenceTest.kt`. A
`FakeReferenceRepository` goes wherever `:core`'s existing fakes live, beside the fakes the shipped
use-case tests already use.

**Modify**

- `app/.../di/AppGraph.kt` — construct `LinkLaunchPolicy`, `StreamSourcePolicy` (with ServiceTag's
  own authorities injected — see Interfaces), `AddReference`, `UpdateReference`, `RemoveReference`,
  and expose them as `val`s in the style of the shipped `addAttachment` / `deleteAttachment` fields.
  **This is the only `:app` file this brief touches.**

**Untouched:** every `:core` file B01 created or modified — this brief adds beside them and edits
none of `AssetReference.kt`, `Ids.kt`, `Repositories.kt`, `BackupFormat.kt`, `BackupCodec.kt`,
`MergePlan.kt`, `MergePlanner.kt`; `AttachmentProblem` and every attachment use case;
`core/.../core/links/DeepLinkRoute.kt` (the `servicetag://` scan route is a different thing entirely
and gains nothing here); `app/.../links/LinkLauncher.kt` (B04); every tombstone (I-5); every
`ui/**`, `share/**`, `api/**`, `data/**`; `tools/`; every document.

## Interfaces

**Produces, for B03, B04 and B05.**

```kotlin
// core/.../core/references/LinkLaunchPolicy.kt
sealed interface LinkDecision {
    data object Allowed : LinkDecision
    data object Blocked : LinkDecision
    data class Unknown(val scheme: String) : LinkDecision
}

class LinkLaunchPolicy {
    fun schemeOf(uri: String): String?          // lowercased; null when there is no scheme at all
    fun classify(uri: String): LinkDecision
    companion object {
        val ALLOWED: Set<String>                // http, https, joplin, obsidian, logseq
        val BLOCKED: Set<String>                // javascript, file, content, intent, android-app, tel, sms, mailto
    }
}
```

`classify` is total: a URI with **no scheme at all is `Blocked`** (spec §4.2), an `ALLOWED` scheme
is `Allowed`, a `BLOCKED` scheme is `Blocked`, anything else is `Unknown(scheme)`. Comparison is on
the lowercased scheme, so `JavaScript:` and `HTTP:` classify as their lowercase selves. **The same
instance answers both save and launch**, which is what makes "a URI that was legal when saved and is
not now is shown and refused, never launched" true by construction.

```kotlin
// core/.../core/references/ShareTextParser.kt
data class ParsedShare(val uri: String, val label: String?)

object ShareTextParser {
    /** The first URI token in shared text; null when the text holds none. */
    fun firstUri(text: String): ParsedShare?
}
```

Rules, each with a test: a bare URL anywhere in the text is found; a Markdown `[label](uri)` yields
the URI **and** the label; a URI mid-sentence is found and its trailing sentence punctuation is not
part of it; a query string and a fragment survive **verbatim**; `text/uri-list` — one URI per line,
`#`-comment lines ignored — yields the first non-comment line; text holding no URI yields `null`.
**Raw text is never returned as a URI** (#35's own rule): `firstUri` returns `null` rather than
guessing.

```kotlin
// core/.../core/references/StreamSourcePolicy.kt
class StreamSourcePolicy(private val ownAuthorities: Set<String>) {
    /** I-9. True only for a `content` scheme whose authority is not one of ours. */
    fun accepts(scheme: String?, authority: String?): Boolean
}
```

Android-free by construction: it takes two plain strings, so `:core` never sees a `Uri`. The `:app`
graph constructs it with ServiceTag's own authorities — `"${applicationId}.files"` and any other
authority the app itself publishes — and **B03's stream reader calls it before it constructs the
`ByteSource`**. A null or blank scheme is refused; a null or blank authority on a `content` URI is
refused; every non-`content` scheme is refused; the comparison on both scheme and authority is
case-insensitive.

```kotlin
// core/.../core/references/ReferenceLimits.kt
const val MAX_REFERENCE_URI_CHARS = 2_048
const val MAX_REFERENCE_NAME_CHARS = 200
const val MAX_REFERENCE_DESCRIPTION_CHARS = 2_000

object ReferenceText {
    /** Trim, strip control characters and newlines, collapse runs of blanks, cap at [MAX_REFERENCE_NAME_CHARS]. */
    fun sanitiseName(raw: String): String
    /** A path-free basename: no separators, no `..` segment, no NUL, no newline; empty when nothing survives. */
    fun sanitiseFilename(raw: String): String
}
```

```kotlin
// core/.../core/references/ReferenceKinds.kt
object ReferenceKinds {
    fun inferFrom(scheme: String?): ReferenceKind    // http/https -> WEB_URL; an ALLOWED app scheme -> NOTE_LINK; else OTHER
}
```

```kotlin
// core/.../core/usecase/ReferenceCommands.kt
sealed interface ReferenceResult<out T> {
    data class Ok<out T>(val value: T) : ReferenceResult<T>
    data class Refused(val problem: ReferenceProblem) : ReferenceResult<Nothing>
}

data class AddReferenceCommand(
    val uri: String,
    val displayName: String,
    val description: String = "",
    /** Set only after the person answered "Save this link?". The API and MCP never set it (§18.2). */
    val confirmedUnknownScheme: Boolean = false,
)

/** What the edit sheet can change. `uri`, `assetId` and `kind` are absent: I-1 and I-6. */
data class UpdateReferenceCommand(val displayName: String, val description: String)

sealed interface ReferenceProblem {
    data object BlankName : ReferenceProblem
    /** Empty, no scheme, or a hierarchical scheme with no host (§18.19). */
    data object NotALink : ReferenceProblem
    data object UriTooLong : ReferenceProblem
    data object SchemeBlocked : ReferenceProblem
    data class UnknownSchemeNeedsConfirmation(val scheme: String) : ReferenceProblem
    data object DuplicateUri : ReferenceProblem
    data object OwnerMissing : ReferenceProblem
    data object NoSuchReference : ReferenceProblem
    data object Unchanged : ReferenceProblem
}
```

`ReferenceProblem` is a **new** sealed interface and not a member of `AttachmentProblem`, whose KDoc
says "the list is closed" and whose exhaustive `when` in `AttachmentsSectionViewModel.say` is on the
untouched list.

```kotlin
class AddReference(
    private val references: ReferenceRepository,
    private val assets: AssetRepository,
    private val policy: LinkLaunchPolicy,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) { suspend fun run(assetId: AssetId, cmd: AddReferenceCommand): ReferenceResult<AssetReference> }

class UpdateReference(references, uow, clock) {
    suspend fun run(id: ReferenceId, cmd: UpdateReferenceCommand): ReferenceResult<AssetReference>
}

class RemoveReference(references, uow) { suspend fun run(id: ReferenceId): ReferenceResult<Unit> }
```

**`AddReference.run`, in this order** — the order is the contract, because each step's refusal must
be reachable:

1. trim the URI; **and check it is structurally a URI at all** → `NotALink` when it is not. Three
   conditions, all of them this layer's (master §18.19): it is non-empty; `policy.schemeOf` returns a
   scheme; and, when the scheme is **hierarchical** — `http`, `https`, or any scheme written with a
   `//` authority — the **host is non-empty**. So `https://` and `http:///path` are refused, while
   `joplin:x-callback-url/openNote?id=…` (opaque, no `//`) is not. **Why here and not in the parser:**
   `ShareTextParser` only ever sees a share, and **"Add link" and the API both bypass it entirely**, so
   a URI's shape can only be guaranteed at the one place all three paths meet — this use case. On the
   wire the refusal is `REFERENCE_URI_INVALID`; in app it is the ratified "That is not a link." — **no
   new string**, because the arm folds into `NotALink`;
2. length > `MAX_REFERENCE_URI_CHARS` → `UriTooLong`;
3. `policy.classify` → `Blocked` → `SchemeBlocked` (**I-2**); `Unknown(scheme)` with
   `confirmedUnknownScheme == false` → `UnknownSchemeNeedsConfirmation(scheme)`;
4. `ReferenceText.sanitiseName(cmd.displayName)` blank → `BlankName`;
5. `assets.get(assetId) == null` → `OwnerMissing`;
6. `references.findByUri(assetId, uri) != null` → `DuplicateUri` (**I-7**);
7. otherwise write, inside `uow.write`, with `scheme` **derived from the URI** and both timestamps
   set to `clock.nowMillis()`, `kind = ReferenceKinds.inferFrom(scheme)`, and the description
   trimmed and capped at `MAX_REFERENCE_DESCRIPTION_CHARS`.

The URI is stored **exactly as it was validated** — trimmed only, never re-encoded, never
lowercased, never with a scheme prepended (**I-1**).

**`UpdateReference.run`**: absent row → `NoSuchReference`; a blank sanitised name → `BlankName`; a
command whose sanitised name and capped description both equal the stored ones → `Unchanged` and
**no write, so `updated_at` does not move** (this is what keeps a re-imported archive `IDENTICAL`,
which B01's matrix asserts from the data side). Otherwise write `display_name`, `description` and
`updated_at` and **nothing else**.

**`RemoveReference.run`**: absent row → `NoSuchReference`; otherwise a hard delete inside
`uow.write`. There is nothing to orphan — one metadata row, no bytes, no artifacts entry (**I-3**).

**Consumes:** B01's `AssetReference`, `ReferenceKind`, `ReferenceId`, `ReferenceRepository`, and the
shipped `AssetRepository`, `UnitOfWork`, `IdGenerator`, `Clock`.

## Invariants this brief must hold

**I-1** (`uri` immutable after creation; only name, description and `updated_at` are mutable);
**I-2** (a hard-blocked scheme is refused **in the use case**, so no API or MCP path can smuggle one
in); **I-3** (no bytes: nothing here touches `AttachmentStore`, a locator or a sha256); **I-5** (no
tombstone is read or written); **I-6** (no owner change: `UpdateReferenceCommand` has no `assetId`);
**I-7** (the duplicate refusal, backed by B01's unique index); **I-9** (the predicate's own
correctness; B03 owns the call site).

It must not make **I-8** unholdable: none of the three use cases writes anything on a refusal, so a
cancelled intake that never reached step 7 has nothing to undo.

## Test matrix

One test per hazard class. Each must fail without the change it names.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| **a structurally broken URI is stored** | one case per shape: `https://` with no host, `http:///path`, a bare `notaurl`, and the empty string are each `NotALink` with nothing written; while `joplin:x-callback-url/openNote?id=0f1e2d3c4b5a6978` — opaque, no `//`, no host — is **accepted** | the parser cannot cover this: "Add link" and the API never call it (master §18.19), so without the check a host-less `https://` reaches the row and then fails at `ACTION_VIEW` forever |
| a bare URL is not recognised | `firstUri("see https://example-mower.invalid/xt1 for parts")` yields that URI and a null label | a parser anchored at the string start returns null |
| a Markdown link loses its label | `firstUri("[Mower maintenance](joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978)")` yields the URI **and** "Mower maintenance" | a parser that only scans for a scheme returns the URI with a null label and the name prefill is lost |
| plain text becomes a URI | `firstUri("just some words")` is null | a fallback that wraps the text as a URI stores prose as a link, against #35 |
| punctuation is swallowed into the URI | a URI ending a sentence ("… at https://example-mower.invalid/xt1.") yields the URI without the trailing full stop | a greedy non-whitespace match stores a URI that resolves to nothing |
| a query or a fragment is rewritten | a URI with `?a=1&b=2#frag` round-trips through `firstUri` and `AddReference` **byte-identically** | any normalisation, re-encoding or lowercasing changes what is stored (I-1) |
| `text/uri-list` is parsed as prose | a uri-list body whose first line is `# comment` yields the **second** line's URI | treating the body as plain text yields the comment line |
| an over-long URI is stored | a 2,049-character URI is refused `UriTooLong` and 2,048 is accepted | an off-by-one or a missing check writes an unbounded column |
| each blocked scheme is reachable | **one case per blocked scheme** — `javascript`, `file`, `content`, `intent`, `android-app`, `tel`, `sms`, `mailto` — and a URI with **no scheme at all**: every one `classify` → `Blocked` and `AddReference` → `SchemeBlocked` | a list that is a prefix of the spec's, or a missing no-scheme arm, lets one through at save |
| a blocked scheme is launchable | `classify` answers `Blocked` for the same nine inputs, so the launch path refuses them too | a policy applied only at save lets a stored URI that is now illegal be launched |
| each allowed scheme is refused by mistake | one case per allowed scheme — `http`, `https`, `joplin`, `obsidian`, `logseq` — `classify` → `Allowed` and `AddReference` saves | a typo in the set breaks a note app silently |
| case makes a scheme legal | `JavaScript:alert(1)` is `Blocked` and `HTTPS://…` is `Allowed` | a case-sensitive comparison is a bypass |
| an unknown scheme saves without asking | `zotero://…` with `confirmedUnknownScheme = false` → `UnknownSchemeNeedsConfirmation("zotero")` and **nothing written**; the same with `true` → saved, `kind = OTHER`, `scheme = "zotero"` | a policy with two tiers instead of three saves silently or refuses outright |
| `kind` is picked by hand | `inferFrom` yields `WEB_URL` for `http`/`https`, `NOTE_LINK` for each of the three app schemes, `OTHER` for an unknown one and for none — and `AddReference` ignores any kind a caller might wish for, there being no `kind` on the command | a `kind` field on the command makes two catalogs of the same fact |
| `scheme` and `uri` disagree | `scheme` is re-derived on every write, so a row whose URI was edited can never keep a stale scheme | storing the scheme once and trusting it lets the two drift, which spec §3.2 says the derivation exists to prevent |
| a blank name is stored | a name that is blank, or only whitespace and control characters, is refused `BlankName` on both add and update | a `isEmpty()` check that runs before sanitisation accepts `"\u0000"` |
| a name or description is unbounded | a 201-character name is stored at 200; a 2,001-character description at 2,000 | a missing cap puts an unbounded value in a column the row DTO round-trips |
| a filename escapes its directory | `sanitiseFilename` strips `../`, a NUL byte, a newline and every separator, and yields empty when nothing survives | a raw filename from a hostile sharer becomes part of a path (spec §4.3) |
| a duplicate is stored | a second `AddReference` with the same URI on the same asset is `DuplicateUri` and writes nothing; the **same URI on a different asset** saves (I-7) | a check on `uri` alone refuses a legitimate second asset |
| an absent owner is stored | `AddReference` on an unknown `assetId` is `OwnerMissing` and writes nothing | a missing owner check produces a foreign-key failure at the DAO instead of a refusal |
| a refusal writes anyway | after **each** refusal arm above, the fake repository has received no `upsert` and the fake unit of work was never entered | a use case that generates the id and writes before validating leaves a row behind (I-8) |
| the URI is edited | `UpdateReferenceCommand` has no `uri` and no `assetId` — asserted structurally on the data class's constructor parameters — and an update leaves the stored `uri`, `assetId`, `kind`, `scheme` and `created_at` unchanged | a widened command makes I-1 and I-6 unenforceable |
| a no-op update moves the timestamp | an update whose values equal the stored ones is `Unchanged` and `updated_at` does not move | a write on every call makes a re-imported archive `CONTENT_DIFFERS` on the next merge |
| removing is soft | `RemoveReference` deletes the row, and a second call is `NoSuchReference` | a soft delete leaves a row the References section would have to filter |
| I-9 accepts a `file://` stream | `accepts("file", null)` is false | a predicate that only checks the authority turns intake into a self-exfiltration primitive |
| I-9 accepts our own provider | `accepts("content", "com.loosecannon.servicetag.files")` is false for every configured own authority, compared case-insensitively | a predicate that only checks the scheme lets a non-exported FileProvider be read back through the share path |
| I-9 refuses a legitimate provider | `accepts("content", "com.android.providers.downloads.documents")` is true | an allow-list instead of a deny-of-our-own breaks every real document provider |
| I-9 is Android-aware | `grep -rlE '^import (android\|androidx)\.' core/src/main` produces no output | an `android.net.Uri` parameter puts a platform type in `:core` and makes the predicate untestable on the JVM |

## Gate

- `./gradlew :core:test --console=plain` — green, with the seven new classes present and counted.
- `./gradlew :app:testDebugUnitTest --console=plain` — green (the only `:app` change is `AppGraph`
  wiring). **The whole `:app` unit suite is green at this brief's tip** — B01 already updated `VersionAgreementTest`'s schema and format assertions (master §18.16), so there is nothing here to report as expected-red.
- `grep -rlE '^import (android|androidx)\.' core/src/main` → no output.
- `grep -rn 'ExternalLink\|LinkKind\|external_link' core/src/main/kotlin/com/loosecannon/servicetag/core/references core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/AddReference.kt core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/UpdateReference.kt core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/RemoveReference.kt` → no output.
- Report the `:core` test delta in the report file.

## This brief must NOT

Introduce a user-visible string. **Every refusal here is a `ReferenceProblem`, and the sentence a
person reads is chosen by the caller** — B03 for intake, B04 for the sheets, B05 for the wire codes
— from spec §10. Touch Room, a DTO, a route, an MCP tool, the manifest or any UI file. Add an
`AttachmentProblem` member. Add a `kind` or a `provenance` field to any command. Read or write a
tombstone. Import an Android type into `:core`. Widen `UpdateReferenceCommand`. Take a persistable
URI permission, open a stream, or reference `AttachmentStore`. Change `app/build.gradle.kts` or any
document.
