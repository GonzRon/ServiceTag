# #16 — [PRE-1.0][FEATURE] Sync Asset operating season from Home Assistant over LAN: plan and briefs (rev 1.5, 2026-10-03)

> **Rev 1.5 (2026-10-03) folds the second owner gate (comment 5972214001)**, nothing else: §5's block RATIFIED (six
> rows amended, P16-83/84 added); §6 R16-Q-H…J; **C33** and the new brief **B2b** (§27), folded before B8b1; pointers
> in C3, C9, C13, C27, C30, C32, limit 15; rows 75–80 and B2b's pins; §4 (twenty dispatches); the B8a, B8b1 and B10
> briefs; §7's API key. The same comment authorizes lane B (B8a →).

> **GATE PASSED 2026-10-02; execution authorized from B1a.** B1a, B1b landed; B1a2 in flight (`e24b4371`, `c2654a65`).
> Placement #16 → #98 → ServiceTag 1.0.0 (1.6.0 / code 19; schema 21 / format 20; no release). **Rev 1.4 (2026-10-03)**
> folds the owner's **amended R16-20** (`issue-16-rev3.md`: "Background checks on this home network: Off / On") —
> C4a, C9, C17, C21, C22, C26, C29, C32, limits 19–20, rows 59, 66b, 71, 73, §6 — and the scoped review of rev 1.3
> (`plan-review-rev1.3.md`): **C-1** C32's table, R16-21, §7, row 54c; **C-2** no wired identity (C4a, C9, C32, limit 15,
> rows 54a, 54c, 66a, 73; P16-64 withdrawn); **C-3** the "could not confirm" causes (C19, C26, C27, C32, rows 54a,
> 54c, 66a, 67); **C-4** the fences (global, C31, briefs-common, B5, R16-Q-B); **C-5** C19 step 1a fails closed, rows
> 54a, 54b; **C-6** R16-20, R16-Q-F; **C-7** §4/§10a, row 73; notes N-1…N-14 where tagged. §5's **PENDING** block (31
> strings) is also `.superpowers/sdd/2026-10-02-issue-16/strings-pending-rev1.4.md`. *History:* rev 1.1
> `153409c9`, rev 1.2 `ec4854f6`, rev 1.3 `f9364504`, rev 1.4 `c816c569` (`git show` each for its change list).

> **Scope, RATIFIED by the owner 2026-10-02 (R16-0), binding on every brief; `GET /api/` for Test connection is also
> in scope:** *#16 = one
> bounded workflow — an owner-enabled binding from an existing Asset to one Home Assistant boolean entity on an
> owner-configured LAN endpoint, read by authenticated outbound polling of `GET /api/states/<entity_id>`; exact
> `on`/`off` applied through the shipped season operation only when the effective season changes; every other outcome
> "no new decision" with visible status; Follow / Force in season / Force out of season overrides that survive polls
> and restarts; foreground-on-stale, bounded unique periodic WorkManager work and an explicit Sync now; the token in
> platform-backed secure storage and out of every log, diagnostic, backup and Transfer Pack; bindings as device-local
> configuration with read-only non-secret API/MCP visibility. Nothing more.* **Out:** any telemetry, measurement or
> meter ingestion (#56); publishing state back to Home Assistant; NFC interoperability; InfluxDB, Grafana, MQTT;
> WebSocket/SSE subscriptions; remote access; countdown or projected-start sensors; any inbound listener, foreground
> service or permanent socket; any change in a Home Assistant repository; a second credential store or a second poller.
> *(R16-Q-D amended 2026-10-03: "Any network" lets the owner use an HTTPS origin they deliberately made reachable —
> their own endpoint, never a tunnel or relay ServiceTag provides; §6.)*

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-02-issue-16/progress.md` (the controller's; implementers never write it).
> **Audit (inventory of record):** `.superpowers/sdd/2026-10-02-issue-16/audit.md`, every citation read on `811aecae`.
> This planner re-read on the same base every site it cites, and **corrects the audit in six places** (§4, "Audit
> corrections"). **Twenty dispatches on one branch `issue-16`:** B1a, B1b, B1a2, B2, B3a, B3b, B3c, B4, B5, B6a, B6b,
> then **(B7a → B7b → B9 → B2b) ∥ (B8a → B8b1 → B8b2 → B8c)**, B2b folded before B8b1 (rev 1.5), then B10 — each `<base>` the previous accepted tip; **B1a's `<base>` is
> master at dispatch** (today `811aecae`: 1.6.0 / code 19, Room schema 20 / backup format 20, MCP 89 tools, gitlink
> `7e0377a`, 59 device classes) plus this plan's commit. One task review each, at most one bounded fix round each,
> one whole-branch review, the merge, one merged-tip gate.

**Goal:** a fictional "Example Heater" is linked to one HA on/off helper, `input_boolean.example_heater_in_season`,
whose automation decides the heating season. When the phone can check (on open after a cadence, on Sync now, or by
the periodic work where allowed), ServiceTag reads that entity and, only when its season differs from the asset's
today, records one manual START or END through #14's operation; #60 and #4 do the rest. Every other answer is "no new
decision", drawn as a status; a forced season survives polls and restarts; the token lives in a Keystore-backed file
Auto Backup never copies; the binding is device-local; one read-only route and one MCP tool show its non-secret
state. No engine, scheduler, archive, merge or pack code changes.

**Inputs:** issue #16 and the owner's rulings (`.superpowers/sdd/2026-10-02-issue-16/`); the audit; the #69 plan (§30)
and #47's §20 as precedents; D3, D9, `docs/api/v1.md`, `docs/release-proofs.md`, the planning policy. Paths: `C/`,
`CT/` = core main and test under `com/loosecannon/servicetag/core/`; `A/`, `T/`, `AT/` = app main, test and androidTest
under `com/loosecannon/servicetag/`; `M/` = `tools/servicetag-mcp/`; `S/` = `tools/servicetag-schedules/`. Every
`file:line` was read on `811aecae` or the tip it names.

## Global constraints

- **One season authority, no new mode (R16-1).** HA drives only a **MANUAL** asset: `SeasonMode { YEAR_ROUND,
  CALENDAR, MANUAL }` (`C/model/SeasonModel.kt:8`) is unchanged, and activation rows are read only in MANUAL
  (`C/schedule/SeasonContext.kt:239-248`, phase rule `:66-74`). While a binding is **enabled** it owns that asset's
  season decision, and every other season writer answers one new 409 (C15). No engine, `SeasonContext`, policy or
  scheduler change.
- **The shipped operation is reused, never forked (R16-2).** The only new writer of `asset_season_activation` rows is
  the applier (C13), and it writes through `RecordSeasonActivation`'s own body, extracted unchanged into an
  in-transaction function (C12, `ReplaceAsset`'s C15 shape: "It never nests another use case: it calls their
  in-transaction bodies", `C/usecase/ReplaceAsset.kt:175-176`). One row and one `recompute.forAsset`, nothing else
  (`C/usecase/RecordSeasonActivation.kt:15-19`, `:81-82`). **No direct `activations.insert` outside the body.**
- **One schema step, format unchanged (R16-4).** Room 20 → 21 adds two **device-local** tables (`ha_connection`,
  `season_sync_binding`) and nothing else; `BackupData`, `FORMAT_VERSION` (20), the codec, the merge planner, every
  `MergeTable`, manifest key, transfer class and key position are **untouched** — the #79
  `deadline_local_delivery` precedent ("never exported, never merged",
  `A/data/room/entities/DeadlineLocalDeliveryEntity.kt:6-16`). `20.json` is never edited.
- **The token is never data (R16-5).** It exists only inside the `SecretStore` (C18) and, for one request, inside the
  client (C19). It is never a Room column, a field of any `data class`, a `toString`, an exception message, a log
  line, a view-model state after save, an API or MCP byte, a backup, merge or pack byte, or a test fixture value that
  looks like a real token (fixtures use `fictional-token-1` and the like).
- **The network fence (R16-6).** Outbound only: one authenticated GET to the one configured origin, no redirect
  followed, system trust anchors only. No inbound listener, server socket, foreground service, WebSocket, SSE, MQTT,
  network callback (**except C32's one-shot default-network read** in `CurrentNetworkReader`, once per request) or
  second HTTP stack; no dependency. #85's policy and transport are untouched but for one keyword and one KDoc
  sentence (§4, B1b, B5).
- **The twin-pin rule (#15 §20, #47 E-4, #69 E-16).** A shipped assertion moves only where §3's moving-pins table names
  it, or as its **twin** (the same fact pinned for the same reason elsewhere), listed in the report; anything else
  stops the brief. Pin sets are confirmed with "Briefs — common"'s greps **before** editing.
- **Nothing that travels moves.** Every format literal 20, every one-past archive, `counts.size`, `MergeTable`'s
  members, every key position and every manifest-count map stays as it is: a hit in any of them stops the brief.
- **The 2.6 tombstones are never touched.** `git diff <base> -- . ':!app/schemas' | grep -cE
  '^[-+].*(external_link|externalLinks)'` → 0.
- **API version stays 1, additive:** stable codes (422 body, 409 another row, 404 none); each new mapping its own arm,
  never the mapper's `else` → 500 (#47 E-18); the phone's `SyncErrorKind` → sentence map is an exhaustive `when`.
  **The API returns codes, not sentences.**
- **The fence (C31).** No telemetry, reading, meter or measurement word, column, route or tool (#56); nothing written
  to HA; no NFC surface; no InfluxDB, Grafana, MQTT, WebSocket or SSE; no remote-access service, tunnel or relay of
  ServiceTag's own (the owner's own https origin under "Any network" is in, limit 18); no
  countdown or projected-start value; no inbound listener, foreground service or permanent socket; no new `SeasonMode`
  value; no column on `asset`, `asset_season_activation` or any other shipped table; no API or MCP write for season
  sync; no base URL or token over the API or the MCP. **C-3 wording rule (#47, #69):** the word list of #69 §2 C33(1)
  appears nowhere a brief adds — code, KDoc, docstring, document, test name or commit — not even to deny it; its
  tripwire grep is run exactly as #69 wrote it (`git show 811aecae:docs/superpowers/plans/2026-10-01-issue-69-resource-owners.md`, C33(1)).
- **No personal data.** The owner's private data, household network, HA instance and entity names are never quoted,
  copied, counted into a fixture or loaded by a test. Fixtures are fictional: the asset "Example Heater", the entity
  `input_boolean.example_heater_in_season`; **the accepted http endpoint `http://192.168.0.10:8123`** (a fictional
  RFC 1918 literal, C-7); the **refused** `http://192.0.2.10:8123` (RFC 5737); `https://ha.example:8123` (RFC 2606); the
  home Wi-Fi `ExampleHomeWifi`; RFC 1918 range edges only in C8's rows; the emulator's own guest address and Wi-Fi read
  at run time (C29). No real name, host, Wi-Fi, token, serial or e-mail address anywhere; home paths `~`.
- **Strings.** Every user-visible string is §5's with its one home — RATIFIED 2026-10-02 (R16-Q-C) or 2026-10-03
  (R16-Q-H…J); developer-facing texts are the G-list (G1–G5); no brief invents or rewords one.
- **Tests.** JVM first (core over the core doubles through `BackupInstall`; app over the production router and the
  Room-backed `FakeGraph`; the HTTP client over a fake `HttpURLConnection` through the shipped `open` seam,
  `A/fetch/UrlConnectionTransport.kt:44`). Device rows only for genuine Android boundaries (R16-12): one platform class
  (C29), one Compose class (C28) — **59 → 61**. **No JVM test opens a socket**; the one device socket reaches only an
  in-process fake HA. Every counted RED is a real mutation (`--no-build-cache --rerun-tasks`), quoted, reverted.
  **No rerun-until-green.**
