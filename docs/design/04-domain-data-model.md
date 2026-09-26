# D4 — Proposed domain and data model

Status: design-phase document, 2026-09-14. This is the concrete Room/SQLite schema and the
domain vocabulary it stores. D5 describes how the scheduling parts of it behave; D6 describes how
the legacy data lands in it; D7 says which tables arrive in which phase.

Conventions used throughout:

| Convention | Rule |
|---|---|
| Primary keys | `TEXT` UUID v4, canonical lower-case 36 chars, generated in the domain layer (`java.util.UUID.randomUUID()`), never by the database. Stable across devices and backups. |
| Instants | `INTEGER` epoch milliseconds UTC, column suffix `_at`. Used for audit (`created_at`, `updated_at`), sync, snooze. |
| Calendar dates | `TEXT` ISO-8601 `YYYY-MM-DD`, suffix `_on`. Used for everything the user thinks of as a date: when maintenance happened, when it is due, purchase date. Never converted to an instant for scheduling. |
| Month-day | `TEXT` `MM-DD` for season boundaries. |
| Enums | `TEXT` with the Kotlin enum name; unknown values rejected at the repository boundary. |
| Money | `INTEGER` minor units + `TEXT` ISO-4217 currency. |
| Booleans | `INTEGER` 0/1. |
| Soft delete | Not used except `status`/`archived_at` on assets, schedules, definitions, profiles, supplies. Events, measurements, links, tags are hard-deleted (with confirmation and an automatic pre-delete snapshot, see D3 §11). |

Why UUID text keys and not autoincrement: NFC tags and backups must resolve the same record on a
different phone. Autoincrement ids are per-database. ULID/UUIDv7 would give time-ordering but add
a dependency and buy nothing we need. Rejection ground recorded.

Why calendar dates and not instants for due/occurred: "Change the oil on April 10" is a label, not
a point on the world clock. Storing an instant would make the date depend on the device time zone
at read time and make every recurrence test DST-sensitive. Instants are kept for audit and for the
moment a reminder fires (D5 §11).

---

## 1. Entity overview and the decisions behind it

| Entity (table) | Verdict | Why |
|---|---|---|
| `asset` | Keep, with optional `parent_asset_id` | Components that are independently maintained or replaced (UPS battery pack) can be modelled; nothing forces it. |
| `nfc_tag` | Keep, **tag identity is separate from asset identity** | Lets a tag be rebound, revoked (lost), duplicated per asset, or bound to a standalone link. See §3. |
| `external_link` | Keep; `asset_id` nullable | Standalone links preserve the original product; asset-owned links generalise it. |
| `measurement_definition` | Keep, **asset-scoped**, doubles as meter definition | One catalogue row per asset per metric; carries unit, type, acceptable range, `is_meter`. No global catalogue table; global knowledge lives in seed templates. |
| `event_profile`, `profile_field`, `profile_consumable` | Keep, asset-scoped | The data-driven part of quick entry. A profile is an ordered list of definitions plus suggested consumables. |
| `asset_event` | Keep, aggregate root of a journal entry | One table for all kinds; `kind` enum; owns measurements, consumable usage, attachments. |
| `measurement` | Keep, controlled EAV | Typed value columns + FK to a definition. See §6 for the trade-off. |
| `consumable_usage` | Keep | Name snapshot + quantity + unit + optional supply FK. |
| `maintenance_schedule` | Keep (canonical config + two user overrides) | One provider-neutral rule model. |
| `schedule_provider` | Keep (join table) | Which reminder providers a schedule uses. The MVP UI writes at most one row; the model permits several so redundant delivery (local + Todoist) can be enabled later without a migration (ruling R-12). |
| `schedule_state` | Keep as a **derived** 1:1 table | Materialised for sorting and for the notification job; fully recomputable from schedule config + events. |
| `ScheduleOccurrence` | **Not introduced** | No backlog of missed occurrences by design; one current occurrence per schedule is enough. An occurrence table would add rows, states, and UI for no MVP requirement. Would be revisited only if per-occurrence audit ("skipped July") becomes a requirement. |
| `supply_item`, `asset_supply`, `stock_ledger` | Keep | Reference ("what part belongs here") and approximate stock ("do I have enough") share one identity; the ledger makes usage decrement reversible. |
| `reminder_projection`, `provider_op` | Keep (Phase 5) | Provider-side state (including which Todoist representation was chosen) and an outbox for idempotent, offline-tolerant external operations. Local reminders need neither. |
| `integration_account` | Keep (Phase 5) | Connection status and token alias; the secret itself is not in Room. |
| `attachment` | Keep (Phase 4) | Metadata + provider + locator; bytes live behind `AttachmentStore`. |
| Preferences | DataStore, not Room | Reminder hour, reminders-enabled, attachment tree URI, backup folder URI, migration flags, unit preferences. |

## 2. Relationship diagram

