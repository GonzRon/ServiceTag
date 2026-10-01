# noteNFC evolution — design review package

Prepared 2026-09-14; revised the same day after review 1 (see
[11-review-1-changes.md](11-review-1-changes.md) for every change and its evidence). Nothing
outside `docs/design/` was changed; no GitHub issue was edited; nothing is committed.

| Doc | Deliverable |
|---|---|
| [01-current-state-archaeology.md](01-current-state-archaeology.md) | D1 — what actually exists, with evidence labels |
| [02-requirements-reconciliation.md](02-requirements-reconciliation.md) | D2 — issues #1–#16 mapped, reconciled, re-tiered; missing requirements; issue-action plan |
| [03-target-architecture.md](03-target-architecture.md) | D3 — components, modules, UI toolkit, persistence, reminders, Todoist boundary, NFC, links, attachments, backup, decision ledger |
| [04-domain-data-model.md](04-domain-data-model.md) | D4 — concrete Room schema, canonical vs derived, lifecycle |
| [05-scheduling-semantics.md](05-scheduling-semantics.md) | D5 — recurrence rules, seasons, the four operations, worked examples, invariants |
| [06-legacy-compatibility.md](06-legacy-compatibility.md) | D6 — keeping every existing tag working |
| [07-implementation-sequence.md](07-implementation-sequence.md) | D7 — phases (1A/1B/1C inside M1, 3R automatic backup after Phase 3, before attachments) with falsifiable exit criteria |
| [08-risk-register.md](08-risk-register.md) | D8 — decisions, spikes, rulings received, ranked risks |
| [09-security-privacy.md](09-security-privacy.md) | threat analysis |
| [10-testing-strategy.md](10-testing-strategy.md) | testing pyramid and the deterministic scheduling suite |
| [11-review-1-changes.md](11-review-1-changes.md) | change log from review 1, with verification of the reviewer's factual claims |
| [12-visual-design-apollo-service-binder.md](12-visual-design-apollo-service-binder.md) | approved visual-design direction (theme, semantic status colours, signature devices); implemented from Phase 1C, never in Phase 0 |
| [13-compatibility-policy.md](13-compatibility-policy.md) | **post-Phase-0 ruling**: legacy compatibility is best-effort and non-blocking; package identity normalised to `com.loosecannon.notenfc`; what was dropped and what stays |
| [14-asset-model.md](14-asset-model.md) | the current asset model (2026-10-01, #15): Asset → nested Component → optional SupplyItem identity → maintenance usage/history, and when a SupplyItem needs a #47 Component |
| [phase-1a-evidence.md](phase-1a-evidence.md) | Phase 1A evidence: package identity, schema v1, durable IDs, replace-mode backup |
| [phase-1b-evidence.md](phase-1b-evidence.md) | Phase 1B evidence: payload format v1, resolver, reader mode, safe writer; device checklist |
| [phase-1c-evidence.md](phase-1c-evidence.md) | Phase 1C evidence: Compose shell, theme, Nav3, assets/links/scan/backup; milestone M1 exit criteria, device checklist, final gate |
| [phase-2a-evidence.md](phase-2a-evidence.md) | Phase 2A evidence: event journal, measurement definitions, profiles, seed templates, schema v2, backup format 2; device checklist, final gate |
| [phase-2b1-evidence.md](phase-2b1-evidence.md) | Phase 2B-1 evidence: definition and profile editors, derived readings, schema v3, backup format 3; automated device proof, v2→v3 device migration, final gate |
| [phase-2b2-evidence.md](phase-2b2-evidence.md) | Phase 2B-2 evidence: the physical asset model — full asset fields, parent/child composition, season windows, retirement as data, category suggestions, two-tab navigation, schema v4, backup format 4; automated device proof, v3→v4 device migration, final gate |
| [phase-4a-evidence.md](phase-4a-evidence.md) | Phase 4A evidence: attachments — the `Attachment` model and store ports, the SAF-tree managed store chosen once in Settings, DOCUMENTS sections with thumbnails and the system viewer, schema v5, backup format 5 plus artifacts format 1 as a two-archive set; emulator device proof, final gate, and the owner's phone step |
| [g1/01-g1-visual-gate-report.md](g1/01-g1-visual-gate-report.md) | **G1 visual-design gate** (pre-1C): four archetype screens against D12, D12 corrections a–j, verdict PASS WITH CHANGES; input inventory in [g1/00-source-data-inventory.md](g1/00-source-data-inventory.md) |

Terminology: the new NFC payload is **noteNFC tag payload format v1** (version byte `0x01`, record type `com.loosecannon.notenfc:tag`), the new-generation replacement for the legacy `md5_short` record. "v1" always refers to that payload version byte, never to an application generation.

## The architecture in plain language

A physical tag carries only a random identifier. The phone holds a small SQLite database (Room
3.0) where that identifier is bound to either an asset or a standalone link. An asset has a
journal of events; each event can carry typed measurements (pH, engine hours, battery voltage)
and consumable usage (1 oz chlorine), entered through per-asset profiles that are data, not code.
Maintenance schedules are one provider-neutral rule model (fixed cadence, completion-relative,
usage-based, or "whichever first", with an optional seasonal window on the asset); their due
state is always recomputed from the journal, so an edited or deleted event can never leave a
stale due date. Reminders are delivered by a local provider (an inexact daily alarm backed by
WorkManager) and, optionally, projected into Todoist: schedules Todoist can express natively
(completion-relative, time-only, year-round) become real recurring Todoist tasks that advance
themselves, everything else becomes a dated task that noteNFC re-dates, and in both cases noteNFC
verifies Todoist's due date against its own after every completion. A health screen tells you
when a reminder path is broken and repairs the unambiguous cases. Backups are a ZIP with every
canonical table and managed attachments, IDs preserved, so tags outlive phones. Attachments are
metadata in the database and bytes in a pluggable store (app-private or a user-chosen document
tree, cloud-backed or not). Old `md5_short` tags are recognised best-effort and offered a rewrite; nothing about the
redesign is constrained by them (D13).

Code shape: two Gradle modules — `:core` (pure Kotlin: model, scheduling engine, NDEF codec,
backup format, link policy, ports) and `:app` (Room 3, Compose + Material 3 + Navigation 3, NFC
reader mode, alarms/WorkManager, Todoist client, SAF, Keystore). Hand-wired dependencies, no DI
framework, no backend, no plugin system.

## Phase sequence

| Phase | Slice | Proves |
|---|---|---|
| 0 | Foundation: clone-buildable repo, AGP 9 toolchain, `:core`, CI, legacy codec characterised, keystore investigation | nothing changes for users; the build is trustworthy |
| **1** | **Tag survival (M1)** = 1A persistence + legacy migration + backup/restore · 1B tag payload format v1 + legacy resolver + real-device proof · 1C Compose shell + asset/link UX + full restore proof | **the smallest useful end-to-end milestone**: a phone can die and every tag still resolves after restore |
| 2 | Asset core, event journal, measurement definitions, profiles, templates | "scan the tub, log a water test, see the history" |
| 3 | Scheduling engine, local reminders, health, dashboard | "see what needs to happen next" and be told reliably |
| 4 | Attachments (SAF-first storage boundary) | manuals, photos, receipts, provenance records on the asset |
| 5 | Todoist provider with capability-based representation and pull sync | optional task delivery without ceding authority |
| 6 | Supplies and parts | "what part belongs here, and do I have enough" |
| 7 | Extension points, polish, targetSdk 37 NFC permission, optional OAuth/App Links | keeps HA/InfluxDB/Grafana possible without building them |

## The most consequential decisions

1. **Tag identity is separate from asset identity** (binding table; ruled R-2).
2. **Schedule state is derived, never authored**: one `rebuild(config, events, today)` function is the only writer.
3. **Calendar dates, not instants, for everything the user calls a date.**
4. **FIXED cadence skips forward after a very late completion; no missed-occurrence backlog exists anywhere.**
5. **No exact alarms.** Inexact daily digest + WorkManager backstop + boot/time receivers.
6. **Todoist representation is capability-based** (review 1): native `every!` recurrence where Todoist's semantics match ours exactly, managed dated tasks otherwise, canonical verification always. Personal API token first (R-3).
7. **Compose, not XML.** No UI to migrate; Views are in maintenance mode as of May 2026.
8. **Room 3.0 from the first schema** (review 1): the stable line as of September 2026; 2.8.5 only as a spike fallback.
9. **Controlled EAV** for measurements (typed value columns + asset-scoped definition catalogue).
10. **Backup is in the first milestone** because the installed release APK is signed with a key that is not in the repo.
11. **Providers per schedule are a set in the model, single-choice in the MVP UI** (review 1, R-12).
12. **Attachment bytes never enter the database**; provider-relative locators behind `AttachmentStore`, SAF tree first.

## Rulings received

All thirteen rulings (R-1..R-13) are recorded with their design consequences in
[08-risk-register.md](08-risk-register.md) §3. The one open gate is your final approval of this
revised package; after that, the issue restructuring below is applied and Phase 0 begins.

## GitHub issue actions (nothing edited yet; detail in D2 §5)

- **Rewrite as epic:** #1 (reconciled MVP list; Todoist moves to NEXT).
- **Split:** #2 (Room/Asset · tag payload format v1 + legacy resolver · bind/rebind UX); #4 (engine · local provider · `ReminderProvider` interface); #9 (token/OAuth · projection with capability-based representation · deep-link contract → foundational · provider UX); #11 (local quick actions · fatigue controls · Todoist link actions); #12 (meter model → MVP · Todoist projection policy → NEXT); #14 (seasons · reminder health).
- **Merge:** the simple/rich completion text from #10 and #11 into #4 as `completion_mode`; the four-operation semantics from #9 and #11 into #4.
- **Re-tier:** #8 backup → MVP-1; #9 Todoist → NEXT (Phase 5); #7 attachments → NEXT (Phase 4, first post-MVP); #15 supplies → NEXT (Phase 6); #12 domain half → MVP.
- **Incorporate review-1 corrections before filing:** Room 3.0 as the persistence line (#2a); `NATIVE_RECURRING` vs `MANAGED_OCCURRENCE`, activity-log completion detection, and canonical due verification (#9/#10/#12); UI single-choice over a multi-provider model (#4-c/#9).
- **Convert ideas into scope:** #7.
- **New issues:** Phase 0 repo hygiene; tag payload format v1 + legacy resolver; `notenfc://` contract; platform permissions and scheduling; signing-key investigation and upgrade path; date semantics; meter model; asset templates; untrusted-input policy; testing and CI.
