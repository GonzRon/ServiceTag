# #105 — browse and pick a Home Assistant entity during season-sync setup: design and plan (rev 1.0, 2026-10-05)

> **Status: RULED 2026-10-05; B1, B2 and B3 authorized** (§8 carries the rulings verbatim in substance; §5's ten strings
> are RATIFIED). **Release:** content of **ServiceTag 1.8.0** — the former 1.7.2 work, folded into the combined 1.8.0
> train with the former 1.7.1 work and #102 (#104); no release of its own. **Classification stands as the issue states:** a UX improvement to the shipped #16 setup flow — the stored
> binding is still the exact entity id, `on`/`off` are still the only season decisions, no new integration type.

**Base:** master `c994341` (1.7.0 / code 20 released; on master unreleased: #102's localization layer and nine language
packs, the 1.7.1 train #94 #99 #101 #103; Room schema 21 / backup format 20; gitlink `7e0377a`; MCP 90 tools).
**Inputs:** #105 (the owner's; its acceptance list is §9's); #16's plan (`2026-10-02-issue-16-home-assistant-season-sync.md`,
whose C5, C8, C19, C27 and §5 this extends and never re-opens); `docs/home-assistant-season-sync.md`; `docs/localization.md`
(every new word is a resource in every pack, from the first commit); `docs/superpowers/planning-policy.md`. Paths as the
#16 plan: `C/`, `CT/` = core main and test under `com/loosecannon/servicetag/core/`; `A/`, `T/`, `AT/` = app main, test and
androidTest under `com/loosecannon/servicetag/`. Every `file:line` was read on `c994341`.
**Process:** the planning policy — plans specify; one task review per brief, batched fixes, at most one scoped re-review;
JVM first, Compose instrumented second, no UI driving; hygiene as #16's briefs (no real host, token, entity or e-mail in
any file; fixtures are `192.168.0.10`, `ha.example`, `fictional-token-1`, `input_boolean.example_heater_in_season`).

## 1. The problem, and the shape of the answer

Today the setup sheet (`A/ui/asset/LinkSeasonSyncSheet.kt`, opened by **Link to Home Assistant** on the season card) asks
for the **Entity ID** typed by hand (P16-42/43), validates its shape (`isValidEntityId`, C4) and links. The owner has to
know `input_boolean.pellet_stove_in_season`, which is an implementation identifier, and a typo is found only by the fresh
check after Save.

The answer is a **browser inside the same sheet**: a **Choose entity** row in place of the bare field; tapping it turns
the sheet into a searchable list of Home Assistant's `input_boolean` helpers — friendly name over entity id, sorted by
name — read once from Home Assistant through the shipped client under every #16 rule; a tap chooses one and returns to
the form with the choice shown; **Enter entity ID manually** keeps the typed path. Save links exactly as today, with the
exact entity id. Nothing about the binding, the poll, the applier, the modes, the token, the network gate, the schema,
the backup, the API or the MCP changes.

**What validates a picked entity, and when (owner correction 2026-10-05).** `LinkSeasonSync.run()` checks the entity
id's shape, **commits** the binding (and any switch into MANUAL) and only then asks the scheduler for a fresh read: the
fresh Home Assistant read happens **after** the binding is written, exactly as #16 shipped it. For a picker-selected
entity the pre-save evidence is the successful foreground `/api/states` list that produced that exact candidate; Save
passes the exact id through `LinkSeasonSync` unchanged; the existing post-commit fresh read remains the authoritative
runtime check, and a helper that has since disappeared or reports a state that is neither on nor off is drawn on the
card by the existing status lines (P16-17, P16-18). **No per-entity network request is added to change that order.**
Manual entry keeps today's behaviour in full.

Three things the design settles up front:

1. **Entity-first, REST-only in this cut (Q1).** The list comes from `GET /api/states` — the one authenticated read
   endpoint the client already speaks, over the same origin, policy, headers, timeouts and failure map — filtered on the
   phone to `input_boolean.*`, with `attributes.friendly_name` as the display name. Home Assistant's display-registry and
   device-registry lists are WebSocket-only; #16's scope ruling (R16-0) excludes a second transport, and a device view is
   a convenience the issue names as optional. So **no device grouping in this cut**: the acceptance bullet about device
   context is **deferred by owner ruling** (Q1), not satisfied; helpers without a device remain fully selectable; a later
   issue may add the WebSocket registry reads.