```
                       ┌────────────────────┐
                       │      asset          │◀──────────── parent_asset_id (0..1, RESTRICT)
                       │  season window      │
                       └──┬──┬──┬──┬──┬──┬───┘
          0..* ┌──────────┘  │  │  │  │  └──────────┐ 0..*
               ▼             │  │  │  │             ▼
      ┌──────────────┐       │  │  │  │     ┌────────────────┐
      │   nfc_tag    │       │  │  │  │     │ external_link  │  asset_id nullable
      │ payload_format│──────┼──┼──┼──┼────▶│ (standalone or │  (standalone = tag target)
      │ payload_key  │ link_id  │  │  │     │  asset-owned)  │
      └──────────────┘       │  │  │  │     └────────────────┘
                             │  │  │  │
        0..* ┌───────────────┘  │  │  └───────────────┐ 0..*
             ▼                  │  │                  ▼
   ┌──────────────────────┐     │  │        ┌──────────────────┐
   │ measurement_definition│    │  │        │ maintenance_schedule│──1:1──▶ schedule_state (derived)
   │ key,label,unit,type,  │◀───┼──┼────────│ meter_definition_id │
   │ range, is_meter       │    │  │        │ profile_id          │──▶ event_profile
   └──────────┬───────────┘     │  │        └──────────┬──────────┘
              │                 │  │                   │ 0..* (schedule_id on events)
              │       0..* ┌────┘  │                   ▼
              │            ▼       │        ┌──────────────────┐
              │   ┌────────────────┴──┐     │   asset_event    │◀── attachment (event_id)
              │   │   event_profile   │     │ kind, occurred_on│
              │   └───┬───────────┬───┘     │ source, cost     │
              │       │           │         └───┬──────────┬───┘
              │   profile_field profile_consumable │          │
              │       │                          ▼          ▼
              └──────▶│                 ┌─────────────┐ ┌──────────────────┐
                      └────────────────▶│ measurement │ │ consumable_usage │──▶ stock_ledger (usage_id)
                                        │ value_num/  │ │ name, qty, unit  │
                                        │ value_text  │ │ supply_id (0..1) │
                                        └─────────────┘ └────────┬─────────┘
                                                                 ▼
   asset ──0..*── asset_supply ──▶ supply_item ──1..*── stock_ledger
                                    │
                                    └──▶ reminder_projection (supply_id)   ◀── maintenance_schedule (schedule_id)
                                                     │
                                                     └──▶ provider_op (outbox)
   integration_account (one row per provider)
   attachment (asset_id XOR event_id) ──▶ AttachmentStore(provider, locator)
```

Cardinalities: asset 1—0..* tags; asset 1—0..* links; link 0..1—0..* tags (a standalone link may
have several tags); asset 1—0..* definitions; asset 1—0..* profiles; profile 1—0..* fields;
asset 1—0..* events; event 1—0..* measurements; event 1—0..* usages; asset 1—0..* schedules;
schedule 1—1 state; schedule 1—0..* completion events; supply 1—0..* ledger rows; asset 0..*—0..*
supplies via `asset_supply`; schedule 1—0..1 projection per provider.

## 3. Identity and NFC binding

### `nfc_tag`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | Row id. For `V1` tags this is **also the identifier written on the tag**. |
| `payload_format` | TEXT | `LEGACY_MD5` \| `V1` |
| `payload_key` | TEXT | Exact identifier carried by the tag: 8 lower-case hex chars for `LEGACY_MD5`, the UUID string for `V1` (equal to `id`). |
| `asset_id` | TEXT FK asset ON DELETE SET NULL | Target when bound to an asset |
| `link_id` | TEXT FK external_link ON DELETE SET NULL | Target when bound to a standalone link |
| `status` | TEXT | `ACTIVE` \| `UNBOUND` \| `LOST` \| `RETIRED` |
| `label` | TEXT | e.g. "Tag on cover", optional |
| `physical_uid` | TEXT | Hex of the tag's hardware UID if readable; informational only, never identity |
| `written_at`, `last_scanned_at`, `created_at`, `updated_at` | INTEGER | |

Constraints: `UNIQUE(payload_format, payload_key)`; `CHECK(NOT (asset_id IS NOT NULL AND link_id IS NOT NULL))`; index on `asset_id`, on `link_id`.

