# Phase 1A — the post-1.4.0 cleanup: plan

**Roadmap:** #76, Phase 1A. **Base:** master `6f4438e` (1.4.0, versionCode 16).
**Inputs:** `audit/` beside this file — the owner's rulings (`owner-rulings-2026-09-25.md`, binding on every brief) and the evidence — plus the rewritten issue bodies (#51 kept, narrowed by comment), which are the acceptance.
**Process:** `docs/superpowers/planning-policy.md` (plans specify; one task review per brief, batched fixes, at most one scoped re-review; the testing hierarchy, no UI driving, no new boundary journey). Implementers also inherit `.superpowers/sdd/2026-09-24-servicetag-1.4/implementer-constraints.md`, with §6's overrides.

## 1. The briefs

| brief | issues | scope | proof |
|---|---|---|---|
| `B01-ci-pins.md` | #54 | pin the five floating `uses:` lines of `ci.yml`'s `build` job | grep; one green CI run |
| `B02-attachments-test-lifetime.md` | #65 | bounded capture of the flake; then the attachments view model's IO moves onto the test clock, and the model is cleared inside `runTest` | JVM; repeated full-suite runs |
| `B03-developer-api-truth.md` | #66 + #51 | typed listener start; denial notice with an app-settings link; `FLAG_SECURE` for the screen's composition; a terminal `accept()` failure shows S6; docs and a permission inventory | JVM; Compose instrumented |
| `B04-api-error-detail.md` | #52 | readable first-problem `message` and `field` for the four 1.1.0 families; `field` on the 1.4 malformed-value rows; `field` passed through the MCP | JVM; pytest |

None changes the schema, the backup format, an endpoint, a status or `code`, a `problems` entry, a manifest declaration or a dependency.

## 2. Order, lanes, the shared file

**B01 → B02 → B03 → B04**, the owner's order and a risk order: trusted CI, then the known flake closed so later gates are believable, then the two behaviour changes. B01–B03 are sequential, one lane each, each branched from master after the previous merge; B03 is one lane for two issues. **B04 may run beside B03** (two lanes at most): their Files are disjoint except `docs/api/v1.md`, and only B04 touches `tools/servicetag-mcp/`.

**`docs/api/v1.md` is shared by section.** B03 edits only `## Where it is, and when` (the permission bullet and **If it cannot start**, base lines 28–39); B04 edits only `## Errors` and its subsections (base lines 693–868). Whichever merges second merges master first and re-runs its whole gate; the hunks cannot overlap. Both gates run `ReferenceRoutesTest` and `CommandShapesGoldenTest`, which read the whole file. Only B03 uses a device (`emulator-5554`, one connected class per invocation).

## 3. Cross-cutting invariants

1. No version bump (§7).
2. No user-visible text except the shipped S6 and §4's two strings.
3. `error.problems` stays a `List<String>`, every entry byte-identical; no `code` changes.
4. No runtime `requestPermissions(INTERNET)`, no rationale string.
5. No device but `emulator-5554`; changing a phone's permissions is owner-only, and an implementer stops before any such step.
6. Hygiene: no e-mail, no home-directory paths (write `~`), no serial but `emulator-5554`, no private equipment names; "the owner's hardened Android build", never a distribution's name.

## 4. Owner gate 1 — B03's strings: RATIFIED AS WRITTEN (owner, 2026-09-25)

| # | surface | proposed text |
|---|---|---|
| P1A-1 | Developer API screen, error line, drawn **instead of** S6 when the network permission is denied | `ServiceTag is not allowed to use the network, so the Developer API cannot start. Allow network access in the app settings, then close ServiceTag and open it again.` |
| P1A-2 | text button under P1A-1, opening the app's own system-settings page | `Open app settings` |

Voice as 1.4 §10.7: plain, no jargon, imperative rather than "you". **The tail** is true either way: the audit could not observe whether a Settings grant reaches a running process on that build; if it does, the notice clears on return (resume re-runs the listener), and if not, only a fresh process helps. If the owner's optional device proof shows a live grant, the shorter tail `…then come back to this screen.` is available by re-ratification. **P1A-2** matches the shipped `Open notification settings` / `Open battery settings` and stays distinct from `Open settings`, which opens ServiceTag's own Settings screen.

**Numbering.** S6 here is **the 1.1.0 ledger's S6** (`docs/superpowers/plans/2026-09-21-servicetag-1.1.0-automation-api.md`, line 87): `The Developer API could not start. Leave this screen and open it again.` It is not 1.4 §10.7's S6 (`Change condition`), because S numbers restart with each release's ledger.