2. **The browser is a mode of the sheet, not a route (Q6).** The sheet already fills the height
   (`skipPartiallyExpanded = true`); its one view model gains a browse state, so the pick needs no result passing between
   destinations and a rotation keeps both the list and the choice. Nothing is read until the owner taps Choose entity.
3. **Nothing is inferred from state or name.** A helper is listed because its id starts with `input_boolean.`, never
   because its state is `on`/`off`; the binding stores the id the owner tapped; a renamed helper in Home Assistant changes
   the name shown next time and nothing else.

## 2. Global constraints (binding on every brief)

- **R16-0 stands.** No write to Home Assistant, no service call, no WebSocket/SSE, no inbound listener, no second HTTP
  stack or credential store, no background inventory read, no persisted registry: the list lives in the sheet's view
  model and is gone when the sheet closes.
- **Every read goes through `HomeAssistantStateClient.exchange`** (C19): the address rule, the network permission, the
  home-network gate and the private-name rule run before anything is opened; the four request properties, no redirect,
  no cache; the token is a parameter for one request and reaches no state, log, message or `toString`.
- **Bounded.** The list answer is read under its own cap (Q2) and marked `truncated` beyond it; each kept entity id and
  friendly name is bounded by C5's rule 4 (`MAX_HA_TEXT_LENGTH` for the id, a longer bound for the name, §3); at most
  `MAX_LISTED_ENTITIES` candidates are kept after filtering (§3). A truncated or non-array answer is `MALFORMED`, drawn
  as P16-19.
- **Exact id, exact rules.** A picker candidate was observed in the explicit foreground list before selection; the
  chosen id goes to `LinkSeasonSync.run` unchanged, which validates the shape and writes exactly as #16 does today; the
  existing fresh read runs **after the commit** and reports a vanished or unsupported helper through the card's existing
  status (P16-17/18). No extra pre-save request.
- **A failed read changes nothing**: not the typed or chosen id, not the binding, not the season. Refresh keeps the
  current selection and the last good list until a new one arrives.
- **Strings are resources from the first commit** (`docs/localization.md`): each §5 string in `values/strings_asset_edit.xml`
  with its P105 id in the comment, read through a getter or `stringResource`, and in **every pack** in the same change,
  marked as a draft translation in the report. `UiLiteralGuardTest` and `LocalizationCoverageTest` are the gate.
- **No schema, format, API, MCP, permission or manifest change.** `docs/api/v1.md` is untouched; `GET /v1/assets/{id}/season-sync`
  reads the same binding.
- No device but `emulator-5554` for implementers; the real-HA proof is the controller's (§7).

## 3. Contracts (frozen; B1 builds, B2 and B3 consume)