- **Standing rules.** Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump** (1.6.0 / 19). Commits: one
  casual lowercase subject line; no body, no trailers, no AI attribution. `tools/` never names a screen-driving tool.
  **Implementers never write ledgers.** No dependency is added (no `security-crypto`, no HTTP library, no
  `lifecycle-process`). **Every `AppGraph` and `FakeGraph` edit lands in B2, B3b, B4, B5 or B6a (#47 C-1); B6a is
  the last edit of either;** B6b, B7a, B7b, B2b, B8a, B8b1, B8b2, B8c, B9 and B10 edit neither.
- **Schema 21 on master**, emulator only, never a phone; the schema-21 release paragraph is B10's (C30).
- **Time boxes:** 1 h target, 2 h hard stop per brief (B1a2 shipped in ~45 min); past 1 h with under half the rows green,
  stop and report; fix rounds 45 min; mutation caps per brief; one task review, at most one bounded fix round,
  mechanical fixes by controller inspection, one whole-branch review.

## 1. Scope

**Scope (RATIFIED 2026-10-02, R16-0):** the sentence in the header, verbatim, plus `GET /api/` for Test connection.

**In scope — the acceptance criteria, each on its contracts** (AC1–AC10 as the issue lists them):

| AC | the issue's words (abridged) | contracts · rows |
|---|---|---|
| AC1 | a fictional seasonal heater follows a dedicated HA boolean: on enters season, off leaves it, through the ordinary operation | C5, C6, C12, C13 · rows 1, 6, 22, 60 |
| AC2 | appliance on/off cycling has no effect while the season boolean is unchanged | C13 (compare first), C30 (the helper, never the appliance switch) · rows 23, 60 |
| AC3 | a change while offline is found on the next successful foreground, manual or background refresh | C21–C23 · rows 57, 60 |
| AC4 | repeated reads, concurrent refreshes, retry and restart: no duplicate transition or recurrence reset | C13 (compare inside the write, 409s mapped), C21 (single flight) · rows 23, 24, 57, 60 |
| AC5 | unreachable, unauthorized, missing, malformed, unknown/unavailable preserve state with accurate status; an unchanged fetched boolean is a fresh observation | C5, C13 (status fields), C19 · rows 2–5, 28, 53a, 54a, 54b |
| AC6 | both overrides persist; stale or in-flight reads cannot defeat them; Follow uses a fresh read | C13 (revision; FORCE re-asserted), C17 · rows 25, 29, 36, 42, 60 |
| AC7 | in-service, continuous, re-entry and break behaviour stays canonical; no fabricated completion, health repair or missed-occurrence backlog | C12, C14 · rows 31, 32 |
| AC8 | binding edits, disconnect, disposition and replacement cannot let an old request mutate an unintended asset | C13 (revision, `maintainedHere`), C16, C17, R16-18 · rows 25–27, 26a, 41, 43, 43a, 44 |
| AC9 | credentials, restore/transfer reauthorization and normal offline use are proven | C9–C11, C17, C18, C19 · rows 8, 17, 20, 45–48, 53, 54, 71 |
| AC10 | (as amended 2026-10-02) a bounded end-to-end proof: deterministic JVM coverage, platform/device tests against an in-process fake HA, and an emulator-to-real-HA LAN proof with no private endpoint, entity or token committed | **R16-Q-A (DECIDED):** row 60 (JVM), row 71 (the device class's fake HA), and §7's real-HA proof run by the controller after the merge; the development phone is untouched (its check waits for the #98 / 1.0.0 smoke) |

**Out of scope** (the sentence's list, and): an opportunistic network callback (R16-7); user CAs (R16-Q-B); a path
prefix on the HA endpoint; IPv6 literals for http; a second connection (R16-10) or binding per asset; another binding
kind (#56); a `last_changed`-dated transition (R16-3); replaying missed transitions; a provenance column (R16-11); an
API or MCP write; the base URL over the API or MCP; a generic REST or provider-neutral source (#100).

**Recorded limits (stated, not fixed):**
1. **Current state, not history:** HA going on, off and on while unreachable is seen as its final state only.
2. **Dated the day applied** (R16-3), never by HA's `last_changed` (information only).
3. **A replace restore removes every binding** (assets wiped first, `C/usecase/ImportBackupReplace.kt:196`, CASCADE);
   the connection and token stay; the owner links again.
4. **A platform restore** (Auto Backup is on, `app/src/main/AndroidManifest.xml:58-62`) brings the Room rows back
   without the key or the no-backup file: every binding reads **NEEDS_TOKEN** and sends nothing until a token is
   entered (C18); everything else in the two tables, the home Wi-Fi's name included, is in that backup (R16-Q-F).
5. **A merge or a pack can move a synced asset's phase** (H7); the next run corrects it with at most one transition, by
   HA's answer in FOLLOW or the forced phase in FORCE (C13, C-5; row 36).
6. **Same-day flapping re-anchors** IN_SERVICE on each START (audit H10): the defence is a season helper, never the
   appliance's switch (C30, P16-43).
7. **Background timing is best effort:** the cadence is a requested period that may run late, never a deadline.
8. **A device date before the latest season row** gives DATE_BEFORE_HISTORY until the date catches up (H4).
9. **A binding on an archived, retired, transferred-out or replaced asset is inert** (NOT_MAINTAINED_HERE; no successor
   inherits it, R16-8); still enabled, it resumes **on its own** once the asset is maintained here again (`unarchive`,
   `C/usecase/ArchiveAsset.kt:30`; a withdrawal leaves assets archived, `WithdrawTransferRecord.kt:37-38`), the next read
   applying the current state only, no catch-up, no owner step (R16-18, row 26a).
10. **Disconnect cannot revoke the token in HA**; P16-9 tells the owner to.
11. **`.local` names are not verified** (H9): http takes IP literals only; an https name depends on the router's DNS.
12. **A `targetSdk` 37 bump needs `ACCESS_LOCAL_NETWORK`** (H11, C32); at 36 INTERNET grants it (`README.md:84`).
13. **What #16's proofs do not observe** (R16-Q-A): the real HA is reached through the emulator's NAT (§7); a phone's
    own Wi-Fi, Private DNS, OEM and Doze timing wait for the optional #98 / 1.0.0 smoke. **With a VPN up** the default
    network carries no `WifiInfo`, so an always-on-VPN owner cannot use the home-network mode (N-1).
14. **Linking or resuming a CALENDAR or YEAR_ROUND asset re-anchors its IN_SERVICE schedules** (C-2, R16-Q-G): the
    switch row is dated today (`C/usecase/SetSeasonMode.kt:23-26`), a MANUAL span starts at its first START
    (`C/schedule/SeasonContext.kt:84-88`, `:183-190`), IN_SERVICE re-enters at `cycleStartAt(at)`
    (`C/schedule/ServicePolicyEngine.kt:108-123`); e.g. window start 10-01, AT_START offset 14 (due 10-16), linked
    10-20 → opens 11-03. Disclosed by P16-44/45; row 40a.
15. **Cleartext on another network (as amended):** http requires "Only on this home Wi-Fi" (P16-60 as amended) and each
    request first matches the captured Wi-Fi name (C32); a network copying that name still passes — the check narrows
    the exposure, it cannot authenticate the network; **Ethernet never passes** (C-2). In that mode an https name must
    resolve privately (R16-Q-B (c)); a DNS change after that check is caught only by TLS's name check.
16. **Provenance is short-lived (N-13):** the binding keeps only its last applied change (R16-11) and Disconnect deletes
    it (R16-14); activation rows stay, unmarked.
17. **Home Assistant has no read-only tokens:** a non-admin token can call any allowed service; ServiceTag reads by
    discipline (one GET, C19). The proof's token is a house-control credential that never leaves the development
    machine (§7).
18. **"Any network" is remote access by the owner's explicit choice** (R16-Q-D as amended): https only, an https
    **name** may resolve anywhere (a public IPv4 literal stays refused, B1b's rule 5; N-12), the token goes to that
    origin from any network; no cleartext and no tunnel or relay of ours.
19. **Home-network background checks are the owner's opt-in** (R16-20 as amended). Off (the default): checks on open
    after a cadence without a success and on Sync now; away from home each such open records "not on your home Wi-Fi"
    on every binding — accurate, and recurring (N-13). On: the work runs only with "Allow all the time" location
    (API 29+), which Android reminds the owner about and which can be withdrawn at any time; withdrawn or refused, the
    connection falls back to Off's behaviour with a visible status (C22, C26). A worker's view of the Wi-Fi name is
    proven only on the emulator's instrumentation process, never on a real backgrounded phone (C29).

## 2. Contracts

### Common (C1–C3)

- **C1, the words.** Package `C/seasonsync/` (core) and `A/seasonsync/` (app: the client, the store, the runner, the
  worker), `A/ui/homeassistant/` (the connection screen), the season-card block in `A/ui/asset/`. Tables
  `ha_connection`, `season_sync_binding`. Domain: `HaConnection`, `SeasonSyncBinding`, `SyncMode { FOLLOW, FORCE_IN,
  FORCE_OUT }`, `HaSwitchState { ON, OFF }`, `SyncErrorKind`, `HaReadOutcome`, `SecretStore`, `Secret`; rev 1.3's
  `SyncCadence`, `NetworkEligibility`, `BackgroundChecks`, `CurrentNetwork` (C4a). Route
  `/v1/assets/{id}/season-sync`; MCP tool `get_season_sync`; constant `_MIN_SEASON_SYNC_SCHEMA_VERSION`. WorkManager
  unique name `"season-sync"`. Keystore alias prefix `servicetag.ha.`; no-backup directory `ha-secrets/`. **Never** a
  telemetry, sensor, reading or measurement word on any of these (the #56 fence), and never "read-only token": the
  adapter performs reads only; the token is whatever HA grants its user (the issue's wording).
- **C2, the codes.** **One new code:** 409 `SEASON_SYNC_ENABLED`, from a new core exception
  `SeasonSyncOwnsSeason(assetId)` (beside `SeasonNotManual`, `C/usecase/SeasonCommands.kt:101-103`), mapped by one
  `is` arm beside `SEASON_NOT_MANUAL`'s (`A/api/ApiJson.kt:509-511`), never left to the mapper's `else` → 500
  `internal` (`:582-584`), message G1. Reused unchanged: `no_such_asset` (404) on
  the new read; every shipped season code; the guard's 409 `asset_transferred_out`. The new route answers no other
  code: an asset without a binding is a 200 with `"binding": null`, never a 404.
- **C3, the wire shape.** Additive, API version 1. `GET /v1/assets/{id}/season-sync` → 200:

| key | value |
|---|---|
| `assetId` | the path's id |
| `connection` | `{"configured": bool, "needsToken": bool, "cadence": "EVERY_12_HOURS"\|"DAILY"\|"WEEKLY"\|"MONTHLY", "networkEligibility": "ANY_NETWORK"\|"HOME_NETWORK_ONLY", "backgroundChecks": "OFF"\|"ON", "backgroundAllowed": bool, "homeNetworkSet": bool}` — the settings are non-secret; `backgroundAllowed` is whether Android grants what On needs (C32); **never** the address, the home Wi-Fi's name or the token (R16-9); the settings absent when not configured |
| `binding` | `null`, or the object below |
| `binding.entityId` | the linked entity id, e.g. `input_boolean.example_heater_in_season` |
| `binding.mode` | `"FOLLOW"` \| `"FORCE_IN"` \| `"FORCE_OUT"` |
| `binding.enabled` | bool (false after **Stop syncing**) |
| `binding.state` | `"ACTIVE"` \| `"STOPPED"` \| `"NEEDS_TOKEN"` \| `"NOT_MAINTAINED_HERE"` — derived at read time, never stored (C17) |
| `binding.observation` | `null`, or `{"state": "ON"\|"OFF", "haLastChanged": string\|null}` — `haLastChanged` is HA's text verbatim, information only; its fetch time is `lastSuccessAt` (N-5: one field, not two) |
| `binding.lastSuccessAt` | millis or `null` — the fetch time of the last valid observation; a failure never moves it |
| `binding.lastAttemptAt` | millis or `null` — any outcome |
| `binding.lastError` | `null`, or `{"kind": SyncErrorKind name, "detail": string\|null, "at": millis}` — `detail` is an HTTP status or HA's reported state, never a URL or token |
| `binding.lastApplied` | `null`, or `{"action": "START"\|"END", "occurredOn": "YYYY-MM-DD", "at": millis, "source": "HOME_ASSISTANT"\|"FORCED_IN"\|"FORCED_OUT"}` — the provenance (R16-11); `source` added by rev 1.5 (C33, R16-Q-H) |

  Times are epoch milliseconds (`createdAt`'s shape, `C/model/SeasonModel.kt:21-29`); the effective season is
  `GET …/season`'s phase, not repeated; `GET /v1/status` moves `schemaVersion` to 21, nothing else.

### B1a — the domain: model, outcome, effective season, ports (C4–C7) — SHIPPED (`9b69ee03`)

Rev 1.3 shortens C4–C7 to their contract; the signatures of record are the B1a report's
(`.superpowers/sdd/2026-10-02-issue-16/task-b1a-report.md`, "Signatures").
- **C4, the model.** `HaConnection` (canonical `baseUrl` = `scheme://host[:port]`, lowercase, no path), the binding with
  exactly C9's columns and no defaults, the seventeen `SyncErrorKind`s (each with one P16 sentence, §5), the entity id
  rule `^[a-z0-9_]+\.[a-z0-9_]+$` (≤ 255 characters, one path segment, refused at link time), and `Secret`, whose
  `toString()` is constant; no model type holds a `Secret`.
- **C4a, the connection's settings (B1a2; R16-Q-D and R16-20 as amended; added to the shipped B1a model).**
  `HaConnection(id, baseUrl, cadence, networkEligibility, homeNetworkSsid, backgroundChecks, createdAt, updatedAt)` —
  four fields added, none defaulted (B1a's rule; as B1a2 builds them):
  - `enum class SyncCadence(val hours: Long) { EVERY_12_HOURS(12), DAILY(24), WEEKLY(168), MONTHLY(720) }` — exactly
    four; a new connection is `DAILY` (C17); `hours` is the work's period (C22) and the stale threshold (C21, C27), and
    **no other period exists anywhere**.
  - `enum class NetworkEligibility { ANY_NETWORK, HOME_NETWORK_ONLY }`; `enum class BackgroundChecks { OFF, ON }` (a new
    connection is `OFF`; it matters only in `HOME_NETWORK_ONLY`).
  - `homeNetworkSsid: String?` — **the Wi-Fi name only** (C-2): as Android reports it, unquoted, compared exactly,
    never blank or whitespace-only;
    null = not captured; never a BSSID, an address, a location, a time or a wired marker.
  - `sealed interface CurrentNetwork { Wifi(ssid), WifiUnnamed, Wired, Other, None }` and the pure
    `eligibleNow(connection, current)`: ANY_NETWORK → true; HOME_NETWORK_ONLY → only `Wifi` with the captured name;
    `WifiUnnamed`, `Wired`, `Other`, `None` and an uncaptured name are **never** eligible. Why a name was hidden is the
    app reader's (C32), not the model's.
  - **Invariants** (C17's writers keep them; the double refuses a violating upsert): `HOME_NETWORK_ONLY` ⇔
    `homeNetworkSsid != null`; an `http` base URL ⇒ `HOME_NETWORK_ONLY` (and C19 re-checks it per request, C-5). No
    port changes; `haConnectionOf`'s http fixture is DAILY, HOME_NETWORK_ONLY, `"ExampleHomeWifi"`, OFF (fictional).
- **C5, the read outcome and its mapper (the issue's exact-state rule).** `mapHaAnswer` → `Observed(ON|OFF,
  haLastChanged)` for a 200 JSON object naming exactly the requested entity whose `state` is exactly `"on"`/`"off"`
  (case-sensitive, no trim); 401/403 `AUTH_REFUSED`, 404 `ENTITY_NOT_FOUND`, 3xx `REDIRECTED`, other statuses
  `HTTP_ERROR` (status as `detail`); a truncated, non-JSON, wrong-type or other-entity answer `MALFORMED`; any other
  state `UNSUPPORTED_STATE` (state as `detail`). Text bounded to 64 characters, controls → `?`; `haLastChanged` is
  text and **never dates a decision**. Transport failures are mapped by the client first (C19).
- **C6, the effective season.** `desiredPhase(mode, fresh)`: FORCE_IN/FORCE_OUT → their phase whatever HA answered;
  FOLLOW → `Observed`'s phase, else `null`. A stored observation is never an input (AC6).
- **C7, the ports and the doubles.** `SeasonSyncRepository` (`update` is a compare-and-set on `revision`, answering
  whether it wrote), `HaConnectionRepository` (one row, R16-10), `SecretStore` (`get` null when absent or
  undecryptable), `HaStateReader` (never throws for a network outcome); the core doubles cascade through
  `BackupInstall` as Room does (C9), are `Rollbackable` among `FakeUnitOfWork`'s stores
  (`CT/testing/InMemoryRepositories.kt:645-650`; N-10); the connection double refuses three invariant breaks (C4a: home ⇔
  a name, http ⇒ home, no blank name), and the scripted reader records calls and can hold one in
  flight.

### B1b — the endpoint policy (C8) — SHIPPED (`9dde6754`; signatures: the B1b report's "Signatures for B5 and B8a")

- **C8, `HaEndpointPolicy` (`classify` / `check` / `canonical`, pure; R16-6, R16-Q-B).** `HopPolicy`'s authority
  allowlist made `internal` (`C/fetch/HopPolicy.kt:66`, the one `C/fetch` edit). Rules: (1) an `http`/`https` origin
  — no userinfo, an empty path or `/` (dropped by `canonical`), no query or fragment; (2) the shared allowlist; (3) no
  `localhost` or `*.localhost`; (4) **`http` only to an
  RFC 1918 IPv4 literal**; (5) `https` to a DNS name or an RFC 1918 IPv4 literal, system trust anchors only (C20).
  **In the home-network mode a name must resolve, at each request, only to private addresses** (RFC 1918 or `fc00::/7`,
  `isPrivateLanAddress`) else `NAME_NOT_LOCAL` (C19 step 1b, through the shipped `HostResolver`,
  `C/fetch/AddressPolicy.kt:12-17`); in "Any network" it may resolve anywhere (limit 18). The rules stay as B1b ships
  them in both modes; the mode is C19's (rev 1.3 adds, never reshapes). #85's `HopPolicy`/`AddressPolicy` are not
  called and not changed (`staticProblem("http://…")` stays `NotHttps`, H8, row 14). The policy runs at save (C17) and
  on every request (C19).

### B2 — Room schema 21 (C9–C11)

- **C9, the two tables (device-local; R16-4, R16-10).**
  - `ha_connection`: `id TEXT PRIMARY KEY NOT NULL`, `base_url TEXT NOT NULL`, `cadence TEXT NOT NULL`,
    `network_eligibility TEXT NOT NULL`, `home_network_ssid TEXT`, `background_checks TEXT NOT NULL`, `created_at
    INTEGER NOT NULL`, `updated_at INTEGER NOT NULL` — eight columns for C4a's eight fields (schema 21 is unshipped:
    one `CREATE TABLE`, no second migration). No token column, no display name (one connection per installation), no secret
    marker: whether the token exists is the store's answer alone (C18), so a platform restore cannot leave a stale
    marker.
  - `season_sync_binding`: `asset_id TEXT PRIMARY KEY NOT NULL` with `FOREIGN KEY(asset_id) REFERENCES asset(id) ON
    DELETE CASCADE`; `connection_id TEXT NOT NULL` with `FOREIGN KEY(connection_id) REFERENCES ha_connection(id) ON
    DELETE CASCADE` and `Index("connection_id")`; `entity_id TEXT NOT NULL`; `mode TEXT NOT NULL`; `enabled INTEGER
    NOT NULL`; `revision INTEGER NOT NULL`; `observed_state TEXT`, `observed_changed_at TEXT`, `last_success_at
    INTEGER`, `last_attempt_at INTEGER`; `error_kind TEXT`, `error_detail TEXT`, `error_at INTEGER`;
    `applied_action TEXT`, `applied_on TEXT`, `applied_at INTEGER`, **`last_applied_source TEXT`** (rev 1.5, C33);
    `created_at INTEGER NOT NULL`, `updated_at INTEGER NOT NULL`. Enum columns hold the enum name.
  - Neither table is named by `BackupCodec`, `BackupData`, the merge planner, `TransferTables.CLASSES` or any
    export, so neither is in any ServiceTag backup, export, merge or pack (R16-Q-E; Android Auto Backup may restore
    the rows, inert, R16-Q-F — the classification test reads `BackupData`'s lists only,
    `CT/transfer/TransferTableClassificationTest.kt:19-25`, and stays green unchanged). `DeleteAsset`, the replace
    restore's `assets.deleteAll()` (`ImportBackupReplace.kt:196`) and a connection delete remove bindings by CASCADE.
- **C10, the step and the pins.** `MIGRATION_20_21` after `MIGRATION_19_20`: two `CREATE TABLE`, one `CREATE INDEX`,
  Room's exact v21 DDL, no backfill; `version = 21` (`A/data/room/AppDatabase.kt:103`), `SCHEMA_VERSION = 21`
  (`A/di/AppGraph.kt:1100`), the migration list (`:276`), the test chain (`T/data/room/MigrationTestSupport.kt:65-70`)
  and `V21_TABLES` beside `V19_TABLES` (`:235`); the nine whole-chain table sets move (§3).
- **C11, the DAOs and adapters.** One DAO, Room repository and mapper per table; `update` is `UPDATE … WHERE asset_id =
  :id AND revision = :expected`, answering whether one row changed (C13); one unguarded instance each in `AppGraph` and
  `FakeGraph` (a held asset's binding is refused by the applier's `maintainedHere`, C13).

### B3a — the applier (C12–C14)

- **C12, the body split (R16-2; H3).** `RecordSeasonActivation` gains `internal suspend fun
  recordInTransaction(assetId, cmd): SeasonActivation` holding **exactly** today's body (`:47-83`, from the asset read
  to the returned row), and `run` becomes `uow.write { recordInTransaction(assetId, cmd) }`. B3b then adds one
  parameter with no default, `guarded: Boolean`, and the guard call **inside** the body between the 422 and the first
  409 (C15): `run` passes `true`, the applier `false`. B3a moves the body and nothing else (the shipped season
  suites stay green); the KDoc gives `ApplyTemplate`'s reason (`C/usecase/ApplyTemplate.kt:46-51`).
- **C13, `RecordSeasonSyncResult` — the one writer of a binding's status and the one HA path to a season row.**
  Input: `assetId`, `readRevision` (the binding's revision when the request was built), `startedAt`, `fetchedAt`
  (the runner's clock when the read returned — the reader's time, not the write's, N-5) and the `HaReadOutcome`.
  Everything below runs inside **one** `uow.write`:

```kotlin
// C13 — shape, not code to transcribe. Every branch ends in exactly one conditional binding update.
val b = bindings.get(assetId) ?: return Dropped           // deleted: nothing to record
if (b.revision != readRevision || !b.enabled) return Dropped  // edited, stopped, re-moded, re-linked since the read
val asset = assets.get(assetId) ?: return Dropped
var next = b.withAttempt(startedAt)                           // last_attempt_at
next = when (outcome) {
    is NoDecision -> next.withError(outcome.kind, outcome.detail, now)    // never touches last_success_at
    is Observed -> next.withObservation(outcome, fetchedAt).clearingError() // an unchanged ON still counts
}
val desired = desiredPhase(b.mode, outcome)       // FORCE_*: the forced phase whatever HA answered (C-5)
if (desired != null) next = applyIfChanged(asset, desired, next)        // FOLLOW + NoDecision: null, nothing
check(bindings.update(next.copy(revision = b.revision + 1)))           // the write is the compare-and-set
```

  A `false` from the conditional update (C11) throws inside the write, so the whole transaction — an activation
  row with it — rolls back; inside one Room transaction it is unreachable, and the double proves the rollback.
  **A forced phase is re-asserted on every run (C-5):** on each result, `Observed` or `NoDecision`, at most one row
  (a `NEEDS_TOKEN` binding is not read, C21). **The `enabled` check is defensive:** Stop bumps the revision, so the
  revision compare drops every reachable stale result; a synthetic case pins the check (row 25).

  `applyIfChanged`: if `!asset.maintainedHere(transfers.heldIds())` → error `NOT_MAINTAINED_HERE`; else if
  `asset.seasonMode != MANUAL` → error `NOT_MANUAL`; else compare `desired` with `SeasonContext.of(asset.seasonInputs(
  activations.forAsset(assetId))).phaseAt(today)`; equal → nothing; different → `record.recordInTransaction(assetId,
  ActivationCommand(START|END, occurredOn = today))` and `withApplied(action, today, now)` (rev 1.5: and the source of
  the mode that applied, C33(3)). The shipped refusals are
  **mapped, never thrown**: `SeasonAlreadyStarted` / `SeasonAlreadyEnded` → "already applied" (no `lastApplied`
  change, no error); `SeasonValidation` holding `SeasonDateOutOfRange` → `DATE_BEFORE_HISTORY`; `SeasonNotManual` →
  `NOT_MANUAL`; `AssetTransferredOut` → `NOT_MAINTAINED_HERE`. With the compare in the same write, the two 409 arms
  are unreachable through the applier: **defensive arms with no RED** (the review's reading); the compare-first
  check's own RED is row 30's agreeing read on a moved-back clock. **Invariants:** a failure never advances
  `last_success_at`; `last_applied_*` moves only when a row was written; the three times — HA's `last_changed`
  (text), the fetch time (`last_success_at`) and the application time (`applied_at`, dated `applied_on`) — are three
  fields; the transition is dated `today` (R16-3); a result whose `readRevision` is not the stored revision writes
  nothing at all (AC6, AC8). **Every write to a binding row bumps `revision` by one**, so of two overlapping results
  the second finds the revision moved and is dropped.
- **C14, the canon (AC7).** An applied START or END writes one activation row (`eventId` null, `createdAt` the clock),
  one binding update and the body's `recompute.forAsset` — no event, closure, condition, case, schedule or asset column
  or reminder, so no completion, snooze, health repair, policy change or break bypass (audit fact 3); notifications
  follow the next sweep, as after **Start season** (`A/ui/asset/AssetViewModels.kt:1297-1300`).

### B3b — one season authority (C15)

- **C15, the guard (R16-1).** `SeasonSyncGuard(bindings).requireNotSynced(assetId)` throws `SeasonSyncOwnsSeason`
  when the asset's binding exists **and is enabled** (any mode; a stopped binding refuses nothing). Placement — after
  the 404 and the 422 validation, first among the 409s, and **only where the use case would write a season fact**,
  so a no-op stays a no-op:

| writer | where the guard sits | not refused |
|---|---|---|
| `RecordSeasonActivation.run` (API `POST …/season`, MCP `start_season`/`end_season`, the phone's Start/End) | inside the body when `guarded`, after `SeasonValidation`, before `SeasonNotManual` (`RecordSeasonActivation.kt:63-66`) | the applier, which calls the body with `guarded = false` (C12) |
| `AcceptSeasonOffer.run` (the journal's offer) | inside its write, before `record.run` (`C/usecase/SeasonEventOffer.kt:54-66`) | — |
| `SetSeasonMode.run` (API `POST …/season-mode`, MCP `set_season_mode`) | after the equality return (`C/usecase/SetSeasonMode.kt:51-56`), before the strands rule (`:65-66`) | an equal command (writes nothing, as shipped) |
| `SaveAssetSettings` (the editor) | only where the season part changes the mode or window or `manualSwitchActivation` returns a row (`C/usecase/SaveAssetSettings.kt:121-125`, `:153-154`) | a save that changes no season field: name, notes, break, health, warranty |
| `UpdateAsset`'s legacy pair (API asset PATCH) | **not guarded (C-3):** a synced asset is always MANUAL, and on a MANUAL asset a different pair is already the shipped 422 `LEGACY_WRITE_CANNOT_REPRESENT` before any write (`C/usecase/UpdateAsset.kt:52-54`, `:62-63`); a guard before `:63` would move a shipped refusal's order. Pinned unchanged (rows 33, 62) | — |
| `ApplyBackupMergePlan`, `ImportBackupReplace`, the Transfer Pack import | **not guarded** (H7): they bring history; the next run reconciles once, in FOLLOW and in FORCE (row 36); a replace restore cascades the binding away | — |
| `CreateAsset`, `ReplaceAsset` | not guarded: a new asset or successor has no binding (R16-8; `ReplaceAsset` never writes the predecessor's activations, `:177-180`) | — |

  Plus the phone: `SeasonOffers.offerFor` (`A/ui/condition/Offers.kt:233-237`) returns null for an asset whose
  binding is enabled (the guard stays the backstop; R16-16's recorded exception, N-11). The guard is a constructor
  parameter **with no default** on the **four** guarded use cases and `SeasonOffers`; B3b owns the **26**
  construction sites (N-9; arguments only) and the `AppGraph`/`FakeGraph` lines.

### B3c — link, modes, stop, resume, disconnect (C16–C17)

- **C16, `LinkSeasonSync(assetId, entityId)` (R16-1, R16-10; the setup reconciliation).** Phone-only (no route).
  In one write, refusals first, each a `SeasonSyncLinkRefused(reason)` the phone maps to one sentence:
  `NOT_MAINTAINED_HERE` (archived, retired or held — P16-36), `NO_CONNECTION` (P16-10), `NEEDS_TOKEN` (P16-11),
  `BAD_ENTITY_ID` (C4's rule — P16-49), `ALREADY_LINKED` (unreachable on the phone, whose action is not drawn for a
  linked asset; pinned on the JVM, no sentence). Then, by the asset's mode:
  - **MANUAL:** write no season row; insert the binding `FOLLOW`, enabled, revision 1.
  - **CALENDAR or YEAR_ROUND:** call `SetSeasonMode`'s rules (its body, in this transaction) with `SeasonModeCommand(
    MANUAL, manualPhase = phaseAt(today))` — one activation dated today matching today's phase, so **the phase** does
    not transition and "preserve the existing local season before the first read" holds (`SeasonCommands.kt:184-201`);
    the window is cleared as any switch into MANUAL clears it. **The strands refusal is surfaced and stops the link**
    (`SeasonModeStrandsPolicy`, `SeasonCommands.kt:256-263`; S55 on the phone): no binding, no row.
  - **The cadence does move** (limit 14, R16-Q-G): the shipped switch's behaviour, not forked; row 40a.
  - The reconciliation sentence (P16-44…46) comes **before** the write and, after a YEAR_ROUND link with live
    CONTINUOUS schedules, #78's prompt (C27) — never silently.
  - The switch runs **before** the binding row exists, in the same write, so B3b's guard (inside `SetSeasonMode`'s
    extracted body, `ApplyTemplate`'s shape) passes; nothing else calls that body. The runner then reads (C21).
- **C17, the other commands** (each one write; each bumps `revision`; none writes a season row except the two Force
  cases and a Resume on an asset that is no longer MANUAL):
  - `SetSeasonSyncMode(assetId, mode)`: `FORCE_IN` / `FORCE_OUT` apply the desired phase **now** through C13's
    `applyIfChanged` (one row only if it differs; provenance recorded); `FOLLOW` writes no season row and asks the
    runner for a fresh read (AC6). Setting the stored mode again is a no-op.
  - `EditSeasonSyncEntity(assetId, entityId)`: validates, clears the observation and error, keeps the mode.
  - `StopSeasonSync(assetId)`: `enabled = false`; writes **no END** and leaves the asset MANUAL at its phase (the
    issue's disconnect rule); the guard is off, so the phone's Start/End and the editor's season block return.
  - `ResumeSeasonSync(assetId)` (**R16-19**) needs a connection and a token; on an asset no longer MANUAL it **runs
    C16's reconciliation** (P16-44/45 first, the switch dated today — limit 14 —, S55 refusing with nothing written,
    #78's prompt after YEAR_ROUND); on MANUAL it writes no row; then `enabled = true`, a revision bump, a fresh read.
    No stopped binding is a dead end; Disconnect stays the only delete (R16-14).
  - `SaveHaConnection(baseUrl, token?, cadence, networkEligibility, homeNetworkSsid?, backgroundChecks)`:
    `HaEndpointPolicy` first (`ENDPOINT_REFUSED`, P16-12); then **http with `ANY_NETWORK` → `HTTP_NEEDS_HOME_NETWORK`**
    (P16-67) and `HOME_NETWORK_ONLY` with no captured name, or a blank one, → `HOME_NETWORK_NOT_SET` (P16-62's prompt) — phone-only
    `SaveHaConnectionRefused` reasons; the one row is created (`DAILY`, `OFF` unless chosen) or replaced (R16-10); a new
    token is `SecretStore.put` **after** the commit; a changed address, token, eligibility or home name bumps every
    binding's revision; a changed cadence, eligibility or `backgroundChecks` calls `SeasonSyncScheduler.ensure()` or
    `cancel()` after the commit (C22). Storing `ON` needs no permission; whether it *runs* is C22's.
  - `ForgetHaConnection()` (**Disconnect**): deletes the row (bindings by CASCADE, no END, history kept), then the
    secret; the work is cancelled (C22); a Keystore failure is logged by kind and swept at the next start (C18).
  - **The runner's port:** the commands reach the runner through a core port `SeasonSyncScheduler` (`ensure()`,
    `cancel()`, `requestFreshRead(assetId)`), called after the write commits; B6a's runner implements it, B3c tests
    against a recording double.
  - **The derived state** (never stored): `NEEDS_TOKEN` when the connection exists but `SecretStore.has` is false;
    `STOPPED` when `!enabled`; `NOT_MAINTAINED_HERE` when the asset fails `maintainedHere`; else `ACTIVE`. Only
    `ACTIVE` bindings are read (C21). Entering a token is the reauthorization: it moves `NEEDS_TOKEN` bindings back to
    `ACTIVE` with a fresh read (R16-4, AC9). **Because it is derived (R16-18, C-11),** an asset maintained here again
    makes a still-enabled binding `ACTIVE` with no owner step and no catch-up (limit 9; row 26a); the lifecycle use
    cases stay unedited.

### B4 — the secret store (C18)

- **C18, `KeystoreSecretStore` (`A/seasonsync/`; R16-5, D9 as written: `docs/design/09-security-privacy.md:25`).** One
  AES-256-GCM key per connection in `AndroidKeyStore` under alias `servicetag.ha.<connectionId>`, `PURPOSE_ENCRYPT |
  PURPOSE_DECRYPT`, **user authentication not required** (background reads must work), no export; the ciphertext (a
  fresh 12-byte IV, then the GCM output) is one file `<noBackupFilesDir>/ha-secrets/<connectionId>.bin`, written then
  renamed. Neither Auto Backup nor a device transfer copies that directory (`A/prefs/InstallationIdentity.kt:12-15`), so
  no backup rule changes (R16-5's deviation, N-1). A `KeyedAead` seam lets the JVM use the JDK's AES-GCM; `get` is
  **null, never a throw**, when the file or key is absent or the tag fails (H5); `has` = file and key present;
  `sweepOrphans(knownIds)` runs at start, off the main thread, guarded (`A/ServiceTagApp.kt:51-69`).

### B5 — the LAN client and the cleartext policy (C19–C20)

- **C19, `HomeAssistantStateClient : HaStateReader` (`A/seasonsync/`; R16-6).** Per call, on its own
  `HttpURLConnection` from the `open: (URL) -> URLConnection` seam (`UrlConnectionTransport`'s shape, `:39-45`): 
  1. `HaEndpointPolicy.check(baseUrl)` again — refused → `NoDecision(ENDPOINT_REFUSED)`, nothing opened; the INTERNET
     probe (`AppGraph.kt:631-634`) false → `NoDecision(DENIED)`, nothing opened (a quiet status, no retry);
     **1a (the eligibility check; R16-Q-D as amended, replacing rev 1.2's Wi-Fi/Ethernet point check):** in
     `HOME_NETWORK_ONLY` — **or whenever the base URL is `http`, whatever the stored eligibility (fail closed, C-5)** —
     read the current network once through C32's `CurrentNetworkReader` and require `eligibleNow` (C4a), else
     `NoDecision(NOT_ON_LOCAL_NETWORK)`, nothing opened, no retry (the reader maps a blank or whitespace-only name to
     `WifiUnnamed`). **`detail` tells the causes apart (C-3; no new kind,
     so B1a2's seventeen stand):** null — a different Wi-Fi name, mobile data or no network ("not on your home Wi-Fi",
     P16-50); otherwise the name could not be read and the check is **unconfirmed** (P16-77) with the reader's cause —
     `LOCATION_OFF` (P16-68), `PERMISSION_MISSING` (revoked, a one-time grant expired, or auto-reset; P16-78),
     `APPROXIMATE_ONLY` (P16-79), `NOT_FOREGROUND` (a read outside the foreground without the background grant;
     P16-80), `WIRED` (Ethernet has no name; P16-81). In `ANY_NETWORK` with https, step 1a passes. A seam: the JVM
     rows script it;
     **1b (an https name in `HOME_NETWORK_ONLY` only; C-9, R16-Q-B (c), DECIDED):** resolve the name through the shipped
     `HostResolver`; any answer that is not `isPrivateLanAddress` (C8 rule 5), or no answer →
     `NoDecision(NAME_NOT_LOCAL)` (or `UNREACHABLE` when resolution itself fails), nothing opened;
  2. the URL is exactly `baseUrl + "/api/states/" + entityId`; the platform parser's host must equal the policy's
     host, ASCII case-insensitively (#85 review M1), else `ENDPOINT_REFUSED`;
  3. `GET`; `instanceFollowRedirects = false`; `useCaches = false`; exactly four request properties —
     `Authorization: Bearer <token>`, `Accept: application/json`, `Accept-Encoding: identity`, `User-Agent:
     ServiceTag` (`DocumentTransport.USER_AGENT`, `C/fetch/FetchPorts.kt:23`); no cookie;
  4. connect 10 s, read idle 15 s, the whole call 30 s; the body read only on 200, capped at **64 KiB** (`truncated`
     beyond); a cancel disconnects (#85's discipline, `UrlConnectionTransport.kt:34-37`);
  5. an `SSLException` is `TLS_FAILED`, classified first (N-4; `A/fetch/TransportFailures.kt:20-22` would fold it into
     `UNREACHABLE`); every other failure through `transportFailureOf` (`:27-40`) — `UNREACHABLE`/`INTERRUPTED` →
     `UNREACHABLE`, `TIMED_OUT`, `DENIED`; then C5's mapper.
  **Never** a log line, message or `toString` carrying the URL, host, entity, Wi-Fi name or token. **Test connection**
  (R16-13) is the same call with the path `/api/`, mapped on its own (N-3): a 200 JSON object → OK; 401/403
  `AUTH_REFUSED`; 3xx `REDIRECTED`; **404 and every other status `HTTP_ERROR`**; a non-JSON 200 `MALFORMED`.
- **C20, the network security config (R16-Q-B, DECIDED).** New `app/src/main/res/xml/network_security_config.xml`:
  one `<base-config cleartextTrafficPermitted="true">` with `<trust-anchors><certificates src="system"/></trust-anchors>`;
  no `<domain-config>` (a runtime host cannot be named), no `src="user"`, no debug override.
  `android:networkSecurityConfig="@xml/network_security_config"` on `<application>`
  (`app/src/main/AndroidManifest.xml:58-62`). The manifest comment "Its one intentional outbound use is Save as
  document (#85)" (`:12`) is restated to name both outbound uses; `UrlConnectionTransport`'s KDoc "the only class in
  the app that opens an HTTP connection" (`:21`) is restated (comment-only). **Cleartext is global once permitted
  (H8):** #85 stays https-only by code (`HopPolicy.kt:28`), pinned by row 14. The only permissions added are C32's
  three, by B5.

### B6a / B6b — the orchestration (C21–C23)

- **C21, `SeasonSyncRunner` (`A/seasonsync/`; R16-7).** One per `AppGraph`, holding one `Mutex` (single flight; the
  `apiLongWrites` precedent, `AppGraph.kt:638-643`). For each binding it reads: snapshot `(binding, revision,
  connection.baseUrl, SecretStore.get)` → `HaStateReader.read` → `RecordSeasonSyncResult(assetId, revision, startedAt,
  outcome)` — only `ACTIVE` bindings (C17); an inert one is neither read nor written; an `ACTIVE` one whose
  `SecretStore.get` is null records `NoDecision(NEEDS_TOKEN)` with no request. Entry points:
  - `syncNow(assetId: AssetId? = null)` — waits for the lock, then reads (one asset, or every active binding);
  - `refreshIfStale(now)` — if the lock is held it returns (the running pass is fresh); else reads the active bindings
    that are **stale: `last_success_at` null or at least one cadence (`connection.cadence.hours`) old** (R16-Q-D as
    amended — staleness follows the cadence); at most one attempt per resume, no other throttle;
  - `runAll()` — the worker's: waits for the lock, reads every active binding; in `HOME_NETWORK_ONLY` each request
    still passes C19 step 1a first, so an unconfirmed or different network sends nothing (R16-20 as amended).
  Correctness never rests on the lock (C13's revision does); the lock only spares duplicate GETs.
- **C22, the worker, its seam and the start-up reconcile.** `SeasonSyncWorker : CoroutineWorker`, reached through a
  `@Volatile` dispatch object assigned synchronously in `ServiceTagApp.onCreate` (`A/ServiceTagApp.kt:32-40`, the
  `ReminderRunDispatch` precedent). Unique periodic work `"season-sync"`, **period = the connection's cadence**
  (`SyncCadence.hours`: 12 h, 24 h, 7 d, 30 d — R16-Q-D as amended), `Constraints(requiredNetworkType = CONNECTED)`,
  `BackoffPolicy.EXPONENTIAL` from 5 minutes, enqueued with **`ExistingPeriodicWorkPolicy.UPDATE`** (work 2.11.2,
  `gradle/libs.versions.toml:24`) so a changed cadence replaces the old period and no obsolete interval survives (the
  owner's words). **When work is enqueued (R16-20 as amended):** `ANY_NETWORK` (https) — yes;
  `HOME_NETWORK_ONLY` with `backgroundChecks = OFF` — **no**; with `ON` — **only while Android grants what C32 says a
  background read needs** (precise location, plus `ACCESS_BACKGROUND_LOCATION` on API 29+). Otherwise the connection
  runs **Off's behaviour** and C26/C27 draw the paused status (P16-75) with "Allow again" (P16-76) — a refusal or a
  later withdrawal never stops the sync. The grant is re-read at start-up, on every resume (C23) and after each
  command, so a withdrawal made in Settings cancels the work on the next start or resume.
  **Result:** `success()` for every recorded outcome and an unassigned dispatch, `retry()` only for a local database
  failure, cancellation propagates (not the backstop's blanket `retry`, `A/reminders/BackstopWorker.kt:39-49`, H6).
  Seam `SeasonSyncWork` (`isEnqueued`, `ensure`, `cancel`; `A/reminders/ReminderHealthCheck.kt:72-94`'s shape). At
  start, off the main thread: `ensure()` when any binding is enabled and the rule above says yes, else `cancel()`;
  `KeystoreSecretStore.sweepOrphans` (C18). Every C16/C17 command that changes "any enabled", the cadence, the
  eligibility or `backgroundChecks` calls `ensure()` or `cancel()` after its commit.
- **C23, the foreground refresh.** A second `LifecycleResumeEffect` beside reader mode's (`A/ui/nav/ServiceTagRoot.kt:106-110`)
  launches `refreshIfStale(now)` on the graph's scope, never blocking or drawing. **Seam (N-8):** `fun interface
  ResumeRefresh`, provided by `AppGraph`, is a parameter of `ServiceTagRoot` (`:71`) defaulting to `graph.resumeRefresh`,
  so its callers compile unchanged and B8c passes a fake. No hook elsewhere; no opportunistic network callback.

### B7a — the API (C24)

- **C24, the route, the 409 and the document.** `"season-sync" to "GET" -> handlers.seasonHealth.getSeasonSync(rest[1])`
  after the twenty-seventh sub-resource (`A/api/ApiRouter.kt:264-265`; KDoc "#16 — the twenty-eighth … read only";
  other verbs fall to the shipped 404). `SeasonHealthHandlers` (`A/api/SeasonHealthHandlers.kt:46-62`) gains the two
  repositories and the store (its graph constructor and `seasonHealthHandlersFor`, `T/api/MaintenanceFixtures.kt:78`,
  arguments only) and answers C3 from a new `SeasonSyncDtos.kt` — `SecretStore.has` only, never `get`, never `baseUrl`
  or the Wi-Fi name. The route-count KDoc (`ApiRouter.kt:103`) reads "Eighty-two path shapes over one hundred and two
  method-and-path rows"; `SEASON_SYNC_ENABLED` is one arm beside `ApiJson.kt:509-511`. `docs/api/v1.md`: "twenty-eight
  `/v1/assets/{id}/…` sub-resources"; ", 21 since #16 (Home Assistant season sync)" on the schema clause only
  (`:213-217`); a "Home Assistant season sync (#16)" section (C3, read-only, "no address, no token, no write"); the
  code in the season codes table; the INTERNET paragraph (`:38-41`) names both outbound uses (audit correction 4).

### B7b — the MCP (C25)

- **C25, `get_season_sync(asset_id)`** — one GET of the C24 route, its body returned as is;
  `_MIN_SEASON_SYNC_SCHEMA_VERSION = 21` beside `_MIN_RESOURCE_OWNER_SCHEMA_VERSION` (`M/src/servicetag_mcp/server.py:247`),
  applied before any request (G2's feature name). Appended to `TOOL_NAMES` as a new "#16" block after the installed
  components (the shipped convention: each block's comment names the new total, `server.py:175`) — **90 tools**. The
  docstrings of `start_season`, `end_season` and `set_season_mode` (`server.py:2380`, `:2398`, `:2414`) name
  `SEASON_SYNC_ENABLED`. No other tool changes; no write tool.

### B8a / B8b1 / B8b2 — the phone (C26–C27)

- **C26, the Home Assistant screen.** Settings → **Utilities** gains a fifth row "Home Assistant" (P16-1) after
  "Categories" (`A/ui/settings/SettingsScreen.kt:241-261`), pushing `Route.HomeAssistant` (the `Route.DeveloperApi`
  precedent, `A/ui/nav/Route.kt:113-118`; no deep link). The screen: **Server address** (P16-2, helper P16-3,
  `KeyboardType.Uri`), **Access token** (P16-4, helper P16-5) — masked with `PasswordVisualTransformation`,
  `KeyboardType.Password`, autocorrect off, **no reveal toggle**, never pre-filled; after a save the field empties and
  P16-48 shows; **Save** (reused), **Test connection** (P16-6 → P16-7 or the outcome's sentence), **Disconnect**
  (P16-8, confirm P16-9 with **Cancel** reused), the not-connected line P16-10 and NEEDS_TOKEN's P16-11. Its view
  model's state holds `tokenPresent: Boolean`, never the token text after the save call returns. **Settings (rev 1.3–1.4):**
  "How often to check" (P16-53: P16-54…57, Once a day preselected); "Where to check" (P16-58: P16-59 with helper P16-61,
  and P16-60); under P16-60 only, "Background checks on this home network" (P16-70: P16-71 Off preselected, P16-72 On).
  - **Foreground flow** (choosing P16-60): P16-65, then the system request for precise location; granted →
    "Use the network I'm on now" (P16-62) captures the Wi-Fi name (P16-63; a blank one captures nothing, C17's refusal); denied or approximate → P16-66, the option
    stays off; at capture Location off → P16-68, Ethernet → P16-81, mobile data or none → P16-82. "Any network" asks
    nothing. An http address with "Any network" → P16-67.
  - **Background flow** (choosing P16-72, after the foreground grant): P16-73 with Android's own label for the option
    (`getBackgroundPermissionOptionLabel()`), **Cancel** (reused) to decline, then the request — on API 29 the system
    dialog, on 30+ Android's settings page (**Open app settings**, reused); API 26–28 need nothing more. Not granted →
    P16-75 and the connection runs Off's behaviour; P16-69 states Off's behaviour whenever background checks are off.
  - **Recovery:** P16-75 (background withdrawn) and P16-78 (precise location withdrawn, a one-time grant expired or
    auto-reset) carry **"Allow again"** (P16-76), which re-runs the matching flow; P16-79 carries Open app settings.
- **C27, the season card and the editor.** In `SeasonSection` (`A/ui/asset/AssetDetailScreen.kt:1192-1222`) — the
  binding block is drawn **on every mode's branch**, not only MANUAL's (C-4, R16-19), so a stopped binding on an asset
  moved to CALENDAR or YEAR_ROUND stays visible with its Resume:
  - **no binding, a connection exists, the asset writable and maintained here** → the shipped controls stay (MANUAL's
    Start/End, the CALENDAR window, YEAR_ROUND's line) and a **Link to Home Assistant** action (P16-22) is added (C16
    reconciles CALENDAR and YEAR_ROUND); with no connection, nothing is added;
  - **an enabled binding** → `PhaseBadge` stays (the effective season, reused); the Start/End button is replaced by
    the three-way mode control (P16-23…25); the source line (P16-27, or P16-28/29 when forced); **Sync now** (P16-26);
    "Last successful check" (P16-30) or P16-31; the stale marker (P16-32) when `last_success_at` is null or at least
    one cadence old (the owner's definition, rev 1.3; it replaces N-12's attempt-keyed marker); HA's `last_changed` as information (P16-33); the provenance line (P16-34/35 from Home Assistant, P16-83/84 when forced — C33(4), rev 1.5); the latest error's sentence (`NOT_ON_LOCAL_NETWORK`: P16-50, or P16-77 then
    the cause's remedy and action, C19, C26)
    (§5's table) on its own line — **never in place of** the last-success line; **Stop syncing** (P16-39);
  - **a stopped binding** → P16-41 and **Resume syncing** (P16-40) beside the mode's shipped controls; on a
    non-MANUAL asset Resume opens the setup sheet's reconciliation step (C17) before anything is written;
  - the setup sheet: **Entity ID** (P16-42, helper P16-43), the reconciliation sentence for the asset's mode
    (P16-44/45/46), **Save**/**Cancel** reused; a strands refusal shows S55 and keeps the sheet open; #78's prompt
    (P78-1a/1b/2/3) after a YEAR_ROUND link with live CONTINUOUS schedules, decided by `liveContinuousCount`, lifted to
    `C/usecase/SeasonCommands.kt` from the editor's `reconcilePromptFor` (`AssetViewModels.kt:2609-2617`), which then
    calls it (behaviour unchanged; #78's tests green);
  - the editor's `OperatingSeasonBlock` (`A/ui/asset/AssetEditScreen.kt:448`) is drawn read-only with P16-47 while
    the binding is enabled; its other blocks are as shipped.
  Season history (`AssetDetailScreen.kt:1225-1233`) is unchanged and lists HA-applied rows like any other.

### B8c, B9, B10 and cross-cutting (C28–C31)

- **C28, drawn (Compose).** `AT/seasonsync/SeasonSyncScreensTest` (~11 cases, budget 14): the masked field
  (`SemanticsProperties.Password`), the Settings row, the mode control in place of Start/End, Sync now, the status
  lines side by side, the stale marker, the setup sheet's sentence and S55, the editor's read-only block, a stopped
  binding on a CALENDAR asset, the resume hook calling `refreshIfStale` on a fake (C23's seam).
- **C29, the platform proof.** `AT/seasonsync/SeasonSyncPlatformProofTest` (~10 cases), facts no JVM test can see:
  one `"season-sync"` work with `NetworkType.CONNECTED` and the cadence's period read back off `WorkInfo`
  (`ReminderPlatformDeviceProofTest.kt:110-128`'s template) — DAILY, then WEEKLY after a change (UPDATE: one work, the
  new interval) — and `cancel()` finishing it; a Keystore round trip leaving only ciphertext under
  `noBackupFilesDir/ha-secrets/`, a deleted key reading null; cleartext permitted by `NetworkSecurityPolicy`; C32's real
  reader naming the emulator's Wi-Fi — expected `AndroidWifi`, unverified until here — **preconditions (N-2):** precise
  location granted (`GrantPermissionRule`, while-in-use), Location switched on through `UiAutomation`, the read made
  from an activity scenario in the foreground; a `WifiUnnamed` there is a stop, never a rerun; **the background path
  (R16-20 as amended):** with `ACCESS_BACKGROUND_LOCATION` also granted (`UiAutomation`), `ensure()` enqueues the work
  for `HOME_NETWORK_ONLY` + `ON` and the worker body, run by `TestListenableWorkerBuilder` (`work-testing`), reads the
  name and proceeds on a match and sends nothing on a mismatch; without the grant nothing is enqueued (the fallback). A
  withdrawal kills the process, so it is §7's step, and a truly backgrounded phone is limit 19's; one GET through the
  real client, with a scripted eligible network, to a fake HA on a `ServerSocket` in the instrumentation process at
  this device's own site-local IPv4 address (read at run time), carrying `Authorization: Bearer fictional-token-1`,
  answering `on` → `Observed(ON)`; a 302 → `REDIRECTED`, its target untouched. Teardown cancels `"season-sync"` and
  deletes the test's key and file (N-15).
- **C30, the documents.** `docs/release-proofs.md`: a schema-21 paragraph beside the schema-20 one (`:135`), naming both tables and
  `season_sync_binding.last_applied_source` (C33, rev 1.5) — its
  checks include 18–20's; nothing seeded for #16 on the older side; after the upgrade `schemaVersion` 21, format 20,
  `counts` unchanged, the pre-upgrade export re-plans `IDENTICAL` with zero INSERT, and an export after linking carries
  no `ha_`, entity, address or Wi-Fi-name byte; all on `emulator-5554` with the signed candidate (N-14), where a
  connection to the unreachable `http://192.168.0.10:8123` (home-network mode, the emulator's Wi-Fi captured) answers
  Sync now `UNREACHABLE` or `TIMED_OUT` with no row; following on and off is R2's and §7's, never a signed-build step.
  `docs/design/09-security-privacy.md`: the HA token row (D9's Todoist row as template; "revoked on disconnect:
  deleted locally; the owner revokes it in HA") and the location permissions' narrow, opt-in use (C32, R16-20);
  `docs/design/03-target-architecture.md:30`: `SecretStore` "built for #16"; beside D3 `:445` and D8's S7, the
  no-backup note (N-1). New `docs/home-assistant-season-sync.md`: the fictional helper and automation, the dedicated
  non-admin user and token, the address rule, the cadence and the two network options, "the season helper, never the
  appliance's switch" (AC2, H10), every value fictional (`input_boolean.example_heater_in_season`,
  `http://192.168.0.10:8123`, `https://ha.example:8123`, Wi-Fi `ExampleHomeWifi`). `README.md:80` names both outbound
  uses and the Home Assistant screen; `:83` and new lines list C32's permissions — location only for "Only on this
  home Wi-Fi" (P16-60 as amended, R16-Q-I), background location only for its background checks, `ACCESS_WIFI_STATE` (N-16). `docs/versioning.md` and `docs/releases/` untouched.
- **C31, the fence, as greps** (every brief; anchored, over the brief's diff): (1) #69's C33(1) tripwire as written;
  (2) `git diff <base> -U0 -- app/src/main core/src/main tools/servicetag-mcp/src | grep -ciE
  '^[+].*\b(telemetry|measurement|meter|sensor_reading|mqtt|websocket|influx|grafana)\b'` → 0; (3) `git diff <base>
  -U0 -- app/src/main | grep -cE '^[+].*(ServerSocket|startForeground|ForegroundService|registerNetworkCallback|WebView)'`
  → 0 (C32's one-shot `registerDefaultNetworkCallback` is the only callback, read it); (4) `git diff <base> --
  C/model/SeasonModel.kt C/schedule C/backup C/merge C/transfer` → empty; (5) `git diff <base> -U0 -- app/src/main
  core/src/main | grep -ciE '^[+].*Log\.[a-z]+\(.*(token|secret|bearer|base_?url|ssid)'` → 0; (6) `git grep -nE
  'setRequestProperty\("Authorization"' -- app/src/main` → 1.

### C32 — the Android mechanism audit and the home-network identity (rev 1.3–1.4; B5 reads, B8a asks)

**This app:** `minSdk = 26`, `targetSdk = 36` (`app/build.gradle.kts:41`, `:44`); the manifest declares only NFC,
INTERNET, POST_NOTIFICATIONS and RECEIVE_BOOT_COMPLETED (`app/src/main/AndroidManifest.xml:3`, `:13`, `:18`, `:22`);
WorkManager merges in `ACCESS_NETWORK_STATE` (`README.md:83`), which B5 also declares (N-7).

| API level | the connected Wi-Fi's name | what it needs |
|---|---|---|
| 26–30 | `WifiManager.getConnectionInfo().ssid` (C-1: `WifiInfo` rides `NetworkCapabilities` only from 31) | `ACCESS_WIFI_STATE` and precise location, Location on; from 29 a background read also needs `ACCESS_BACKGROUND_LOCATION` |
| 31–37 | a one-shot `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` on the default network → `transportInfo as WifiInfo` | the same; without the flag no location information is sent "even if the app holds the necessary permissions" |

**Verified** (developer.android.com, 2026-10-03): `WifiInfo`'s location-sensitive fields need "the same permissions as
`WifiManager.getScanResults`", else `UNKNOWN_SSID` and BSSID `02:00:00:00:00:00` (`/reference/android/net/wifi/WifiInfo`,
whose `TransportInfo` methods are API 31); `getScanResults` needs `ACCESS_WIFI_STATE` and `ACCESS_FINE_LOCATION`, on
Android 13+ too, and `getConnectionInfo` moved to `getTransportInfo()` at API 31 (`/reference/android/net/wifi/WifiManager`;
`/develop/connectivity/wifi/wifi-permissions`); `FLAG_INCLUDE_LOCATION_INFO`, API 31, checks the permission and the
Location toggle (`/reference/android/net/ConnectivityManager.NetworkCallback`); `NEARBY_WIFI_DEVICES` covers hotspot,
Wi-Fi Aware, P2P and RTT, **not** the connected name — not used; WorkManager tasks are background location access,
needing `ACCESS_BACKGROUND_LOCATION` from API 29 (`/develop/sensors-and-location/location/background`); **on API 29
the system dialog offers "Allow all the time"; on 30+ it does not — the owner grants it on the app's settings page,
after an educational screen naming the option by `getBackgroundPermissionOptionLabel()` and offering a decline; an
approximate foreground grant stays approximate in the background; the system reminds the owner of background
access** (`/develop/sensors-and-location/location/permissions/background`); targetSdk 36 keeps LAN access through
INTERNET and must not request `ACCESS_LOCAL_NETWORK` (`/privacy-and-security/local-network-permission`; Phase 7's bump
adds it, H11). **Ethernet has no readable name** and a VPN default network carries no `WifiInfo` (N-1). **Unverified,
proven by B9 (row 71):** the emulator's Wi-Fi reads `AndroidWifi`. **Not provable on the gate (limit 19):** a truly
backgrounded phone's worker; an approximate grant hiding the name (B8a treats approximate as not granted).

**The decision (R16-21, R16-20 as amended):**
- **Identity:** the Wi-Fi name only, captured by "Use the network I'm on now" (P16-62); Ethernet, mobile data or no
  network → nothing captured (P16-81, P16-82); never a BSSID, an address, a location, a time or a wired marker (C-2).
- **The reader** (`CurrentNetworkReader`, B5) answers `CurrentNetwork` plus, for `WifiUnnamed` and `Wired`, the cause
  C19 records (`LOCATION_OFF`, `PERMISSION_MISSING`, `APPROXIMATE_ONLY`, `NOT_FOREGROUND`, `WIRED`; C-3). Revoked, an
  expired one-time grant and auto-reset all read as `PERMISSION_MISSING`.
- **Permissions — the owner's principle, verbatim: "Background location / nearby-Wi-Fi capability is optional and
  owner-controlled, not forbidden and not mandatory."** Declared in the manifest, which grants nothing:
  `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (asked together, as API 31+ requires; approximate counts as not
  granted) **only when the owner picks "Only on this home Wi-Fi"** (P16-60 as amended); `ACCESS_BACKGROUND_LOCATION` **only
  when the owner turns Background checks on**, after the foreground grant (API 29+; 26–28 need nothing more);
  `ACCESS_WIFI_STATE` (install-time, no `maxSdkVersion`, C-1). "Any network" asks for nothing. No
  `NEARBY_WIFI_DEVICES` and no `ACCESS_LOCAL_NETWORK`.
- **At request time** C19 step 1a reads through the same reader (the one-shot callback unregistered on its first answer
  or after 2 s); background work reads only with the background grant, else it is not enqueued (C22).
- **A permission-free alternative** replaces this only if it authenticates the intended endpoint — "Gateway/subnet/
  reachability heuristics or 'some Wi-Fi is active' are not sufficient" (the owner). Home Assistant's mDNS
  `_home-assistant._tcp` TXT `uuid` is recorded as a **later candidate that does not meet that bar in v1**: spoofable on
  a hostile LAN, unverified against HA's documentation, and dependent on multicast (C-6).

**The invariants (D9's and C32-style statement in C30's documents; each has rows):** **I1** HA drives a MANUAL asset
only, through the shipped operation, dated today; **I2** a transition only when the desired phase differs from
today's phase; **I3** every non-`on`/`off` outcome is no decision; **I4** a failure never advances
`last_success_at`; **I5** a result read at an older revision writes nothing; **I6** a FORCE mode is re-asserted on every
run and never undone by a poll, and FOLLOW never applies a stored observation (C-5); **I7** while enabled, the binding
is the only writer of the asset's season (four writers refused, the offer withheld, the legacy pair's shipped 422); **I8** an asset not maintained here takes no decision; **I9** the token is only in the store and one
request; **I10** the bearer goes to the configured origin only; in the home-network mode only on the captured
network and to a name only when it resolves to private addresses; http only in that mode (C4a, C19, C32); **I11** the binding and the connection are in no ServiceTag backup, export, merge or pack (Auto Backup may restore
them inert, R16-Q-F);
**I12** a restored or imported configuration sends nothing before a token is entered here.

### C33 — the provenance column and the G5 line (rev 1.5; R16-Q-H, R16-Q-J; B2b; #16's own, not #69's C33)

Provenance **(a)**: a forced application is never labelled as one from Home Assistant. Every site read on `35befab3`.
- **C33(1), the model.** `enum class LastAppliedSource { HOME_ASSISTANT, FORCED_IN, FORCED_OUT }`;
  `SeasonSyncBinding.lastAppliedSource: LastAppliedSource?` after `appliedAt`, **no default** (B1a's rule; null =
  nothing applied); the pure `appliedSourceOf(mode)`: FOLLOW → `HOME_ASSISTANT`, FORCE_IN → `FORCED_IN`, FORCE_OUT →
  `FORCED_OUT`, exhaustive, no `else`. Three values, not `HOME_ASSISTANT | FORCED`: the source is the image of the
  applying mode, so the API and the phone each need one lookup; `FORCED_IN` only ever goes with START and `FORCED_OUT`
  with END (row 76). **I13:** the four `applied*` fields are all null or all set, and only `applyIfChanged`'s `copy` sets them.
- **C33(2), the column (inside schema 21; R16-4 stands).** `season_sync_binding.last_applied_source TEXT` (nullable,
  the enum's name) after `applied_at`: the entity; `MIGRATION_20_21`'s binding `CREATE TABLE`, byte-equal to the
  regenerated `21.json` — **both change in place, 21 being unreleased** (no new step, no version); the DAO's
  `updateAtRevision` SQL and parameter (`SeasonSyncBindingDao.kt:22-49`); the two mappers and the update call
  (`SeasonSyncRepositories.kt:92`, `:113`, `:40`). `20.json`, `V21_TABLES` and the nine table sets do not move (a
  column is not a table). **No format change** (C9, R16-Q-E). A database from an earlier schema-21 build fails Room's
  identity check, so the gate and §7's proof start from a fresh install.
- **C33(3), the applier.** `applyIfChanged`'s one `copy` (`RecordSeasonSyncResult.kt:124`) also sets
  `lastAppliedSource = appliedSourceOf(binding.mode)`, `binding` being its argument: `run` passes `read` (the stored
  mode: FOLLOW → `HOME_ASSISTANT`, a FORCE re-assertion → `FORCED_*`; `:96-97`); `SetSeasonSyncMode` passes `moded`
  (the new mode → `FORCED_*`; `SeasonSyncCommands.kt:94-97`, so no B3c or `C/usecase` main line changes; row 77 pins
  it). No row — an agreeing season, "already applied", an apply error, FOLLOW + `NoDecision` — leaves the four fields
  as they were; Link writes null; Edit, Stop, Resume and choosing Follow leave them (a forced source stays until Home
  Assistant applies a change).
- **C33(4), the phone's line rule (core, pure).** `enum class AppliedLine { STARTED_FROM_HOME_ASSISTANT,
  ENDED_FROM_HOME_ASSISTANT, FORCED_IN_SEASON, FORCED_OUT_OF_SEASON }`, `appliedLineOf(binding): AppliedLine?`:
  `HOME_ASSISTANT` by the action (P16-34/35), `FORCED_IN` → P16-83, `FORCED_OUT` → P16-84, null → no line; dated
  `appliedOn`. B8b1 maps it in `SeasonSyncStrings.kt` by an exhaustive `when`; `A/ui` never re-derives it.
- **C33(5), the API — its only change.** `SeasonSyncAppliedDto` (`A/api/SeasonSyncDtos.kt:75`) gains `source` (the
  enum's name); `lastApplied` shows only when all four fields are set (`:98-99`); `docs/api/v1.md:1849` names it; C3
  as amended. The MCP passes it through (B7b): one docstring line (`server.py:4318`); `M/tests` unchanged.
- **C33(6), G5 (R16-Q-J).** One `const val` beside `KEY_SWEEP_FAILED` (`A/seasonsync/SeasonSyncRunner.kt:269`), logged
  at B6a's four silent sites (its concern 2 and MINOR-1): a command's fresh read and the resume refresh, both through
  `launchQuietly`'s catch (`:165-171`, `:223-225`), by a new trailing runner parameter `log: (String) -> Unit`
  defaulting to `Log.w` (the `SeasonSyncWorkerBody` precedent: no `AppGraph`/`FakeGraph` edit; the JVM's `Log` returns
  defaults, `app/build.gradle.kts:100`); the start-up reconcile (`:259-265`) and the worker's catch-all
  (`SeasonSyncWorker.kt:57-62`) through their existing `log`. The constant alone — no URL, host, entity, Wi-Fi name,
  token or exception; a cancellation propagates unlogged; each site's result is as shipped.

## 3. Test matrix

Rows: hazard · class · cases · counted RED. App in `T/`, core in `CT/`, MCP in `M/tests/`; "pin" = a shipped
assertion moved or re-run, no RED; **JVM** unless **Compose** or **Device** (first run at the merged-tip gate). Case
names are the implementer's; what each proves is not.

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C5 exact on/off (AC1, I3) | `CT/seasonsync/HaStateMapperTest` (new) · `onAndOffExactlyAreObservations`; `caseVariantsAndSynonymsAreUnsupportedNamingTheState` (`On`, `ON`, `" on"`, `true`, `1`); `unknownUnavailableAndEmptyAreUnsupported` | `state.equals("on", ignoreCase = true)` |
| 2 | C5 statuses (AC5) | same · `401And403AreAuthRefused`; `404IsEntityNotFound`; `everyThreeHundredIsRedirected` (301, 302, 307, 308); `otherStatusesAreHttpErrorWithTheCode` (500, 502, 503, 418) | 403 mapped to `HTTP_ERROR` |
| 3 | C5 malformed (AC5) | same · `nonJsonWrongTypeTruncatedOrStatelessIsMalformed`; `aNonStringStateIsMalformed` (`true`, `1`, `null`) | `truncated` ignored (the 64 KiB-and-more answer parses) |
| 4 | C5 bounds | same · `detailAndLastChangedAreBoundedAndControlFree` (a 10 000-character state; a `\n` inside) | the 64-character bound removed |
| 5 | C5 the answer names the entity | same · `anotherEntitysAnswerIsMalformed` | the `entity_id` compare removed |
| 6 | C6 effective season (AC6) | `CT/seasonsync/DesiredPhaseTest` (new) · `forceInAndForceOutIgnoreTheRead`; `followMapsOnAndOff`; `followWithoutAFreshObservationDecidesNothing` | FOLLOW with no read answers `OUT_OF_SEASON` |
| 7 | C4 the entity id is one segment | `CT/seasonsync/SeasonSyncModelTest` (new) · `aLowercaseDomainDotObjectIdIsAccepted`; `slashesDotsEscapesCapitalsAndEmptyPartsAreRefused` (`a/b.c`, `../x`, `input_boolean.`, `Input_Boolean.x`, `a.b%2e`, 256 characters) | `/` admitted |
| 8 | C4 no printable secret (I9) | same · `secretsToStringIsConstant`; `noModelTypeHasASecretField` (reflection over `C/seasonsync`) | `Secret` as a plain `data class` |
| 9 | C7 the doubles cascade (#69 C11) | `CT/testing/SeasonSyncDoubleTest` (new) · `anAssetDeleteThroughBackupInstallTakesItsBinding`; `aConnectionDeleteTakesItsBindings`; `aStaleRevisionUpdateAnswersFalse` | the cascade registration line removed |
| 10 | C8 http to private literals (R16-Q-B) | `CT/seasonsync/HaEndpointPolicyTest` (new) · `theCanonicalHttpFixtureIsAllowed` (`http://192.168.0.10:8123`, C-7); `httpToEachPrivateRangesEdgesIsAllowed` (10.0.0.1, 10.255.255.254, 172.16.0.1, 172.31.255.254, 192.168.0.1, 192.168.255.254; with and without a port) | 172.16.0.0/12 written as /16 (172.31.255.254 refused) |
| 11 | C8 http refused elsewhere | same · `httpToANameIsRefused` (`http://ha.example:8123`); `httpToEveryOtherIpv4IsRefused` (`192.0.2.10` documentation, 172.32.0.1, 11.0.0.1, 127.0.0.1, 169.254.1.1, 100.64.0.1 CGNAT, 0.0.0.0, 255.255.255.255); `httpToAnIpv6LiteralIsRefused` | the private test reads `AddressPolicy`'s deny-list inverted (127.0.0.1, 169.254.1.1, 100.64.0.1, 0.0.0.0 and 255.255.255.255 pass; `192.0.2.10` is still refused, since `AddressPolicy` allows the documentation ranges, `C/fetch/AddressPolicy.kt:29-30` — N-2) |
| 12 | C8 https | same · `httpsToANameOrAPrivateIpv4LiteralIsAllowed`; `httpsToAPublicLiteralIsRefused` (C-9); `isPrivateLanAddressAcceptsRfc1918AndFc00Only` (fd00::1 in; fe80::1, 2001:db8::1, 192.0.2.10 out); `localhostAndDotLocalhostAreRefusedUnderBothSchemes` | the localhost check removed |
| 13 | C8 the rest of the URL | same · `userinfoPathQueryAndFragmentAreRefused`; `oddAuthoritiesAreRefusedByTheSharedAllowlist` (`%`, a Unicode host, `:0`, `:65536`, `:08123`); `canonicalLowercasesAndDropsTheSlash` | a path admitted |
| 14 | H8, #85 untouched | `CT/fetch/HopPolicyTest` (+1) · `httpToAPrivateLiteralIsStillNotHttps`; every shipped `HopPolicyTest` / `AddressPolicyTest` / `FetchDocumentTest` case green unchanged | `staticProblem` admits http when C8 would |
| 15 | C9 the tables | `T/data/room/Migration20To21Test` (new) · `bothTablesExistEmptyWithKeysForeignKeysAndTheIndex`; `everyV20TableIsUnchanged` (snapshots, the #47 shape); `theMigratedSchemaEqualsAFreshVersion21` | the binding's asset key written `ON DELETE NO ACTION` (the schema compare fails) |
| 16 | C9 cascade on Room | `T/data/room/SeasonSyncDaoTest` (new) · `deletingAnAssetTakesItsBinding`; `deletingTheConnectionTakesItsBindings`; `oneBindingPerAsset` | the connection key written without CASCADE |
| 17 | C9, C11 no token column; round trip (I9) | same · `aBindingRoundTripsEveryField`; `noColumnOfEitherTableIsATokenOrSecret` (`PRAGMA table_info` names); `theConditionalUpdateAnswersFalseOnAStaleRevision` | the mapper drops `revision` |
| 18 | C10 schema pins | §3's B2 pins | none: pins |
| 19 | C10 the nine table sets | §3's B2 twins (audit correction 1) | none: pins |
| 20 | C9 never travels (I11, R16-Q-E) | `T/backup/SeasonSyncNeverTravelsTest` (new, Room-backed `FakeGraph`) · `anExportCarriesNoConnectionBindingEntityOrAddressByte`; `aReplaceRestoreRemovesBindingsAndKeepsTheConnection`; `aMergeLeavesTheBindingAndItsRevision`; `aTransferPackCarriesNeither` | none: tripwires (row 16's RED covers the restore's cascade) |
| 21 | C12 the body split | `CT/usecase/ManualSeasonTest` (7), `SeasonEventOfferTest`, `T/ui/asset/AssetSeasonActionsTest` (9) green unchanged | none: pins |
| 22 | C13 apply (AC1, I1) | `CT/seasonsync/RecordSeasonSyncResultTest` (new) · `onOverOutOfSeasonWritesOneStartDatedToday`; `offOverInSeasonWritesOneEnd`; `theRowIsTheShippedOperationsRow` (`eventId` null, `createdAt` the clock); `haLastChangedNeverDatesTheRow` | `occurredOn` taken from `haLastChanged` |
| 23 | C13 idempotency (AC2, AC4, I2) | same · `theSameOnTenTimesWritesOneRowAndOneProvenance`; `offOnAManualAssetWithNoRowsWritesNothingAndRaisesNothing`; `aFreshApplierOverTheSameStateWritesNothing` (process recreation) | `withApplied` called on every FOLLOW observation (the provenance time moves on each repeat; the two 409 arms are defensive and have no RED; the compare's own RED is row 30's) |
| 24 | C13 concurrency (AC4, AC6) | same · `twoIdenticalOverlappingResultsWriteOneRow`; `anOlderResultArrivingLastIsDropped` (A reads OFF, B reads ON at the same revision; B applies a START first; A must not write an END) | the revision compare removed (A writes the END: two rows, the phase flipped by a stale read) |
| 25 | C13 stale result (AC6, AC8, I5) | same · `aResultReadBeforeAModeEntityStopResumeOrAddressChangeWritesNothing` (each edit, then the old result); `aResultAtAStoppedBindingsCurrentRevisionWritesNothing` (synthetic: pins the defensive `enabled` check, C13) | the revision compare removed (the old result lands after the edit; C-6 — Stop bumps the revision, so removing the `enabled` check alone cannot fail the first case) |
| 26 | C13 lifecycle (AC8, I8, R16-8) | same · `archivedRetiredHeldAssetsTakeNoDecisionAndShowNotMaintainedHere`; `aDeletedAssetsResultIsDropped` | the `maintainedHere` check removed (an archived asset gains a START: the body checks neither ARCHIVED nor retired, audit fact 6) |
| 26a | R16-18 a binding resumes on its own (C-11) | same · `anUnarchivedAssetsStillEnabledBindingAppliesTheCurrentStateOnceOnTheNextRead` (archived while HA went off, on and off; unarchived; the next read writes at most one row, dated that day); `aWithdrawnOutStaysInertUntilUnarchived` | none: pins the chosen rule (row 26's mutation covers the check itself) |
| 27 | C13 replacement (R16-8) | same · `aReplacedAssetsBindingStaysInertOnThePredecessorAndTheSuccessorHasNone` (through `ReplaceAsset`) | none: row 26's mutation covers it |
| 28 | C13 status (AC5, I4) | same · `aFailureNeverAdvancesLastSuccess` (each `SyncErrorKind` from C5/C19); `anUnchangedOnAdvancesLastSuccessAndClearsTheError`; `observationFetchAndApplicationTimesAreThreeFields` | the failure path writes `last_success_at` |
| 29 | C13 FORCE survives polls (AC6, I6) | same · `aForcedBindingRecordsTheObservationAndIgnoresIt` (both FORCE modes, both answers: the observation is stored, the forced phase stands); `aForcedPhaseIsReassertedOnANoDecisionRun` | the observation applied in a FORCE mode |
| 30 | C13 the clock moved back (H4) | same · `aDateBeforeTheLatestRowIsAStatusNotAThrow`; `aDateBeforeTheLatestRowWithAnAgreeingReadRecordsNoError` (C-6) | `SeasonValidation` rethrown; **and** the compare removed (the agreeing read reaches the body, which throws `SeasonDateOutOfRange` before its 409s, `RecordSeasonActivation.kt:56-60`, so DATE_BEFORE_HISTORY is recorded where none belongs) — two counted |
| 31 | C14 writes nothing else (AC7) | same · `anAppliedStartWritesOneRowOneBindingUpdateAndRecomputesOnly` (counting doubles: no event, closure, condition, case, schedule, asset write — the `ApiReadsWriteNothingTest` idiom) | the applier also upserts the asset's `updatedAt` |
| 32 | C14 policy semantics (AC7) | `CT/seasonsync/SeasonSyncPolicyTest` (new) · `inServiceGoesDormantAndReentersAcrossAnHaEndThenStart`; `continuousIgnoresIt`; `noBacklogAcrossAnHaDormancy`; `theBreakStillHolds` — through the shipped evaluator on rows the applier wrote | none: the shipped engine suites (`NoBacklogAcrossDormancyTest`, `ServicePolicyEvaluatorTest`, `BreakTest`) are the mutation targets; this row proves the HA path reaches them |
| 33 | C15 the guard (I7, R16-1) | `CT/seasonsync/SeasonSyncAuthorityTest` (new) · per writer: `recordSeasonActivation`, `acceptSeasonOffer`, `setSeasonModeChange`, `saveAssetSettingsModeChange` each `SeasonSyncOwnsSeason` with nothing written; `aStoppedBindingRefusesNothing`; `theLegacyPairOnASyncedAssetStaysTheShipped422WithNothingWritten` (C-3: `LegacyWriteCannotRepresent`, `UpdateAsset.kt:62-63`, unguarded) | the guard reads "a binding exists" instead of "enabled" |
| 34 | C15 no-ops stay no-ops | same · `anEqualSetSeasonModeAndARenameSaveOnASyncedAssetSucceed` | the guard before `SetSeasonMode`'s equality return |
| 35 | C15 the 422 first | same · `aBadDateOnASyncedAssetIs422` | the guard before `SeasonValidation` |
| 36 | C15 merge stays unguarded (H7) | same · `aMergeInsertingAnEndForASyncedAssetLandsAndTheNextOnCorrectsOnce`; `aMergeThatLandedTheOtherPhaseOnAForcedAssetIsCorrectedOnTheNextRun` (C-5: one row, whatever HA answers) | the guard added to the merge's activation pass |
| 37 | C15 the offer | `T/ui/condition/OffersTest` (+2) · `noOfferForAnAssetWhoseBindingIsEnabled`; `aStoppedBindingStillGetsTheOffer` | the condition removed |
| 38 | C15 construction sites | every `RecordSeasonActivation(`, `AcceptSeasonOffer(`, `SetSeasonMode(`, `SaveAssetSettings(`, `SeasonOffers(` site takes the guard (arguments only; 26 sites, N-9); `UpdateAsset(` is untouched (C-3); the shipped season suites green | none: pins |
| 39 | C16 link from MANUAL | `CT/seasonsync/LinkSeasonSyncTest` (new) · `fromManualWritesNoSeasonRowAndLinksFollow` | the link writes a START |
| 40 | C16 link reconciles (R16-Q-C) | same · `fromCalendarSwitchesToManualAtTodaysPhaseWithOneRow` (in and out of the window); `fromYearRoundStartsInSeason`; `aPreServiceStrandRefusesAndWritesNothing` (no binding either) | the link sets `seasonMode = MANUAL` directly (an in-window calendar asset reads OUT) |
| 40a | C16 the cadence moves (C-2, limit 14, R16-Q-G) | same · `anInWindowCalendarLinkMovesAnAtStartScheduleToLinkDayPlusOffset` (the fictional example of limit 14: window start 10-01, offset 14; linked 10-20, it opens 11-03, through the shipped evaluator); `aYearRoundLinkGainsACycleStartAtToday` | the switch row dated `cycleStartAt(today)` (R16-Q-G's alternative: the schedule keeps 10-15) |
| 41 | C16 refusals | same · `notMaintainedHereNoConnectionNeedsTokenBadEntityAndAlreadyLinkedRefuse` (each reason, nothing written) | the `maintainedHere` check removed |
| 42 | C17 modes (AC6) | `CT/seasonsync/SeasonSyncCommandsTest` (new) · `forceInWritesAStartWhenOut`; `forceOutWritesAnEndWhenIn`; `forcingTodaysPhaseWritesNothing`; `followWritesNothingBumpsTheRevisionAndAsksForARead` | FOLLOW re-applies the stored observation |
| 43 | C17 stop and resume (AC8) | same · `stopWritesNoEndAndLeavesManualAtItsPhase`; `resumeOnAManualAssetNeedsATokenAndWritesNoRow`; `eachCommandBumpsTheRevision` | stop writes an END |
| 43a | C17 Resume reconciles (C-4, R16-19) | same · `stopThenCalendarThenResumeWritesOneSwitchRowAndNoTransition`; `stopThenYearRoundThenResumeAsksP78After` (the phone's half is row 68); `aStrandRefusalOnResumeWritesNothingAndStaysStopped` | Resume refuses a non-MANUAL asset (rev 1's dead end) |
| 44 | C17 disconnect (AC8, AC9) | same · `forgetDeletesTheConnectionItsBindingsAndTheSecretAndWritesNoSeasonRow`; `aChangedAddressOrTokenBumpsEveryRevision` | the secret not deleted |
| 45 | C17 reauthorization (AC9, I12) | same · `withoutATokenEveryBindingIsNeedsTokenAndNothingIsRead`; `enteringATokenMakesThemActiveWithAFreshRead` | the derived state ignores `SecretStore.has` (N-17: the runner is B6a's) |
| 46 | C18 the store's contract (I9) | `T/seasonsync/KeystoreSecretStoreTest` (new; JDK AES-GCM through `KeyedAead`) · `putGetRoundTrip`; `aMissingFileOrKeyIsNull`; `aTamperedFileIsNullNotAThrow`; `theFileHoldsNoPlaintextAndAFreshIvEachPut`; `deleteRemovesFileAndKey`; `noExceptionOrToStringCarriesTheValue` | a failed tag rethrown (the tamper case throws) |
| 47 | C18 the orphan sweep | same · `filesAndAliasesNamingNoConnectionAreSwept`; `aKnownOneIsKept` | the sweep keeps every file |
| 48 | C18 platform restore (H5) | same · `aRowRestoredWithoutItsKeyReadsNullAndHasFalse` | a key-missing failure rethrown |
| 49 | C19 the request (I10) | `T/seasonsync/HomeAssistantStateClientTest` (new; a fake `HttpURLConnection` through `open`, the `UrlConnectionTransportTest` precedent) · `oneGetToTheStatesPathWithExactlyFourHeaders`; `theBearerIsTheStoredToken` | `Accept` omitted (three properties, not four) |
| 50 | C19 redirects (I10) | same · `redirectsAreNotFollowedAndNoSecondConnectionOpens` | `instanceFollowRedirects` left true (the fake records a second open) |
| 51 | C19 the origin re-checked | same · `aRefusedAddressOpensNothing`; `aHostDisagreementIsEndpointRefused` | the per-request policy check skipped |
| 52 | C19 bounds | same · `aBodyOverSixtyFourKibIsTruncatedThenMalformed`; `theWholeCallStopsAtThirtySeconds` (virtual time); `aCancelDisconnects` | the cap removed |
| 53 | C19 nothing leaks (I9) | same · `noOutcomeExceptionOrToStringCarriesTheUrlHostEntityOrToken` | an exception message built from the URL |
| 53a | C19 TLS (N-4) | same · `anSslExceptionIsTlsFailedNotUnreachable` | the `SSLException` left to `transportFailureOf` (`UNREACHABLE`) |
| 54 | C19 DENIED is quiet | same · `aDeniedPermissionAnswersDeniedAndOpensNothing` | the probe skipped (a connection opened) |
| 54a | C19 step 1a, the eligibility check (R16-Q-D as amended; C-2, C-3, C-5) | same · `homeModeOnTheCapturedWifiProceeds`; `anotherWifiCellularOrNoneIsNotOnLocalNetworkWithNoDetail`; `eachHiddenNameCauseIsUnconfirmedWithItsDetail` (LOCATION_OFF, PERMISSION_MISSING, APPROXIMATE_ONLY, NOT_FOREGROUND); `homeModeOnAWiredNetworkIsNotEligibleWithDetailWired`; `anyNetworkHttpsProceedsOnAnyTransport`; `anHttpRowStoredWithAnyNetworkIsStillNetworkChecked` (fail closed) | the check made "any Wi-Fi or Ethernet" (another Wi-Fi sends the token); **and** step 1a keyed on the stored eligibility alone (the http row with ANY_NETWORK sends from cellular) — two counted |
| 54c | C32 the reader (R16-21; C-1, C-3, N-1) | `T/seasonsync/CurrentNetworkReaderTest` (new; scripted platform answers per path: `getConnectionInfo` on 26–30, the one-shot callback on 31+) · `aQuotedNameIsUnquoted`; `aBlankOrWhitespaceNameIsWifiUnnamed`; `unknownSsidIsWifiUnnamedWithItsCause` (Location off, permission missing, approximate only, not foreground); `ethernetIsWired`; `cellularIsOther`; `aVpnDefaultNetworkIsOther`; `noNetworkIsNone`; `api29And30ReadGetConnectionInfo`; `theCallbackIsUnregisteredOnItsFirstAnswerOrTimeout` | `UNKNOWN_SSID` read as a name (`<unknown ssid>` matches a home network of that name) |
| 54b | C19 step 1b, an https name stays local in the home-network mode (C-9, C-5) | same · `aNameResolvingToAPublicAddressIsNameNotLocalAndOpensNothing`; `aNameResolvingOnlyToPrivateAddressesProceeds`; `onePublicAnswerAmongPrivateOnesRefuses`; `aResolutionFailureIsUnreachable`; `anyNetworkNameResolvingPubliclyProceeds` | the check reads `any` private answer instead of `all`; **and** 1b applied in both modes (the Any-network origin answers `NAME_NOT_LOCAL`) — two counted |
| 55 | C20 the config (R16-Q-B) | `T/seasonsync/NetworkSecurityConfigTest` (new; parses the XML and the manifest source) · `oneBaseConfigPermitsCleartextWithSystemAnchorsOnly`; `noUserCertificatesNoDomainConfigNoDebugOverrides`; `theApplicationNamesIt` | `<certificates src="user"/>` added |
| 56 | C20 merged manifest | both classes of `T/reminders/ManifestContractTest.kt` (`ManifestContractTest`, `MergedManifestContractTest`) green; the permission set gains C32's lines (a B5 twin) | none: pins (a pin on `<application>`'s attributes is a B5 twin) |
| 57 | C21 the runner (AC3, AC4) | `T/seasonsync/SeasonSyncRunnerTest` (new) · `syncNowReadsEachActiveBindingOnce`; `aSyncNowDuringARunWaitsThenReadsFresh`; `neverTwoRequestsInFlight` (the scripted reader records the overlap); `refreshIfStaleSkipsWhileARunHoldsTheLockAndReadsOnlyStaleOnes`; `staleIsOneCadenceWithoutASuccess` (each of the four cadences; a recent failure does not freshen); `inertBindingsAreNeitherReadNorWritten`; `anUndecryptableTokenRecordsNeedsTokenAndSendsNothing` | the lock removed (two requests in flight at once) |
| 58 | C22 the worker's result (H6) | `T/seasonsync/SeasonSyncWorkerBodyTest` (new; the body behind the worker shell) · `everySyncErrorKindIsSuccess`; `aLocalDatabaseFailureIsRetry`; `cancellationPropagates`; `anUnassignedDispatchIsSuccess` | `retry` on `UNREACHABLE` |
| 59 | C22 enqueue and cancel (R16-20 as amended; N-6) | same · `startEnsuresWhenAnyBindingIsEnabledAndCancelsWhenNone`; `linkStopResumeAndForgetEnsureOrCancelAfterCommit`; `theRequestedPeriodIsTheCadence` (12 h, 24 h, 7 d, 30 d); `aCadenceChangeEnqueuesWithUpdate`; `switchingToHomeOnlyWithBackgroundOffCancels`; `switchingToAnyNetworkEnsures`; `homeOnlyWithBackgroundOnAndTheGrantEnsures`; `homeOnlyWithBackgroundOnWithoutTheGrantCancelsAndReportsPaused`; `aGrantWithdrawnIsSeenOnTheNextStartOrResume`; `runAllInHomeModeChecksTheNetworkFirst` | `ensure` on a start with none enabled; the policy left `KEEP` (a changed cadence keeps the old period); **and** the grant ignored (On without background location enqueues work that sends nothing) — three counted |
| 60 | the scenario (AC1–AC6) | `T/seasonsync/SeasonSyncScenarioTest` (new; Room-backed `FakeGraph`, the scripted reader, the connection `http://192.168.0.10:8123`, C-7) · on → in season; HA's appliance cycling leaves the helper unchanged → no row; UNREACHABLE for a "week", then off → one END dated the day it is read; FORCE_IN survives a new graph over the same database file (a restart) and a poll answering off; FOLLOW then waits for a fresh read and applies it | none: composed of rows 22–29's REDs |
| 61 | C24 the route (I9, R16-9) | `T/api/SeasonSyncRoutesTest` (new) · `aLinkedAssetReadsC3WithNoAddressAndNoToken` (the connection `http://192.168.0.10:8123`; the body contains neither `192.168.0.10` nor the token fixture); `anUnlinkedAssetReadsBindingNull`; `anUnknownAssetIs404`; `postIs404NotA405`; `needsTokenAndStoppedReadAsSuch`; `theFourSettingsAreShownTheHomeWifiNameIsNot` | the DTO carries `baseUrl` |
| 62 | C24 the 409 (C2) | `T/api/SeasonHealthRoutesTest` (+4) · `POST …/season` and `POST …/season-mode` (a change) answer 409 `SEASON_SYNC_ENABLED` with nothing written; the asset PATCH's legacy pair (a change) on a synced asset stays the shipped **422 `LEGACY_WRITE_CANNOT_REPRESENT`** (C-3, a pin); a stopped binding answers as before | the arm maps to `SEASON_NOT_MANUAL` |
| 63 | C24 pins | §3's B7a pins | none: pins |
| 64 | C25 the tool | `M/tests/test_season_sync_tools.py` (new) · one GET of the route; below schema 21 refused with nothing sent; the result keys are C3's (the settings shown; no address or home Wi-Fi name); the three season write docstrings name `SEASON_SYNC_ENABLED` | the minimum written 20 |
| 65 | C25 pins | §3's B7b pins | none: pins |
| 66 | C26 the screen's model (I9) | `T/ui/homeassistant/HomeAssistantViewModelTest` (new) · `aRefusedAddressShowsP16_12AndSavesNothing`; `afterSaveTheStateHoldsNoTokenText`; `testConnectionShowsEachOutcomesSentence`; `disconnectAsksP16_9ThenForgets`; `needsTokenShowsP16_11`; `theCadenceOffersExactlyFourAndPreselectsOnceADay` (rev 1.3) | the state keeps the typed token after save |
| 66a | C26, C32 the home-network option, foreground (C-2, C-3) | same · `anyNetworkAsksForNothing`; `homeNetworkShowsP16_65ThenRequestsPreciseLocation`; `deniedOrApproximateLeavesItOffWithP16_66`; `grantedCapturesTheCurrentWifiAndShowsP16_63`; `captureOnEthernetShowsP16_81`; `captureOnMobileDataOrNoneShowsP16_82`; `locationOffAtCaptureShowsP16_68`; `httpWithAnyNetworkShowsP16_67` | the option enabled on a denial (the token would go to an unchecked network) |
| 66b | C26, C32 background checks (R16-20 as amended) | same · `backgroundChecksAppearOnlyUnderHomeOnlyAndStartOff`; `turningOnAfterTheForegroundGrantShowsP16_73ThenRequestsBackgroundLocation`; `api30AndUpOpensAppSettings`; `declineOrRefusalShowsP16_75AndRunsForegroundOnly`; `allowAgainRerunsTheMatchingFlow` (P16-76 under P16-75 and P16-78); `offNeverAsksForBackgroundLocation` | background location asked while Background checks is Off |
| 67 | C27 the block's model | `T/ui/asset/SeasonSyncBlockViewModelTest` (new) · `effectiveSeasonSourceLastSuccessAndLatestErrorAreSeparateLines`; `staleFollowsTheCadence` (one cadence without a success; the attempt time does not count); `theModeControlReplacesStartAndEnd`; `theBlockIsOnEveryModesBranchAndAStoppedOneOffersResume` (C-4); `syncNowCallsTheRunnerForThisAsset`; `everySyncErrorKindHasItsSentence` (exhaustive); `unconfirmedShowsP16_77AndTheCausesRemedy` (C-3); `theProvenanceLineIsC33s` (rev 1.5, after B2b: a Home Assistant START draws P16-34, a forced START P16-83, a forced END P16-84, no source no line) | the error line replaces the last-success line; **and** the provenance line chosen by the action alone (P16-34 for a `FORCED_IN` source) — two counted |
| 68 | C27 the setup sheet (R16-Q-C) | `T/ui/asset/LinkSeasonSyncViewModelTest` (new) · `eachModeShowsItsSentenceBeforeTheWrite`; `aYearRoundLinkWithContinuousSchedulesAsksP78After`; `aStrandRefusalShowsS55AndKeepsTheSheet`; `aBadEntityShowsP16_49`; `resumeOnACalendarAssetShowsP16_44BeforeTheWrite` (C-4) | the CALENDAR link written without its sentence (the state skips the confirm step) |
| 69 | C27 the editor | `T/ui/asset/AssetEditViewModelTest` (+2) · `aSyncedAssetsSeasonBlockIsReadOnlyWithP16_47`; `#78's prompt is unchanged after the lift` (#78's shipped cases green); `anArchivedContinuousScheduleIsNotCounted` | the lifted count includes ARCHIVED schedules |
| 70 | C28 drawn | **Compose** `AT/seasonsync/SeasonSyncScreensTest` (new, ~10) · the masked token field; the Settings row; the mode control in place of Start/End; Sync now; the status lines side by side; the stale marker; the setup sheet's sentence and S55; the editor's read-only block; a stopped binding drawn on a CALENDAR asset with Resume (C-4); the resume hook calls `refreshIfStale` | none counted (first run at the gate; B8c's report predicts the mask-removed failure) |
| 71 | C29 the platform (R16-12) | **Device** `AT/seasonsync/SeasonSyncPlatformProofTest` (new, ~10) · WorkManager's unique work, constraint and the cadence's period, UPDATE and cancel; the Keystore round trip and the no-backup file; a deleted key reads null; cleartext permitted; the real client to a fake HA at this device's address with the bearer, `on` → `Observed(ON)`; a 302 not followed and its target untouched; C32's reader names the emulator's Wi-Fi (`AndroidWifi`, unverified until here; N-2's preconditions); with the background grant the work is enqueued for Home + On and the worker body reads the name and proceeds on a match, sends nothing on a mismatch; without it nothing is enqueued; teardown cancels `"season-sync"` (N-15) | none counted (first run at the gate) |
| 72 | C30, C31 | the documents, the fence and the no-leak greps (§7) | none: greps |
| 73 | C4a the connection's settings (B1a2, shipped `ba46d1d8`) | `CT/seasonsync/SeasonSyncModelTest` (+8) · `theCadenceIsExactlyFourWithTheirHours`; `eligibleNowFollowsTheMode` (ANY; HOME × Wifi same/other, `WifiUnnamed`, `Wired`, `Other`, `None`, no captured name); `theDoubleRefusesHomeWithoutANetworkAndHttpWithAnyNetwork`; `theDoubleRefusesABlankName`; B1a's field-name pin moved to the connection's **eight fields** (a twin; C-7) | `WifiUnnamed` made eligible (a hidden name passes) |
| 74 | C17 the connection's settings (rev 1.3) | `CT/seasonsync/SeasonSyncCommandsTest` (+4) · `anHttpAddressWithAnyNetworkIsHttpNeedsHomeNetwork`; `homeWithoutACapturedNetworkIsHomeNetworkNotSet`; `aBlankCapturedNameIsHomeNetworkNotSet`; `aNewConnectionIsDailyAndBackgroundOff` (the writer sets both, the model defaults neither); `aCadenceChangeCallsEnsureAfterCommitAndAnEligibilityChangeBumpsEveryRevision` | the http/any-network refusal removed |
| 75 | C33(2) the column survives the migration; the round trip (R16-4) | `T/data/room/Migration20To21Test` (moved pin: the binding's exact column list gains `last_applied_source TEXT`; `theMigratedSchemaEqualsAFreshVersion21` re-run) and `T/data/room/SeasonSyncDaoTest` (moved pins: `aBindingRoundTripsEveryField` sets a source; `noColumnOfEitherTableIsATokenOrSecret` counts 8 and 19; the guarded update's full-field `next` writes a source unlike the inserted row's) | `MIGRATION_20_21`'s binding `CREATE TABLE` without the column (the schema compare fails); **and** `updateAtRevision`'s SQL without `last_applied_source = :lastAppliedSource` (the full-field compare fails) — two counted |
| 76 | C33(1), C33(3) the applier's source per outcome (I13) | `CT/seasonsync/RecordSeasonSyncResultTest` (+3) · `aFollowApplicationRecordsHomeAssistant` (on over out: START; off over in: END; both `HOME_ASSISTANT`); `aForcedReassertionRecordsTheForcedSource` (a FORCE_OUT binding on an in-season asset, HA answering on: END, `FORCED_OUT`; FORCE_IN the mirror: START, `FORCED_IN`); `noApplicationMovesTheSource` (an agreeing read, an "already applied" refusal, `NOT_MANUAL`, `DATE_BEFORE_HISTORY`, FOLLOW + `NoDecision`: the four `applied*` fields as they were, a forced source included) | the source written as `HOME_ASSISTANT` whatever the mode; **and** the source set on an agreeing read (it moves with no row) — two counted |
| 77 | C33(3) the Force commands write `FORCED_*` (R16-Q-H) | `CT/seasonsync/SeasonSyncCommandsTest` (+2) · `forceInAndForceOutRecordTheirForcedSource` (FOLLOW → FORCE_IN on an out-of-season asset: START, `FORCED_IN`; then FORCE_OUT: END, `FORCED_OUT`); `choosingFollowKeepsTheForcedSourceUntilHomeAssistantApplies` (no row at the switch; the next applying read records `HOME_ASSISTANT`) | `SetSeasonSyncMode` hands `applyIfChanged` `stored` for `moded` (`SeasonSyncCommands.kt:97`): the forced START records `HOME_ASSISTANT` |
| 78 | C33(4) the phone's line rule | `CT/seasonsync/SeasonSyncModelTest` (+3) · `appliedSourceOfMapsEachMode`; `appliedLineFollowsTheSource` (`HOME_ASSISTANT` × START/END → the two Home Assistant lines; `FORCED_IN` → `FORCED_IN_SEASON`; `FORCED_OUT` → `FORCED_OUT_OF_SEASON`); `noSourceNoLine` | `appliedLineOf` by the action alone (a forced START reads as started from Home Assistant) |
| 79 | C33(5) the API key (R16-9) | `T/api/SeasonSyncRoutesTest` (moved pin `:148`: `lastApplied`'s keys gain `source`; +1) · `aForcedApplicationReadsSourceForcedIn` (FORCE_IN through the graph's `setSeasonSyncMode`: `"FORCED_IN"`; the linked asset's Home Assistant application: `"HOME_ASSISTANT"`) | the DTO's `source` written as the constant `"HOME_ASSISTANT"` |
| 80 | C33(6) G5 at each silent site (R16-Q-J) | `T/seasonsync/SeasonSyncWorkerBodyTest` (+4; a recording `log`, the runner through `runnerOver`) · `aCommandsFreshReadThatFailsLogsG5`; `aResumeRefreshThatFailsLogsG5`; `aStartUpReconcileThatFailsLogsG5`; `aWorkerScheduleCheckThatFailsLogsG5AndSucceeds` — each: the recorded lines are exactly `[G5]` (none holds `192.168.0.10`, the entity, `ExampleHomeWifi` or `fictional-token-1`) and the site's result is as shipped | the line removed from `launchQuietly` (two cases fail); **and** from the start-up reconcile; **and** from the worker's catch-all — three counted |

**Moving pins (each a twin-rule anchor; a hit elsewhere stops the brief):**

| pin | brief | moves, because |
|---|---|---|
| B1a's `theBindingCarriesExactlyTheColumnsOfItsTable` (the connection's 4 fields → 8, C-7) and the one `HaConnection(` construction, `haConnectionOf` | B1a2 (shipped) | C4a's four fields |
| the merged-manifest permission set (`T/reminders/ManifestContractTest.kt`) | B5 | C32's `<uses-permission>` lines: FINE, COARSE, BACKGROUND location, `ACCESS_WIFI_STATE`, and `ACCESS_NETWORK_STATE` declared (already merged) |
| `T/VersionAgreementTest.kt:85-88` (`theSchemaIsTwentyAndTheFormatIsTwenty` → split: schema 21, format 20, renamed in the shipped two-step manner, its KDoc `:65-84` gaining one sentence for #16), `:157` → 21 (`:158` stays 20); `T/api/MaintenanceRoutesTest.kt:1552` → 21 (`:1553` stays); `T/data/room/MigrationTestSupport.kt:65-70` (the chain gains `MIGRATION_20_21`) | B2 | the schema is 21; the format is not |
| the nine whole-chain table sets subtract `V21_TABLES`: `Migration10To11Test.kt:101`, `Migration11To12Test.kt:167`, `Migration12To13Test.kt:150`, `Migration13To14Test.kt:109`, `Migration14To15Test.kt:114`, `Migration17To18Test.kt:76`, `ReferenceMigrationTest.kt:109-110`, **`Migration18To19Test.kt:35`** and **`Migration19To20Test.kt:177`** (the fresh install is now v21, so "no table is added" compares `V19_ALL_TABLES + V21_TABLES` — the brief keeps the assertion's meaning for v20) | B2 | `openMigrated` runs the whole chain (`MigrationTestSupport.kt:61-70`), so every set measures the latest schema |
| **unchanged, re-run:** every format literal 20, the one-past archives and their `refusal.found` lines, `counts.size`, `MergeTable`'s members, key positions, manifest-count maps, `TransferTableClassificationTest`, the "20 since #69 (resource owners)" counts (`T/api/ResourceOwnerRoutesTest.kt:504`, `T/api/CommandShapesGoldenTest.kt:185` — the format clause keeps its line, so each still counts 2) | — | nothing that travels moves |
| the construction sites of the five guarded classes (C15: four use cases and `SeasonOffers`; 26 sites, N-9; `UpdateAsset` untouched, C-3) and the forced `guarded = false` argument in B3a's `RecordSeasonSyncResult.kt` (N-6) | B3b | one constructor parameter with no default (arguments only, counted in the report) |
| `T/api/ReferenceRoutesTest.kt:764-773` ("twenty-seven" → "twenty-eight `/v1/assets/{id}/…` sub-resources"; "twenty-seven" joins the refused list); `docs/api/v1.md:213-217` (schema clause only); `A/api/ApiRouter.kt:103` (KDoc) | B7a | a twenty-eighth sub-resource; schema 21 |
| the tool count 89 → 90: `M/tests/test_argument_guard.py:8`, `:46`, `:51`, `:182`, `:203-204`, `:207`, `:257-258`, `:326`, `:341-342`; `test_tools.py:133-137` and its `EXPECTED_TOOLS` tuple (`:23`, the new name appended); `test_reference_tools.py:35`, `:65-66`; the docstrings `test_maintenance_tools.py:45`, `test_installed_component_tools.py:45`, `test_supply_tools.py:47` | B7b | one tool appended (audit correction 2) |
| the tail pins `test_installed_component_tools.py:134` (`TOOL_NAMES[-len(INSTALLED_COMPONENT_TOOLS):]` → offset by the new block) and `test_supply_tools.py:128` (`[-len(SUPPLY_TOOLS) - 5:-5]` → `- 6:-6`), and a new tail pin for the #16 block | B7b | an appended block moves every tail position (audit correction 3) |
| the binding's column pins: `CT/seasonsync/SeasonSyncModelTest.kt:180` (`theBindingCarriesExactlyTheColumnsOfItsTable`: 18 → 19), `T/data/room/Migration20To21Test.kt:24` (the binding's exact column list), `T/data/room/SeasonSyncDaoTest.kt:153` (8 and 18 → 8 and 19) and its full-field rows (`:141`, `:187`); `T/api/SeasonSyncRoutesTest.kt:148` (`lastApplied`'s keys + `source`); a shipped `emptyList<String>(), logged` assertion in `T/seasonsync/SeasonSyncWorkerBodyTest.kt` that covers one of G5's four sites (→ `listOf(G5)`, a twin, listed); the construction sites, arguments only: seven `SeasonSyncBinding(` (`C/seasonsync/LinkSeasonSync.kt:90`, `A/data/room/SeasonSyncRepositories.kt:92`, `CT/testing/InMemorySeasonSync.kt:216`, `T/backup/SeasonSyncNeverTravelsTest.kt:56`, `T/data/room/SeasonSyncDaoTest.kt:50`, `T/ui/condition/OffersTest.kt:371`, `AT/seasonsync/SeasonSyncPlatformProofTest.kt:502`), `SeasonSyncBindingEntity(` (`SeasonSyncRepositories.kt:113`), `SeasonSyncAppliedDto(` (`SeasonSyncDtos.kt:99`), `updateAtRevision(` (`SeasonSyncRepositories.kt:40`) — counts on `35befab3` | B2b (rev 1.5) | C33: one column in the unreleased schema 21, one API key, G5 |
| **unchanged, re-run (B2b):** `V21_TABLES` and the nine whole-chain table sets, `20.json`, every format literal, `TransferTableClassificationTest`, `SeasonSyncNeverTravelsTest` (its one site only), `M/tests/test_season_sync_tools.py` (`:59`, `:108`: its canned body pins the pass-through, not C3) | — | a column is not a table; nothing travels |

**Device rows.** Two new classes: `SeasonSyncPlatformProofTest` (C29; tier-3 framework contracts plus one tier-4
socket to a peer in this process through the real `HttpURLConnection` and security policy) and `SeasonSyncScreensTest`
(C28; tier 2) — **59 → 61** classes, ~+19 cases (345 → ~364). **Gate time:** ~20 min projected under software
rendering (#69's fill 5: ~3.3 s per test), about twice a hardware run; the 14/15-minute lines (#90) are reporting only
and not judged on a software run. **Known cost:** `AssetDetailConditionHealthSeasonTest` (10) stays green unchanged
(a linked asset is a new fixture).

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1a | `C/seasonsync/{SeasonSyncModel,HaReadOutcome,HaStateMapper,DesiredPhase,SeasonSyncPorts}.kt` (new); `CT/testing/{InMemorySeasonSync,BackupInstall}.kt` (the doubles and their one cascade registration); `CT/seasonsync/{HaStateMapperTest,DesiredPhaseTest,SeasonSyncModelTest}.kt`, `CT/testing/SeasonSyncDoubleTest.kt` (new) — rows 1–9 | `C/fetch`, `C/usecase`, `C/schedule`, `C/backup`, `C/merge`, `C/transfer`, `A/**`, `docs`, `tools` |
| B1b | `C/seasonsync/HaEndpointPolicy.kt` (new); `C/fetch/HopPolicy.kt` (`:66`'s `private` → `internal`, **nothing else**); `CT/seasonsync/HaEndpointPolicyTest.kt` (new); `CT/fetch/HopPolicyTest.kt` (+1) — rows 10–14 | every other `C/fetch` file, `A/**`, `docs`, `tools` |
| B1a2 (shipped `ba46d1d8`) | `C/seasonsync/SeasonSyncModel.kt` (C4a's types, four fields, `eligibleNow`); `C/seasonsync/SeasonSyncPorts.kt` (one KDoc line on `SecretStore.has`) and `C/seasonsync/HaStateMapper.kt` (two helpers made public) — **controller-ordered fold-ins from B1a's review** (MINOR-1 the source scan, NOTE-1, NOTE-4, NOTE-6; C-7); `CT/testing/InMemorySeasonSync.kt`; `CT/seasonsync/SeasonSyncModelTest.kt` (row 73, the eight-field pin); `haConnectionOf` | every other `C/` file, `A/**`, `docs`, `tools` |
| B2 | `A/data/room/entities/{HaConnectionEntity,SeasonSyncBindingEntity}.kt`, `A/data/room/dao/{HaConnectionDao,SeasonSyncBindingDao}.kt`, `A/data/room/SeasonSyncRepositories.kt` (new); `A/data/room/{Migrations,AppDatabase}.kt`; `A/di/AppGraph.kt` (`SCHEMA_VERSION`, the migration list, two repository instances); `T/testing/FakeGraph.kt` (two instances); `app/schemas/…/21.json` (generated); `T/data/room/{Migration20To21Test,SeasonSyncDaoTest}.kt` (new); `T/backup/SeasonSyncNeverTravelsTest.kt` (new); §3's B2 pins — rows 15–20 | `C/**` main, `A/api`, `A/ui`, `20.json`, `docs`, `tools` |
| B3a | `C/usecase/RecordSeasonActivation.kt` (the body split, C12); `C/seasonsync/RecordSeasonSyncResult.kt` (new); `CT/seasonsync/{RecordSeasonSyncResultTest,SeasonSyncPolicyTest}.kt` (new) — rows 21–32 | `C/schedule`, `C/backup`, `C/merge`, `C/transfer`, other use cases, `A/**`, `docs`, `tools` |
| B3b | `C/seasonsync/SeasonSyncGuard.kt` (new); `C/usecase/{SeasonCommands,RecordSeasonActivation,SeasonEventOffer,SetSeasonMode,SaveAssetSettings}.kt` (the exception, the guard parameter and its one call each; **not** `UpdateAsset.kt`, C-3); `C/seasonsync/RecordSeasonSyncResult.kt` (the forced `guarded = false` argument only, N-6); `A/ui/condition/Offers.kt` (`SeasonOffers`' parameter and condition); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (arguments); every construction site `git grep` names (arguments); `CT/seasonsync/SeasonSyncAuthorityTest.kt` (new); `T/ui/condition/OffersTest.kt` (+2) — rows 33–38 | `C/schedule`, `C/backup`, `C/merge`, `C/transfer`, `A/api`, `A/data`, `A/ui` but `Offers.kt`, `docs`, `tools` |
| B3c | `C/seasonsync/{LinkSeasonSync,SeasonSyncCommands}.kt` (new); `C/usecase/SetSeasonMode.kt` (an in-transaction body for C16's switch, `ApplyTemplate`'s shape — its `run` byte-identical in behaviour); `CT/seasonsync/{LinkSeasonSyncTest,SeasonSyncCommandsTest}.kt` (new) — rows 39–45, 40a, 43a, 74 | `C/schedule`, `C/backup`, `C/merge`, `C/transfer`, every use case but `SetSeasonMode.kt` (N-6), `A/**`, `docs`, `tools` |
| B4 | `A/seasonsync/{KeystoreSecretStore,KeyedAead}.kt` (new); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (one store each: Keystore in `AppGraph`, the JDK AEAD over a temporary directory in `FakeGraph`); `T/seasonsync/KeystoreSecretStoreTest.kt` (new) — rows 46–48 | `C/**`, `A/api`, `A/ui`, `A/data`, the manifest, `docs`, `tools` |
| B5 | `A/seasonsync/HomeAssistantStateClient.kt` (new); `app/src/main/res/xml/network_security_config.xml` (new); `app/src/main/AndroidManifest.xml` (the attribute, the `:12` comment and C32's `<uses-permission>` lines); `A/seasonsync/CurrentNetworkReader.kt` (new: C32's seam, its per-API-level implementation and the hidden-name causes, C19 step 1a); `T/reminders/ManifestContractTest.kt` (the merged permission set, a twin); `A/fetch/UrlConnectionTransport.kt` (the `:21` KDoc sentence only); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the client with the shipped `InetHostResolver` and the reader; the scripted reader in `FakeGraph`); `T/seasonsync/{HomeAssistantStateClientTest,NetworkSecurityConfigTest,CurrentNetworkReaderTest}.kt` (new) — rows 49–56, 53a, 54a, 54b, 54c | `C/**`, every other `A/fetch` line, `A/api`, `A/ui`, `docs`, `tools` |
| B6a | `A/seasonsync/{SeasonSyncRunner,SeasonSyncWorker,SeasonSyncWork,SeasonSyncDispatch,ResumeRefresh}.kt` (new); `A/ServiceTagApp.kt` (the dispatch, the start-up reconcile and sweep); `A/ui/nav/ServiceTagRoot.kt` (the second `LifecycleResumeEffect` and the `resumeRefresh` parameter only, C23); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the runner, the work seam scheduled from the cadence, `ResumeRefresh`, B3a's and B3c's use cases — **the last edit of either**); `T/seasonsync/{SeasonSyncRunnerTest,SeasonSyncWorkerBodyTest}.kt` (new) — rows 57–59 | `C/**`, `A/api`, `A/ui` but `ServiceTagRoot.kt`, the manifest, `docs`, `tools` |
| B6b | `T/seasonsync/SeasonSyncScenarioTest.kt` (new) — row 60 (test-only, C-10) | every main file, `AppGraph`, `FakeGraph` |
| B7a | `A/api/{ApiRouter,ApiJson,SeasonHealthHandlers,SeasonSyncDtos}.kt` (the route, the arm, the handler, the new DTO file); `docs/api/v1.md`; `T/api/{SeasonSyncRoutesTest (new),SeasonHealthRoutesTest,ReferenceRoutesTest}.kt`; `T/api/MaintenanceFixtures.kt` (`:78`'s arguments) — rows 61–63 | `C/**`, `A/ui`, `A/seasonsync`, `A/di`, `FakeGraph`, `docs` but `v1.md`, `tools` |
| B7b | `M/src/servicetag_mcp/server.py`; `M/README.md` (the tool's line); `M/tests/test_season_sync_tools.py` (new); §3's B7b pins — rows 64–65 | `app/**`, `core/**`, `S/**`, `docs` |
| B8a | `A/ui/homeassistant/{HomeAssistantScreen,HomeAssistantViewModel,HomeAssistantStrings}.kt` (new); `A/ui/settings/SettingsScreen.kt` (one `UtilityRow` and its callback); `A/ui/nav/{Route,ServiceTagRoot}.kt` (`Route.HomeAssistant` and its entry — `ServiceTagRoot` past B6a's hook); the cadence, eligibility and background-checks controls, the two permission flows, recovery and the capture (C26, C32); `T/ui/homeassistant/HomeAssistantViewModelTest.kt` (new); the forced named-argument sites of the new `SettingsScreen` callback, `AT/ui/settings/SettingsBackupEntryTest.kt:41`, `:65`, `:92` (arguments only, N-7) — rows 66, 66a, 66b | `C/**`, `A/api`, `A/data`, `A/di`, `A/seasonsync`, `A/ui/asset`, `docs`, `tools` |
| B8b1 | `A/ui/asset/{SeasonSyncBlock,SeasonSyncBlockViewModel,SeasonSyncStrings}.kt` (new); `A/ui/asset/AssetDetailScreen.kt` (`SeasonSection`: the block on every mode's branch, C-4); `T/ui/asset/SeasonSyncBlockViewModelTest.kt` (new) — row 67 | `C/**`, `A/api`, `A/data`, `A/di`, `A/seasonsync`, `A/ui/homeassistant`, `AssetEditScreen.kt`, `AssetViewModels.kt`, `docs`, `tools` |
| B8b2 | `A/ui/asset/{LinkSeasonSyncSheet,LinkSeasonSyncViewModel}.kt` (new); `A/ui/asset/SeasonSyncStrings.kt` (B8b1's file: the sheet's strings); `A/ui/asset/AssetDetailScreen.kt` (the link and Resume actions open the sheet); `A/ui/asset/AssetEditScreen.kt` (`OperatingSeasonBlock`'s read-only state); `A/ui/asset/AssetViewModels.kt` (`reconcilePromptFor` calls the lifted count; the editor's synced flag); `C/usecase/SeasonCommands.kt` (`liveContinuousCount`, lifted, pure); `T/ui/asset/LinkSeasonSyncViewModelTest.kt` (new), `AssetEditViewModelTest.kt` (+2) — rows 68–69 | `C/**` but the one function, `A/api`, `A/data`, `A/di`, `A/seasonsync`, `A/ui/homeassistant`, `docs`, `tools` |
| B8c | `AT/seasonsync/SeasonSyncScreensTest.kt` (new) — row 70 | every main file; every shipped `AT/` file (B8a owns N-7's three sites) |
| B9 | `AT/seasonsync/SeasonSyncPlatformProofTest.kt` (new) — row 71 | every main file |
| B2b (rev 1.5) | `C/seasonsync/SeasonSyncModel.kt` (C33(1), C33(4)); `C/seasonsync/RecordSeasonSyncResult.kt` (the one `copy`); `C/seasonsync/LinkSeasonSync.kt` (its site, `null`); `A/data/room/entities/SeasonSyncBindingEntity.kt`, `A/data/room/dao/SeasonSyncBindingDao.kt`, `A/data/room/SeasonSyncRepositories.kt`, `A/data/room/Migrations.kt` (`MIGRATION_20_21` only), `app/schemas/…/21.json` (regenerated in place); `A/api/SeasonSyncDtos.kt`; `docs/api/v1.md` (the `lastApplied` row); `A/seasonsync/{SeasonSyncRunner,SeasonSyncWorker}.kt` (G5, the runner's defaulted `log`); `M/src/servicetag_mcp/server.py` (one docstring line); `CT/seasonsync/{SeasonSyncModelTest,RecordSeasonSyncResultTest,SeasonSyncCommandsTest}.kt`, `CT/testing/InMemorySeasonSync.kt`, `T/data/room/{Migration20To21Test,SeasonSyncDaoTest}.kt`, `T/api/SeasonSyncRoutesTest.kt`, `T/seasonsync/SeasonSyncWorkerBodyTest.kt`; construction sites only in `T/backup/SeasonSyncNeverTravelsTest.kt`, `T/ui/condition/OffersTest.kt`, `AT/seasonsync/SeasonSyncPlatformProofTest.kt` — rows 75–80 | `A/ui/**` (main); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt`; `C/usecase/**`, `C/seasonsync/SeasonSyncCommands.kt`; `C/schedule`, `C/backup`, `C/merge`, `C/transfer`, `C/model`; `20.json`; `M/tests`, `M/README.md`, `S/`; the manifest |
| B10 | `docs/release-proofs.md`; `docs/design/{03-target-architecture,09-security-privacy}.md`; `docs/home-assistant-season-sync.md` (new); `README.md` (`:80` only) | any `.kt`, `.py`, `.xml`, `tools`, `docs/api`, `docs/versioning.md`, `docs/releases` |

**(B7a → B7b → B9) ∥ (B8a → B8b1 → B8b2 → B8c) — the one parallel pair.** Lane 1's files are `A/api/**`, `docs/api/**`,
`T/api/**`, `M/**` and one new `AT/seasonsync/` file; lane 2's are `A/ui/**` (but `A/ui/condition`, untouched after
B3b), `A/ui/nav/{Route,ServiceTagRoot}.kt`, one `C/usecase/SeasonCommands.kt` function, `T/ui/**` and one other new
`AT/seasonsync/` file — **no file in common**, and neither edits `AppGraph` or `FakeGraph`. Under the two-lane rule
lane 2 may run in a second worktree off B6b's tip (B6b precedes the fork); the controller lands both before B10, whose
`<base>` holds both. `ServiceTagRoot.kt` is B6a's (the hook and the seam) and then B8a's (the route entry) — sequential
by construction. Lane 1's `AT/` file and lane 2's B8a edit of `SettingsBackupEntryTest.kt` (N-7) are different files.
**B2b (rev 1.5)** runs on a side lane off the tip holding B9, beside B8a (disjoint files), and is folded before B8b1;
a `SeasonSyncBinding(` site a parallel brief adds after its `<base>` is the fold's (arguments only).

**Order:** B1a → B1b → **B1a2** → B2 → B3a → B3b → B3c → B4 → B5 → B6a → B6b → { (B7a → B7b → B9 → **B2b**) ∥ (B8a → B8b1 →
B8b2 → B8c) } → B10, B2b folded before B8b1 — **twenty dispatches** (rev 1.5 added B2b; rev 1.3 inserted B1a2, shipped in ~45 min; rev 1.1 split B6 and B8b; B1a, B2, B3a
and B5 keep 40-minute clauses), every one ≤ 1 h by estimate. B1b needs B1a's model; B1a2 adds to B1a's model; B2
needs B1a's ports and B1a2's columns;
B3a needs nothing of B2's (core only) but follows it so the schema lands before any writer; B3b needs B3a's body
split; B3c needs B3a's `applyIfChanged` and B3b's guard (a link must not be refused by its own guard: the link writes
before the binding exists, and the Force path calls the body); B4 and B5 need B1a's ports; B6a needs everything
before it; B6b needs B6a's wiring; B7a needs B6a's wiring (the handler reads the store); B8a, B8b1 and B8b2 need
B6a's runner, and B8b2 needs B8b1's strings file; B9 needs B4–B6a; B2b needs B7a–B9's files, B8b1 needs B2b (C33(4)); B10 describes everything.

**The season authority (R16-1, DECIDED)** and **the six audit corrections of rev 1** (nine whole-chain table sets; the
twenty-one 89-tool sites; two tail pins; the two untrue documents, `docs/api/v1.md:38-41` and `README.md:80`; the
refused documentation address; 59 → 61 device classes) stand as written in rev 1.2 (`git show ec4854f6`, §4); each
has landed in §3's pins, §5 or the briefs.

**Recomputed counts:** +2 device-local tables, no shipped column; format 20, lists, `MergeTable`, transfer classes
unchanged; asset sub-resources 27 → **28**, router 81/101 → **82/102**; **1** new API code and one key (`lastApplied.source`, rev 1.5); MCP 89 → **90**; moving
pins: 4 schema sites, the chain and 9 table sets (B2), 26 construction sites (B3b, N-9), 3 + v1.md (B7a), 21 + 2 tails
(B7b), 3 named arguments (B8a, N-7), the column pins and ten construction sites (B2b); **83** phone strings (all
RATIFIED: 50 on 2026-10-02, 33 on 2026-10-03; P16-64 withdrawn); device classes
59 → **61** (~+20 cases, ~20 min under software rendering, reporting only); **20** dispatches, each ≤ 1 h. **Gate
budget:** core +~120, app +~120, MCP +~8 (B2b: core +~5, app +~6, MCP 0).

**Between-brief gaps** (each unreachable: nothing links a binding before B6a wires the commands and B8b1/B8b2 draw
them): B2 → B3b, writers unguarded (rows 33–36); B3a → B3b, the API's season POST unguarded (row 33); B3c → B6a, the
scheduler port a recording double (rows 57, 59); B5 → B6a, the client uncalled (row 57); B3b → B7a, the new exception
answered 500 (row 62).

## 5. Strings

**RATIFIED by the owner 2026-10-02 (P16-1…52 and G1–G3; rev 1.3–1.4 reopen P16-32 and P16-50 and add P16-53…82, below, P16-64 withdrawn; eight amendments — P16-3, 5, 12, 28, 29, 44, 45, 51, copied verbatim from the owner's rulings) and 2026-10-03 (the rev 1.4 block below, six rows amended, P16-83/84, G4,
G5; comment 5972214001, R16-Q-H…J; 83 phone strings in all).** Each new string is declared once as a `const val`
(or a one-line function for a format) at the home named, and imported, never copied. Two homes:
`A/ui/homeassistant/HomeAssistantStrings.kt` (P16-1…21, P16-48, P16-50…52; the outcome sentences live here because both screens
draw them) and `A/ui/asset/SeasonSyncStrings.kt` (P16-22…47, P16-49, P16-83/84).

| id | proposed wording | where (contract) |
|---|---|---|
| P16-1 | "Home Assistant" | the Settings → Utilities row and the screen's title (C26) |
| P16-2 | "Server address" | the address field's label (C26) |
| P16-3 | "Use https:// with the server's name or private IPv4 address, or http:// with its private IPv4 address on your home network." | the address field's helper (C26, C8) |
| P16-4 | "Access token" | the masked field's label (C26) |
| P16-5 | "A long-lived access token from a Home Assistant user made for ServiceTag, without administrator rights. ServiceTag uses it only for read requests." | the token field's helper (C26; the issue's "do not call it a read-only token") |
| P16-6 | "Test connection" | the button (C26, C19) |
| P16-7 | "Home Assistant answered. The address and the token work." | Test connection's success line (C26) |
| P16-8 | "Disconnect" | the button and the dialog's confirm (C26, C17) |
| P16-9 | "Disconnect Home Assistant? Linked assets stop following it and keep their current season and history. The token is deleted from this phone; revoke it in Home Assistant as well." | the confirm dialog's body, no title (C17; limit 10) |
| P16-10 | "Not connected. Enter the server address and an access token." | the screen with no connection; C16's `NO_CONNECTION` (C26) |
| P16-11 | "Enter the access token again: this phone no longer has it." | `NEEDS_TOKEN`, on both screens (C17, C18) |
| P16-12 | "That address is not allowed. Use https:// with a server name or private IPv4 address, or http:// with a private IPv4 address." | `ENDPOINT_REFUSED` (C8, C17, C19) |
| P16-13 | "Could not reach Home Assistant. Check that this phone is on the same network." | `UNREACHABLE` |
| P16-14 | "Home Assistant took too long to answer." | `TIMED_OUT` |
| P16-15 | "ServiceTag is not allowed to use the network. Allow network access in the app settings, then close ServiceTag and open it again." | `DENIED`, with "Open app settings" (reused) — P85-10's shape without its download clause |
| P16-16 | "Home Assistant refused the access token." | `AUTH_REFUSED` |
| P16-17 | "Home Assistant has no entity %s." — `%s` the entity id | `ENTITY_NOT_FOUND` |
| P16-18 | "Home Assistant reports \"%1$s\" for %2$s, which is neither on nor off, so the season is unchanged." — the reported state (C5's bound), the entity id | `UNSUPPORTED_STATE` |
| P16-19 | "Home Assistant sent an answer ServiceTag cannot read." | `MALFORMED` |
| P16-20 | "Home Assistant answered with a redirect, which ServiceTag does not follow. Enter the address it redirects to." | `REDIRECTED` |
| P16-21 | "Home Assistant answered with an error (%d)." | `HTTP_ERROR` (P85-15's shape) |
| P16-22 | "Link to Home Assistant" | the season card's action (C27) |
| P16-23 | "Follow Home Assistant" | mode control (the issue's words) |
| P16-24 | "Force in season" | mode control (the issue's words) |
| P16-25 | "Force out of season" | mode control (the issue's words) |
| P16-26 | "Sync now" | the block's button (C21) |
| P16-27 | "Follows %s" — the entity id | the source line in FOLLOW |
| P16-28 | "Forced in season. %s is still checked, but it does not change the season until you choose Follow Home Assistant." | the source line in FORCE_IN |
| P16-29 | "Forced out of season. %s is still checked, but it does not change the season until you choose Follow Home Assistant." | the source line in FORCE_OUT |
| P16-30 | "Last successful check %s" — the shipped date-and-time display | the last-success line (C13's `last_success_at`) |
| P16-31 | "No reading from Home Assistant yet." | before the first valid observation |
| P16-32 | **REOPENED — the block below, RATIFIED 2026-10-03** | the stale marker (C27) |
| P16-33 | "Changed in Home Assistant %s" — HA's `last_changed`, formatted for display when it parses as ISO-8601, the line omitted otherwise | information only (R16-3) |
| P16-34 | "Started from Home Assistant on %s" | the provenance line after a START applied from Home Assistant: source `HOME_ASSISTANT` (R16-11; C33(4)) |
| P16-35 | "Ended from Home Assistant on %s" | after an END applied from Home Assistant (`HOME_ASSISTANT`) |
| P16-36 | "This asset is no longer maintained here, so Home Assistant no longer changes its season." | `NOT_MAINTAINED_HERE` (C13, C16) |
| P16-37 | "This phone's date is before the latest season entry, so the season stays as it is until the date catches up." | `DATE_BEFORE_HISTORY` (H4) |
| P16-38 | "This asset's season is no longer started and ended by hand, so Home Assistant cannot change it. Choose Stop syncing, then Resume syncing." | `NOT_MANUAL` (defensive; reworded in rev 1.1 so it names what C17 allows — Resume runs the reconciliation, C-4) |
| P16-39 | "Stop syncing" | the block's action (C17) |
| P16-40 | "Resume syncing" | a stopped binding's action (C17) |
| P16-41 | "Syncing is stopped. Home Assistant no longer changes this asset's season." | a stopped binding's line, on every mode's branch (reworded in rev 1.1: the asset may no longer be MANUAL, C-4) |
| P16-42 | "Entity ID" | the setup sheet's field (C16) |
| P16-43 | "An on/off helper that is on while this asset is in season, for example input_boolean.example_heater_in_season. Not the appliance's own power switch." | the field's helper (AC2, H10) |
| P16-44 | "Linking this asset to Home Assistant replaces its calendar dates. From today, its season starts and ends when Home Assistant says. Maintenance that counts from the start of the season will count from today." | the setup sheet, a CALENDAR asset (C16) |
| P16-45 | "This asset has no operating season now. Linking it to Home Assistant gives it one: in season from today, then started and ended when Home Assistant says. Maintenance that counts from the start of the season will count from today." | the setup sheet, a YEAR_ROUND asset (C16) |
| P16-46 | "From now on Home Assistant starts and ends this asset's season. Its season history stays as it is." | the setup sheet, a MANUAL asset (C16) |
| P16-47 | "This asset's season follows Home Assistant. Stop syncing on the asset's page to change it here." | the editor's read-only season block (C27) |
| P16-48 | "A token is saved on this phone." | the token field after a save (C26) |
| P16-49 | "Enter an entity ID such as input_boolean.example_heater_in_season: lowercase letters, digits and underscores, with one dot." | C16's `BAD_ENTITY_ID` |
| P16-50 | **REOPENED — the block below, RATIFIED 2026-10-03** | `NOT_ON_LOCAL_NETWORK` (C19 step 1a) |
| P16-51 | "That server name does not resolve only to private network addresses, so ServiceTag did not contact it." | `NAME_NOT_LOCAL` (C19 step 1b, C-9) |
| P16-52 | "Home Assistant's certificate could not be verified." | `TLS_FAILED` (C19 step 5, N-4) |

**RATIFIED 2026-10-03 (the rev 1.4 block, R16-Q-I; R16-Q-D and R16-20 as amended, C32, C-2/C-3).** Thirty-one strings,
the set new or changed since rev 1.2, as written but **six amended verbatim** (P16-60, 66, 67, 69, 75, 80; the inner ‘ ’ are the owner's); **P16-64
withdrawn** (C-2); **P16-83/84 added** (R16-Q-H). Homes: `HomeAssistantStrings.kt` but P16-32 and P16-83/84
(`SeasonSyncStrings.kt`). Source: `.superpowers/sdd/2026-10-02-issue-16/owner-rulings-2026-10-03.md`.

| id | proposed wording | where it is drawn |
|---|---|---|
| P16-32 | "Not checked successfully within the chosen interval." | the season card's stale marker: one cadence without a success (C27) |
| P16-50 | "This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant." | the card's error line: a different Wi-Fi, mobile data or no network (C19 step 1a) |
| P16-53 | "How often to check" | Home Assistant screen: the cadence control's label (C26) |
| P16-54 | "Every 12 hours" | cadence choice |
| P16-55 | "Once a day" | cadence choice, preselected |
| P16-56 | "Once a week" | cadence choice |
| P16-57 | "Once a month" | cadence choice |
| P16-58 | "Where to check" | Home Assistant screen: the network control's label (C26) |
| P16-59 | "Any network" | network choice (https only) |
| P16-60 | "Only on this home Wi-Fi" | network choice — **amended 2026-10-03** |
| P16-61 | "Only for an https:// address you have made reachable from outside your home. The token is sent from whatever network this phone is on." | under "Any network" (limit 18) |
| P16-62 | "Use the network I'm on now" | the capture button under P16-60 (C32) |
| P16-63 | "Home Wi-Fi: %s" | after a capture; %s is the captured Wi-Fi name |
| P16-65 | "To check that this phone is on your home Wi-Fi before it sends the access token, ServiceTag needs to read the Wi-Fi network's name. Android allows that only with precise Location permission, so choose Precise. ServiceTag does not use your location, but Android will list it among apps that used location." | before the precise-location request, when P16-60 is chosen (C26) |
| P16-66 | "Without precise Location permission, ServiceTag cannot tell which Wi-Fi this is, so ‘Only on this home Wi-Fi’ stays off." | after a refusal or an approximate-only grant — **amended 2026-10-03** |
| P16-67 | "An http:// address needs ‘Only on this home Wi-Fi’. Choose it, or use an https:// address." | saving an http address with "Any network" (C17) — **amended 2026-10-03** |
| P16-68 | "Turn on Location so ServiceTag can read the Wi-Fi network's name, then try again." | at capture with Location off; after P16-77 when Location is off |
| P16-69 | "With background checks off, ServiceTag checks when you open or return to the app after the chosen interval, and when you tap Sync now. It still needs precise Location while the app is in use." | under P16-70 while background checks are off or paused — **amended 2026-10-03** |
| P16-70 | "Background checks on this home network" | under P16-60: the sub-setting's label (R16-20) |
| P16-71 | "Off" | background checks choice, preselected |
| P16-72 | "On" | background checks choice |
| P16-73 | "To check in the background, ServiceTag must read the Wi-Fi network's name while the app is closed. Android allows that only when Location is set to \"%s\". ServiceTag reads only the network's name, never where you are, and Android will remind you that it has this access." | before the background-location request; %s is Android's own label for the option (C26) |
| P16-74 | "ServiceTag also checks in the background while this phone is on your home Wi-Fi." | under P16-70 while background checks run |
| P16-75 | "Background checks are paused because Android does not let ServiceTag read the Wi-Fi network's name in the background. ServiceTag checks when you open or return to the app and when you tap Sync now." | Home Assistant screen and card: On without the background grant (C22) — **amended 2026-10-03** |
| P16-76 | "Allow again" | the action under P16-75 and P16-78: re-runs the matching permission flow |
| P16-77 | "ServiceTag could not confirm that this phone is on your home Wi-Fi, so it did not contact Home Assistant." | the card's error line when the Wi-Fi name could not be read (C19 step 1a) |
| P16-78 | "ServiceTag no longer has permission to read the Wi-Fi network's name. Allow precise Location again." | after P16-77: permission withdrawn, a one-time grant expired, or reset by Android |
| P16-79 | "ServiceTag has only approximate Location, which hides the Wi-Fi network's name. Change it to Precise in the app settings." | after P16-77, with "Open app settings" |
| P16-80 | "ServiceTag could not read the Wi-Fi network's name in the background. It checks again when you open or return to the app." | after P16-77: the read ran outside the foreground — **amended 2026-10-03** |
| P16-81 | "On Ethernet, ServiceTag cannot tell which network this is. Connect to your home Wi-Fi, or choose \"Any network\" with an https:// address." | at capture on Ethernet; after P16-77 on Ethernet |
| P16-82 | "Connect this phone to your home Wi-Fi, then try again." | at capture on mobile data or with no network |
| P16-83 | "Forced in season on %s" — `%s` the date, as P16-34's | the card's provenance line after a START applied under Force in season: source `FORCED_IN` (C33(4); R16-Q-H) — `SeasonSyncStrings.kt` |
| P16-84 | "Forced out of season on %s" | after an END applied under Force out of season: source `FORCED_OUT` — `SeasonSyncStrings.kt` |

**Reused verbatim from their one home:** "Operating season" (`OPERATING_SEASON`, `A/ui/asset/AssetEditScreen.kt:94`;
also the mode control's group name for TalkBack); the effective season drawn by the shipped `PhaseBadge`
(`A/ui/asset/AssetDetailScreen.kt:1265`) with its words unchanged; "Season history" (`SEASON_HISTORY`,
`A/ui/asset/AssetViewModels.kt:1487`); "Start season" / "End season" (`A/ui/condition/Offers.kt:43`, `:46`), drawn as
shipped for an unlinked or stopped asset; "Open app settings" (`OPEN_APP_SETTINGS`, `A/ui/api/DeveloperApiScreen.kt:42`)
beside P16-15; S55 (`SEASON_STRANDS_PRE_SERVICE` through `seasonStrands`, `AssetEditScreen.kt:129-130`,
`AssetViewModels.kt:2909-2910`) for a strands refusal at link time; #78's P78-1a / P78-1b / P78-2 / P78-3
(`AssetEditScreen.kt:171-193`, through `notTiedToSeason`) after a YEAR_ROUND link; "Save" (`SAVE_LABEL`,
`A/ui/attachments/AttachmentEditSheet.kt:43`); "Cancel" (`CANCEL_BUTTON`, `A/ui/asset/AssetDetailScreen.kt:177`);
"Back" (the shipped top bars' content description, e.g. `AssetDetailScreen.kt:291`).

**Not reusable:** P85-10/P85-12 speak of a download (`A/ui/references/MaterializeStrings.kt:29`, `:31`); P16-15/P16-14
take their shapes. **Flags, settled by the ratification:** P16-44/45 disclose the cadence move, P16-28/29 say HA is
still checked under Force; kept as ratified: the fictional entity id in P16-43/49, no reveal toggle, the issue's mode
labels. N-11 noted, not changed: ratified P16-13 ("same network") reads oddly for an "Any network" origin.

**G-list (developer-facing, ratified with the block):**
- **G1** — `SEASON_SYNC_ENABLED`'s message: "this asset's season follows Home Assistant; stop its season sync on the
  phone first".
- **G2** — the MCP's `APP_SCHEMA_TOO_OLD` feature name "Home Assistant season sync".
- **G3** — the log lines: "season sync run failed; WorkManager retries it" (a local database failure only), "the Home
  Assistant key sweep failed; the next start repeats it" — each names the step and never a URL, host, entity or
  token (C31's grep (5) reads every `Log` call for those words and expects none).
- **G4** (2026-10-03, as written) — "the Home Assistant key store failed; the next run repeats it" (`KEY_STORE_FAILED`, B6a).
- **G5** (2026-10-03, amended) — "a Home Assistant check could not run; it will retry on the next resume, scheduled
  run, or app start": B6a's four silent sites (C33(6), B2b); no URL, host, entity, Wi-Fi name, token or exception.

## 6. Owner rulings

| ruling | status | the ruling (the owner's words where the owner ruled) | where it lands |
|---|---|---|---|
| **R16-0** | **owner — DECIDED 2026-10-02** | **The scope sentence: RATIFIED exactly in substance**, and `GET /api/` for Test connection is also in scope; the fence stays binding. | header, every brief's must-nots |
| R16-1 | **DECIDED** — ratified | **Season authority = audit Q1:** the binding owns a MANUAL asset's season while enabled; setup switches CALENDAR or YEAR_ROUND into MANUAL at today's phase through `SetSeasonMode`'s rules (the strands refusal stays and is shown; #78's prompt follows a YEAR_ROUND link, never silently; the cadence consequence is R16-Q-G's); while enabled the phone's Start/End become the three mode controls and four writers answer one 409 `SEASON_SYNC_ENABLED` — the asset PATCH's legacy pair keeps its shipped 422 (C-3); the merge and the replace restore stay unguarded (the merge brings history the next run reconciles once, in FOLLOW and FORCE; the restore cascades the binding away); Stop and Disconnect write nothing and leave MANUAL at today's phase. A fourth `SeasonMode` forces a format bump and travels; writing over CALENDAR needs an engine change. | C15–C17, §4 |
| R16-2 | **DECIDED** — ratified | **Idempotency inside the write:** compare the desired phase with today's from the activation rows; the two 409s are "already applied"; one transaction discipline — the extracted in-transaction body (`ReplaceAsset`'s C15 shape), never a nested `run` (H3). | C12, C13 |
| R16-3 | **DECIDED** — ratified | **The transition date = the local day it is applied;** HA's `last_changed` is information; HA's change time, the fetch time and the application time are three fields. | C5, C13 |
| R16-4 | **DECIDED** — ratified | **Device-local, schema 21, format 20:** `ha_connection` (address, no token, no display name — one connection needs none) and `season_sync_binding`; neither travels; the nine table sets and the schema-only `VersionAgreementTest` split move; a platform restore without the key reads **NEEDS_TOKEN** — derived from the store at read time rather than flipping `enabled`, so entering the token is the one reauthorization step. | C9–C11, C17, C18 |
| R16-5 | **DECIDED** — ratified | **The secret store = D9's design:** a Keystore AES-GCM key, ciphertext in `noBackupFilesDir`, no dependency, a core `SecretStore` port with a JVM double; the token never in a log, diagnostic, `toString`, API, MCP, backup or pack. **One stated deviation (N-1):** D9's "excluded by `dataExtractionRules`" (`09-security-privacy.md:25`; D3 `:445`; D8 S7) is not done — the platform never copies `noBackupFilesDir`, so the rule would add nothing; B10 notes it beside those lines. | C7, C18, C30 |
| R16-6 | **DECIDED** — ratified | **A new small LAN client, not #85's downloader;** #85's classifier, probe, allowlist and `User-Agent` reused; the rule in code; exact `on`/`off` only; DENIED quiet; #85's policy untouched. | C8, C19, C20 |
| R16-7 | **DECIDED** — ratified; **period amended 2026-10-03** | **The backstop's shape:** unique periodic work, CONNECTED, exponential backoff, single flight plus the revision guard, Sync now explicit, no opportunistic network callback; **the period is the connection's cadence and changes enqueue with UPDATE** (R16-Q-D as amended; C22). | C21–C23 |
| R16-8 | **DECIDED** — ratified | **Lifecycle:** `maintainedHere(held)` on the applier and the link; a replaced asset's binding stays inert on the predecessor. | C13, C16 |
| R16-9 | **DECIDED** — ratified | **API/MCP read-only:** one sub-resource (the 28th), one MCP read tool (90); no write, no address, no token. | C3, C24, C25 |
| R16-10 | **DECIDED** — ratified | **Cardinality:** one connection per installation, any number of bindings, at most one per asset. | C9, C17 |
| R16-11 | **DECIDED** — ratified | **No provenance column on activation rows;** the binding's `applied_*` fields carry it. | C9, C13 |
| R16-12 | **DECIDED** — ratified | **Proofs under the 2026-10-02 ruling:** JVM first; one device class for WorkManager, the Keystore, the cleartext policy and the client against an in-process fake HA, one Compose class; 59 → 61 on the software-rendered emulator; the fake HA is the device test, and AC10's proof is §7's real-HA proof (R16-Q-A). | §3, §7 |
| R16-13 | **DECIDED** — ratified (plan default) | **Test connection reads `GET <base>/api/`** (HA's API root), the same client, headers and rules as the poll: the one way to prove the address and token before any entity is linked. | C19, C26 |
| R16-14 | **DECIDED** — ratified (plan default) | **Disconnect deletes the connection, its bindings (CASCADE) and the token;** a per-asset **Stop syncing** keeps the binding and its provenance. | C17 |
| R16-15 | **DECIDED** — ratified (plan default) | **Polls continue under a Force mode** and record HA's answer as status, and **every run re-asserts the forced phase through `applyIfChanged`**, whatever HA answered (C-5): idempotent through the compare, at most one row, provenance recorded — so a merge or pack that landed the other phase is corrected on the next run (row 36) and the owner still sees what HA says before returning to Follow. | C13, C27 |
| R16-16 | **DECIDED** — ratified (plan default) | **The editor's season block is read-only and the journal's season offer is not raised** while a binding is enabled; the guard stays the backstop. `SeasonOffers.offerFor`'s condition is the plan's **one recorded exception** to "never re-check in the UI a rule the use case owns" (N-11): the offer is a prompt, not a write, and its refusal would come only after the owner accepted. | C15, C27 |
| R16-17 | **DECIDED** — ratified (plan default) | **The MCP tool is appended as a "#16" block** (the shipped convention), so the two tail pins move (audit correction 3) rather than inserting it among the season tools. | C25, §3 |
| R16-18 | **DECIDED** — ratified (plan default) | **A binding resumes on its own when its asset is maintained here again (C-11).** Unarchiving (`C/usecase/ArchiveAsset.kt:30`), or withdrawing an OUT and then unarchiving (a withdrawal leaves the assets archived, `WithdrawTransferRecord.kt:37-38`), leaves a still-enabled binding enabled; the next successful read applies the current state only — at most one transition dated that day, no catch-up — with no owner step. The binding is this phone's own, never imported, so the issue's "no resume before explicit reauthorization" (about restored or imported configuration) does not apply. The alternative — archive, retire and transfer-out flip `enabled = false` in the same write, so Resume is the owner's step — costs a writer in `ArchiveAsset`, `RetireAsset` and `MarkTransferredOut`, which stay fenced. | C17, row 26a |
| R16-19 | **DECIDED** — ratified (plan default) | **Resume runs the link's reconciliation (C-4).** A stopped binding whose asset the owner moved to CALENDAR or YEAR_ROUND resumes through C16's steps — the P16-44/45 sentence, the switch row dated today, S55 on a strands refusal, #78's prompt after a YEAR_ROUND resume — and the binding block is drawn on every season mode's branch, so no stopped binding is a dead end. No per-asset Unlink is added (R16-14's Disconnect stays the only delete); P16-38 and P16-41 are reworded to match. | C17, C27, rows 43a, 67, 68 |
| R16-20 | **owner — DECIDED, AMENDED 2026-10-03** (supersedes "foreground only in v1") | **"Background checks on this home network: Off / On"** under "Only on this home Wi-Fi/local network": **Off** (default) — "No background-location capability is requested"; checks on foreground resume when stale and on Sync now; **Off still needs the foreground precise-location grant**, without which the home-network mode cannot read the Wi-Fi name — only background location is never asked. **On** — "ServiceTag may request the Android permissions actually required to identify the selected home network from background execution, then run the configured WorkManager cadence only when that network matches"; "Denying or later revoking the permission falls back to foreground-only behavior"; "Do not request location / nearby-Wi-Fi permissions unless the owner enables this option." "Any network" is https-only with background work allowed. **Costs of On, stated honestly (C-6):** a fourth runtime permission (`ACCESS_BACKGROUND_LOCATION`) and its strings (P16-73…76); on API 30+ the owner grants it on the app's settings page, not in a dialog; Android's periodic reminders of background location access; a D9 change (C30). **The permission-free mDNS `uuid` option** is a later candidate that does not meet the owner's authentication bar in v1 (C32). | C4a, C21, C22, C26, C29, C32, limit 19 |
| R16-21 | **DECIDED (plan; the owner's required audit; C-1, C-2)** | **The home-network identity = the Wi-Fi name only** (`homeNetworkSsid`), captured by "Use the network I'm on now"; never a BSSID, address, location or wired marker; read by `WifiManager.getConnectionInfo()` on 26–30 and a one-shot `FLAG_INCLUDE_LOCATION_INFO` callback on 31+, with `ACCESS_WIFI_STATE` (no `maxSdkVersion`) and precise location asked only for the home-network option, background location only for On; `NEARBY_WIFI_DEVICES` cannot read the name and is not used; the manifest declares, it does not grant. | C4a, C32, P16-62…82 |
| **R16-Q-A** | **owner — DECIDED** | **Proof = deterministic JVM scenario + device/platform tests against an in-process fake HA + one bounded end-to-end proof from the emulator to the real owner HA over the LAN**, with the private fixture already recorded; no real endpoint, hostname, entity id or token in committed code, docs or logs (placeholders only); **the development phone is untouched** — a physical-phone check may come later as an optional #98 / 1.0.0 smoke, not #16's gate. AC10 in the issue now reads this way. | §1 AC10, §7, rows 60, 71 |
| **R16-Q-B** | **owner — DECIDED (strict, as proposed)** | **(a)** http only to an RFC 1918 IPv4 literal; https to a DNS name or a private IPv4 literal; no redirects; system trust anchors only. **(b) superseded 2026-10-03 by R16-Q-D's network eligibility:** http requires "Only on this home Wi-Fi/local network", and every request in that mode first matches the owner-selected network, else the quiet `NOT_ON_LOCAL_NETWORK` and nothing sent ("do not reduce it to 'any Wi-Fi or Ethernet'"); no VPN special case. **(c)** in the home-network mode, an https name only when **every** resolved address is private (RFC 1918 IPv4 or IPv6 ULA `fc00::/7`) at request time, else `NAME_NOT_LOCAL`. **(d) No user-installed or private CA trust in v1:** a private-CA endpoint honestly reports `TLS_FAILED`. The limit stays explicit (limit 15): **the captured network** plus an RFC 1918 literal narrows but cannot remove a foreign LAN's host owning that address (C-4). | C8, C19, C20, limits 15, 17 |
| **R16-Q-C** | **owner — DECIDED** | **P16-1…52, the reused strings and G1–G3 RATIFIED, with eight wording changes** (P16-3, 5, 12, 28, 29, 44, 45, 51 — §5, verbatim); P16-28/29 say HA is still checked under Force but does not change the season. | §5, C26, C27 |
| **R16-Q-D** | **owner — DECIDED, AMENDED 2026-10-03** | **Cadence:** "V1 offers only: Every 12 hours / Once a day — DEFAULT / Once a week / Once a month"; "No 15-minute, 30-minute, 1-hour, 2-hour, 4-hour or 8-hour product choices"; "a best-effort WorkManager cadence, not an exact deadline. Sync now remains available. Staleness follows the selected cadence". **Network eligibility:** "Any network — for an HA endpoint intentionally reachable through its configured HTTPS origin" or "Only on this home Wi-Fi/local network — for a private HA instance"; "do not reduce it to 'any Wi-Fi or Ethernet'. ServiceTag must determine that it is on the owner-selected relevant local network before sending the bearer token"; the permission "only when the owner enables this option, with clear UI wording", never "preemptively for owners who choose Any network"; "the existing LAN transport fence still applies"; both are "one-connection-per-installation settings" in the connection model and schema 21; "Changing cadence must update/re-enqueue the unique periodic work so an obsolete interval does not survive." | C4a, C9, C17, C19, C21, C22, C26, C32, §5 |
| **R16-Q-E** | **owner — DECIDED** | **The HA connection and the binding are never in a ServiceTag backup/export, merge or Transfer Pack contract;** no format bump (format stays 20). Wording: never "never travels under any restore mechanism" — Android Auto Backup is Q-F's. | C9, row 20, I11 |
| **R16-Q-F** | **owner — DECIDED: YES** | **The non-secret Room configuration may stay in Android Auto Backup** (base URL, entity ids, observed state and HA change text, error detail, modes and times, applied provenance — and, since rev 1.3, **the home Wi-Fi's name**, which Android treats as location-sensitive; C-6); the token, key and ciphertext do not restore as a usable credential, so after a platform restore the binding reads **NEEDS_TOKEN and sends nothing** until the owner re-enters a token — the explicit reauthorization step. The rule: device-local in ServiceTag portability; restorable as inert non-secret configuration through the platform backup. | C9, C17, C18, limit 4 |
| **R16-Q-G** | **owner — DECIDED: the shipped behaviour** | **`SetSeasonMode`'s switch row dated today; no special backdated-anchor path for #16.** Linking or resuming a CALENDAR or YEAR_ROUND asset may re-anchor IN_SERVICE schedules to today; this is disclosed before the write by the amended P16-44/45 and pinned by the schedule-cadence row (40a). | C16, C17, limit 14, row 40a |
| **R16-Q-H** | **owner — DECIDED 2026-10-03: (a)** | **Provenance under a forced override:** "Add the last-application source to the binding within the still-unreleased schema 21 so a forced application is never mislabeled as one applied from Home Assistant"; the two new strings "Forced in season on %s" / "Forced out of season on %s" RATIFIED (P16-83/84); "Keep P16-34 / P16-35 for actual Home Assistant-driven applications." R16-11 stands: the source is a binding column, never one on activation rows. | C3, C9, C13, C27, C33, B2b |
| **R16-Q-I** | **owner — DECIDED 2026-10-03** | **The rev 1.4 block's 31 strings RATIFIED AS WRITTEN but six amendments**, copied verbatim into §5: P16-60, 66, 67, 69, 75, 80 — "The implementation only recognizes a captured Wi-Fi SSID; Ethernet is explicitly not eligible. Do not label that mode as a generic “local network”." | §5, C26, B8a, B10 |
| **R16-Q-J** | **owner — DECIDED 2026-10-03** | **G4 RATIFIED AS WRITTEN; G5 YES, amended:** "a Home Assistant check could not run; it will retry on the next resume, scheduled run, or app start"; "Same privacy rule as G1–G4: no URL, host, entity, Wi-Fi name, token or attached exception text." Emitted once at each of B6a's four silent sites, one line each, G3's shape. | §5's G-list, C33(6), B2b |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; B7b `uv run --frozen pytest` in `M/` and in `S/` (unchanged). **No device
  run in any brief** — B8c's and B9's classes first run at the merged-tip gate.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **61 classes**, zero
  skips; rows 70 and 71 run here first — a red is one post-merge fix round), R3 (the three Python suites), R5 (both
  classes of `ManifestContractTest.kt`, `VersionAgreementTest`), R6 greps. `ReleaseProofPolicyTest` unchanged and
  green. **Record the environment** (#69 §30): the emulator, how it was started (`-no-window -gpu swangle_indirect
  -feature -Vulkan` is the one path alive on this host), whether one run, total elapsed and device time separately;
  the ~20-minute software-rendered projection is **not** judged against the 14/15-minute lines.
- **R6 greps (anchored; `git grep -nE`), expected counts at the tip:**
  - `'^        const val SCHEMA_VERSION = 21$'` → 1; `'^    version = 21,$'` in `AppDatabase.kt` → 1;
    `'^    const val FORMAT_VERSION = 20$'` → 1 (unchanged); `'MIGRATION_20_21'` in `AppGraph.kt` and
    `MigrationTestSupport.kt` → each equal to `'MIGRATION_19_20'`'s count there; `21.json` present; `20.json` diff →
    empty; `'CREATE TABLE'` inside `MIGRATION_20_21` → 2 (read it).
  - `'^enum class SeasonMode \{ YEAR_ROUND, CALENDAR, MANUAL \}$'` → 1; `git diff <base> -- C/schedule C/backup
    C/merge C/transfer C/model/SeasonModel.kt` → empty.
  - `git diff <base> -U0 -- C/fetch A/fetch` → exactly `HopPolicy.kt`'s one keyword and `UrlConnectionTransport.kt`'s
    one KDoc sentence (read it).
  - `'^internal fun recordInTransaction|suspend fun recordInTransaction'` in `RecordSeasonActivation.kt` → 1 (read
    it); `'activations\.insert\('` in `C/seasonsync` → 0 (the body is the only writer, C12).
  - `'"SEASON_SYNC_ENABLED"'` in `ApiJson.kt` → 1; `'"season-sync" to "GET"'` in `ApiRouter.kt` → 1; `'Eighty-two
    path shapes over one hundred and two'` → 1; `'twenty-eight `/v1/assets/\{id\}/…` sub-resources'` in `v1.md` → 1;
    `'21 since #16'` in `v1.md` → 1 (the schema clause only); **rev 1.5:** `'val source: String'` in `SeasonSyncDtos.kt`
    → 1 and `v1.md`'s `lastApplied` row names `source` (read it; C33(5)).
  - `'^@mcp\.tool\('` in `server.py` → 90; `'^_MIN_SEASON_SYNC_SCHEMA_VERSION = 21$'` → 1; `'SEASON_SYNC_ENABLED'`
    in `server.py` → 3 (the three season write docstrings).
  - `'cleartextTrafficPermitted="true"'` in `res/xml/network_security_config.xml` → 1; `'src="user"'` → 0;
    `'domain-config'` → 0; `'android:networkSecurityConfig'` in the manifest → 1.
  - rev 1.3–1.4: `'FLAG_INCLUDE_LOCATION_INFO'` and `'getConnectionInfo'` in `A/seasonsync` → 1 each; in the
    manifest `'ACCESS_FINE_LOCATION'`, `'ACCESS_COARSE_LOCATION'`, `'ACCESS_BACKGROUND_LOCATION'` and
    `'ACCESS_WIFI_STATE'` → 1 each, `'maxSdkVersion'` on them → 0 (C-1), `'NEARBY_WIFI_DEVICES|ACCESS_LOCAL_NETWORK'`
    → 0; no period literal in `A/seasonsync`
    but `SyncCadence.hours` (`'MINUTES|30L|15L'` → 0, read the hits);
    `'isPrivateLanAddress\('` in `C/seasonsync` → 1 declaration (read the callers: C8 and C19 step 1b);
  - `'setRequestProperty\("Authorization"'` in `app/src/main` → 1 (C19); `'instanceFollowRedirects = false'` in
    `HomeAssistantStateClient.kt` → 1.
  - `'enqueueUniquePeriodicWork\("season-sync"|UNIQUE_NAME = "season-sync"'` → 1 (read it);
    `'NetworkType\.CONNECTED'` in `A/seasonsync` → 1.
  - C31's six greps; the tombstone check → 0; gitlink `7e0377a`; `versionName` / `versionCode` unchanged; `git diff
    <base> -- app/build.gradle.kts core/build.gradle.kts gradle/libs.versions.toml tools/*/pyproject.toml
    tools/*/uv.lock` → empty; `git diff <base> -- tools/servicetag-schedules tools/servicetag-bundle` → empty.
- **The schema-21 upgrade note.** The schema 20 → 21 in-place upgrade of a signed build is **not** proven by this
  plan's gate: it is the carrying release's gate, written by B10 (C30) beside the schema-18…20 paragraphs; no
  release carries 18–20 yet, so that first release proves all four.
- **The end-to-end proof against the real HA (AC10; R16-Q-A, DECIDED).** A proof, not a test: the gate keeps the
  in-process fake HA (row 71) and never depends on the owner's network.
  - **Who, where, when:** the controller, once, after the merge and the merged-tip gate, on `emulator-5554`
    (`-no-window -gpu swangle_indirect -feature -Vulkan`), the merged tip's debug build, reaching the owner's HA through
    the emulator's NAT. The http connection is saved "Only on this home Wi-Fi" (P16-60) with the emulator's Wi-Fi
    captured (precise location granted by the driver; the name recorded, expected `AndroidWifi`); the same address with
    "Any network" is refused (P16-67); the environment record states the active transport.
  - **Secrets by path only:** the base URL, the https name and the entity ids from `~/.config/servicetag/ha-test-proof.env`,
    the token from `~/.config/servicetag/ha-test.env`, read by the driver, never echoed, never in a tracked file,
    report, ledger, log or issue (reports say `<ha-base-url>`, `<ha-https-name>`); the token is typed into the masked
    field on the development machine only (limit 17).
  - **The fixture (the owner's real test helpers, allowed by name):** `input_boolean.servicetag_test_in_season` (starts
    off) and the other `servicetag_test_*` helpers by key — a source switch, a template binary_sensor `unavailable` while
    it is off, an input_text holding an odd state, an entity id no entity has. The season boolean is flipped **only from
    the host through HA's service API**, never through the app.
  - **What it observes** (Sync now, read back through C24's route and the card; a fictional MANUAL "Example Heater"):
    on → one START today; off → one END; **offline recovery** (airplane mode or a blocked route: a status, no row; the
    boolean flipped meanwhile; the next success applies one transition that day); **an override** (Force out while HA
    says on: one END, `lastApplied.source` `FORCED_OUT`; polls leave it; Follow applies a fresh read, `HOME_ASSISTANT`); **status only** — `unavailable` and the odd state
    (`UNSUPPORTED_STATE`), the missing entity (`ENTITY_NOT_FOUND`); **the private-CA https name** → `TLS_FAILED`
    (R16-Q-B (d)); Test connection OK; **background (R16-20 as amended)** — Off: no work; On with the background grant:
    the work, forced once, checks the network first; the grant revoked (`pm revoke` ends the process) and the app
    reopened: P16-75, no work, Sync now still works. Background timing is recorded honestly, never as a deadline.
  - **Afterwards:** the fixture set back, Disconnect on the emulator, the record in the ledger with placeholders only.
- **The emulator and the phones.** The merged-tip gate runs a debug build at schema 21 on the emulator only; the
  development phone is untouched (no install). Not proven by #16: a phone's own Wi-Fi, DNS and Doze timing (limit 13;
  the optional #98 / 1.0.0 smoke); a platform Auto Backup restore onto a second device (JVM-proven through the
  store's missing-key answer, row 48, and the device row's deleted-key case, row 71).

## 8. Relationships

- **#14, #60, #4:** reused unchanged — an HA transition is a manual START or END through the shipped operation, so
  every policy and the break behave as for a tap (C12, row 32). **#78:** its prompt reused, its predicate lifted (C27).
- **#85 / #66:** one visibility keyword and one KDoc sentence; `transportFailureOf`, the INTERNET probe, the
  `User-Agent` and #66's denial pattern reused; #85 stays https-only (row 14). **#77 / #86:** `maintainedHere(held)`;
  no successor inherits a binding. **#46 / #92:** one read route; the loopback listener is untouched.
- **#56 / #57:** fenced (C31); the connection is its own table, so a later binding kind could share it with no second
  credential store or poller (audit §10). **#76:** the roadmap of record. **#90:** reporting only on a software run.
  **#98:** follows; by R16-Q-E its export → restore carries no binding. **#62:** rows 70 and 71 are tiers 2–4.

## Briefs — common to every brief (twenty dispatches)

Read §1–§8, the audit, issue #16 and every earlier report on this branch. **Dispatch precondition: MET** (§6); §5's
rev 1.4 block was RATIFIED 2026-10-03 (R16-Q-I). **Core doubles (C7):** fixtures through `BackupInstall`, never a bare list.
**Fenced words (C-3, C31):** no #69 C33(1) or #56 word added anywhere, even to deny it. **Constructor plumbing (#47
C-1):** a new constructor parameter's brief owns its `AppGraph` and `FakeGraph` lines and every construction site
(arguments only); **no `AppGraph`/`FakeGraph` edit after B6a**. **Forced arms (#69 C-4):** a brief owns every compile
error its own change forces, arguments and receivers only, each listed; any other compile error stops it.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (over `app/src/main` and `core/src/main` unless
named); the untouched diff against the brief's "never touches"; C31's six greps; the tombstone check → 0; `git ls-tree
HEAD libs/nfc-tag-core` → `7e0377a…`; `versionName` / `versionCode` equal `<base>`'s; `git diff <base> --
app/build.gradle.kts core/build.gradle.kts gradle/libs.versions.toml tools/*/pyproject.toml tools/*/uv.lock` →
empty; `git diff <base> -- app/src/main/AndroidManifest.xml` → empty **except in B5**; `git status` clean; B7b adds
`uv run --frozen pytest` in `M/` and `S/`.

**Pin rule and the twin rule.** A shipped assertion moves only where §3's table names it for this brief, or as its
twin. Confirm the set first with `git grep -nE 'SCHEMA_VERSION|schemaVersion\)|V19_TABLES|V20_|ALL_TABLES|tableNames\(\) - ROOM_INTERNAL|twenty-seven|since #69|RecordSeasonActivation\(|SetSeasonMode\(|SaveAssetSettings\(|AcceptSeasonOffer\(|SeasonOffers\('`
and `git grep -nE 'TOOL_NAMES|\b89\b'` over `app/src/test app/src/androidTest core/src/test
tools/servicetag-mcp/tests`; report every hit the table does not name and how the twin rule treats it before
editing.

**Commits:** one casual lowercase subject line, no body, trailers or attribution; natural REDs committed test-first.
**Report:** each counted RED with its quoted failure, rows without one and why, each grep's count, test counts before
and after, twins moved, wall time, what is done, proven and not proven.

**Stop and report, always:** an edit to a never-touched file; an assertion that must move outside the pin and twin
rules; a JVM or build failure the brief did not cause (reported, not retried); a socket opened by a JVM test; a phone
string not in §5; a schema change outside B2 and B2b (C33) or any format change; a cited line that reads differently on `<base>`;
an undecided owner question; the cap reached, the 1-hour target passed with under half the rows green, or the 2-hour
hard stop.

**Must NOT, always:** step outside the scope (R16-0, RATIFIED): "#16 = one bounded workflow — an
owner-enabled binding from an existing Asset to one Home Assistant boolean entity … Nothing more." — so never add a
telemetry, reading, meter or measurement path (#56); a write to HA; an NFC surface; InfluxDB, Grafana, MQTT,
WebSocket or SSE; a remote-access service, tunnel or relay of ServiceTag's own (the owner's https origin under "Any
network" is in); a countdown; an inbound listener, server socket in `app/src/main`, foreground service or permanent
socket; a network callback but C32's one-shot read; a second credential store, poller or HTTP stack; a dependency;
a location permission request the owner did not choose (C32). Also never:
add a `SeasonMode` value or a column to a shipped table; touch `BackupData`, the codec, the merge planner or the
transfer graph; write an activation row except through the body (C12); date a row from HA's `last_changed`; apply a
stored observation; put a token in a row, field, log, message, `toString`, state, API or MCP byte, fixture that looks
real, or commit; put the base URL on the API or the MCP; follow a redirect; call `HopPolicy.check`/`staticProblem` or
change #85's behaviour; delete a shipped assertion; commit outside the brief's files; add a device-boundary class or
run a device; bump a version; write a repository from a handler or a view model; re-check in the API or the UI a rule
the use case owns; touch the 2.6 tombstones, `20.json`, `S/`, `tools/servicetag-bundle`, `tools/emulator/*`, a
harness helper or the gate script; write a ledger; quote the owner's private data or write a real name, host, token,
serial or e-mail address (fixtures: §Global constraints).

## 9. B1a — the domain: model, outcome, effective season, ports (C4–C7; core JVM)

**Read:** audit §0.1–0.3, §1, §3, §9; `C/model/SeasonModel.kt`; `C/schedule/SeasonContext.kt:58-74`;
`C/fetch/FetchProblem.kt` (`TransportFailure.Kind`); `CT/testing/BackupInstall.kt` and the #69 C11 doubles' cascade
registration. **Rows:** 1–9. **Rulings:** R16-2, R16-3, R16-5, R16-11. **Interfaces produced:** C4's types, C5's
`mapHaAnswer`, C6's `desiredPhase`, C7's four ports and their doubles. **Greps:** `'class Secret|value class Secret'`
→ 1 and its `toString` constant (read it); `'ignoreCase'` in `C/seasonsync` → 0. **Untouched:** every shipped `C/`
file but `CT/testing/BackupInstall.kt`'s one registration. **Must NOT:** parse `last_changed` into a date; trim or
case-fold the state; give a port a default method. **Counted RED (9):** rows 1–9. **Caps:** 12 runs (`--tests`
filtered for the REDs); 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 220 production lines, 380 test lines.
**Estimate:** 60 min. **Split clause:** past 40 min with rows 6–9 unstarted, rows 6–9 go to a B1a2.

## 10. B1b — the endpoint policy (C8; core JVM)

**Read:** `C/fetch/HopPolicy.kt:25-153` (the allowlist and its helpers), `C/fetch/AddressPolicy.kt:20-49` (what is
**not** reused), `CT/fetch/HopPolicyTest.kt`. **Rows:** 10–14. **Rulings:** R16-6, R16-Q-B (a), (c). **Interfaces produced:**
`HaEndpointPolicy.check`, `canonical`, `EndpointProblem`, `isPrivateLanAddress` (C-9's predicate, used by B5). **Greps:** `git diff <base> -U0 -- core/src/main/kotlin/com/loosecannon/servicetag/core/fetch`
→ exactly one changed line, `HopPolicy.kt:66`'s keyword; `'AddressPolicy|staticProblem'` in `C/seasonsync` → 0.
**Untouched:** every other `C/fetch` line; `A/**`. **Must NOT:** admit http to a name or to a non-RFC 1918 literal;
admit IPv6 for http; reuse `isForbiddenAddress` as an allow rule. **Counted RED (5):** rows 10–14. **Caps:** 8 runs;
1 h / 2 h. **Size:** about 90 production lines, 170 test lines. **Estimate:** 40 min.

## 10a. B1a2 — the connection's settings (C4a; core JVM) — SHIPPED (`ba46d1d8`)

As built (the B1a2 report's signatures are of record): `HaConnection`'s four fields (`cadence`, `networkEligibility`,
`homeNetworkSsid`, `backgroundChecks`, none defaulted), `SyncCadence`, `NetworkEligibility`, `BackgroundChecks`,
`CurrentNetwork`, `eligibleNow`, the double's two invariants; plus the **controller-ordered fold-ins** from B1a's review
— `SeasonSyncPorts.kt` (one KDoc line on `SecretStore.has`) and `HaStateMapper.kt` (`isJsonMediaType`, `parseObject`
made public), the source-scan guard (MINOR-1) and NOTE-6 (C-7). Row 73; the field-name pin counts **eight fields**.
Estimate as re-based: 45 min (C-7). Nothing here is re-dispatched; later briefs build on it.

## 11. B2 — Room schema 21 (C9–C11; app JVM)

**Read:** audit §3; `A/data/room/entities/DeadlineLocalDeliveryEntity.kt` (the device-local precedent);
`A/data/room/Migrations.kt` (`MIGRATION_19_20` for the shape); `A/data/room/AppDatabase.kt:66-104`;
`T/data/room/{MigrationTestSupport,Migration18To19Test,Migration19To20Test}.kt` and the seven other table-set tests;
`T/VersionAgreementTest.kt:65-88`, `:150-159`. **Rows:** 15–20 and §3's B2 pins. **Rulings:** R16-4, R16-10,
R16-Q-E, R16-Q-F, R16-Q-D (C9's four connection columns, C4a). **Interfaces produced:** the two entities, DAOs and adapters; `MIGRATION_20_21`; `21.json`;
`V21_TABLES`. **Greps:** §7's schema lines; `'CREATE TABLE'` inside `MIGRATION_20_21` → 2; `git diff <base> -- core`
→ empty; `'token|secret'` (case-insensitive) in the two entity files → 0. **Untouched:** `C/**`, `A/api`, `A/ui`,
`20.json`. **Must NOT:** add a column to a shipped table; name either table in `BackupCodec`, `BackupData` or
`TransferTables`; give a foreign key anything but CASCADE; store a token marker. **Counted RED (3):** rows 15, 16, 17.
**Caps:** 7 runs; 1 h / 2 h. **Size:** about 190 production lines plus `21.json`, 300 test lines, 13 pin edits.
**Estimate:** 60 min. **Split clause (C-10):** past 40 min with row 20 unstarted, row 20 goes to a test-only B2b.

## 12. B3a — the applier (C12–C14; core JVM)

**Read:** `C/usecase/RecordSeasonActivation.kt` whole; `C/usecase/ApplyTemplate.kt:46-51`; `C/usecase/ReplaceAsset.kt:170-182`;
`C/usecase/SeasonCommands.kt:101-114`, `:265-270`; `C/model/Asset.kt:46-60`; `C/usecase/ArchiveAsset.kt:28-31`;
`C/transfer/HeldWriteGuard.kt:60-70`, `:140-150`; `CT/testing/InMemoryRepositories.kt:645-678`;
`CT/usecase/ManualSeasonTest.kt`; the shipped engine suites named in row 32. **Rows:** 21–32, 26a. **Rulings:** R16-2,
R16-3, R16-8, R16-11, R16-15, R16-18. **Interfaces produced:** `recordInTransaction`; `RecordSeasonSyncResult` with
`applyIfChanged` (internal, for B3c). **Greps:** §7's body lines; `'occurredOn = .*[Ll]astChanged'` → 0;
`'last_success|lastSuccessAt'` assigned only in the `Observed` branch (read it); `desiredPhase` is consulted for every
mode (read it: FORCE re-asserts, C-5). **Untouched:** every other use case; `C/schedule`; `A/**`. **Must NOT:** nest
`run` inside a write; catch a cancellation; write the asset; read the stored observation as a decision; hold a
`Secret` in a typed local (`val t: Secret`) in `C/seasonsync` main — B1a2's source scan flags it; pass it as a parameter. **Counted RED
(10):** rows 22–26, 28, 29, 30 (two), 31. **Caps:** 13 runs; 1 h / 2 h. **Size:** about 170 production lines, 520 test
lines. **Estimate:** 60 min. **Split clause:** past 40 min with rows 28–32 unstarted, they go to a B3a2 (same files).

## 13. B3b — one season authority (C15; core JVM, one app class)

**Read:** C15's table and every cited line; `C/usecase/UpdateAsset.kt:45-73` (left alone, C-3); `A/ui/condition/Offers.kt:225-241`;
`T/ui/condition/OffersTest.kt`; `CT/usecase/{ManualSeasonTest,SeasonModeCommandTest,SeasonEventOfferTest}.kt`.
**Rows:** 33–38. **Rulings:** R16-1, R16-16. **Interfaces produced:** `SeasonSyncOwnsSeason`, `SeasonSyncGuard`.
**Greps:** `'requireNotSynced\('` in `C/usecase` → **4** (one per guarded writer; read each placement);
`'SeasonSyncGuard'` in `Offers.kt` → 1 (the constructor); `git diff <base> -- C/usecase/UpdateAsset.kt
C/usecase/ApplyBackupMergePlan.kt C/usecase/ImportBackupReplace.kt C/usecase/CreateAsset.kt C/usecase/ReplaceAsset.kt`
→ empty. **Untouched:** as §4. **Must NOT:** default the guard parameter; refuse a no-op; guard the legacy pair, the
merge, the restore or the pack; move a shipped refusal's order. **Counted RED (5):** rows 33–37. **Caps:** 8 runs;
1 h / 2 h. **Size:** about 80 production lines, 210 test lines, **26** construction-site argument edits (N-9; the brief
re-counts them first and reports the number) plus the forced `guarded = false` in `RecordSeasonSyncResult.kt` (N-6).
**Estimate:** 50 min.

## 14. B3c — link, modes, stop, resume, disconnect (C16–C17; core JVM)

**Read:** `C/usecase/SetSeasonMode.kt` whole; `C/usecase/SeasonCommands.kt:177-201`, `:239-263`;
`C/schedule/SeasonContext.kt:84-88`, `:183-190`; `C/schedule/ServicePolicyEngine.kt:100-128`; B3a's applier; C16 and
C17. **Rows:** 39–45, 40a, 43a, 74. **Rulings:** R16-1, R16-4, R16-10, R16-14, R16-15, R16-19, R16-Q-G, R16-Q-D. **Interfaces
produced:** `LinkSeasonSync`, `SetSeasonSyncMode`, `EditSeasonSyncEntity`, `StopSeasonSync`, `ResumeSeasonSync`
(with the reconciliation step), `SaveHaConnection`, `ForgetHaConnection`, the derived-state function,
`SeasonSyncLinkRefused`, and a core port the runner implements in B6a (`SeasonSyncScheduler`: `ensure`, `cancel`,
`requestFreshRead(assetId)`) with a recording double. **Greps:** `SetSeasonMode`'s `run` still opens one write and
calls the extracted body (read it); `'manualSwitchActivation'` call sites unchanged in count. **Untouched:** as §4.
**Must NOT:** write an END on stop or forget; link or resume a non-MANUAL asset without the reconciliation step;
date the switch row other than today (R16-Q-G, DECIDED); put the token into a row or a typed local (`val t: Secret`,
flagged by B1a2's source scan) — `SaveHaConnection(…, token: Secret?)` takes it as a parameter only. **Counted RED (10):** rows
39–45, 40a, 43a, 74. **Caps:** 13 runs; 1 h / 2 h. **Size:** about 230 production lines, 380 test lines. **Estimate:**
60 min. **Split clause:** past 40 min with rows 43a–45 unstarted, they go to a B3c2 (same files).

## 15. B4 — the secret store (C18; app JVM)

**Read:** `docs/design/09-security-privacy.md:25`, `:31`; `A/prefs/InstallationIdentity.kt` (the no-backup
precedent); `A/di/AppGraph.kt:424-426`; `A/ServiceTagApp.kt:51-69`. **Rows:** 46–48. **Rulings:** R16-5.
**Interfaces produced:** `KeystoreSecretStore`, `KeyedAead` (Keystore and JDK implementations), `sweepOrphans`.
**Greps:** `'setUserAuthenticationRequired\(true\)'` → 0; `'noBackupFilesDir'` in `A/seasonsync` → 1;
`'Log\.'` in `KeystoreSecretStore.kt` naming no value (read each). **Untouched:** the manifest; `C/**`. **Must
NOT:** add `security-crypto`; reuse an IV; let a decrypt failure throw out of `get`; add a backup rule (R16-5's stated
deviation). **Counted RED (3):** rows 46–48. **Caps:** 6 runs; 1 h / 2 h. **Size:** about 140 production lines, 200
test lines. **Estimate:** 45 min.

## 16. B5 — the LAN client and the cleartext policy (C19–C20; app JVM)

**Read:** `A/fetch/UrlConnectionTransport.kt` whole; `A/fetch/TransportFailures.kt`; `A/fetch/InetHostResolver.kt`;
`C/fetch/AddressPolicy.kt:12-17`; `T/fetch/UrlConnectionTransportTest.kt` (the fake connection);
`C/fetch/FetchPorts.kt:9-30`; `app/src/main/AndroidManifest.xml:1-64`; `T/reminders/ManifestContractTest.kt`.
**Rows:** 49–56, 53a, 54a, 54b, 54c. **Rulings:** R16-6, R16-13, R16-Q-B (a)–(d), R16-Q-D, R16-21. **Interfaces produced:**
`HomeAssistantStateClient` (`read`, `testConnection`), `CurrentNetworkReader` (C32), the XML, C32's permission lines. **Greps:** §7's client and config
lines; `git diff <base> -U0 -- app/src/main/AndroidManifest.xml` → the attribute, the comment and C32's permission lines (C-4); `git diff
<base> -U0 -- A/fetch` → `UrlConnectionTransport.kt`'s KDoc sentence only; `'registerNetworkCallback'` → 0.
**Untouched:** `C/**` (the private-address predicate is B1b's); #85's code. **Must NOT:** follow a redirect; trust user CAs;
set a process-wide default; send in the home-network mode off the captured network; treat a hidden name as a match;
request a permission (B8a's); contact a name that resolves to a non-private address in the home-network mode; log a
URL, host, entity, Wi-Fi name or token; open a socket in a JVM test. **Counted RED (13):** rows 49–55, 53a, 54a (two),
54b (two), 54c. **Caps:** 13 runs; 1 h / 2 h. **Size:** about 260 production lines plus the XML,
400 test lines. **Estimate:** 60 min. **Split clause:** past 40 min with rows 54a–56 unstarted, the transport and
name checks (C19 steps 1a–1b) and the configuration (C20) go to a B5b.

## 17. B6a — the runner, the worker and the wiring (C21–C23; app JVM)

**Read:** `A/reminders/BackstopWorker.kt` whole; `A/reminders/ReminderHealthCheck.kt:60-96`;
`A/ServiceTagApp.kt` whole; `A/ui/nav/ServiceTagRoot.kt:60-112`; `A/MainActivity.kt:40-50`;
`A/di/AppGraph.kt:636-660`, `:955-962`; `gradle/libs.versions.toml:24`, `:63-65` (`work-testing` is
instrumented-only, so the worker's body is a plain class). **Rows:** 57–59. **Rulings:** R16-7, R16-12, R16-15,
R16-20, R16-Q-D (as amended: the period is the cadence, UPDATE on a change, staleness by the last success). **Interfaces produced:** `SeasonSyncRunner` (`syncNow`, `refreshIfStale`, `runAll`) implementing B3c's
`SeasonSyncScheduler`, `SeasonSyncWorker`, `SeasonSyncWork`, `WorkManagerSeasonSync`, `SeasonSyncDispatch`,
`ResumeRefresh` (C23's seam); the graph's wiring of B3a–B5. **Greps:** §7's WorkManager lines;
`'Result\.retry\(\)'` in `A/seasonsync` → 1 (the database failure; read it); `'registerNetworkCallback'` → 0;
`'LifecycleResumeEffect'` in `ServiceTagRoot.kt` → base + 1; `ServiceTagRoot`'s call sites unchanged (the seam's
default). **Untouched:** as §4. **Must NOT:** retry on a network outcome; read an inert binding; block a frame; edit
`AppGraph` or `FakeGraph` beyond the wiring — and this is the last brief that may. **Counted RED (5):** rows 57, 58, 59 (three).
**Caps:** 7 runs; 1 h / 2 h. **Size:** about 270 production lines, 300 test lines. **Estimate:** 50 min.

## 18. B6b — the scenario (row 60; app JVM, test-only)

**Read:** B6a's report; row 60; `T/testing/FakeGraph.kt` (read only). **Rows:** 60. **Rulings:** R16-12, R16-15.
**Interfaces produced:** none. **Greps:** `git diff <base> -- app/src/main core/src/main` → empty; the connection is
`http://192.168.0.10:8123` (C-7). **Must NOT:** edit main code, `AppGraph` or `FakeGraph`; open a socket. **Counted
RED (0):** row 60 is composed of rows 22–29's. **Caps:** 3 runs; 1 h / 2 h. **Size:** about 150 test lines.
**Estimate:** 30 min.

## 19. B7a — the API (C24; app JVM, docs)

**Read:** `A/api/ApiRouter.kt:96-115`, `:225-268`; `A/api/ApiJson.kt:485-520`, `:578-585`;
`A/api/SeasonHealthHandlers.kt:40-70`; `T/api/MaintenanceFixtures.kt:76-95`; `A/api/SeasonHealthDtos.kt:205-237`;
`T/api/{SeasonHealthRoutesTest,ReferenceRoutesTest}.kt` (`:740-775`); `docs/api/v1.md:30-45`, `:205-225`, its season
section and codes tables. **Rows:** 61–63. **Rulings:** R16-9, R16-Q-B (the v1.md wording). **Interfaces produced:**
the route, `SeasonSyncDtos.kt`, the arm. **Greps:** §7's API lines; `'baseUrl|base_url'` in `A/api` → 0;
`'SecretStore\.get|\.get\(.*connection'` in `A/api` → 0 (`has` only; read it). **Untouched:** as §4. **Must NOT:** add
a write route; return the address or the token; move the format clause of the status line; touch the legacy pair's
422. **Counted RED (2):** rows 61, 62. **Caps:** 5 runs; 1 h / 2 h. **Size:** about 120 production lines, 70 doc
lines, 220 test lines. **Estimate:** 50 min.

## 20. B7b — the MCP (C25; pytest)

**Read:** `M/src/servicetag_mcp/server.py:78-190` (`TOOL_NAMES`), `:235-260`, `:2350-2440`; §3's B7b pins;
`M/tests/{test_season_health_tools,test_argument_guard,test_tools}.py`. **Rows:** 64–65. **Rulings:** R16-9,
R16-17. **Interfaces produced:** `get_season_sync`. **Greps:** §7's MCP lines; `'\b89\b'` in `M/tests` → 0 but
history sentences that name 89 as a past total (each read and listed). **Untouched:** `app/**`, `core/**`, `S/**`.
**Must NOT:** add a write tool; send a request below schema 21. **Counted RED (1):** row 64. **Caps:** 4 runs;
1 h / 2 h. **Size:** about 45 production lines, 90 test lines, about 23 pin edits. **Estimate:** 40 min.

## 21. B8a — the Home Assistant screen (C26; app JVM)

**Read:** `A/ui/settings/SettingsScreen.kt:230-265`; `AT/ui/settings/SettingsBackupEntryTest.kt:35-110`;
`A/ui/nav/Route.kt:100-120`; `A/ui/api/DeveloperApiScreen.kt` (the pushed-screen precedent); C32; §5's P16-1…21,
P16-48, P16-50…52 and P16-53…82 as RATIFIED 2026-10-03 — six amended verbatim (P16-60, 66, 67, 69, 75, 80;
R16-Q-I): the option is "Only on this home Wi-Fi", and P16-66/67 keep the owner's ‘ ’. **Rows:** 66, 66a, 66b. **Rulings:** R16-13, R16-14, R16-Q-C, R16-Q-D,
R16-20, R16-21 (N-8), R16-Q-I. **Interfaces produced:**
`Route.HomeAssistant`, the screen, its view model, `HomeAssistantStrings`. **Forced sites (N-7):** the new
`SettingsScreen` callback breaks the named-argument calls at `SettingsBackupEntryTest.kt:41`, `:65` and `:92`; B8a
adds the argument there (arguments only, listed in its report; compile-checked by `:app:compileDebugAndroidTestKotlin`).
**Greps:** every P16 const declared once (`git grep -c` per const → 1); `'PasswordVisualTransformation'` → 1;
`'token' ` in the view model's state class → only `tokenPresent` (read it). **Untouched:** as §4. **Must NOT:** keep
the token text in state after save; add a reveal toggle; deep-link the route; ask for any permission on "Any network";
enable the home-network option without the grant; ask for background location while Background checks is Off; call the home-network option a "local network" (R16-Q-I).
**Counted RED (3):** rows 66, 66a, 66b. **Caps:**
4 runs; 1 h / 2 h. **Size:** about 270 production lines, 170 test lines, 3 argument edits. **Estimate:** 55 min.

## 22. B8b1 — the season card's block (C27; app JVM)

**After B2b is folded (rev 1.5).** **Read:** `A/ui/asset/AssetDetailScreen.kt:420-435`, `:1190-1290`;
`A/ui/asset/AssetViewModels.kt:780-795`, `:1676-1690`; §5's P16-22…41, P16-83/84; C33(4); B2b's report. **Rows:** 67.
**Rulings:** R16-1, R16-15, R16-19, R16-Q-C, R16-Q-H. **Interfaces produced:** the block, its view model,
`SeasonSyncStrings` (the block's strings). **Consumes C33(4):** the provenance line is `appliedLineOf(binding)` →
P16-34/35, P16-83/84 or no line, dated `appliedOn`, never re-derived from the mode or the action. **Greps:** every P16
const declared once; the `SyncErrorKind` and `AppliedLine` → sentence `when`s have no `else` (read both); the block is
called from every mode's branch of `SeasonSection` (read it). **Untouched:** as §4. **Must NOT:** hide the last-success
line behind an error; draw Start/End beside an enabled binding; draw a stopped binding only on MANUAL; draw P16-34/35
for a forced application. **Counted RED (2):** row 67 (two). **Caps:** 5 runs; 1 h / 2 h. **Size:** about 210
production lines, 180 test lines. **Estimate:** 45 min.

## 23. B8b2 — the setup sheet, the editor and the #78 lift (C27; app JVM)

**Read:** B8b1's report and `SeasonSyncStrings.kt`; `A/ui/asset/AssetViewModels.kt:2600-2620`;
`A/ui/asset/AssetEditScreen.kt:94-193`, `:440-470`; §5's P16-42…47, P16-49 and the flags. **Rows:** 68–69.
**Rulings:** R16-16, R16-19, R16-Q-C, R16-Q-G. **Interfaces produced:** the sheet and its view model (link and Resume),
`liveContinuousCount`. **Greps:** every P16 const declared once; #78's strings' diff → empty; `'liveContinuousCount'`
→ declared once in `C/usecase/SeasonCommands.kt`, called by the editor and the sheet (read both). **Untouched:** as
§4. **Must NOT:** change #78's behaviour; link or resume without the mode's sentence; write before the confirm.
**Counted RED (2):** rows 68, 69. **Caps:** 5 runs; 1 h / 2 h. **Size:** about 190 production lines, 160 test lines.
**Estimate:** 45 min.

## 24. B8c — drawn (C28; Compose sources)

**Read:** B8a's, B8b1's and B8b2's reports; `AT/ui/asset/AssetDetailConditionHealthSeasonTest.kt` (the fixture
shape, which stays unchanged). **Rows:** 70. **Interfaces produced:** none. **Greps:** `git diff <base> --
app/src/main` → empty. **Must NOT:** run a device; change a shipped `AT/` file (N-7's three sites are B8a's, already
landed). **Counted RED (0):** row 70 runs first at the gate; the report states the predicted failure under the
mask-removed mutation. **Caps:** 3 compile runs; 1 h / 2 h. **Size:** about 300 test lines (~11 cases, budget 14).
**Estimate:** 40 min.

## 25. B9 — the platform proof (C29; instrumented sources)

**Read:** `AT/reminders/ReminderPlatformDeviceProofTest.kt:95-130`; B4's, B5's and B6a's reports. **Rows:** 71.
**Rulings:** R16-12, R16-Q-A, R16-20 (the background path), R16-21. **Preconditions (N-2):** Location on through
`UiAutomation`, precise and background location granted through `UiAutomation`, the name read from a foreground
activity scenario; a `WifiUnnamed` read is a stop. **Interfaces produced:** none. **Greps:** `git diff <base> -- app/src/main` → empty;
the fake HA binds only to this device's own address and port 0 (read it); no literal IP address in the file but in
assertions on the parsed URL (read it); the teardown cancels `"season-sync"` (read it, N-15). **Must NOT:** reach
beyond the device; skip a case when no address is found (fail it); make the GET depend on the emulator's active
network (the rule is JVM-proven; the GET runs with a scripted eligible network); run a device in the brief. **Counted RED
(0):** row 71 runs first at the gate. **Caps:** 3 compile runs; 1 h / 2 h. **Size:** about 330 test lines (~10 cases).
**Estimate:** 50 min.

## 26. B10 — the documents (C30; docs)

**Read:** `docs/release-proofs.md:120-140`; `docs/design/03-target-architecture.md:20-40`, `:440-450`;
`docs/design/08-risk-register.md:30-36`; `docs/design/09-security-privacy.md:15-40`; `README.md:76-86`; every report on
the branch. **Rows:** 72 (the greps). **Rulings:** R16-5 (N-1), R16-Q-A, R16-Q-E, R16-Q-F, R16-Q-H, R16-Q-I. **Rev 1.5:** `docs/release-proofs.md`'s schema-21
paragraph names `season_sync_binding.last_applied_source` (C33); `README.md` and the new document call the option
"Only on this home Wi-Fi" (P16-60), never a "local network". **Greps:** C31's; `git diff <base> -U0 -- README.md docs | grep -c
'Wi-Fi/local network'` → 0; every
value in `docs/home-assistant-season-sync.md` is from § Global constraints' fixture list (read it); every RFC 1918
literal **but `192.168.0.10`** in the new document → 0 (`git grep -nE '\b(10\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])|192\.168)\.[0-9]+\.[0-9]+'`
lists only `192.168.0.10`; C-7). **Untouched:** as §4. **Must NOT:** describe the token as read-only; name a real host,
entity or network; touch `docs/versioning.md` or `docs/releases/`. **Counted RED (0):** a tripwire row. **Caps:** 3
runs; 1 h / 2 h. **Size:** about 175 doc lines. **Estimate:** 40 min.

## 27. B2b — the provenance column and the G5 line (C33; core and app JVM, one docstring line) — rev 1.5

**When:** a side lane off the accepted tip holding B7a, B7b and B9 (it edits their DTO, test, docstring and device
site), beside B8a (disjoint files, §4); folded **before B8b1 is dispatched**. (B2's split clause once reserved the
name for row 20; that split never ran.) **Read:** C33 and every site it cites; the B1a, B2, B3a, B3c, B6a, B7a and
B7b reports' signatures; `T/seasonsync/SeasonSyncWorkerBodyTest.kt:35-65` (`runnerOver`, the recording `log`).
**Rows:** 75–80 and §3's B2b pins. **Rulings:** R16-4, R16-11, R16-Q-E, R16-Q-H, R16-Q-J. **Interfaces produced:**
`LastAppliedSource`, `appliedSourceOf`, `AppliedLine`, `appliedLineOf` (for B8b1), the field, the column,
`lastApplied.source`, G5's `const val`, the runner's defaulted `log`. **Count first:** `git grep -nE
'SeasonSyncBinding\(|SeasonSyncBindingEntity\(|SeasonSyncAppliedDto\(|updateAtRevision\('` over `app/src core/src`
(on `35befab3`: 7, 1, 1, 1) and `'emptyList<String>(), logged'` in `T/seasonsync`; report the numbers and every hit
§3's pins do not name before editing; a site a parallel brief lands after `<base>` is the fold's (arguments only).
**Greps:** `'last_applied_source'` in `21.json` → 2 (the `createSql` and the field), in `Migrations.kt` → 1 (read
both: byte-equal); `'CREATE TABLE'` inside `MIGRATION_20_21` → 2; `20.json`'s diff → empty; `'appliedSourceOf\('` in
main → its declaration and the one `copy`; `'lastAppliedSource ='` in main → that `copy`, Link's `null`, the two
mappers (read each); G5's text in `app/src/main` → 1, its name at three catches for four sites; the one added `Log.`
is the runner's default (read it); `git diff <base>` of `A/di`, `T/testing/FakeGraph.kt`, `C/usecase` and
`C/seasonsync/SeasonSyncCommands.kt` → empty; `git diff <base> --stat -- tools` → `server.py`, one line;
`FORMAT_VERSION = 20` → 1; C31's six. **Gate:** briefs-common's, plus `uv run --frozen pytest` in `M/`.
**Untouched:** as §4. **Must NOT:** default the field; set the source outside `applyIfChanged`'s `copy`, move it
without a row, or derive it from anything but the applying binding's mode; add a migration step, version, format
literal, `MergeTable` member or backup field; edit `20.json`, `AppGraph`, `FakeGraph` or main `A/ui/**`; change a
silent site's result or attach an exception, URL, host, entity, Wi-Fi name or token to a log line; edit `M/tests` or
`M/README.md`. **Counted RED (10):** rows 75 (two), 76 (two), 77, 78, 79, 80 (three). **Caps:** 9 runs (mutations
batched only across disjoint classes, B2's rule); 1 h / 2 h. **Size:** about 60 production lines plus `21.json`, 220
test lines, 15 pin and site edits. **Estimate:** 50 min. **Split clause:** past 40 min with row 80 unstarted, row 80
goes to a B2c (the runner, the worker and their test).
