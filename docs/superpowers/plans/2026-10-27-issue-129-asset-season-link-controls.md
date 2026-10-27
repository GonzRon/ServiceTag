# #129 — an asset's season link can be changed, removed and driven over `/v1` and MCP: plan (rev 2, 2026-10-27)

> **Rev 2 (2026-10-27).** The independent plan review of rev 1
> (`.superpowers/sdd/2026-10-27-issue-129/plan-review-rev1.md`, APPROVE WITH CORRECTIONS) is folded: I1–I6, M1–M12,
> M14 and M15, each as the review corrects it. M13 is answered by the owner's D2 ruling (§10). **M12's choice:** the
> three Errors rows in `docs/api/v1.md` and their pin move to **K2** (§4). The owner's rulings of 2026-10-27 are in §10:
> the strings re-ratified verbatim (§5), D2 restated with the `appVersion` fact, and D3 and D4 accepted.
>
> **Rev 1 (2026-10-27).** #129 is ServiceTag **1.9.1**, a **PATCH**, version code **24** (owner, 2026-10-27). It has its
> own plan, shaped like #125's. The issue binds it:
> - on the phone, a linked season card offers **Change helper**, which runs the shipped `EditSeasonSyncEntity`;
> - it also offers **Remove link**, which deletes that asset's binding only;
> - `/v1/assets/{id}/season-sync` takes `LINK`, `CHANGE_ENTITY`, `STOP`, `RESUME`, `SET_MODE` and `UNLINK`, with the
>   phone's commands and refusals;
> - six matching MCP tools are added, none of which invents a confirmation;
> - ServiceTag still only reads the helpers.
>
> This plan **specifies** (`docs/superpowers/planning-policy.md`). The implementers author the code and the tests.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-27-issue-129/` (the controller's).
> **Branch:** `issue-129-season-link-controls`, worktree `../noteNFC-wt-129`.
> **Base:** `origin/master` = `22dc9537`, the `servicetag-v1.9.0` tag.
> **Four briefs:** K1 (wave 1), then K2 and K4 (wave 2, two lanes), then K3 (wave 3).
> **Paths:**

| short | path |
|---|---|
| `C/` | `core/src/main/kotlin/com/loosecannon/servicetag/core/` |
| `CT/` | `core/src/test/kotlin/com/loosecannon/servicetag/core/` |
| `A/` | `app/src/main/kotlin/com/loosecannon/servicetag/` |
| `T/` | `app/src/test/kotlin/com/loosecannon/servicetag/` |
| `AT/` | `app/src/androidTest/kotlin/com/loosecannon/servicetag/` |
| `M/` | `tools/servicetag-mcp/` |

Every `file:line` here was read on `22dc9537`.

**Goal.** In 1.9.0 an asset's #16 link could not be re-pointed or removed:
- Link answers `ALREADY_LINKED` while any binding exists (`C/seasonsync/LinkSeasonSync.kt:97`);
- `EditSeasonSyncEntity` (`C/seasonsync/SeasonSyncCommands.kt:115-144`) is wired at `A/di/AppGraph.kt:993`, but no
  screen or route calls it;
- the only removal is Disconnect (`ForgetHaConnection`, `:375-396`), which drops the connection, every link and the
  token;
- `/v1` and MCP only read the link (`A/api/ApiRouter.kt:304`, `get_season_sync`).

The production commissioning of 1.9.0 needed a "Change entity" it counted on as shipped. This plan closes that gap on
the phone, on `/v1` and on MCP. It adds no schema, no format and no Home Assistant write.

## Global constraints

1. **ServiceTag only reads these helpers.** No new code path reaches `HomeAssistantPublishClient`
   (`A/hapublish/HomeAssistantPublishClient.kt:104`, `SERVICES_PATH` `:277`) or any other writer of Home Assistant.
   No route or tool takes a season state: no body key carries `ON`/`OFF`, an observation, an applied action, a phase
   or a date. `SET_MODE`'s Force acts through the shipped applier exactly as the phone's control does (R16-15).
2. **A change of entity reads the new helper fresh and keeps the history.** This is `EditSeasonSyncEntity`, unchanged:
   - C4's id rule is checked;
   - C26's outbound refusal applies;
   - the old observation, error and fetch time are cleared;
   - the mode and the provenance (`appliedAction`, `appliedOn`, `appliedAt`, `lastAppliedSource`) are kept;
   - the revision moves, so a read of the old entity is dropped;
   - an enabled binding asks for one fresh read.

   No activation row is written or deleted by the change itself. The first read of the new helper decides as any
   read does (R16-3: dated the day it is applied).
3. **An unlink writes no season row.** `UnlinkSeasonSync` (L-C1) deletes one asset's binding and nothing else:
   - no activation, event, asset column or schedule;
   - no other binding, and no schedule binding;
   - not the connection, the token or any publishing row.

   The asset stays `MANUAL` at the phase its rows give it. With the binding gone the season guard
   (`C/seasonsync/SeasonSyncGuard.kt:19-27`) refuses nothing, so the owner's own Start, End and season-mode controls
   return.
4. **R16-14 narrowed.** "Disconnect stays the only delete" (#16 plan R16-14, line 427) becomes: Disconnect deletes the
   connection and every binding, and **Remove link deletes one**. The port and DAO docs that say "no per-binding
   delete" (`C/seasonsync/SeasonSyncPorts.kt:9-12`, `A/data/room/dao/SeasonSyncBindingDao.kt:9-12`) are amended in
   the same commit as the delete.
5. **R16-9 narrowed for the asset link** (it was narrowed for #113/#114 by O-21 and for schedule links by #125). Over
   `/v1` and MCP:
   - **travel:** link, change entity, stop, resume, Follow/Force and unlink;
   - **stay phone-only:** `SaveHaConnection` (the address, the eligibility, the Wi-Fi name and its capture, the
     cadence, the background setting), **the token**, Forget/Disconnect, the location asks, and **Sync now** (the
     issue lists no `SYNC_NOW`; every write that needs a read already launches one).

   No route returns the address, the Wi-Fi name or the token. This is the parity fence (k17, k22).
6. **No invented confirmation.** The API checks exactly the issue's two confirmations:
   - `confirmEntityId` equal to `entityId` for `LINK` and `CHANGE_ENTITY`;
   - the JSON `true` for `confirmUnlink` on `UNLINK`.

   `STOP`, `RESUME` and `SET_MODE` take none, as the phone asks none. No tool fills a confirmation, defaults one, or
   adds one the route does not check.
7. **Exhaustive `when`, never `else`,** over every closed set this plan touches:
   - the new `SeasonSyncAction`;
   - `SeasonSyncLinkRefusal`, in the new API message map;
   - `SyncMode`;
   - `SeasonSyncSheetPurpose` (gaining `CHANGE_ENTITY`), in the sheet and its model (the screen has no `when` over it:
     `openSyncSheet` and `LinkSeasonSyncSheet(purpose = …)` pass it through, M14);
   - `SeasonSyncBlockKind`.

   The exception map in `A/api/ApiJson.kt` gains `is` arms beside its shipped ones; its shipped fall-through for an
   unknown `Throwable` is not one of these closed sets.
8. **No schema or format change.** A DAO `@Query("DELETE …")` is not schema: `app/schemas/…/22.json` stays
   byte-identical, Room stays 22 and the backup format stays 21. Bindings never travel (`SeasonSyncNeverTravelsTest`
   untouched). §8 holds the classification and the R6 grep that proves it.
9. **Insets (the master plan's G28).** No new `Scaffold`, sheet or input dialog:
   - Change helper reuses `LinkSeasonSyncSheet` (group E in `InsetsContractTest`, unchanged);
   - the Remove link confirm is an `AlertDialog` with no typed input.
10. **Strings** are resources in all ten packs and ratified before K4 (§5). Only three are new.
11. **Standing rules:**
    - fictional names only: `example_heater`, `input_boolean.example_heater_in_season` →
      `input_boolean.example_heater_season`, `192.168.0.10`, `ha.example`;
    - one-line commits;
    - JVM first, with real counted REDs; a row labelled **pin / characterisation (no RED expected)** in §3 (k4,
      k10(c), k20) proves shipped behaviour and is written green, never with a manufactured RED (M7);
    - time boxes: 1 h target, 2 h hard.
12. **Shipped #16 tests stay as they are, but for two named pins** (k18, k22). Every other file under
    `CT/seasonsync`, `T/seasonsync` and `AT/seasonsync` is untouched: new behaviour gets new test files.
    `RecordSeasonSyncResult` and the runner change additively (L-C3), so their shipped tests pass unmodified.

## 1. Scope

| AC | the issue's acceptance (abridged) | contracts · rows |
|---|---|---|
| AC129-1 | Change helper on a linked season card opens the link sheet on the stored binding and runs `EditSeasonSyncEntity`; the new helper is read fresh and the history is kept | L-C2, L-C6, L-C7 · k4, k8, k24 |
| AC129-2 | Remove link asks first, deletes this asset's binding only; the asset keeps its manual season and history; the connection and the other links are untouched | L-C1, L-C4, L-C5, L-C6 · k1–k3, k7, k9–k11, k24 |
| AC129-3 | `/v1/assets/{id}/season-sync` takes `LINK`, `CHANGE_ENTITY`, `STOP`, `RESUME`, `SET_MODE`, `UNLINK`, with the phone's commands and refusals by name | L-C8 · k12–k20 |
| AC129-4 | the six MCP tools, none inventing a confirmation | L-C9 · k21–k23 |
| AC129-I | nothing writes Home Assistant; no route takes a season state; the connection and the token stay phone-only | constraints 1, 5 · k14, k17, k22, R6 |

**Out of scope:**
- `SYNC_NOW` over `/v1` (constraint 5);
- a schedule's own link changing its helper: #125's schedule link changes by unlink and link, and
  `LinkSeasonSyncViewModel` keeps refusing any purpose but `LINK` for a schedule target (`:176-178`);
- a confirm on Stop, Resume or a mode change;
- the stopped block naming its entity: the Change helper sheet shows it (L-C7);
- `ALREADY_LINKED`'s meaning, unchanged: Link is for an asset with no binding.

**Recorded limits:**
1. **A 1.9.0 phone has no write route.** It is at schema 22, like 1.9.1, so the MCP's schema gate cannot tell the two
   apart. A 1.9.0 phone answers the POST with the shipped asset sub-resource **404 `not_found`** and writes nothing
   (`ApiRouter.kt:255` falls to its `else`). The tools' docstrings say so (L-C9). `/v1/status` already reports
   `appVersion` (`A/api/ApiDtos.kt:48`), so a version gate would be a small comparison on the status read the pairing
   already makes; the owner accepts the plain 404 all the same (§10, D2).
2. **The provenance goes with the binding.** Remove link deletes the binding, and with it `lastApplied`. The activation
   rows the link recorded stay in the asset's history (`GET /v1/assets/{id}/season`'s `activations`).
3. **Relinking after Remove link starts a new binding at revision 1** (M10).
   - A relink to a **different** helper: an old read at the reused revision is dropped by L-C3's entity guard.
   - A relink to the **same** helper: the guard cannot tell the old binding from the new, so an old read at the reused
     revision is recorded on the new binding. This is **not closed, and benign**: it is a genuine reading of that
     helper, and the new binding's own read then runs at the next revision.

## 2. Contracts

- **L-C1, `UnlinkSeasonSync` (K1, core; one `uow.write`, no HTTP).** It goes in `C/seasonsync/SeasonSyncCommands.kt`,
  after `ResumeSeasonSync`.

  ```kotlin
  /** #129 — Remove link: this asset's binding deleted, nothing else written; null when it had none. */
  class UnlinkSeasonSync(
      private val bindings: SeasonSyncRepository,
      private val scheduler: SeasonSyncScheduler,
      private val uow: UnitOfWork,
  ) {
      suspend fun run(assetId: AssetId): SeasonSyncBinding? // the removed binding
  }
  ```

  - **The write:**
    1. read the binding; with none, return `null` and touch nothing, the scheduler included;
    2. `bindings.delete(assetId)`;
    3. read `bindings.anyEnabled()` in the same write.
  - **After the commit:** `true` → `scheduler.ensure()`, `false` → `scheduler.cancel()`. That is `StopSeasonSync`'s
    rule (`:157-172`). The runner's `cancel()` is C22's rule over **both** kinds when the schedule pass is wired
    (`A/seasonsync/SeasonSyncRunner.kt:251-257`, `reconcile` `:325-340`), so an enabled #125 schedule binding keeps
    the periodic work. Nothing else needs to change for that.
  - **What it does not do:**
    - no `NoSuchAsset`: the route checks the asset first (L-C8), and the phone draws the action only on a binding;
    - no refusal: the binding is device-local configuration, like `StopSeasonSync`. A held, archived or retired
      asset's binding is removable here; the phone's own gate is in L-C6;
    - no recompute: no season fact changed, so no due date can move.
  - **Idempotent:** a second run answers `null` and writes nothing.
- **L-C2, the port and its two implementations (K1).**
  - `SeasonSyncRepository` (`C/seasonsync/SeasonSyncPorts.kt:13-36`) gains `suspend fun delete(assetId: AssetId)`.
    A missing row is a no-op. It is abstract, with no default: the two implementors must answer it, and the
    delegating test wrappers (`by inner`) compile unchanged.
  - **`A/data/room/dao/SeasonSyncBindingDao.kt`** gains `DELETE FROM season_sync_binding WHERE asset_id = :assetId`.
    `RoomSeasonSyncRepository` (`A/data/room/SeasonSyncRepositories.kt:27-55`) maps it. Room's table flow makes
    `observeFor` emit `null` and `observeAll` emit the shorter list. Those flows are what the block, the publishing
    inputs (`A/hapublish/PublishInputs.kt:51`) and #116's marks (`A/ui/asset/AssetViewModels.kt:394`) already observe.
  - **`CT/testing/InMemorySeasonSync.kt`'s `InMemorySeasonSyncRepository`** deletes under the double's own rules: the
    delete is rolled back with its write (`Rollbackable`), and its `version` is bumped so both observers emit. The
    double's `TransactionWitness` counts only `all()` reads outside a snapshot (`InMemoryRepositories.kt:81-92`), so a
    delete is not witnessed (M8). The double's own `SeasonSyncDoubleTest` gains one case for that.
  - **`A/data/room/entities/SeasonSyncBindingEntity.kt:13`**'s R16-14 sentence ("the connection takes every binding on
    it") gains that Remove link deletes one (M4).
- **L-C3, the asset applier's entity guard (K1; additive, #125's I1 applied to #16).**
  - **The hazard:**
    1. link to A (revision 1; the fresh read of A is launched);
    2. Remove link;
    3. link to B (revision 1 again);
    4. A's answer lands at revision 1 and is recorded against B's binding.
  - **The fix:**
    - `RecordSeasonSyncResult.run` (`C/seasonsync/RecordSeasonSyncResult.kt:73-79`) gains a trailing
      `readEntityId: String? = null`;
    - a non-null id that is not the stored binding's `entityId` answers `Dropped` and writes nothing, after the
      revision check, exactly as `RecordScheduleSeasonSyncResult` does (`C/seasonsync/ScheduleSeason.kt:296-306`);
    - the runner's asset pass (`A/seasonsync/SeasonSyncRunner.kt:279`) passes `readEntityId = binding.entityId`.

    A null id keeps the revision-only check, so every shipped `RecordSeasonSyncResultTest` case and every caller
    compile and pass unchanged. The guard closes a relink to another helper only; a relink to the same helper stays
    open and benign (limit 3, M10).
- **L-C4, what the unlink does to #113/#114's date binding (K1; settled: unlinking is Stop's case, no new rule).**
  - **C9a's authority:** `haBoundaryOf` (`C/hapublish/BoundaryFreshness.kt:36-65`) lets Home Assistant decide a
    `MANUAL` asset's season end only with `inboundEnabled` and a season-ended helper on the date binding. With the
    binding deleted, `inboundEnabled` is false, exactly as after Stop. The boundary is `HaBoundary.NotUsed` and the
    phone's own END decides readiness.
  - **The date binding is not touched.** It keeps its stored `boundaryEntityId`, unread while Home Assistant does not
    decide. The runner's boundary step reads it "never when HA does not decide" (`A/hapublish/PublishRunner.kt:628`).
    A later link restores Home Assistant's authority with the stored helper.
  - **Later changes follow #114's rule.** After the unlink, `boundaryRequired` (`A/api/HaPublishingHandlers.kt:157-166`;
    `boundaryOf`, `C/hapublish/PublishCommands.kt:204-222`) is `false`, so a later Change target stores no
    season-ended helper. That is the shipped rule for any asset no longer driven by Home Assistant. It is documented
    and not altered.
  - **What may change in Home Assistant.** Whatever value the projector then wants is #113/#114's shipped
    reprojection, identical to the one after Stop (k11). #129 adds no publishing path.
  - **C26:** the unlinked entity is no longer inbound (`readsAsInbound`, `PublishCommands.kt:198-201`), so it may
    become a publish target, as after Disconnect.
  - **#116:** the Assets row's Home Assistant mark is derived from the binding list, so it goes with the binding.
- **L-C5, the graph (K1, the only graph edit).**
  - `A/di/AppGraph.kt` (beside `:995-997`) and `T/testing/FakeGraph.kt` (beside `:527-529`) gain
    `val unlinkSeasonSync = UnlinkSeasonSync(seasonSyncBindings, seasonSyncRunner, uow)`.
  - No other graph member changes. K2 and K4 read `graph.editSeasonSyncEntity`, `graph.unlinkSeasonSync`,
    `graph.seasonSyncBindings` and the shipped commands.
- **L-C6, the season card's block (K4).** `SeasonSyncBlockViewModel` (`A/ui/asset/SeasonSyncBlockViewModel.kt`):
  - **State.** `SeasonSyncBlockState` gains:
    - `offersChangeHelper` and `offersRemoveLink`: true on `ENABLED` and `STOPPED` exactly when the asset is
      `writable` (not held, `:243`), the gate Stop and Resume already use (`:256`, `:271`); false on `NONE`;
    - `removeAsk: String?`: P129-2 with the binding's entity while the confirm is asked, else null.
  - **Commands.**
    - `askRemoveLink()` sets `removeAsk` and writes nothing;
    - `dismissRemoveLink()` clears it and writes nothing;
    - `confirmRemoveLink()` clears it and runs `UnlinkSeasonSync.run(assetId)` through the shipped `command` (busy
      guard, one in flight, a store failure draws nothing: `:220-238`).

    The binding flow then emits `null`. The block becomes `NONE`, Link is offered again when a connection exists
    (`:245`), and `hidesStartAndEnd` turns false, so Start and End come back.
  - **Constructors.** The primary constructor gains a trailing `unlink: suspend (AssetId) -> Unit` whose default
    fails loudly (`error("…")`, the `NO_SCHEDULE_LINK` precedent at `LinkSeasonSyncViewModel.kt:390-393`). So
    `SeasonSyncBlockViewModelTest` compiles unchanged. The graph constructor (`:123-129`) passes
    `graph.unlinkSeasonSync::run`.
  - **`SeasonSyncBlock`** (`A/ui/asset/SeasonSyncBlock.kt:41-94`) gains `onChangeHelper: (() -> Unit)? = null`.
    - In the `ENABLED` row beside Sync now and Stop syncing, and in the `STOPPED` branch beside Resume, it draws
      **Change helper** (P129-1, when offered and the callback is non-null) and **Remove link** (P15-23, when
      offered).
    - While `removeAsk` is set it draws an `AlertDialog`: text P129-2, confirm P15-23, dismiss `common_cancel`.
      Back and the scrim dismiss, as the schedule row's confirm does (`A/ui/maintenance/ScheduleSeasonRow.kt:370-378`).
  - **`AssetDetailScreen`** (`:537-542`) passes `onChangeHelper = { openSyncSheet(SeasonSyncSheetPurpose.CHANGE_ENTITY) }`.
- **L-C7, the sheet's third purpose (K4).** `SeasonSyncSheetPurpose` (`A/ui/asset/LinkSeasonSyncViewModel.kt:41`)
  gains **`CHANGE_ENTITY`**. The sheet is the same `LinkSeasonSyncSheet`, keyed per opening (`:86-90`).
  - **The model.** `LinkSeasonSyncViewModel`'s primary constructor gains two trailing parameters:
    - `storedEntity: suspend (AssetId) -> String?`, which is `graph.seasonSyncBindings.get(it)?.entityId`;
    - `changeEntity: suspend (AssetId, String) -> Unit`, which is `graph.editSeasonSyncEntity.run(…)`.

    Their defaults fail loudly, so `LinkSeasonSyncViewModelTest` compiles unchanged through #16's secondary
    constructor (`:139-151`). The graph constructor (`:153-161`) wires both.
  - **`init`, for an asset target with `CHANGE_ENTITY`:**
    - read the stored entity once;
    - set `chosen = HaEntityCandidate(stored, friendlyName = null)` and `storedEntityId = stored`;
    - set the sentence to P129-3;
    - if there is no binding (it went with a Disconnect, or another device path), finish `CLOSED`, as the shipped
      `SeasonSyncNotLinked` arm does.

    A schedule target refuses `CHANGE_ENTITY` at construction, as it refuses `RESUME` (`:176-178`); its `save` arm is
    the exhaustive `when`'s, never reached.
  - **State.** `LinkSeasonSyncState` gains `storedEntityId: String?`. `canSave` for `CHANGE_ENTITY` needs a non-empty
    `entityToLink` **different from** `storedEntityId`. Choosing the same helper writes nothing, and the sheet holds
    Save.
  - **The form.** The entity row, the browser and manual entry are drawn for `LINK` **and** `CHANGE_ENTITY`
    (`LinkSeasonSyncSheet.kt:167`). With the stored id chosen, the row reads the id with **Change** (P105-2) beside it
    (`:243-245`), as after a pick.
  - **`save()` for `CHANGE_ENTITY`:** `changeEntity(assetId, entityToLink)`, then `Done(CLOSED)`. There is never a
    #78 prompt, since the asset's mode does not move. Refusals go through the shipped `refusedFor` (`:357-364`),
    exhaustive and unchanged:
    - `BAD_ENTITY_ID` → P16-49 under the entity;
    - `OUTBOUND_TARGET` → P113-43 under the entity;
    - `SeasonSyncNotLinked` → P16-10 when no connection is stored, else close (`:325-327`);
    - a store failure → nothing drawn, the sheet as typed (`:328-331`).

    `NOT_MAINTAINED_HERE`, `NO_CONNECTION`, `NEEDS_TOKEN` and `ALREADY_LINKED` keep their shipped arms. The edit never
    raises them.
  - **The screen.** `openSyncSheet` and `LinkSeasonSyncSheet(purpose = …)` (`AssetDetailScreen.kt:298-302`,
    `:418-429`) carry the new value; there is no `when` over the purpose there (M14). The `rememberSaveable` enum
    survives rotation as it does.
  - **The sentence is the mode's truth.** P129-3 says the link *reads* the helper from its next check. Under
    `FORCE_IN` or `FORCE_OUT` the forced season holds (P16-28/29), and on a stopped link nothing is read until Resume;
    the change keeps the stored mode (constraint 2), and the sheet shows the same sentence whatever the mode (I2).
- **L-C8, the wire: `POST /v1/assets/{id}/season-sync` (K2).**
  - **Routing.** `ApiRouter.kt:304` gains `"season-sync" to "POST" -> (handlers.seasonSyncWrites ?: throw
    ApiFailure.notFound(request.path)).act(rest[1], request)`. `ApiHandlers` (`:190`, `:211`) gains `internal val
    seasonSyncWrites: SeasonSyncHandlers? = null`, wired by its graph constructor. A suite that wires none answers 404,
    as #125's does. `ApiRouter.route`'s KDoc #16 paragraph (`:191-194`: "**No route writes season sync**: linking, the
    mode, Sync now, stopping, resuming and the connection itself are the phone's alone, so every other verb on the
    shape is a 404") is amended: since #129 `POST` writes the link; Sync now and the connection stay the phone's;
    `PATCH`, `PUT` and `DELETE` are a 404 (M4).
  - **Other verbs.** `PATCH`, `PUT` and `DELETE` on the shape stay **404**: the asset sub-resource convention
    (`:265-267`), unlike #125's schedule shape.
  - **`GET`** is unchanged (`A/api/SeasonHealthHandlers.kt:146-158`).
  - **The handler.** `A/api/SeasonSyncHandlers.kt` (new) mirrors `ScheduleSeasonHandlers`' shape
    (`A/api/ScheduleSeasonHandlers.kt:76-97`). It runs the graph's shipped commands and L-C1 and never touches the
    connection, the token or the runner, beyond what those commands already ask of the scheduler.
  - **The body: `SeasonSyncRequest`** (`A/api/SeasonSyncDtos.kt`), strict like every request (`ignoreUnknownKeys =
    false`), with exactly these keys, in this order:
    - `action: String`;
    - `entityId: String? = null`;
    - `confirmEntityId: String? = null`;
    - `mode: String? = null`;
    - `confirmUnlink: JsonElement? = null`, compared to `JsonPrimitive(true)`, as `ScheduleSeasonHandlers.kt:92`
      does.

    `internal enum class SeasonSyncAction { LINK, CHANGE_ENTITY, STOP, RESUME, SET_MODE, UNLINK }`.
  - **The order of checks, first failure wins:**
    1. the body's shape: 400 for not JSON, an unknown key, an unknown `action`, or a `mode` that is not a `SyncMode`
       name (`enumOr400`);
    2. the asset: `assets.get` or **404 `no_such_asset`**, before any key or confirmation check;
    3. the action's keys (the table below), in this order inside step 3 for **every** action (M2, #125's order at
       `ScheduleSeasonHandlers.kt:83-92`):
       1. the 422 for a confirmation the action does not take (`taken = true`);
       2. the 400s, for a key the action needs and lacks, or has and does not take;
       3. the action's own confirmation's 422.

       One departure from #125, stated so it is not copied: #125's `UNLINK` arm checks `entityId` (400) before the
       not-taken `confirmEntityId` (422); here the not-taken 422 comes first for `UNLINK` too, so one rule covers all
       six actions;
    4. the command, whose refusals write nothing.

    | action | needs | 400 when present | 422 `SEASON_SYNC_CONFIRMATION_REQUIRED` | runs |
    |---|---|---|---|---|
    | `LINK` | `entityId` (else 400) | `mode` | `confirmEntityId` ≠ `entityId` (field `confirmEntityId`); `confirmUnlink` present (taken by `LINK`) | `LinkSeasonSync.run` |
    | `CHANGE_ENTITY` | `entityId` (else 400) | `mode` | as `LINK` | `EditSeasonSyncEntity.run` |
    | `STOP` | — | `entityId`, `mode` | `confirmEntityId` or `confirmUnlink` present (taken by `STOP`) | `StopSeasonSync.run` |
    | `RESUME` | — | `entityId`, `mode` | as `STOP` | `ResumeSeasonSync.run` |
    | `SET_MODE` | `mode` (else 400) | `entityId` | as `STOP` | `SetSeasonSyncMode.run` |
    | `UNLINK` | — | `entityId`, `mode` | `confirmUnlink` ≠ JSON `true` (field `confirmUnlink`); `confirmEntityId` present (taken by `UNLINK`) | `UnlinkSeasonSync.run` |

  - **The post-write read.** No handler launches it. `LinkSeasonSync`, `EditSeasonSyncEntity` (when enabled),
    `ResumeSeasonSync` and `SetSeasonSyncMode` (to `FOLLOW`) already call `scheduler.requestFreshRead`. The runner
    launches that read on its own scope and returns (`SeasonSyncRunner.kt:259-261`), so no answer waits on Home
    Assistant.
  - **The answer is 200, `SeasonSyncActionResponse`:** `{assetId, connection, binding, switchedFrom}`.
    - The first three are `GET`'s, built the same way after the write. `SeasonSyncHandlers` **builds them again; it
      does not extract** a shared builder from `SeasonHealthHandlers.kt:146-158`, which stays untouched and outside
      K2 (M6).
    - `switchedFrom` is `"CALENDAR"`, `"YEAR_ROUND"` or `null`: the mode a `LINK` or `RESUME` switched the asset out of
      into `MANUAL` (`SeasonSyncLinked.switchedFrom`, `LinkSeasonSync.kt:59`). Every other action, and a link of a
      `MANUAL` asset, answers `null`.

      It is the wire's equivalent of the phone's P16-44/45 disclosure and #78's question, and it asks nothing
      (constraint 6).
    - After `UNLINK`, `binding` is `null`.
  - **The error codes, new and shipped:**

    | status | code | when | `problems` / field |
    |---|---|---|---|
    | 400 | shipped | body shape and the table's 400s | — |
    | 404 | `no_such_asset` (shipped) | no such asset | — |
    | 409 | **`SEASON_SYNC_REFUSED`** (new) | a `SeasonSyncLinkRefused` | `SeasonSyncLinkRefused(reason=…)`, the `SeasonSyncLinkRefusal` name |
    | 409 | **`SEASON_SYNC_NOT_LINKED`** (new) | `CHANGE_ENTITY`, `STOP`, `RESUME` or `SET_MODE` on an asset with no binding (`SeasonSyncNotLinked`, `LinkSeasonSync.kt:51-52`); message "this asset has no season link; link it first" (M5) | — |
    | 409 | `SEASON_MODE_STRANDS_POLICY` (shipped, `ApiJson.kt:522-526`) | `LINK`/`RESUME` whose switch into `MANUAL` strands a `PRE_SERVICE` schedule | the schedules |
    | 409 | `SEASON_START_STRANDED` (shipped) | any other refusal of `SetSeasonMode.setInTransaction` (`C/usecase/SetSeasonMode.kt:69-93`) on that switch | shipped |
    | 422 | **`SEASON_SYNC_CONFIRMATION_REQUIRED`** (new) | the table's 422s | `SeasonSyncConfirmationRequired(field=…)`, field |

    Which refusal each action can answer:
    - `LINK`: `NOT_MAINTAINED_HERE`, `NO_CONNECTION`, `NEEDS_TOKEN`, `BAD_ENTITY_ID`, `OUTBOUND_TARGET`,
      `ALREADY_LINKED` (with a stopped binding too);
    - `CHANGE_ENTITY`: `BAD_ENTITY_ID`, `OUTBOUND_TARGET`;
    - `RESUME`: `NOT_MAINTAINED_HERE`, `NO_CONNECTION`, `NEEDS_TOKEN`;
    - `STOP`, `SET_MODE`, `UNLINK`: none of `SeasonSyncLinkRefusal`.

    The developer sentence per reason (`seasonSyncRefusalMessage`, an exhaustive `when` beside
    `scheduleSeasonRefusalMessage`, `ApiJson.kt:780-791`):
    - `NOT_MAINTAINED_HERE`: "the asset is archived, retired or transferred out";
    - `NO_CONNECTION`: "no Home Assistant connection is saved on this phone";
    - `NEEDS_TOKEN`: "the Home Assistant token is not on this phone; enter it on the phone first";
    - `BAD_ENTITY_ID`: "entityId is not a Home Assistant entity id";
    - `ALREADY_LINKED`: "this asset already has a season link; change its entity, resume it or unlink it first";
    - `OUTBOUND_TARGET`: "ServiceTag writes that helper, so it cannot follow it".

    **The confirmation exception always carries its action (M1):** `SeasonSyncConfirmationRequired(field: String,
    action: SeasonSyncAction, taken: Boolean)`, where `taken` means "a confirmation this action does not take". Its
    message is chosen by `when (action)` alone, exhaustive, with **no `when (e.field)`**:
    - `LINK`, `CHANGE_ENTITY`: `taken` → "`<ACTION>` takes no `<field>`"; else "`<ACTION>` needs confirmEntityId, exactly
      entityId, the caller's own confirmation";
    - `UNLINK`: `taken` → "UNLINK takes no `<field>`"; else "UNLINK needs confirmUnlink: true, the caller's own
      confirmation";
    - `STOP`, `RESUME`, `SET_MODE`: "`<ACTION>` takes no `<field>`" (they have no confirmation of their own).

    `taken` is read with `if`, never an `else ->` arm. Copying #125's `scheduleSeasonConfirmationMessage`
    (`ApiJson.kt:794-800`, whose inner `when (e.field)` has an `else ->`) would fail R6's `else ->` grep.
  - **The shipped code `SEASON_SYNC_ENABLED`** (`ApiJson.kt:536-539`) and its message are unchanged. v1.md's
    paragraph on it (`:1900-1903`) adds that `STOP` is now also on `/v1`.
  - **`docs/api/command-shapes.json`** gains `"seasonSync": {"keys": ["action", "entityId", "confirmEntityId", "mode",
    "confirmUnlink"]}`, appended after `scheduleSeason`. `CommandShapesGoldenTest` (`:55-78`) pins it, and no key is a
    value key (`state`, `observedState`, `phase`, `occurredOn`, `date`, `value`).
  - **The three new codes' Errors rows and their pin are K2's (M12, the choice made).**
    - **The rows.** K2 adds three rows to `docs/api/v1.md`'s Errors table (beside `:2557`, `:2824`), each a line
      starting ``| 409 | `SEASON_SYNC_REFUSED` |``, ``| 409 | `SEASON_SYNC_NOT_LINKED` |`` and
      ``| 422 | `SEASON_SYNC_CONFIRMATION_REQUIRED` |``.
    - **The pin.** K2 adds `ERROR_CODES_1_9_1` beside `ERROR_CODES_1_9_0` (`CommandShapesGoldenTest.kt:20-25`), holding
      the three codes, and joins it to both loops: the named-in-doc loop and the own-Errors-row loop (`:285-294`).
    - **Why K2.** The codes and the golden test file are already K2's (k19), so the pin lands with the code it pins and
      K3 keeps its fence out of `app/**`. K2's only `v1.md` edit is those three rows. K3 writes the rest of `v1.md`
      after K2 and removes none of them.
- **L-C9, MCP (K3).**
  - **The block.** A "#129" block of six tools is appended to `TOOL_NAMES` after `unlink_schedule_season`
    (`M/src/servicetag_mcp/server.py:202-206`), in the issue's order:
    - `link_season_sync`;
    - `change_season_sync_entity`;
    - `stop_season_sync`;
    - `resume_season_sync`;
    - `set_season_sync_mode`;
    - `unlink_season_sync`.

    The total moves **104 → 110**, and the comment at `:201-202` says so. Each tool is defined after the "#125" block
    (`:4960-5047`) and before `_forbid_unknown_arguments` (`:5056`; its `expected_count=len(TOOL_NAMES)` at `:5145`
    follows by itself).
  - **Signatures and bodies.** Each body is built from `command_shapes.SEASON_SYNC_KEYS` (the vendored golden keys,
    `M/src/servicetag_mcp/command_shapes.py:139`'s pattern), so a tool can send no key the phone does not take.

    | tool | signature | sends `POST /v1/assets/{id}/season-sync` |
    |---|---|---|
    | `link_season_sync` | `(asset_id: str, entity_id: str, confirm_entity_id: str \| None = None)` | `{action: "LINK", entityId, confirmEntityId}` only when `confirm_entity_id == entity_id`, else `ToolError`, nothing sent |
    | `change_season_sync_entity` | same | `{action: "CHANGE_ENTITY", entityId, confirmEntityId}`, the same rule |
    | `stop_season_sync` | `(asset_id: str)` | `{action: "STOP"}` |
    | `resume_season_sync` | `(asset_id: str)` | `{action: "RESUME"}` |
    | `set_season_sync_mode` | `(asset_id: str, mode: str)` | `{action: "SET_MODE", mode}`: passed as given; the phone answers 400 for a name that is not `FOLLOW`, `FORCE_IN` or `FORCE_OUT` (the shipped `set_season_mode` pattern, `:2635-2667`) |
    | `unlink_season_sync` | `(asset_id: str, confirm_unlink: Annotated[bool, Strict()])` | `{action: "UNLINK", confirmUnlink: true}` only when `confirm_unlink is True`, never defaulted, required in the schema |

  - **The order inside a tool, as #125's:**
    1. the path id (`_path_id`);
    2. the local confirmation check, where the tool has one;
    3. `_require_season_link_schema(tool)`;
    4. `_call("POST", …, json_body=…, content_type="application/json", retry_on_connect=False)`.

    No write is re-sent on a dropped connection, and each docstring says to read `get_season_sync` before running it
    again.
  - **The gate.**
    - `_MIN_SEASON_LINK_SCHEMA_VERSION = 22`, whose docstring says why: the lowest schema whose app can carry the
      routes; a 1.9.0 phone is also at 22 and answers 404 `not_found` with nothing written (limit 1). No `appVersion`
      comparison is added (§10, D2);
    - `_SEASON_LINK_FEATURE = "an asset's Home Assistant season link"`;
    - `def _require_season_link_schema(tool: str) -> None`, a one-line call of `_require_tool_schema` beside
      `_require_schedule_season_schema` (`:446-448`).

    A phone below 22 is refused `APP_SCHEMA_TOO_OLD` naming the feature, with nothing sent but the pairing's one
    `/v1/status` read.
  - **The docstrings** follow #125's (`:4996-5047`). Each states, in this order:
    1. what it does on the phone;
    2. the exact body;
    3. **the confirmation, if any, is the caller's own and never filled** (link, change, unlink);
    4. the answer, `get_season_sync`'s shape plus `switchedFrom`;
    5. every refusal by code and reason: L-C8's per-action `SEASON_SYNC_REFUSED` reasons, and also (M15)
       `SEASON_MODE_STRANDS_POLICY` and `SEASON_START_STRANDED` for `link_season_sync` and `resume_season_sync`, and
       `SEASON_SYNC_NOT_LINKED` for `change_season_sync_entity`, `stop_season_sync`, `resume_season_sync` and
       `set_season_sync_mode`;
    6. the 1.9.0 404 (limit 1);
    7. "never re-sent on a dropped connection";
    8. the schema-22 line.

    `link_season_sync` and `resume_season_sync` also say that a `CALENDAR` or `YEAR_ROUND` asset is switched into
    `MANUAL` at today's season and that `switchedFrom` reports it. `change_season_sync_entity` says the new helper is
    read at once and the season history is kept. `unlink_season_sync` says the asset keeps its season and its history
    and that the connection and other links stay. A fragment, to pin the tone:

    ```python
    """Point one asset's Home Assistant season link at another helper: `POST /v1/assets/{id}/season-sync` with
    exactly `{action: "CHANGE_ENTITY", entityId, confirmEntityId}` → `get_season_sync`'s shape plus `switchedFrom`
    (always `null` here). The old helper's reading and error are cleared, the season history is kept, and the
    phone reads the new helper at once when the link is not stopped.

    **`confirm_entity_id` must be the caller's own, exactly `entity_id`, given once the owner has confirmed that
    helper.** Without it, or with another id, this tool sends nothing; it never fills it.
    """
    ```

  - **`get_season_sync`'s docstring** (`:4513-4542`) **keeps "read only"**: the tool still only reads (I3). It loses
    only the sentence that "Linking, the follow and force modes, Sync now, stopping, resuming … are the phone's alone",
    and names the six tools, with Sync now, the connection, the token and Forget as what stays phone-only. The block
    comment at `:4505-4509` is amended the same way, keeping its "read-only route". The shipped pin
    `M/tests/test_season_sync_tools.py:166-170` (the words `read only`, at `:169`) stays green, unedited.
  - **The "#125" block comment** (`:4960-4965`: "the asset's link, Force, the token, the connection and Forget stay the
    phone's alone") becomes: the token, the connection and Forget stay the phone's alone, and an asset's link travels
    through the "#129" block (M4).
  - **README** (`M/README.md`):
    - `:124` → "One hundred and ten:";
    - the six names in the list;
    - a gate bullet beside `:79-80`;
    - a "#129" paragraph beside `:409-416`;
    - `:344` ("Linking, the modes, Sync now, stopping and the connection are the phone's alone") → only Sync now and
      the connection stay the phone's; linking, the modes, stopping, resuming and unlinking are the "#129" tools (M4);
    - `:417` ("… own link, the token, the connection and Forget stay the phone's alone") → the asset's link now travels;
      the token, the connection and Forget stay (M4).

## 3. Test matrix

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| k1 | JVM | `CT/seasonsync/UnlinkSeasonSyncTest` (new, K1) · two linked assets and a schedule binding: unlinking one deletes its binding only; the other binding, the schedule binding, the connection, the asset row, its `asset_season_activation` rows and every event are unchanged (whole-double snapshot); the asset stays `MANUAL` at its phase; `SeasonSyncGuard.isSynced` is false and a guarded `RecordSeasonActivation` START then succeeds | L-C1, constraint 3 | a delete by connection (both assets unlinked), or an END written "to close the link" |
| k2 | JVM | same · the scheduler: the last enabled binding removed → one `cancel()`; another enabled → one `ensure()`; a stopped binding removed → the rule by `anyEnabled`; no binding → `null`, no write, no scheduler call | L-C1 | `ensure` always (work left for nothing), or a call on the no-op |
| k3 | JVM | same · a failing write (the double's rollback) leaves the binding, and no scheduler call is made | L-C1 | the scheduler called inside the write |
| k4 | JVM | `CT/seasonsync/ChangeEntityHistoryTest` (new, K1; the owner's case, fictional; **pin / characterisation (no RED expected)**: `EditSeasonSyncEntity` is shipped, M7) · `example_heater` linked to `input_boolean.example_heater_in_season`, START applied 10-20 (row 1, provenance `HOME_ASSISTANT`); change to `input_boolean.example_heater_season` → observation, error and fetch time cleared, provenance kept, revision +1, one `requestFreshRead`; a result for the old entity at the old revision → `Dropped`; the new helper answers `off` on 10-27 → one END dated 10-27, row 1 kept; a stopped binding's change asks no read | constraint 2 | the change writing a row, or the stale read applied |
| k5 | JVM | `CT/seasonsync/RecordSeasonSyncEntityGuardTest` (new, K1) · `readEntityId` naming another entity than the stored binding at the same revision → `Dropped`, nothing written; the stored entity → recorded; `null` → the revision-only check; `RecordSeasonSyncResultTest` green unmodified (pin) | L-C3 | the guard missing (an answer about A recorded on B) |
| k6 | JVM | `CT/testing/SeasonSyncDoubleTest` (+1, K1) · the double's delete is rolled back with its write, and makes `observeFor` emit `null` and `observeAll` the shorter list | L-C2 | a delete that does not bump `version` (the block never redraws) |
| k7 | JVM | `T/ui/asset/SeasonSyncBlockControlsTest` (new, K4) · `ENABLED` and `STOPPED` offer Change helper and Remove link when writable; an **archived** asset and a **retired** one, each with an `ENABLED` and with a `STOPPED` binding, offer both (`writable`, not `here`: I5, since `maintainedHere` is `isInService && id !in held`, `Asset.kt:60`); a held asset offers neither; `NONE` neither; `askRemoveLink` → `removeAsk` = P129-2 with the entity; dismiss writes nothing; confirm runs the unlink once (a double tap one command); the state then is `NONE`, `offersLink`, `hidesStartAndEnd` false | L-C6 | the offers gated on `here` (an archived asset's link stuck) or the confirm skipped |
| k8 | JVM | `T/ui/asset/ChangeEntitySheetTest` (new, K4) · `CHANGE_ENTITY` opens on the stored id (`chosen` with no friendly name) with P129-3; Save held while empty or equal to the stored id; a pick or a typed id enables it; Save calls the change with the picked or trimmed id and closes; `BAD_ENTITY_ID` → P16-49 under the entity; `OUTBOUND_TARGET` → P113-43 under it; not linked + no connection → P16-10; not linked + connection → closes; no #78 prompt on any path; a schedule target refuses `CHANGE_ENTITY` at construction; a `FORCE_IN` binding's sheet shows the same P129-3 and Save keeps `mode = FORCE_IN` (I2) | L-C7 | Save calling `link.run` (`ALREADY_LINKED`, the 1.9.0 dead end) |
| k9 | JVM | `T/data/room/SeasonSyncUnlinkRoomTest` (new, K1) · the DAO's delete removes one asset's row; the other rows, `ha_connection`, `asset_season_activation` and every `schedule_season_*` row stay; both flows emit; a missing row deletes nothing; `app/schemas/…/22.json` unchanged (R6) | L-C2, constraint 8 | a delete without its `WHERE` |
| k10 | JVM | `T/seasonsync/SeasonUnlinkRunnerTest` (new, K1; the runner over `FakeGraph`) · (a) unlinking the last enabled binding cancels the work; (b) with an enabled #125 schedule binding the work stays enqueued; (c) a read in flight when the binding is removed records nothing (**pin / characterisation (no RED expected)**: `RecordSeasonSyncResult` already drops a missing binding, M7); (d) link A, A's read held, unlink, link B, A's answer released → dropped, B's status untouched, no row; every shipped `T/seasonsync` test green unmodified (pin) | L-C1, L-C3 | the runner not passing `readEntityId` (d), or a plain cancel ignoring schedule bindings (b) |
| k11 | JVM | `T/hapublish/SeasonUnlinkBoundaryTest` (new, K1; Room `FakeGraph`) · a `MANUAL` asset with an enabled #16 binding and a date binding naming `input_datetime.example_heater_season_ended`; after `UnlinkSeasonSync`: `haBoundaryOf` is `NotUsed`, readiness reads the phone's END, the date binding row is byte-identical (its `boundaryEntityId` kept), and the projector's desired value equals a twin graph's after `StopSeasonSync`; linking again restores Home Assistant's authority with the stored helper; #116's row facts drop the season-sync mark | L-C4 | the unlink clearing `boundaryEntityId`, or a delete the publishing inputs never observe |
| k12 | contract | `T/api/SeasonSyncWriteRoutesTest` (new, K2) · each action through the graph, answer 200 in L-C8's shape: `LINK` on a `CALENDAR` asset (`switchedFrom: "CALENDAR"`, binding `ACTIVE`, the switch row dated today); `CHANGE_ENTITY`; `STOP`; `RESUME` on `MANUAL` (`null`) and on `YEAR_ROUND` (`"YEAR_ROUND"`); `SET_MODE` `FORCE_IN` out of season (one START, `lastApplied.source` `FORCED_IN`) and back to `FOLLOW` (no row); `UNLINK` (`binding: null`), then `UNLINK` again (200, nothing written); on a **held** asset, `UNLINK` and `CHANGE_ENTITY` answer 200 (D4's non-refusal, M9) | L-C8 | an action wired to the wrong command, or a held-asset guard added on the wire |
| k13 | contract | same · the confirmations: `LINK`/`CHANGE_ENTITY` without `confirmEntityId` or with another id → 422 field `confirmEntityId`; `UNLINK` without `confirmUnlink`, with `"true"` or `1` → 422 field `confirmUnlink`; a confirmation an action does not take → 422 naming the action; the combined fault (M2): `LINK` with `confirmUnlink` present **and** a different `confirmEntityId` → 422 field `confirmUnlink` (the not-taken one wins); each writes nothing (table snapshot) | constraint 6 | the handler defaulting a confirmation |
| k14 | contract | same · the shape: not JSON, an unknown key (`token`, `baseUrl`, `homeNetworkSsid`, `state`, `observedState`, `occurredOn`), an unknown `action` or `mode` → 400; `entityId` or `mode` where the table says 400; `SET_MODE` without `mode`, `LINK` without `entityId` → 400; the combined fault (M2): `LINK` without `entityId` and with a `confirmEntityId` → 400, not the 422 (step 3.2 before 3.3); an unknown asset → 404 `no_such_asset` before any 422; nothing written | constraints 1, 5 | a lenient decoder (a season state accepted) |
| k15 | contract | same · every refusal L-C8 lists, by name, as 409 `SEASON_SYNC_REFUSED` with `SeasonSyncLinkRefused(reason=…)`, `ALREADY_LINKED` on a stopped binding included; `CHANGE_ENTITY`/`STOP`/`RESUME`/`SET_MODE` on an unlinked asset → 409 `SEASON_SYNC_NOT_LINKED` with the message "this asset has no season link; link it first" (M5); `LINK` on a `CALENDAR` asset with a `PRE_SERVICE` schedule and no break → 409 `SEASON_MODE_STRANDS_POLICY`; each writes nothing | L-C8 | `SeasonSyncNotLinked` falling to the 500 |
| k16 | contract | same · no answer body (k12–k15's) contains `192.168.0.10`, `ha.example`, the Wi-Fi name fixture or the token fixture (byte scan); each 200 answer without `switchedFrom` equals an immediate `GET`, race-free (I6): for the four actions that launch the runner's read (`LINK`, `RESUME`, `CHANGE_ENTITY` on an enabled binding, `SET_MODE` to `FOLLOW`), the reader is held on a gate (`haStateReader.answer = { gate.await(); … }`), the POST answer is compared with an immediate `GET`, then the gate is released and `awaitSeasonSyncReads()` (`FakeGraph.kt:503`) runs before `close`; for `STOP`, `UNLINK` and `SET_MODE` to a Force mode no read is launched | constraint 5 | a DTO carrying the connection row |
| k17 | contract | same · the parity fence: `POST`, `PUT` and `DELETE` on `/v1/ha-connection` and `/v1/ha-connection/token` are no route (404); `SeasonSyncRequest`'s descriptor is exactly L-C8's five keys | constraint 5 | a connection or token route slipped in beside the link |
| k18 | contract | `T/api/SeasonSyncRoutesTest` (**the one #16 pin amended**, K2) · `postIs404NotA405` (`:232-244`) becomes `anotherVerbIs404NotA405` over `PATCH` and `DELETE`; `POST` now routes (k12) | L-C8 | none: a pin |
| k19 | contract | `T/api/CommandShapesGoldenTest` (+2, K2) · `seasonSync` appended, equal to `SeasonSyncRequest`'s descriptor, no value key; `ERROR_CODES_1_9_1` (the three new codes) each named in `docs/api/v1.md` and each with its own Errors row (M12) | L-C8 | a request key outside the golden file, or a code without its Errors row |
| k20 | contract | `T/api/ApiReadsWriteNothingTest` (+1, K2; **pin / characterisation (no RED expected)**: the GET is shipped, M7) · `GET /v1/assets/{id}/season-sync`, absent from its list of reads (which ends at `:270`), writes nothing | constraint 3 | a read that recomputes |
| k21 | contract | `M/tests/test_season_link_tools.py` (new, K3) · the six close `TOOL_NAMES` in order after `unlink_schedule_season`; signatures and schemas (`confirm_entity_id` defaults to `None` and is never filled; `confirm_unlink` strict, required, no default); each body exactly its golden keys; nothing sent when a confirmation is absent, different or not `True`; schema 21 refused `APP_SCHEMA_TOO_OLD` naming the feature with only `/v1/status` sent; `_MIN_SEASON_LINK_SCHEMA_VERSION == 22` asserted last; `retry_on_connect=False` on each; answers as sent; 409 and 422 surfaced by code | L-C9 | a tool copying `entity_id` into the confirmation |
| k22 | contract | the MCP pins (K3) · the count 104 → **110** and the slice offsets at every site of §4 K3; the two "sync" pins (`test_season_sync_tools.py:92-96`, `test_schedule_season_tools.py:113-117`) amended to the seven-name set `{get_season_sync} ∪` the six; no tool name carries `home_assistant`, `connection`, `token`, `forget` or `disconnect`; no tool sends a non-`GET` to a `/v1/ha-connection` path (source scan) | constraint 5 | a connection or token tool |
| k23 | contract | `M/tests/test_command_shapes.py` (+1, K3) · the vendored set (`:53-62`) gains `seasonSync`, equal to the golden file | L-C8, L-C9 | the vendored copy behind the golden file |
| k24 | Compose | `AT/seasonsync/SeasonLinkControlsScreensTest` (new, K4, six cases; **the UI proof**) · (1) an enabled block draws Change helper and Remove link beside Sync now and Stop syncing, and a stopped one beside Resume; (2) Change helper opens the sheet fully expanded on the stored id with P129-3, Save disabled until another helper is typed, then saving changes the graph's binding and closes; (3) an outbound helper keeps the sheet open with P113-43 and writes nothing; (4) Remove link asks P129-2, Cancel writes nothing, Remove link deletes the binding, and the card then offers Link to Home Assistant and Start season again; (5) a held asset draws neither action; (6) an archived asset and a retired one, with an `ENABLED` and with a `STOPPED` binding, draw Change helper and Remove link (`writable`, not `here`: I5) | L-C6, L-C7 | a state with no drawn control, or the offers gated on `here` |
| k25 | JVM | `InsetsContractTest`, `LocalizationCoverageTest`, `UiLiteralGuardTest` (pins; K4's strings in all ten packs) | constraints 9, 10 | none: pins |

**Hazards:**
- Z1: a removal wider than one asset's binding (k1, k9);
- Z2: an old helper's answer applied after a change or a relink (k4, k5, k10d);
- Z3: periodic work left or lost after an unlink (k2, k10a, k10b);
- Z4: the date binding's boundary silently cleared, or not reprojected (k11);
- Z5: a confirmation filled or defaulted on the wire or in a tool (k13, k21);
- Z6: the connection, the token or a season state reachable over `/v1` or MCP (k14, k17, k22).

## 4. Files, fences, order

| brief | wave · lane | touches | never touches |
|---|---|---|---|
| **K1** core, data, runner guard, graph | 1 · L1 | `C/seasonsync/SeasonSyncPorts.kt` (`delete`, the R16-14 doc); `C/seasonsync/SeasonSyncCommands.kt` (`UnlinkSeasonSync`, appended); `C/seasonsync/RecordSeasonSyncResult.kt` (`readEntityId`); `CT/testing/InMemorySeasonSync.kt`, `CT/testing/SeasonSyncDoubleTest.kt` (+1); `A/data/room/dao/SeasonSyncBindingDao.kt`, `A/data/room/SeasonSyncRepositories.kt`, `A/data/room/entities/SeasonSyncBindingEntity.kt` (`:13`'s doc only, M4); `A/seasonsync/SeasonSyncRunner.kt` (`:279` only); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (`unlinkSeasonSync`, **the only graph edit**); the new tests of rows k1–k6, k9–k11; ~55 min | `A/api`, `A/ui`, `res/`, `M/`, `docs/`, `app/schemas`, every shipped file under `CT/seasonsync` and `T/seasonsync`, `C/hapublish`, `A/hapublish` (k11 only reads them) |
| **K2** the wire | 2 · L1 | `A/api/{ApiRouter,ApiHandlers,ApiJson,SeasonSyncDtos}.kt` (`ApiRouter.route`'s KDoc #16 paragraph included, M4), `A/api/SeasonSyncHandlers.kt` (new); `docs/api/command-shapes.json`; `docs/api/v1.md`, **the three new Errors rows only** (M12); `T/api/SeasonSyncWriteRoutesTest.kt` (new), `T/api/SeasonSyncRoutesTest.kt` (k18 only), `T/api/CommandShapesGoldenTest.kt`, `T/api/ApiReadsWriteNothingTest.kt`, and the fixture that wires `ApiHandlers` over a `FakeGraph` (`T/api/MaintenanceFixtures.kt`'s `scheduleSeasonHandlersFor` pattern, `:107-110`) — rows k12–k20; ~55 min | `C/**`, `A/ui`, `A/di`, `FakeGraph`, `A/seasonsync`, `M/`, `A/api/SeasonHealthHandlers.kt` (M6), every line of `docs/api/v1.md` but the three Errors rows |
| **K4** the phone | 2 · L2 (beside K2) | `A/ui/asset/{SeasonSyncBlock,SeasonSyncBlockViewModel,LinkSeasonSyncSheet,LinkSeasonSyncViewModel,SeasonSyncStrings,AssetDetailScreen}.kt`; `res/values*/strings_asset_edit.xml` ×10 (P129-1…3, ratified first); `T/ui/asset/{SeasonSyncBlockControlsTest,ChangeEntitySheetTest}.kt`, `AT/seasonsync/SeasonLinkControlsScreensTest.kt` (new) — rows k7, k8, k24, k25; ~55 min | `C/**`, `A/api`, `A/di`, `FakeGraph`, `A/seasonsync`, `A/ui/maintenance`, `InsetsContractTest.kt`, `M/`, `docs/`, the shipped `SeasonSyncBlockViewModelTest`, `LinkSeasonSyncViewModelTest`, `SeasonSyncScreensTest` |
| **K3** MCP and docs | 3 · L1 (after K2; beside K4's tail if it runs on) | `M/src/servicetag_mcp/{server,command_shapes}.py`; `M/README.md`; `M/tests/test_season_link_tools.py` (new); the pin sites below; `docs/api/v1.md`; `docs/home-assistant-season-sync.md`; `docs/capabilities.md` — rows k21–k23; ~50 min | `app/**`, `core/**`, `docs/api/command-shapes.json` and `v1.md`'s three new Errors rows (K2's), `docs/versioning.md`, `docs/releases/` (the release task's) |

**K3's pin sites** (104 → 110, slice offsets +6; each read on `22dc9537`):

| file | lines |
|---|---|
| `M/tests/test_argument_guard.py` | `:8`, `:46`, `:51`, `:182`, `:203-204`, `:207`, `:257-258`, `:326`, `:341-342` |
| `M/tests/test_tools.py` | `EXPECTED_TOOLS` +6, `:156-162`, `:1035-1039` ("One hundred and ten:") |
| `M/tests/test_ha_publishing_tools.py` | `:43`; `:446-448`: `[-10]`→`[-16]`, `[-11]`→`[-17]`, `[-14:-3]`→`[-20:-9]`; `:450` |
| `M/tests/test_ha_publishing_write_tools.py` | `:480` `names[-7:-3]`→`[-13:-9]` (M3); `:481` |
| `M/tests/test_installed_component_tools.py` | `:48`; `:139` offset 15 → 21 |
| `M/tests/test_supply_tools.py` | `:50`; `:132` offset 20 → 26 |
| `M/tests/test_maintenance_tools.py` | `:48` |
| `M/tests/test_reference_tools.py` | `:38`, `:68-69` |
| `M/tests/test_season_sync_tools.py` | `:31`; `:89` offset 14 → 20; `:92-96` (k22) |
| `M/tests/test_schedule_season_tools.py` | `:28`; `:94` `[-3:]`→`[-9:-6]`; `:96-97` (M3); `:113-117` (k22) |
| `M/src/servicetag_mcp/server.py` | `:201-202` |
| `M/README.md` | `:124` |

**K3's docs:**
- **`docs/api/v1.md`** (everything but the three Errors rows, which K2 wrote and K3 keeps):
  - the main routes table gains **only** the asset `POST` row beside `:290-291`; it has no asset `GET` row today and
    gains none (I4);
  - "Home Assistant season sync (#16)" (`:1851-1906`): the section now says the link is read here and written since
    #129; the asset `POST` row is added as a line starting ``| `POST` |``; the verb line ("`PATCH`, `PUT` and `DELETE`
    are a 404"); the `SEASON_SYNC_ENABLED` paragraph (`:1900-1903`) adds that `STOP` is also on `/v1`;
  - a new subsection "An asset's season link over `/v1` (#129)" with L-C8's body, keys and errors tables and the
    `switchedFrom` key;
  - the phone-only paragraph (`:2282-2299`): since #129 the link, change, stop, resume, Follow/Force and unlink
    travel; Sync now, the connection, the token and Disconnect stay the phone's.
- **The shipped #16 doc pin, kept green (I4).** `T/api/SeasonSyncRoutesTest.kt:333-343`
  (`theDocumentNamesTheRouteAndTheCode`) reads `v1.md` and needs, unchanged:
  - exactly one line starting ``| `GET` | `/v1/assets/{id}/season-sync` |`` — add no second asset `GET` row anywhere;
  - exactly one `### Home Assistant season sync (#16)` heading;
  - exactly one line starting ``| 409 | `SEASON_SYNC_ENABLED` |`` — add no second row;
  - the literal **"no address, no token, no write"** inside the section, which runs to the next `## ` and so contains
    the new #129 subsection. Keep it in the GET paragraph, where it stays true of the GET.

  K3 cannot amend that test (`app/**` is outside its fence; constraint 12 allows only k18's pin to change), so it
  writes around it.
- **`docs/home-assistant-season-sync.md`:**
  - Disconnect (`:149-153`) and "On the phone: linking an Asset" through `:193`: Change helper and Remove link on the
    card; Remove link vs Disconnect; the API and MCP note;
  - `:632-636`, the phone-only list that names "an Asset's season link with Follow, Force, Sync now, Stop and Resume
    syncing": since #129 only Sync now stays phone-only among them (M4);
  - `:658`, the limit "The last change is kept only on the link … Disconnect …": Remove link also deletes the last-change
    record, and the season history keeps the rows (M4).
- **`docs/capabilities.md`:**
  - `:167`: the season link is now driven over the API, and the token, the connection, Disconnect and Sync now stay
    on the phone;
  - the "The phone supports:" list (`:55-63`) gains Change helper and Remove link.

**Dependencies:**
- K2 and K4 need K1's port, command and graph member.
- K3 needs K2's codes, `command-shapes.json` and the three Errors rows (it vendors the golden keys, and v1.md documents
  the codes as built).

**Between-brief gaps:**
- K1 → K2/K4: unlink and the guard exist, but nothing calls them.
- K2 → K3: `/v1` writes the link; MCP and the docs still say read only.
- K4 lands on its own lane: the phone draws the controls whatever K2 and K3's state.

**The release task** (the controller's, after the gate, as 1.9.0's "the numbers and the notes"):
- `app/build.gradle.kts:54-55` → `versionCode = 24`, `versionName = "1.9.1"`;
- `docs/versioning.md` gains the 1.9.1 row, **after the 1.9.0 row** (M11). Its text names:
  - PATCH and #129: "an asset's Home Assistant season link can be changed and removed on the phone, and linked,
    changed, stopped, resumed, forced and unlinked over the Developer API and MCP";
  - Room schema **22** and backup format **21**, unchanged;
  - `docs/api/v1.md`;
  - `docs/releases/1.9.1.md`;
  - the tag `servicetag-v1.9.1`;
- `docs/releases/1.9.1.md`;
- `T/VersionAgreementTest.kt` gains **`versioningRecords191`**, on the per-release precedent of `versioningRecords190`
  (`:548-584`), pinning that row (M11).

`VersionAgreementTest` (R5) pins the numbers, the row and the note.

## 5. Strings

**RATIFIED 2026-10-27 by the owner, verbatim** (§10). Home: `strings_asset_edit.xml`, beside the `season_sync_*`
entries; translations follow in all nine other packs.

| id | ratified wording | resource | where |
|---|---|---|---|
| P129-1 | "Change helper" (unchanged from rev 1) | `season_sync_change_helper` | the block's action, on an enabled or a stopped link (L-C6) |
| P129-2 | "Remove the link to %1$s? This asset stops following it and keeps its current season and history. Your Home Assistant connection and other links stay." | `season_sync_remove_link_confirm` | the Remove link confirm; `%1$s` is the entity id (L-C6) |
| P129-3 | "The link reads the helper you choose from its next check. This asset's season history stays as it is." | `season_sync_change_helper_sentence` | the sheet's sentence for `CHANGE_ENTITY`, over Save, whatever the link's mode (L-C7) |

**Why rev 1's P129-2 and P129-3 were replaced.**
- **P129-2 (I1).** Rev 1 said "nothing is changed in Home Assistant". L-C4 shows the unlink can let #113/#114's
  projector re-project: a `MANUAL` asset's boundary becomes `NotUsed`, so readiness and a published Season Start date
  may change. The ratified wording is P16-9's own phrasing, with `%1$s` as `schedule_season_use_asset_confirm` and
  P16-27…29 have it.
- **P129-3 (I2).** Rev 1 said "this asset's season follows the helper you choose". That is false under `FORCE_IN` or
  `FORCE_OUT`, where the forced season holds and the helper is only checked (P16-28/29), and on a stopped link, where
  nothing is checked until Resume. The ratified wording says the link *reads* the helper (k8's `FORCE_IN` case).

**Why P129-1 is new, not P105-2 "Change".** The block's `STOPPED` branch names no helper (`SeasonSyncBlock.kt:82-90`):
a bare "Change" beside Resume would not say what changes. P105-2 is reused **inside** the sheet, beside the stored
entity (L-C7).

**Reused, ratified (the owner's reuse of P15-23 confirmed 2026-10-27, §10):**
- P15-23 "Remove link" (`supplies_remove_link`, `REMOVE_LINK` in `A/ui/supplies/SupplyStrings.kt:87`): the action and
  the confirm's button, reused as #47 reused it. Its nine translations read as a generic "remove link" (de
  "Verknüpfung entfernen", fr "Retirer le lien", es "Quitar vínculo", ja "リンクを解除").
- `common_cancel` "Cancel": the confirm's dismiss.
- P105-2 "Change": the sheet's entity row.
- `SAVE_LABEL`: the sheet's Save.

**Every refusal sentence the new surfaces can draw** (all shipped, none new):

| surface | cause | sentence |
|---|---|---|
| Change helper sheet | `BAD_ENTITY_ID` | P16-49, under the entity |
| Change helper sheet | `OUTBOUND_TARGET` | P113-43, under the entity |
| Change helper sheet | the binding went with a Disconnect (`SeasonSyncNotLinked`, no connection) | P16-10 |
| Change helper sheet | the binding went otherwise | none: the sheet closes onto the card, which offers Link |
| Change helper sheet | a store failure | none: the sheet stays as typed (the shipped rule, `LinkSeasonSyncViewModel.kt:328-331`) |
| Change helper sheet | `NOT_MAINTAINED_HERE`, `NO_CONNECTION`, `NEEDS_TOKEN`, `ALREADY_LINKED` | never raised by the edit; the exhaustive `refusedFor` keeps P16-36, P16-10, P16-11 and none |
| Remove link | — | no refusal (L-C1); a store failure draws nothing (`SeasonSyncBlockViewModel.kt:231-235`) |

**Count:** three new strings.

## 6. Plan positions

| # | position | why |
|---|---|---|
| L-1 | Unlinking is Stop's case for C9a; the date binding is untouched | `haBoundaryOf` already reads only `inboundEnabled`; Stop has shipped this path since 1.9.0; clearing `boundaryEntityId` would lose the owner's helper on a relink |
| L-2 | Unlink is idempotent and refuses nothing | a binding is device-local configuration, like Stop (`StopSeasonSync` refuses nothing); #125's unlink of an `ASSET` schedule is the precedent |
| L-3 | The phone gates both actions on `writable`, and core and the API do not (D4, accepted 2026-10-27) | the phone's Stop and Resume gate is `writable` (`SeasonSyncBlockViewModel.kt:243`); core's Stop has no held refusal; one rule per layer, as shipped; pinned both ways by k7/k24(6) (archived, retired: offered) and k12 (held: 200 on the wire) |
| L-4 | The asset applier gains `readEntityId` | a delete and a relink restart at revision 1, the hazard #125's H3 review found (I1); additive, null-defaulted |
| L-5 | `switchedFrom` on the POST answer, and no extra confirmation for a reconciling `LINK`/`RESUME` (D3, accepted 2026-10-27) | the wire's equivalent of the phone's P16-44/45 disclosure and #78's question; it informs and asks nothing (constraint 6) |
| L-6 | Another verb on the asset shape stays 404 | the asset sub-resource convention (`ApiRouter.kt:265-267`); only #125's schedule shape is a 405 |
| L-7 | Sync now stays phone-only | not in the issue; each write that needs a read launches one |
| L-8 | `CHANGE_ENTITY` to the stored id is allowed on `/v1` (it re-reads, clearing the status) and held on the phone | core's edit is unchanged; the sheet's Save holds an unchanged pick |

## 7. Proofs

- **Per brief:**
  - every brief: `./gradlew :core:test :app:testDebugUnitTest --rerun` green with zero failures and skips, and
    `:app:compileDebugAndroidTestKotlin`;
  - K3 also: `uv run --frozen pytest` in `M/`;
  - K4 also: `./gradlew :app:lintDebug`, 0 errors.
- **The merged-tip gate** (`docs/release-proofs.md`), on the integrated branch tip:
  - **K3's app-side doc pins (I4, M12).** K3 edits no file under `app/**`, but its `v1.md` edits must keep two
    shipped app tests green, so K3 runs them as its own proof: `./gradlew :app:testDebugUnitTest --tests
    '*SeasonSyncRoutesTest.theDocumentNamesTheRouteAndTheCode' --tests '*CommandShapesGoldenTest*'`.
  - **R1** includes rows k1–k20 and k25 (the JVM and contract rows).
  - **R2:** `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`, the preserved set staged first,
    zero skips. It includes `SeasonLinkControlsScreensTest` (k24): one class more than 1.9.0's R2.
  - **R3:** the three Python suites, with the MCP at **110** tools.
  - **R4 is unchanged:** `ShareBoundaryTest` and `ContactGrantBoundaryTest`. #129 crosses no new OS boundary, so it
    adds no black-box test (`planning-policy.md`'s hard rule).
  - **R5:** `VersionAgreementTest` at 1.9.1 / 24, after the release task.
  - **R6:** the standing greps, and these, over `22dc9537..HEAD`:

    | grep | expected |
    |---|---|
    | `git diff 22dc9537 -- app/schemas` | empty (constraint 8) |
    | `git diff 22dc9537 -- app core \| grep -nE '^\+.*(MIGRATION_22_23\|FORMAT_VERSION = 22\|version = 23,)'` (Room's `version = 22,` is `AppDatabase.kt:124`) | 0 |
    | `'"SEASON_SYNC_REFUSED"'`, `'"SEASON_SYNC_NOT_LINKED"'`, `'"SEASON_SYNC_CONFIRMATION_REQUIRED"'` in `ApiJson.kt` | 1 each |
    | `git diff 22dc9537 -- app/src/main core/src/main \| grep -nE '^\+.*(HomeAssistantPublishClient\|SERVICES_PATH\|/api/services)'` | 0 (constraint 1) |
    | `git diff 22dc9537 -U0 -- app/src/main core/src/main \| grep -nE '^\+.*\belse ->'` | 0 (constraint 7) |
    | `git diff 22dc9537 \| grep -nE '^\+.*input_(boolean\|datetime)\.[a-z_]+' \| grep -v example_` | 0 (constraint 11) |
    | `'"confirmEntityId": entity_id\|"confirmUnlink": True'` in `server.py` | 0 |
    | `'^.mcp\.tool\('` in `server.py` | **108**: 102 at the base plus six; two tools register through `mcp.tool()(…)` at `:4819`, `:4842`, so the count of 110 is R3's `len(TOOL_NAMES)` pin, not this grep |
    | `git diff 22dc9537 --diff-filter=M --name-only -- core/src/test/kotlin/com/loosecannon/servicetag/core/seasonsync app/src/test/kotlin/com/loosecannon/servicetag/seasonsync app/src/androidTest/kotlin/com/loosecannon/servicetag/seasonsync` | empty (constraint 12) |
    | `git diff 22dc9537 --diff-filter=M --name-only -- app/src/test/kotlin/com/loosecannon/servicetag/api/SeasonSyncRoutesTest.kt` | exactly that file, k18 |

  - **R7:** the signed 1.9.1 installed in place over 1.9.0 on the development phone, with the same
    `firstInstallTime` and UID and table counts unchanged. There is no migration: schema 22 on both sides.
  - **The real-HA proof** (the controller's, on #16's Home Assistant test fixture, with two of its `input_boolean`
    test helpers, from the emulator or the development phone):
    1. link an asset to helper A and let the first check apply;
    2. Change helper to B: the next check reads B, the earlier rows are kept, and a row is written only if B
       disagrees;
    3. Remove link: the binding is gone and no row is written;
    4. confirm in Home Assistant that neither helper's state or `last_changed` moved because of ServiceTag.

    The record names only the fixture's fictional helper ids.
- **The production follow-up** (after the release reaches the production phone, the controller's, generic):
  1. Under the production phone's standing API-load procedure, read the mis-linked asset with `get_season_sync` and
     note its entity and `get_season`'s activation ids.
  2. The owner names the helper the link should follow. Run `change_season_sync_entity(asset_id, entity_id,
     confirm_entity_id)` with that id in both arguments, given by the owner, never filled by the agent.
  3. Read `get_season_sync` until `lastSuccessAt` moves past the change. Then check:
     - the entity is the new one;
     - every earlier activation id is still listed;
     - at most one new row exists, dated that day, and only if the new helper disagreed with the season;
     - nothing in Home Assistant changed.
  4. The private names stay in the controller's ledger; this plan and the issue name none.
  5. #129 closes after this.

## 8. Classification

**PATCH 1.9.1 / versionCode 24** (owner, 2026-10-27). The rationale is `docs/versioning.md`'s first row, "something
that was supposed to work already now does": the ratified 1.9.0 commissioning sequence counted on a shipped Change
entity, and the shipped core command existed with no caller.

The code proves no MINOR or MAJOR trigger:
- no Room schema change: a DAO delete only, `22.json` identical (R6);
- no backup format change: bindings never travel;
- no NFC or protocol change;
- no shipped `/v1` behaviour broken. The one shipped answer that changes is a `POST` on this shape, from 404 to
  routed: k18, an additive route on an existing path, as #125's was.

**One reading for the record.** On its own, versioning.md's MINOR row ("new user-facing capability") could be read onto
the six `/v1` actions and MCP tools. The owner's PATCH ruling covers them as part of the defect ("isn't on the API or
MCP"), and nothing in the code changes that.

## 9. Relationships

- **#16:**
  - R16-14 narrowed (constraint 4);
  - R16-9 narrowed for the asset link (constraint 5);
  - `RecordSeasonSyncResult` and the runner amended additively (L-C3);
  - two pins amended (k18, k22).
- **#105:** the sheet's browser, its `INPUT_BOOLEANS` scope and manual entry, reused unchanged; the asset link keeps
  #16's id rule (`isValidEntityId`), not #125's `input_boolean`-only rule.
- **#113/#114:** C9a and `boundaryRequired` read the unlink as a Stop (L-C4); C26 in both directions is unchanged.
- **#116:** its Home Assistant mark goes with the binding (k11).
- **#125:** its handler is this route's shape (L-C8); its schedule bindings keep the periodic work through an unlink
  (k10b); a schedule's link gains no Change helper.
- **#121:** no new surface (constraint 9).

## 10. Owner rulings (2026-10-27)

| # | ruling | where it lands |
|---|---|---|
| Strings | **Re-ratified verbatim:** P129-1 "Change helper" (unchanged); P129-2 "Remove the link to %1$s? This asset stops following it and keeps its current season and history. Your Home Assistant connection and other links stay."; P129-3 "The link reads the helper you choose from its next check. This asset's season history stays as it is."; P15-23 "Remove link" reused | §5, L-C6, L-C7, K4 |
| D2 (M13 restated) | **The fact:** `/v1/status` already reports `appVersion` (`A/api/ApiDtos.kt:48`), so a version gate in the MCP would be a small comparison on the status read the pairing already makes, not a new data source. **The ruling:** the owner **still accepts** the plain 404 `not_found` on a 1.9.0 phone (schema 22, no write route, nothing written). The tools keep the schema-22 gate alone, and their docstrings name the 1.9.0 404 | limit 1, L-C9 |
| D3 | **Accepted:** `LINK`/`RESUME` of a `CALENDAR` or `YEAR_ROUND` asset switch it into `MANUAL` with no extra confirmation on the wire; the answer reports `switchedFrom` | L-C8, L-5 |
| D4 | **Accepted:** the phone hides Change helper and Remove link on a held asset, as it hides Stop and Resume; core and the API do not refuse it | L-C1, L-C6, L-3, k7, k12, k24 |