```kotlin
// C/seasonsync/HaEntityList.kt (B1)
data class HaEntityCandidate(val entityId: String, val friendlyName: String?)      // name null when HA gave none
sealed interface HaListOutcome {
    data class Listed(val entities: List<HaEntityCandidate>) : HaListOutcome         // the count bound is pickerRows's
    data class Failed(val kind: SyncErrorKind, val detail: String?) : HaListOutcome   // the poll's kinds, reused
}
const val MAX_HA_NAME_LENGTH = 128
const val MAX_LISTED_ENTITIES = 2_000
/** A 200 JSON array of objects → every object with a string `entity_id` whose shape passes `isValidEntityId`, its
 *  `attributes.friendly_name` when a string; ids bounded by MAX_HA_TEXT_LENGTH, names by MAX_HA_NAME_LENGTH, control
 *  characters as `?`; duplicates by id dropped (first wins). Statuses as `testOutcomeOf`: 401/403 AUTH_REFUSED, 3xx
 *  REDIRECTED, other non-200 HTTP_ERROR(status); truncated body, non-JSON type or non-array → MALFORMED. */
fun mapHaStatesAnswer(answer: HaHttpAnswer): HaListOutcome

// C/seasonsync/EntityPicker.kt (B1) — pure
enum class EntityScope { INPUT_BOOLEANS }                                            // one scope in this cut (Q3)
/** The scope's candidates, searched and sorted: a candidate matches [query] (trimmed, casefolded) as a substring of its
 *  casefolded friendly name or entity id; empty query matches all; sorted by (friendly name casefolded, entity id), a
 *  candidate with no name sorting by its id; at most MAX_LISTED_ENTITIES, `truncatedList` when more matched the scope. */
fun pickerRows(all: List<HaEntityCandidate>, scope: EntityScope, query: String): PickerRows
data class PickerRows(val rows: List<HaEntityCandidate>, val truncatedList: Boolean)

// A/seasonsync/HomeAssistantStateClient.kt (B2)
suspend fun listStates(connection: HaConnection, token: Secret): HaListOutcome      // GET <base>/api/states, cap LIST_MAX_BODY_BYTES
// LIST_MAX_BODY_BYTES = 8 MiB (Q2); everything else exactly `exchange`'s

// A/ui/asset/LinkSeasonSyncViewModel.kt (B3)
data class EntityBrowseState(
    val loading: Boolean, val query: String, val all: List<HaEntityCandidate>, val rows: List<HaEntityCandidate>,
    val truncatedList: Boolean, val loadedOnce: Boolean, val failure: List<Notice>,   // P16 sentences, or empty
)
// LinkSeasonSyncState gains: chosen: HaEntityCandidate?, manualEntry: Boolean, browse: EntityBrowseState?
// methods: chooseEntity() (opens browse, reads once), refreshEntities(), onQuery(text), pick(candidate), closeBrowse(),
//          enterManually(); save() sends chosen?.entityId ?: entityId.trim()
```

The view model takes the list read as a seam, `listEntities: suspend () -> HaListOutcome`, wired in `AppGraph` to
`{ haStateClient.listStates(connection, token) }` over the stored connection and `secretStore.get(HA_TOKEN_KEY)`; no
connection or no token answers `Failed(NEEDS_TOKEN)` / `NO_CONNECTION`'s sentence without opening anything.

## 4. Behaviour (the contract the tests pin)

**The form (LINK purpose only; RESUME is unchanged).**
- With no choice and manual entry off: a **Choose entity** row (P105-1) with P16-43's helper under it; Save disabled
  until a choice or a manual id exists (`canSave` gains `hasEntity`).
- After a pick: the row shows the friendly name over the entity id (or the id alone when HA gave no name) and a
  **Change** affordance (P105-2); Save enabled.
- **Enter entity ID manually** (P105-3) swaps the row for today's text field (P16-42/43/49 unchanged); a typed id clears
  the chosen candidate; **Choose entity** is still offered under the field, and a pick clears the typed text.
- Save: `link.run(assetId, id)` with the exact id — the picked candidate's or the trimmed typed text — so the shape
  check, the commit and the post-commit fresh read are #16's unchanged; every refusal and the #78 question exactly as today.

