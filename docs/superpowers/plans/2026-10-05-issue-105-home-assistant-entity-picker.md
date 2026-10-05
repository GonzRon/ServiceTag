# #105 — browse and pick a Home Assistant entity during season-sync setup: design and plan (rev 1.0, 2026-10-05)

> **Status: PLANNING.** Dispatch waits on the owner's rulings Q1–Q6 (§8) and the ratification of §5's strings. Nothing of
> this plan is built. **Release:** content of **ServiceTag 1.8.0** (#104: the former 1.7.2 folds into 1.8.0; no release
> of its own). **Classification stands as the issue states:** a UX improvement to the shipped #16 setup flow — the stored
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

Three things the design settles up front:

1. **Entity-first, REST-only in this cut (Q1).** The list comes from `GET /api/states` — the one authenticated read
   endpoint the client already speaks, over the same origin, policy, headers, timeouts and failure map — filtered on the
   phone to `input_boolean.*`, with `attributes.friendly_name` as the display name. Home Assistant's display-registry and
   device-registry lists are WebSocket-only; #16's scope ruling (R16-0) excludes a second transport, and a device view is
   a convenience the issue names as optional. So **no device grouping in this cut**; the acceptance bullet about device
   context is met as "none shown because none is read", and a later issue may add the WebSocket registry read.
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
- **Exact id, exact rules.** The chosen id goes to `LinkSeasonSync.run` unchanged; C4's shape check still runs; the
  fresh check after Save (#16, "on the fresh check a link asks for") is the validation the issue asks for before the
  binding takes effect, and a `NOT_FOUND`/`UNSUPPORTED_STATE` there draws P16-17/18 on the card as today.
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
    data class Listed(val entities: List<HaEntityCandidate>, val truncatedList: Boolean) : HaListOutcome
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
- Save: `link.run(assetId, id)` with the exact id; every refusal and the #78 question exactly as today.

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

## 5. Strings — PENDING, for the owner's gate (P105-1…10)

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
| P105-8 | `Home Assistant has no on/off helpers. Make one in Home Assistant, or enter an entity ID.` | the scope is empty |
| P105-9 | `No entity matches that.` | the query matches nothing |
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

## 8. Owner decisions (the gate)

- **Q1 — transport.** Recommended: **REST `GET /api/states` only**, no WebSocket; device grouping deferred to a later
  issue. Alternative: add a one-shot WebSocket read of `config/entity_registry/list_for_display` and
  `config/device_registry/list` for display names and device context — a second transport, its own auth handshake,
  its own failure map, and a new place a token travels; the planner does not recommend it for this cut.
- **Q2 — the list cap.** Recommended **8 MiB** for the one list call (a few thousand entities with attributes is
  low single-digit megabytes); the poll's 64 KiB stays. Beyond the cap the answer is MALFORMED (P16-19) and manual entry
  remains.
- **Q3 — scope.** Recommended: **`input_boolean` only** in this cut, with manual entry for anything else; no "All
  compatible on/off entities" filter, so no warning string is needed and nothing is made to look like a season helper.
  Alternative: a second scope `switch.`/`binary_sensor.` behind a warning line, as the issue allows.
- **Q4 — changing a linked asset's entity.** Core's `ChangeSeasonSyncEntity` (C17) has no phone surface today; #105's
  flow is the setup sheet. Recommended: **out of scope**; the owner unlinks (Disconnect or a later Stop/Link) as today.
- **Q5 — the strings** of §5.
- **Q6 — placement.** Recommended: the browser as a mode of the existing sheet (§1 point 2). Alternative: a pushed
  `Route`, which needs a result path between destinations the app does not have.

## 9. Acceptance map (#105's bullets → rows)

choose without knowing the id → 18; searchable by name, id shown → 6, 18; search matches the id → 6; default results
are `input_boolean` → 5; helpers without a device remain selectable → 1, 5 (no device read at all); device context →
none shown, by Q1; exact id into the #16 flow → 12; manual entry remains → 13, 18; no writes, #16's rules → 9, 17 and
§2; failed discovery changes nothing → 14; names are presentation only → 12 and §4; JVM/Android/fake-HA coverage → §7.

## 10. Proofs at the tip (controller)

CI's command; the connected classes `SeasonSyncScreensTest` first, then the whole suite on `emulator-5554`; R6 hygiene
greps; the real-HA proof as #16's §7, once: open the sheet against the owner's instance, see the helpers listed by name,
pick one, Save, the fresh check lands (no address, entity or token in any file). No upgrade paragraph: no schema or format
step. 1.8.0's release notes carry one paragraph for this.