## 5. Owner gate 2 — #52's contract sentences: APPROVED WITH ONE CORRECTION (owner, 2026-09-25; C6 below)

API contract text for `docs/api/v1.md`, not app UI.

Style: lower-case, no final stop, as 1.4's `Refusal` messages; they name a key or a rule, never a caller's value. **Old text** is in each family's header row; it stays only as the unreachable fallback for a refusal naming no problem. `code` is unchanged everywhere. **`field`:** one key → that key; a pair → its first key (as 1.4's `SEASON_WINDOW_REQUIRED`); a reading's id without the key that sent it → `null` (the id is already in `problems`).

| # | problem | new `message` | `field` |
|---|---|---|---|
| | **`asset_validation`**, old: `the asset was refused` | | |
| C1 | `NameRequired` | an asset needs a name | `name` |
| C2 | `BadCurrency` | currency must be an ISO 4217 code this build knows | `currency` |
| C3 | `CurrencyRequired` | a purchasePriceMinor needs a currency | `currency` |
| C4 | `NegativePrice` | purchasePriceMinor may not be negative | `purchasePriceMinor` |
| C5 | `BadDate(field)` | ⟨field⟩ must be an ISO YYYY-MM-DD date | its field |
| C6 | `Season(BothOrNeither)` | seasonStartMmdd and seasonEndMmdd go together: send both or neither | `seasonStartMmdd` |
| C7 | `Season(BadDate(which=start))` | seasonStartMmdd must be a real MM-DD date | `seasonStartMmdd` |
| C8 | `Season(BadDate(which=end))` | seasonEndMmdd must be a real MM-DD date | `seasonEndMmdd` |
| C9 | `UnknownParent` | parentAssetId must name an asset on this phone | `parentAssetId` |
| | **`event_validation`**, old: `the event was refused` | | |
| C10 | `TitleRequired` | title is required unless the quick action gives a default title | `title` |
| C11 | `BadDate` (no reading id) | occurredOn must be an ISO YYYY-MM-DD date | `occurredOn` |
| C12 | `BadTime` (no reading id) | occurredTime must be an HH:MM time of day | `occurredTime` |
| C13 | `BadDate`/`BadTime` with a reading id (not produced today) | a value in values is not a valid date or time | `values` |
| C14 | `Required` | a required reading in values has no value | `values` |
| C15 | `NotANumber` | a value in values does not fit its reading: a number must be finite, a yes-or-no must be true, false, 1 or 0 | `values` |
| C16 | `BadConsumable` | every consumable needs a name and a quantity of zero or more | `consumables` |
| | **`definition_validation`**, old: `the reading was refused` | | |
| C17 | `LabelRequired` | a reading needs a label | `label` |
| C18 | `BadKey` | key must be a lower-case letter followed by up to 39 lower-case letters, digits or underscores | `key` |
| C19 | `KeyTaken` | another reading of this asset already uses that key | `key` |
| C20 | `BadDecimals` | decimals must be 0–4 | `decimals` |
| C21 | `RangeOrder` | rangeLow may not be greater than rangeHigh | `rangeLow` |
| C22 | `RangeOnNonNumber` | only a NUMBER reading takes rangeLow or rangeHigh | `rangeLow` |
| C23 | `MeterOnNonNumber` | only a NUMBER reading can be a meter | `isMeter` |
| C24 | `Derived(NotNumber)` | a DERIVED reading must be a NUMBER | `valueType` |
| C25 | `Derived(IsMeter)` | a DERIVED reading cannot be a meter | `isMeter` |
| C26 | `Derived(MissingSpec)` | a DERIVED reading needs formula, sourceAId and sourceBId | `formula` |
| C27 | `Derived(SpecOnEntered)` | an ENTERED reading takes no formula, sourceAId or sourceBId | `formula` |
| C28 | `Derived(SameSource)` | sourceAId and sourceBId must name two different readings | `sourceAId` |
| C29 | `Derived(UnknownSource)` | a source names no reading on this phone | null |
| C30 | `Derived(SourceOtherAsset)` | a source must be a reading of the same asset | null |
| C31 | `Derived(SourceNotEntered)` | a source must be an ENTERED reading | null |
| C32 | `Derived(SourceNotNumber)` | a source must be a NUMBER reading | null |
| C33 | `Derived(SourceIsMeter)` | a source cannot be a meter | null |
| | **`profile_validation`**, old: `the quick action was refused` | | |
| C34 | `NameRequired` | a quick action needs a name | `name` |
| C35 | `NameTaken` | another quick action of this asset, archived ones included, already has that name | `name` |
| C36 | `BadField` | every entry in fields must name an ENTERED reading of this asset, at most once; an archived reading stays only if the quick action already had it | `fields` |
| C37 | `BadConsumable` | every consumable needs a name, and a defaultQuantity of zero or more when one is given | `consumables` |
| | **1.4 malformed values**, message unchanged | | |
| C38 | `season_validation` · `BadDate(field)` | the season command was refused | its field |
| C39 | `condition_validation` · `BadDate`/`BadTime`/`BadTimeZone(field)` | the condition was refused | its field |
| C40 | MCP `ToolError` text | `<status> <code>: <message> [field=<field>] (<problems>)`; the bracket appears only when `field` is not null | — |