**The browser.**
- Opens on Choose entity; reads once (`loading`, P105-5 while it does, bounded by the client's `CALL_MILLIS`); draws a
  search field (P105-4), the scope line (P105-6), **Refresh** (P105-7), and the rows — friendly name over entity id,
  `LazyColumn`; the list is `pickerRows(all, INPUT_BOOLEANS, query)`.
- A tap on a row is the pick: back to the form with the choice shown. **Enter entity ID manually** is offered here too.
- Empty results are two different sentences: no helper at all in the scope (P105-8), or none matching the query (P105-9).
  A truncated list adds P105-10 above the rows.
- A failure draws its P16 sentence (the `seasonSyncErrorNotices` map by kind, with the entity id argument empty) above
  the last good list, which stays; the selection stays; Refresh retries. A failure on the first read shows the sentence
  and no rows. Authentication/network failures are therefore never confused with "no matching entities".
- Closing the browser without a pick keeps whatever was chosen or typed before.
- Nothing is read on open, rotation or return; only Choose entity and Refresh read.

**Home Assistant's rename of a bound helper** changes nothing: the card draws the stored id as today (P16-27); the
browser shows the new name next time it is opened.

## 5. Strings — RATIFIED by the owner 2026-10-05 (P105-1…10; P105-8 and P105-9 in the owner's wording)

Voice as #16's §5. Each is one resource in `values/strings_asset_edit.xml` (prefix `season_sync_`), with the P105 id in
its comment, and in every pack. Reused unchanged: P16-42 `Entity ID`, P16-43 the helper, P16-49 the shape refusal,
P16-19 `MALFORMED`, P16-13/14/15/16/20/21/50/51/52 by kind, `Save`, `Cancel`.

| id | proposed wording | where |
|---|---|---|
| P105-1 | `Choose entity` | the form's row with nothing chosen; the browser's title |
| P105-2 | `Change` | on the form's row after a pick |
| P105-3 | `Enter entity ID manually` | the form's and the browser's advanced path |
| P105-4 | `Search by name or entity ID` | the search field's hint |
| P105-5 | `Reading entities from Home Assistant…` | the bounded loading line |
| P105-6 | `Showing Home Assistant's on/off helpers (input_boolean).` | the scope line over the rows |
| P105-7 | `Refresh` | the button |
| P105-8 | `No Home Assistant on/off helpers found. Create one in Home Assistant, or enter an entity ID manually.` | the scope is empty |
| P105-9 | `No entities match your search.` | the query matches nothing |
| P105-10 | `Only the first %1$d are shown. Search to narrow the list.` | `truncatedList` — `%1$d` is `MAX_LISTED_ENTITIES` |

## 6. Briefs, order, files

| brief | scope | layer | files |
|---|---|---|---|
| **B1 — the list mapper and the picker rules** (C/seasonsync) | `HaEntityCandidate`, `HaListOutcome`, `mapHaStatesAnswer`, `EntityScope`, `pickerRows` | core JVM | create `C/seasonsync/HaEntityList.kt`, `C/seasonsync/EntityPicker.kt`; `CT/seasonsync/HaStatesMapperTest.kt`, `CT/seasonsync/EntityPickerTest.kt` |
| **B2 — the client's list read** (A/seasonsync) | `listStates` through `exchange`, its own cap, `truncated` honoured; `AppGraph` seam | app JVM | modify `A/seasonsync/HomeAssistantStateClient.kt`, `A/di/AppGraph.kt`; `T/seasonsync/HomeAssistantStateClientTest.kt` (rows on the existing `Harness`/`Script`) |
| **B3 — the sheet's browser** (A/ui/asset) | the states and methods of §3, the form and browser composables, the strings and their nine translations | app JVM + Compose | modify `A/ui/asset/LinkSeasonSyncViewModel.kt`, `LinkSeasonSyncSheet.kt`, `SeasonSyncStrings.kt`; `res/values*/strings_asset_edit.xml` (ten packs); `T/ui/asset/LinkSeasonSyncViewModelTest.kt`; `AT/seasonsync/SeasonSyncScreensTest.kt` (the sheet's rows) |
| **B4 — the documents** | the linking section, the limits, the capabilities page | docs | `docs/home-assistant-season-sync.md` ("On the phone: linking an Asset", "Limits"), `docs/capabilities.md` (the HA paragraph: one sentence) |

**Order:** B1 → B2 → B3 → B4, each `<base>` the previous accepted tip; B2 may start beside B1 only with §3 frozen (it
is). One branch. **Untouched by all four:** `C/seasonsync/HaStateMapper.kt`, `LinkSeasonSync.kt`, `SeasonSyncRunner`,
the applier, the guard, the schema, `BackupData`, the codec, `docs/api/v1.md`, the MCP, the manifest, `libs/nfc-tag-core`,
`SeasonSyncPlatformProofTest`, every #16 string.

## 7. Test matrix (every row names what fails without the change)