Why tag identity ≠ asset identity (this departs from issue #2's "write the asset id"):

| Need | Asset id on tag | Tag id + binding table (chosen) |
|---|---|---|
| Rebind a lost tag to a new tag | New tag carries same asset id; old tag still resolves (cannot revoke) | Old row marked `LOST`; scanning it says "revoked"; new row created |
| Two tags on one asset (unit + breaker panel) | Works, but indistinguishable | Each tag has its own row, label, scan history |
| Standalone link tags (original product) | Needs a second payload type | Same payload; binding decides |
| Pre-write a batch of blank tags, bind later | Impossible | `UNBOUND` rows, bind on first scan |
| Retarget a tag from a link to an asset | Rewrite the tag | Update the row |

Cost: one indirection. What would change the decision: nothing in the requirements; if you prefer
the "tag is the asset" mental model, the codec is unchanged and only the binding rule differs.

### noteNFC tag payload format v1 (`payload_format = V1`, record type `com.loosecannon.notenfc:tag`) — the new-generation replacement for legacy `md5_short`

```
NDEF message
  record 0: TNF_EXTERNAL_TYPE, type "com.loosecannon.notenfc:tag"
            payload (18 bytes):
              byte 0   format version   = 0x01
              byte 1   flags            = 0x00 (reserved, must be 0)
              bytes 2..17  tag id, RFC 4122 byte order (16 bytes)
  record 1: Android Application Record for com.loosecannon.notenfc (the applicationId, D13 §4)
```

- Legacy tags keep record type `com.loosecannon.notenfc:md5_short` and are read-only aliases,
  recognised best-effort (D13): a `LEGACY_MD5` row exists only if the user binds an old tag as-is;
  otherwise the scan offers a rewrite in payload format v1.
- Unknown version byte (> 0x01) → "written by a newer noteNFC; update the app"; never parsed.
- Version 0x00, wrong length, non-zero flags → rejected as malformed.
- Size: ~44 bytes for record 0 + ~43 for the AAR + TLV overhead ≈ 95 bytes; fits NTAG213 (144 B).
- The codec (`core.nfc.NdefCodec`) is pure Kotlin over `ByteArray`; Android `NdefRecord` objects are
  built only in the `nfc` adapter.

## 4. Assets and links

### `asset`

| Column | Type | Canonical? | Notes |
|---|---|---|---|
| `id` | TEXT PK | canonical | |
| `name`, `description` | TEXT | canonical | name required |
| `category` | TEXT | canonical | free text with suggestions ("Hot tub", "Mower", "UPS") |
| `template_key` | TEXT | canonical | which starter template seeded it (`hot_tub`, `power_equipment`, `ups`, `generic`, or null) |
| `manufacturer`, `model`, `serial_number` | TEXT | canonical | optional |
| `purchase_on`, `in_service_on` | TEXT date | canonical | optional |
| `purchase_price_minor`, `currency` | INTEGER, TEXT | canonical | optional |
| `vendor`, `location` | TEXT | canonical | optional |
| `warranty_expires_on`, `warranty_notes` | TEXT | canonical | optional |
| `notes` | TEXT | canonical | |
| `status` | TEXT | canonical | `ACTIVE` \| `ARCHIVED` (2B-2: `RETIRED` removed — retirement is the `retired_on` date, not a status) |
| `retired_on` | TEXT date | canonical | null until retired; the one thing that makes an asset retired |
| `parent_asset_id` | TEXT FK asset ON DELETE RESTRICT | canonical | optional component tree |
| `season_start_mmdd`, `season_end_mmdd` | TEXT | canonical | both null = year-round; end < start wraps the year (Oct 15 → Apr 15) |
| `created_at`, `updated_at` | INTEGER | audit | |

Indexes: `status`, `parent_asset_id`, `name COLLATE NOCASE`.

Equipment-type decision (2026-09-15): there is **no canonical equipment-type key**, and none is
planned unless a later feature shows a concrete need that free text cannot meet (bulk actions by
class, class roll-ups on the dashboard, interoperability with an external equipment ontology).
`category` stays free text and is the user's classification; `template_key` stays provenance
only (starter data, never taxonomy or runtime behaviour); the actual behaviour comes from the
asset's own definitions, profiles and, later, schedules. What the 2B asset editor adds instead is
a compiled **category suggestion catalog** — Generator, Lawn mower, Snowblower, UPS, Battery,
Inverter / charger, Solar charge controller, RO system, Hot tub, HVAC, Pump, Other — where a
suggestion may carry a `suggestedTemplateKey` used only while creating the asset; typing any
other text is equally valid and nothing branches on the chosen string afterwards. The reason for
not modelling the type is that a taxonomy brings its own problems (portable vs inverter
generator; inverter vs inverter/charger vs energy storage; a UPS battery as a battery or a
component) without improving the core workflow, and some assets belong to more than one class.
*Superseded in part by #74 (2026-09-26, `docs/superpowers/plans/2026-09-26-issue-74-durable-categories.md`):
a category saved on an asset becomes a durable catalog row (`asset_category`, keyed by its
normalised name, carried by backup format 9 and the merge), and the stored string is that row's
spelling; `category` is still the one classification and still no equipment-type key.*

Hierarchy guidance: the UPS/battery question is answered without child assets in the common case
(a completion-relative "replace batteries every 4 years" schedule gives the battery age as
`last_completed_on`). Child assets are for users who want a component's own serial number,
documents, and journal. Depth is unbounded and the UI shows one level at a time (2B-2: COMPONENTS
lists the direct children and "Part of" names the direct parent; deeper levels are reached by
tapping through). Deleting a parent with children is refused (`RESTRICT`); archive instead, or
reparent.

### `external_link`

| Column | Notes |
|---|---|
| `id` PK | |
| `asset_id` FK asset ON DELETE CASCADE, nullable | null = standalone link (tag target in its own right) |
| `kind` | `JOPLIN` \| `OBSIDIAN` \| `LOGSEQ` \| `WEB` \| `OTHER` (derived from scheme at save time, editable) |
| `label` | display name; defaults from kind + note id |
| `uri` | validated at save time by `LinkLaunchPolicy` (D3 §9) |
| `created_at`, `last_opened_at`, `updated_at` | |

Index on `asset_id`. Standalone links appear in a "Links" list, not on the asset dashboard.
Attaching a standalone link to an asset sets `asset_id`; tags bound to the link keep working and
may optionally be retargeted to the asset.

## 5. The event journal

### `asset_event` (aggregate root)

| Column | Canonical? | Notes |
|---|---|---|
| `id` PK | canonical | |
| `asset_id` FK asset CASCADE | canonical | |
| `kind` | canonical | `MAINTENANCE` \| `INSPECTION` \| `MEASUREMENT` \| `TREATMENT` \| `INCIDENT` \| `REPLACEMENT` \| `SEASON_START` \| `SEASON_END` \| `NOTE` \| `CUSTOM` |
| `title` | canonical | e.g. "Water test", "Oil change" |
| `profile_id` FK event_profile SET NULL | canonical | which profile produced it (for re-editing with the same form) |
| `schedule_id` FK maintenance_schedule SET NULL | canonical | the schedule this event completes, if any (**this is the completion link**) |
| `occurred_on` | canonical | required calendar date, user-editable, backdating allowed |
| `occurred_time` | canonical | optional `HH:MM` local time |
| `tz_id` | audit | zone at entry, for reconstructing an instant when needed |
| `notes` | canonical | |
| `cost_minor`, `currency` | canonical | optional |
| `source` | provenance | `MANUAL` \| `SCHEDULE_QUICK_COMPLETE` \| `TODOIST_SYNC` \| `IMPORT` \| `TELEMETRY` |
| `source_ref` | provenance | e.g. Todoist completion id, import batch id; `UNIQUE(source, source_ref)` gives sync idempotency (NULLs are distinct in SQLite) |
| `details_pending` | canonical | 1 when created minimally by an external completion and the schedule's `completion_mode` is `FORM` |
| `created_at`, `updated_at` | audit | |

Indexes: `(asset_id, occurred_on DESC, created_at DESC)`, `(schedule_id, occurred_on DESC)`, `UNIQUE(source, source_ref)`.

Append-oriented vs editable (contradiction C2 in D2): events are editable in place with
`updated_at`; there is no supersede chain. The auditability requirement is met by keeping
`created_at`, never overwriting history implicitly, and recomputing schedule state from events so
an edit cannot leave stale derived data.

### `measurement`

| Column | Notes |
|---|---|
| `id` PK | |
| `event_id` FK asset_event CASCADE | |
| `definition_id` FK measurement_definition **RESTRICT** | a definition with recorded data cannot be deleted, only archived |
| `value_num` REAL | for `NUMBER` and `BOOLEAN` (0/1) definitions |
| `value_text` TEXT | for `TEXT` definitions |
| `unit` TEXT | **snapshot** of the definition's unit at entry time; definitions can be edited later |
| `sort_order` INTEGER | |

Indexes: `(definition_id, event_id)`, `event_id`. Time series for "pH over time" = join
`measurement → asset_event` on `definition_id`, order by `occurred_on, occurred_time`.

### `consumable_usage`

| Column | Notes |
|---|---|
| `id` PK | |
| `event_id` FK asset_event CASCADE | |
| `supply_id` FK supply_item SET NULL | optional link to the supply catalogue |
| `name` TEXT | snapshot; required even when `supply_id` is set |
| `quantity` REAL, `unit` TEXT | |
| `affects_stock` INTEGER | 1 if this usage produced a ledger entry |
| `sort_order` | |

## 6. Measurement definitions, meters, and the EAV trade-off

### `measurement_definition` (asset-scoped)

| Column | Notes |
|---|---|
| `id` PK | |
| `asset_id` FK asset CASCADE | |
| `key` TEXT | slug, `UNIQUE(asset_id, key)`; e.g. `ph`, `free_chlorine`, `engine_hours` |
| `label`, `unit` | display |
| `value_type` | `NUMBER` \| `TEXT` \| `BOOLEAN` |
| `decimals` INTEGER | display precision |
| `range_low`, `range_high` REAL | optional acceptable range; classification below/in/above at read time |
| `is_meter` INTEGER | 1 for monotonic counters (hours, miles, cycles) usable by usage-based schedules |
| `sort_order`, `archived_at`, `created_at`, `updated_at` | |

Current meter value (derived, not stored on the definition): the `measurement` row for this
definition with the greatest `(occurred_on, occurred_time, created_at)`. Cached in
`schedule_state.current_meter` for schedules that use it.

Meter reset (new engine, replaced hour meter): archive the old definition and create a new one;
re-point schedules. No special reset flag.

**The trade-off.** Three ways to store "pH = 7.8 ppm on this event":

| Option | Queryable per metric | User-defined metrics without migration | Type safety | Verdict |
|---|---|---|---|---|
| One typed table per profile (`hot_tub_water_test`) | yes | **no** (schema change per metric) | best | rejected: contradicts the configurable-profile requirement |
| JSON blob on the event | no (JSON1 on old SQLite is unreliable) | yes | none | rejected: buries readings in a string |
| Free-string EAV (`key TEXT, value TEXT`) | weakly | yes | none | rejected: the classic EAV failure |
| **Controlled EAV** (FK to a catalogued definition; typed value columns; unit snapshot) | yes (`definition_id` index) | yes (insert a row) | per-definition type enforced at the boundary | **chosen** |

The attribute is a foreign key to a typed catalogue row, not a free string; values live in typed
columns; the "schema" a user edits is data (definitions and profiles), while validation, range
classification, and rendering are compiled code. That is the boundary between data-driven and
compiled logic (D3 §6).

Why asset-scoped rather than a global catalogue: it removes a join table for per-asset ranges
("pH 7.2–7.8 for this tub"), makes deletion cascade obvious, and matches the profile scoping. The
cost is duplicated rows per asset, which is negligible. Cross-asset comparison ("all my pH
readings") is not a requirement; if it becomes one, `key` provides the join.

## 7. Profiles (the data-driven quick-entry layer)

### `event_profile`

| Column | Notes |
|---|---|
| `id` PK | |
| `asset_id` FK asset CASCADE | |
| `name` | "Test water", "Oil change", "Load test" |
| `event_kind` | default `asset_event.kind` |
| `default_title` | |
| `template_key` | which seed template created it (for "reset to default") |
| `sort_order`, `archived_at`, `created_at`, `updated_at` | |

### `profile_field`

| Column | Notes |
|---|---|
| `id` PK | |
| `profile_id` FK event_profile CASCADE | |
| `definition_id` FK measurement_definition CASCADE | |
| `required` INTEGER | |
| `sort_order` | |

`UNIQUE(profile_id, definition_id)`.

### `profile_consumable`

| Column | Notes |
|---|---|
| `id` PK | |
| `profile_id` FK CASCADE | |
| `supply_id` FK supply_item SET NULL | optional |
| `name`, `default_quantity`, `unit`, `sort_order` | |

What is data-driven: which fields, order, labels, units, required, ranges, suggested consumables,
default event kind/title. What is compiled: the single generic entry form (numeric/text/boolean
rows + consumables + notes + date), validation, range classification, the schedule-completion
hook. Not supported by design: conditional fields, custom widgets, expressions. Computed fields are
supported only as a closed formula enum with same-event semantics — Phase 2B-1's DERIVED
definition, whose one member is `PERCENT_DROP` — never as an expression language.
Seed templates are JSON in app assets (`hot_tub`, `power_equipment`, `ups`, `generic`) that create
definitions, profiles, and default schedules when an asset is created from a template; after that
the rows belong to the asset and the template is not consulted again.

## 8. Maintenance schedules

### `maintenance_schedule` (canonical configuration + two user overrides)

| Column | Kind | Notes |
|---|---|---|
| `id` PK | | |
| `asset_id` FK asset CASCADE | config | |
| `title`, `description` | config | |
| `profile_id` FK event_profile SET NULL | config | form used on completion |
| `completion_mode` | config | `QUICK` (one tap creates a minimal event) \| `FORM` (opens the profile; external completions become `details_pending`) |
| `time_interval` INTEGER, `time_unit` | config | `DAY` \| `WEEK` \| `MONTH` \| `YEAR`; nullable pair |
| `time_basis` | config | `FIXED` \| `COMPLETION` |
| `anchor_on` TEXT date | config | FIXED: the series anchor (all due dates are `anchor + k·interval`). COMPLETION: the first due date until the first completion exists. |
| `meter_definition_id` FK measurement_definition RESTRICT | config | nullable; must have `is_meter = 1` |
| `meter_interval` REAL | config | e.g. 50 (hours) |
| `anchor_meter` REAL | config | meter reading at the last service before the schedule existed ("last changed at 120 h"); the baseline until the first completion |
| `meter_lead` REAL | config | due-soon window in meter units (e.g. 5) |
| `lead_days` INTEGER | config | due-soon window for the time side |
| `season_behavior` | config | `FOLLOW_ASSET` \| `IGNORE` |
| `season_reentry` | config | `AT_START` \| `RESUME_CLAMPED` |
| `season_reentry_offset_days` INTEGER | config | used with `AT_START` (0 = on the first day) |
| `reminders_enabled` INTEGER | config | master switch for this schedule; providers are listed in `schedule_provider` |
| `status` | config | `ACTIVE` \| `PAUSED` \| `ARCHIVED` |
| `postponed_due_on` TEXT date | **override** | one-off replacement for the current occurrence's due date; cleared on completion or rule edit |
| `snoozed_until_at` INTEGER | **override** | notification suppression instant; never affects due dates |
| `created_at`, `updated_at` | audit | |

`CHECK (time_interval IS NOT NULL OR meter_definition_id IS NOT NULL)`. Combined rule = both sides
present; the schedule is due when **either** side is due (whichever first). Index `(asset_id, status)`.

### `schedule_provider` (which providers deliver this schedule's reminders)

| Column | Notes |
|---|---|
| `schedule_id` FK maintenance_schedule CASCADE | |
| `provider` | `LOCAL` \| `TODOIST` (future providers add enum values, not columns) |
| `enabled` INTEGER | |
| `created_at`, `updated_at` | |

`PRIMARY KEY (schedule_id, provider)`. A schedule with no rows (or none enabled) has no reminder
delivery and produces the `SCHEDULE_NO_PROVIDER` health finding. The MVP editor offers a
single-choice control (Local / Todoist / None) and therefore writes at most one enabled row; this
is a UI constraint, not a domain invariant (ruling R-12). Low-stock reminders for supplies use a
single app-level preference for their provider rather than a per-supply table.

### `schedule_state` (derived, recomputable)

| Column | Notes |
|---|---|
| `schedule_id` PK FK CASCADE | |
| `last_completed_on` | from the latest completion event (`asset_event.schedule_id = this`), ties broken by `created_at` |
| `last_completion_event_id` | |
| `last_completed_meter` REAL | meter reading recorded on that event (required by the completion form when a meter rule exists) |
| `current_meter` REAL | latest reading for `meter_definition_id` across all events of the asset |
| `computed_due_on` | time side, per D5 rules; null if no time rule |
| `computed_due_meter` REAL | `last_completed_meter + meter_interval`; null if no meter rule |
| `effective_due_on` | `postponed_due_on ?: computed_due_on`; the dashboard sort key |
| `season_active` INTEGER, `next_season_start_on` | evaluated for the recompute date |
| `computed_for_on` | the "today" used; the daily job recomputes; reads after that date recompute in memory |
| `computed_at` | |

`status` (OK / DUE_SOON / DUE / OVERDUE / INACTIVE_SEASON / PAUSED / NO_DATA) is **not stored**; it
is a pure function of the row plus today's date, so it can never be stale. Only `effective_due_on`
is materialised for ordering.

Everything in this table can be rebuilt by `ScheduleRecompute.rebuild(scheduleId)` from the
schedule row and the asset's events. That function runs after every event insert/update/delete,
every schedule edit, every import, and in the daily job.

## 9. Supplies and stock (Phase 6)

### `supply_item`

| Column | Notes |
|---|---|
| `id` PK | |
| `name`, `category`, `manufacturer`, `sku` | identity/reference |
| `preferred_unit` | `oz`, `qt`, `ea`, … |
| `track_stock` INTEGER | 0 = reference only |
| `low_stock_threshold` REAL, `reorder_quantity` REAL | optional |
| `vendor_url`, `notes` | |
| `qty_on_hand_cache` REAL, `qty_as_of_at` | **derived** from the ledger |
| `archived_at`, `created_at`, `updated_at` | |

### `asset_supply` (reference association)

`id`, `asset_id` FK CASCADE, `supply_id` FK CASCADE, `role` TEXT ("Oil filter", "Sanitizer"),
`notes`. `UNIQUE(asset_id, supply_id, role)`. This answers "what part belongs to this asset" even
when `track_stock = 0`.

### `stock_ledger` (canonical for stock)

| Column | Notes |
|---|---|
| `id` PK | |
| `supply_id` FK CASCADE | |
| `kind` | `COUNT` (absolute: "I have ~18 oz") \| `DELTA` (relative: −1.0 usage, +32 purchase) |
| `amount` REAL, `unit` | |
| `usage_id` FK consumable_usage **CASCADE**, nullable | deleting or editing the event reverses the decrement automatically |
| `note`, `recorded_at` | |

Quantity on hand = the latest `COUNT` plus all `DELTA` rows after it. Manual correction is a new
`COUNT`; no historical reconciliation is ever required. Index `(supply_id, recorded_at)`.

Low stock is a derived condition (`track_stock = 1 AND qty < threshold`). It becomes a reminder
subject for the provider layer with the same dedup rule as schedules (§10).

## 10. Reminder projections and provider outbox (Phase 5)

### `reminder_projection`

| Column | Notes |
|---|---|
| `id` PK | |
| `provider` | `TODOIST` (local reminders have no projection rows) |
| `schedule_id` FK CASCADE, nullable / `supply_id` FK CASCADE, nullable | exactly one set (`CHECK`) |
| `representation` | `NATIVE_RECURRING` (the remote task carries the provider's own recurrence rule and advances itself on completion) \| `MANAGED_OCCURRENCE` (an ordinary dated task that noteNFC re-dates). Chosen by the adapter from the schedule's capabilities (D3 §8); re-evaluated on every rule edit |
| `external_id`, `external_url` | Todoist task id / app URL |
| `state` | `PENDING` \| `ACTIVE` \| `PARKED` (season-inactive or paused) \| `COMPLETED_REMOTE` \| `MISSING` \| `CONFLICT` \| `ERROR` \| `WITHDRAWN` |
| `content_hash` | hash of the last projected title/description/due/recurrence; skip no-op updates |
| `projected_due_on` | what we last sent |
| `projected_recurrence` | the provider recurrence string last sent (`NATIVE_RECURRING` only, e.g. `every! 90 days`) |
| `remote_due_on`, `remote_updated_at` | what we last observed |
| `last_remote_completion_ref` | id of the last activity-log completion event consumed (`NATIVE_RECURRING` completions do not close the task, so completion identity comes from the activity log / `completed_info`) |
| `last_synced_at`, `last_error` | |
| `created_at`, `updated_at` | |

`UNIQUE(provider, schedule_id)`, `UNIQUE(provider, supply_id)` (at most one live projection per
subject per provider: this is the duplicate-reorder-task guard). Several providers per subject are
allowed by construction (one row each), which is what keeps ruling R-12 migration-free.

### `provider_op` (outbox)

`id`, `provider`, `projection_id` FK CASCADE, `op` (`UPSERT` \| `COMPLETE` \| `REOPEN` \| `SYNC_DUE` \| `PARK` \| `WITHDRAW`),
`payload_json` (snapshot of the subject as the provider needs it), `request_id` (UUID used as
Todoist `X-Request-Id`), `attempts`, `next_attempt_at`, `state` (`QUEUED` \| `IN_FLIGHT` \| `DONE` \| `FAILED`),
`last_error`, `created_at`. Written in the same transaction as the domain change; drained by a
WorkManager worker. This is what makes "delete a schedule" safe: the domain row is archived, the
`WITHDRAW` op is queued, and the row is purged only after the op is `DONE`.

### `integration_account`

`provider` PK, `status` (`CONNECTED` \| `DISCONNECTED` \| `ERROR`), `account_label`, `auth_kind`
(`OAUTH` \| `PERSONAL_TOKEN`), `token_alias` (name of the encrypted secret; the secret is stored by
`SecretStore`, never in Room or in backups), `connected_at`, `last_sync_at`, `sync_cursor`,
`last_error`.

## 11. Attachments (Phase 4)

### `attachment`

| Column | Notes |
|---|---|
| `id` PK | |
| `asset_id` FK CASCADE, nullable / `event_id` FK CASCADE, nullable | exactly one — **no SQL `CHECK`**; enforced in the entity mapper and in the backup reader (4A, spec §11.5) |
| `kind` | `PHOTO` \| `LABEL_PHOTO` \| `RECEIPT` \| `MANUAL` \| `WARRANTY` \| `DOCUMENT` \| `OTHER` |
| `mode` | `MANAGED` (bytes copied into the configured store; noteNFC owns lifecycle) \| `REFERENCE` (durable pointer to a document the user keeps elsewhere) |
| `display_name`, `mime_type`, `size_bytes`, `sha256` | |
| `storage_provider` | `SAF_TREE` \| `SAF_DOCUMENT` (reference, reserved for 4B). **No `LOCAL` member** — absent, not reserved, by the owner's ruling (4A, spec §11.8); `WEBDAV`, `S3`, `GDRIVE`, `ONEDRIVE`, `DROPBOX` are not in the enum either |
| `storage_locator` | provider-relative: `SAF_TREE` uses `assets/<asset-id>/<attachment-id>.<ext>` or `events/<event-id>/<attachment-id>.<ext>` relative to the store root (the root itself is one setting, so switching trees does not touch rows); `SAF_DOCUMENT` will store the persisted `content://` URI in 4B |
| `captured_on`, `notes`, `created_at`, `updated_at` | |

Indexes: `asset_id`, `event_id`, and **unique** `(storage_provider, storage_locator)` — two rows
cannot claim the same file.

Bytes are never in Room. Backups include managed bytes and only metadata for references (D3 §11).

**As built in Phase 4A** (schema v5, migration 4→5): the table above, with the `CHECK` and the
`LOCAL` provider dropped as noted, the unique locator index added, and both FKs `ON DELETE CASCADE`
so deleting an asset takes its own rows *and* its events' rows. The bytes behind a cascaded row are
swept by `DeleteAsset` / `DeleteEvent`, not by the database. Evidence:
[phase-4a-evidence.md](phase-4a-evidence.md).

## 12. Canonical vs derived — the summary table

| Canonical (user or provenance truth) | Derived / cache / projection state |
|---|---|
| asset, nfc_tag, external_link | `schedule_state` (all columns) |
| measurement_definition, event_profile, profile_field, profile_consumable | `supply_item.qty_on_hand_cache`, `qty_as_of_at` |
| asset_event, measurement, consumable_usage | `reminder_projection` (provider-side mirror), `provider_op` |
| maintenance_schedule config + `postponed_due_on` + `snoozed_until_at`; schedule_provider | `integration_account.last_sync_at`, `sync_cursor` |
| supply_item identity fields, asset_supply, stock_ledger | attachment thumbnails (file cache, not in DB) |
| attachment metadata, integration_account identity | `nfc_tag.last_scanned_at` (informational) |

Backups export every canonical table plus `reminder_projection` (so a restore can re-link Todoist
tasks) and skip the rest; derived tables are rebuilt after import.

## 13. Lifecycle and deletion semantics

| Operation | Behaviour |
|---|---|
| Archive asset | `status = ARCHIVED`; hidden from dashboard; schedules implicitly inactive; tags still resolve (show "archived") |
| Retire asset | `retired_on` only (2B-2: independent of archive — an asset can be retired, archived, both or neither, and the `REPLACEMENT`/`NOTE` event offered afterwards is an optional follow-on that cannot undo the retirement) |
| Delete asset | Refused if children exist (RESTRICT), and 2B-2's refusal names them so the dialog can list which components are in the way. Otherwise, after a typed confirmation and an automatic snapshot: cascades to definitions, profiles, events, measurements, usages, schedules, states, links, asset_supply, attachments (bytes removed after commit); tags become `UNBOUND`; queued `WITHDRAW` ops for projections. 2B-2 ships the typed confirmation (the asset's own name, `TypedConfirmDialog`); the automatic snapshot is not built yet and is deferred to Phase 3R |
| Delete event | Confirmation; cascades to measurements, usages, ledger deltas, attachments; then `ScheduleRecompute` for any schedule it completed |
| Edit event date | `ScheduleRecompute` for the linked schedule |
| Delete definition | Only if nothing references it — no measurement, no DERIVED definition using it as a source, no profile field offering it (RESTRICT); the refusal names every referrer and archive is offered instead |
| Pause schedule | `status = PAUSED`; no due date, no notifications; projection `PARK` (managed: task re-dated to a placeholder or undated; native recurring: due cleared while the recurrence string is retained, restored on resume) |
| Archive schedule | Hidden; projection `WITHDRAW`; events keep `schedule_id` |
| Delete schedule | Archive + `WITHDRAW` op; purge row after op `DONE` (events `SET NULL`) |
| Lose tag | `status = LOST`; scanning it shows "revoked"; user may re-activate |
| Unbind tag | asset/link cleared, `status = UNBOUND` |
| Delete supply | Cascades ledger and asset_supply; usages `SET NULL` (name snapshot survives) |

## 14. Aggregates and repository boundaries (for the `:core`/`:app` split)

| Aggregate root | Owns | Repository (port, defined in `:core`) |
|---|---|---|
| `Asset` | definitions, profiles, links, tags, season window | `AssetRepository` |
| `AssetEvent` | measurements, consumable usages, event attachments | `EventRepository` |
| `MaintenanceSchedule` | its overrides and derived state | `ScheduleRepository` |
| `SupplyItem` | ledger, asset associations | `SupplyRepository` |
| `ReminderProjection` | its outbox ops | `ProjectionRepository` |
| `Attachment` (asset-level) | metadata | `AttachmentRepository` |

One transaction per use case (`@Transaction` DAO methods in the Room adapter, or
`withTransaction {}` in the repository implementation). Cross-aggregate effects (event saved →
schedule recomputed → projection op queued) happen inside the same use-case transaction because
they are all local writes; the only asynchronous part is draining the outbox.

## 15. Room specifics

- `exportSchema = true`, schemas committed under `app/schemas/`; every version bump ships a
  `Migration` and a `MigrationTest` (Room's `MigrationTestHelper`).
- Foreign keys are enforced by Room (it enables `PRAGMA foreign_keys`). `RESTRICT` vs `CASCADE`
  vs `SET NULL` as listed per table.
- Partial unique indexes are not expressible in Room annotations; none are required because SQLite
  treats `NULL` as distinct in `UNIQUE` constraints.
- Version plan: v1 (Phase 1: asset, nfc_tag, external_link), v2 (Phase 2: journal, definitions,
  profiles), v3 (Phase 3: schedules, schedule_provider, state), v4 (Phase 4: attachments),
  v5 (Phase 5: projections, outbox, integration_account), v6 (Phase 6: supplies, with
  `consumable_usage.supply_id` and `profile_consumable.supply_id` added then). Auto-migrations are
  acceptable for pure table additions; hand-written for anything else.

> **Superseded by what shipped. Amended at implementation (2026-09-22, ServiceTag 1.2 / B13); the
> bullet above is kept for the record and is no longer the plan.** The versions did not land in
> that order, because the phases did not. As shipped:
>
> | version | shipped in | tables |
> |---|---|---|
> | v1 | 1.0.0 | `asset`, `nfc_tag`, `external_link` |
> | v2 | 1.0.0 | journal, `measurement_definition`, `event_profile` and their children |
> | v3 | 1.0.0 | no new table: `measurement_definition` recreated for the derived-definition columns |
> | v4 | 1.0.0 | no new table: `asset` recreated for the parent-asset and season columns |
> | v5 | 1.0.0 | **`attachment`** |
> | v6 | **1.2.0** | `maintenance_group`, `maintenance_group_member`, `maintenance_schedule`, `schedule_provider`, `occurrence_closure`, `schedule_state`, `schedule_local_delivery`, and the three new `asset_event` columns |
> | v7 | **1.3.0** | `asset_reference` |
> | v8 | **1.4.0** | `asset_season_activation`, `asset_condition`, `health_subject`; `maintenance_schedule` recreated (the three season columns dropped; `service_policy`, `policy_offset_days` and `rule_changed_at` added) and `schedule_state` recreated as derived and empty; five `asset` columns added (`season_mode`, `blackout_start_mmdd` and `blackout_end_mmdd` for the maintenance break, `health_aggregation`, `health_primary_subject_id`) |
>
> So **attachments shipped at v5, not v4, and schedules land at v6, not v3** (1.2 spec §3.1).
> Supplies, reminder projections and the provider outbox hold no version number yet: they are
> Phase 5 and later, and will take **v9 upward** (amended 2026-09-24, ServiceTag 1.4.0: v8 is
> taken by the seasons, condition and health tables; amended before that on 2026-09-23,
> ServiceTag 1.3.0, when v7 was taken by `asset_reference`). Reading the stale plan is what almost
> put 1.2's schedules at v3, on top of tables that already exist.
- The section above is written against Room's annotation model, which Room 3.0 shares with 2.8
  (`@Entity`, `@ForeignKey`, `@Index`, `@Transaction`, schema export). Room 3.0 is the starting
  line (D3 §5); nothing in this schema depends on a Room 2-only API.
- No FTS in MVP; `name COLLATE NOCASE LIKE` is enough for the expected row counts.