C5's ⟨field⟩ is `purchaseOn`, `inServiceOn`, `warrantyExpiresOn` or, on the retire route, `retiredOn` (the mapper applies the one sentence to any field; noted at B04's review). C38 also covers the maintenance break's `BothOrNeither` (`usecase.SeasonProblem`, raised only by `POST /v1/assets/{id}/maintenance-break`): message unchanged, `field` `blackoutStartMmdd`, the pair's first key on that route — a value not in the Gate 2 table, ruled by the controller under the owner's pair rule and reported to the owner. C8's unreachable arm (any other `which`) answers `a season date must be a real MM-DD date`, `field` null. `SeasonProblem.BothOrNeither` carries no field of its own, so C6 answers `seasonStartMmdd`, the pair's first key, by the rule above (owner ruling 2026-09-25: a caller can localise the problem there; never null). C29–C33 stay null: the problem names a reading id but not the request key that supplied it, and guessing would be worse than none.

## 6. Overrides to the standing constraints

Requirements come from the brief and this plan, not the 1.4 spec or master plan. Strings: B03 uses 1.1.0's S6 (§4) and P1A-1/P1A-2 once ratified; B04 uses §5 verbatim; nothing comes from 1.4 §10.7. Every other constraint stands.

## 7. Release note

No version bump in Phase 1A: `versionName` / `versionCode` stay 1.4.0 / 16 and the MCP `pyproject.toml` stays 1.2.0. Which release carries Phase 1A, its number and SemVer class (B01/B02 change nothing a user sees; B03 is fix-class with two strings; B04 is additive API detail), its version lines, whole-branch review and upgrade smoke test are planned separately and are **the owner's call**. **B03 side effect:** `FLAG_SECURE` blacks the Developer API screen out of screenshots and recordings, `adb screencap` included; a proof that reads the code from an image must change.

## 8. Acceptance per issue

Each issue closes separately, with a comment naming its commits, tests and run ids.

- **#54:** five full-SHA pins with `# vX.Y.Z` comments, each verified against its release tag; every workflow `uses:` line pinned; one green CI run on that commit.
- **#65:** the capture result (a cause chain, or "not reproduced in 10"); no view-model work on a real dispatcher under test; ten consecutive green full `:app:testDebugUnitTest --rerun` runs after the fix (class-alone runs secondary); the report explains both sightings; follow-ups filed separately.
- **#66:** the typed start result and its table; the denial notice and `Open app settings` proven with an injected denial (JVM + Compose); manifest comment, `v1.md` and README corrected, with the inventory. A real denial on the development phone is **optional and owner-run**; if it contradicts P1A-1's advice, the sentence returns for ratification.
- **#51:** `FLAG_SECURE` held across a pause and cleared on leaving (Compose); the terminal `accept()` failure closes the socket, publishes the terminal state and shows S6 (JVM + Compose).
- **#52:** every §5 row proven by an exhaustive mapper test plus one route case per family; `problems` byte-identical; `field` through the MCP (pytest); `v1.md` matches §5.

## 9. Phase gate at the integrated tip

- **CI's command, locally:** `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug :share-test-sender:assembleDebug --console=plain`. Zero failures, zero skips.
- **MCP:** `uv run --frozen pytest` in `tools/servicetag-mcp`.
- **Connected, one class per invocation:** `com.loosecannon.servicetag.ui.api.DeveloperApiListenerTest` and `com.loosecannon.servicetag.ui.maintenance.ReminderHealthScreenTest`.
- **CI:** one green run on the tip.