| # | case | where | fails without |
|---|---|---|---|
| 1 | a 200 array maps every object with a valid `entity_id`; `friendly_name` when a string, null otherwise; invalid ids and non-objects dropped; duplicates by id first-wins | `HaStatesMapperTest` | a map that trusts the shape |
| 2 | statuses: 401/403 → AUTH_REFUSED, 3xx → REDIRECTED, 404/500 → HTTP_ERROR(status) | same | the poll's 404 → ENTITY_NOT_FOUND reused by mistake |
| 3 | truncated, non-JSON type, a JSON object or scalar → MALFORMED | same | a parse of a cut-off array |
| 4 | id bound 64, name bound 128, control characters `?`, surrogate pair never split | same | C5 rule 4 skipped |
| 5 | scope keeps `input_boolean.*` only; `switch.`, `binary_sensor.`, `input_boolean_x.` dropped | `EntityPickerTest` | a prefix test on `input_boolean` without the dot |
| 6 | search is a casefolded substring on name and on id; trimmed; empty matches all; `Pellet Stove In Season` found by `pellet`, by `stove in`, by `pellet_stove` | same | an exact or prefix match |
| 7 | order: name casefolded then id; a nameless candidate sorts by its id and draws it alone | same | an id order, or nulls first |
| 8 | at most MAX_LISTED_ENTITIES rows, `truncatedList` true beyond | same | an unbounded list |
| 9 | `listStates` GETs exactly `<base>/api/states` with the four headers, no redirect, no cache, under the home-network gate; off the home Wi-Fi nothing opens | `HomeAssistantStateClientTest` | a path or header drift |
| 10 | a body over `LIST_MAX_BODY_BYTES` is `truncated` → MALFORMED; one just under maps | same | the 64 KiB cap reused |
| 11 | `Choose entity` reads once and fills `browse.rows`; nothing is read on construction | `LinkSeasonSyncViewModelTest` | a read in `init` |
| 12 | a pick sets `chosen`, clears typed text, closes the browser; `save()` sends the exact id | same | a name sent, or trailing whitespace |
| 13 | manual entry: typing clears `chosen`; Save sends the trimmed text; P16-49 on a bad shape as today | same | the two paths crossing |
| 14 | a failed first read draws the kind's sentence and no rows; a failed refresh keeps the last rows and the selection | same | the list cleared on failure |
| 15 | no helpers vs no match are two different states | same | one empty sentence |
| 16 | `canSave` false with nothing chosen and nothing typed; true after a pick; the #78 prompt flow unchanged | same | Save with an empty id |
| 17 | the token reaches no state: `toString` of every state value names no token, id list only | same | a leak into state |
| 18 | the form shows Choose entity, the pick fills the row with name and id, Save is enabled, and Enter entity ID manually brings the field back | `SeasonSyncScreensTest` | wiring |
| 19 | the browser's search narrows the rows; Refresh re-reads; a failure sentence is drawn over the rows | same | wiring |
| 20 | every P105 string exists in every pack, placeholders match | `LocalizationCoverageTest` (existing) | a missing translation |
| 21 | no English literal in the new Kotlin | `UiLiteralGuardTest` (existing) | a literal |

The fake Home Assistant is the client test's scripted `HttpURLConnection` (`Harness`, `Script`) with a states-array body;
no socket in any JVM test. The view-model tests script `listEntities` directly.

## 8. Owner rulings (2026-10-05; binding on every brief)

- **Q1 — APPROVED, with a scope amendment.** REST `GET /api/states` only for 1.8.0; no WebSocket transport merely to
  obtain the entity, display or device registries. Device grouping/context, `config/entity_registry/list_for_display`
  and `config/device_registry/list` are **explicitly deferred**. The acceptance bullet about device context is deferred
  by this ruling, not satisfied; helpers without devices remain fully selectable.
- **Q2 — APPROVED.** An **8 MiB cap for the explicit foreground `/api/states` list call** only; the ordinary #16 poll
  stays at 64 KiB and its cap is not raised globally. Beyond 8 MiB the read fails boundedly (MALFORMED, P16-19) and
  manual entry remains.
- **Q3 — APPROVED.** The browser exposes **`input_boolean.*` only** in this release; manual entry is the escape hatch for
  anything else; no "all on/off entities" mode and no warning copy in #105.
- **Q4 — APPROVED.** Changing the entity of an already-linked Asset is out of scope; `ChangeSeasonSyncEntity` is not
  surfaced by this work. #105 improves initial linking only.
- **Q5 — RATIFIED** with two wording edits (P105-8, P105-9), as §5 now reads. All ten go into the ten packs.
- **Q6 — APPROVED.** The browser is a mode of the existing setup sheet, not a pushed destination: one setup transaction.
- **Required correction, applied** (§1, §2, §4, §9): the fresh read happens after the commit, as shipped; a picked
  candidate's pre-save evidence is the foreground list it came from; no per-entity request is added.
- **Administrative:** #105 is no longer a standalone 1.7.2 item; it is retitled and reclassified as 1.8.0 content.

## 9. Acceptance map (#105's bullets → rows)

choose without knowing the id → 18; searchable by name, id shown → 6, 18; search matches the id → 6; default results
are `input_boolean` → 5; helpers without a device remain selectable → 1, 5 (no device is read, so none can gate a row);
device context → **deferred by owner ruling Q1** (not met in 1.8.0); exact id into the #16 flow → 12 (the candidate was
observed in the foreground list; `LinkSeasonSync` validates the shape and commits; the post-commit fresh read reports
through the card); manual entry remains → 13, 18; no writes, #16's rules → 9, 17 and §2; failed discovery changes
nothing → 14; names are presentation only → 12 and §4; JVM/Android/fake-HA coverage → §7.

## 9a. Execution record (2026-10-05)

- **B1, B2, B3 and B4 executed** on `claude/sleepy-fermat-h5k5ul` after the rulings. Deviations from the text above,
  each small: `HaListOutcome.Listed` carries the entities only (the count bound is `pickerRows`'s, §3 amended);
  `HaStateMapper.kt`'s `stringOrNull` and `bounded` became `internal`, the latter taking its bound as a parameter, so
  the list mapper applies C5 rule 4 through the same code (behaviour unchanged; the file is otherwise untouched);
  ~~Save stays enabled with nothing chosen and nothing typed~~ (reversed by the PR review, below: row 16 holds as
  written); the two device rows prove the
  form's Choose entity row, the manual path and the browser's static parts and its Cancel, while the rows, the
  sentences and the pick are the JVM's (no Home Assistant answers on the emulator); `enterManually` prefills the field
  with a pick's id. Translations are drafts until a native speaker reviews them.
- **Gates run here:** `:core:test` (the two B1 classes, 8 cases, and the whole core suite green). **Not run here:**
  `:app:testDebugUnitTest` (no Android SDK in the planning environment; CI on the branch is the proof of record for
  the client rows, the view-model rows, `LocalizationCoverageTest` and `UiLiteralGuardTest`) and the connected class
  (R2 on `emulator-5554`, the controller's step). CI's command was green on `471ba25`.
- **Task review (one, B1–B4 together; policy).** Three findings, fixed in one batch: (1) the list's JSON — up to
  8 MiB — was mapped on the caller's thread, the sheet's main one; `listStates` now maps it on the client's `io`;
  (2) a read still running when the browser closed, or when Choose entity started another, was only ignored, so its
  late answer could land in the next browser over a newer list; the view model now holds the read's job and a new
  read, a pick, manual entry and a close each cancel it (the client disconnects on cancel), pinned by a new row-14
  case that fails without it; (3) P105-8's "scope empty" was re-sorted on every keystroke; it is computed once per
  list. Noted, not changed: the system back gesture while the browser is open closes the whole sheet (no pick, nothing
  written), as the shipped sheet's back does; a key-store or database failure before the read draws no sentence, as
  Save's does (none is ratified for it).
- **PR review (#107, at `206a00e`).** Two blocking findings, fixed together: (1) the sheet's own dismissals — back, a
  scrim tap, a swipe and the form's Cancel — called the screen's close directly, so a list read still running outlived
  the sheet (each opening's model is keyed under the asset page, which stays); they now go through the model's
  `dismiss()`, which cancels it; (2) Save is held on Link until there is an entity to send — a pick or non-blank typed
  text — as row 16 specifies; Resume is unchanged. The fix also caught two shipped device rows (the S55 sheet and #78's
  YEAR_ROUND link) that typed into a field the browse-first sheet no longer shows: their typing now opens Enter
  entity ID manually first, and the S55 row asserts Save held, then enabled. **Proof gate kept by the review:** the
  connected `SeasonSyncScreensTest` on `emulator-5554` before merge (CI does not run connected tests).

## 10. Proofs at the tip (controller)

CI's command; the connected classes `SeasonSyncScreensTest` first, then the whole suite on `emulator-5554`; R6 hygiene
greps; the real-HA proof as #16's §7, once: open the sheet against the owner's instance, see the helpers listed by name,
pick one, Save, the fresh check lands (no address, entity or token in any file). No upgrade paragraph: no schema or format
step. 1.8.0's release notes carry one paragraph for this.
