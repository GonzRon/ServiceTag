# D8 — Risk register and decisions

Status: revised 2026-09-14 after review 1. Rulings R-1..R-13 have been received (§3); the
remaining gate is your final approval of this revised package.

## 1. Decisions made (ledger ids in D3 §16)

A1 Room as the sole canonical store, now on the **Room 3.0.x** line (A18 revised); A2 UUID keys;
A3 tag identity with binding table (ruled R-2); A4 NDEF `:tag` v1 + AAR, legacy read-only; A5
Compose/M3/Navigation 3 (current 1.1.x line); A6 `:core` + `:app`; A7 manual DI; A8 calendar
dates for scheduling; A9 single current occurrence, derived state via `rebuild`; A10 FIXED
skip-forward; A11 season window on asset with FOLLOW/IGNORE and two re-entry rules (ruled R-8);
A12 inexact daily alarm + WorkManager backstop; **A13 revised**: Todoist projection is
capability-based (`NATIVE_RECURRING` for completion-relative, time-only, year-round schedules;
`MANAGED_OCCURRENCE` otherwise) with canonical due verification, personal token first (ruled
R-3); A14 controlled EAV with asset-scoped definitions; A15 attachment metadata + `AttachmentStore`
(LOCAL + SAF tree first); A16 backup ZIP with Replace/Merge and auto snapshots, unencrypted by
default (ruled R-5); A17 `notenfc://` navigation-only, App Links designed for but not blocking
(ruled R-4); **A19 added**: providers per schedule are a set in the model, single-choice in the
MVP UI (ruled R-12); **A20 added**: representation chosen per schedule from rule capabilities and
verified after every completion.

## 2. Technical spikes (each ≤ 1 day, before the phase that needs it)

| Id | Spike | Question | Needed before |
|---|---|---|---|
| S1 | Toolchain | Does AGP 9.4 + built-in Kotlin + KSP 2 + **Room 3.0.x** (`androidx.room3`, Room Gradle plugin `schemaDirectory`, bundled SQLite driver for JVM tests, migration test helper) + Compose BOM 2026.08 + Navigation 3 1.1.x configure, compile, and run a DAO test on the JVM? Fallbacks: Room 2.8.5 (only on a concrete deficiency), Navigation-Compose 2.x, AGP 8.13. | Phase 0 |
| S2 | NFC on the target phone | Reader mode write + read-back on NTAG213/215; behaviour when the tag already has content; `Ndef.maxSize`; ~~OEM handling of `FLAG_READER_SKIP_NDEF_CHECK`~~ (resolved in Phase 1B: the flag must not be set for NDEF work at all). | Phase 1B |
| S3 | Todoist link tappability | Is `notenfc://…` (plain and as a markdown link) tappable in Todoist for Android? If not, the description carries a fallback instruction and the case for App Links (R-4) strengthens. | Phase 5 |
| S4 | Reminder delivery on the device | Does `setAndAllowWhileIdle` at 09:00 fire within an acceptable window on the user's OEM under Doze/battery saver? Does the backstop worker run within 12 h? | Phase 3 |
| S5 | SAF tree providers | Which installed providers appear in `ACTION_OPEN_DOCUMENT_TREE` and support `createDocument` on the user's phone. **Ran 2026-09-15** (`spikes/S5-saf-tree-provider.md`): the primary-storage provider works end to end and the grant survives a process kill; Google Drive is not installed on the phone; the owner's Proton Drive-synced folder passed the same probe. | Phase 4A |
| S6 | Todoist Sync commands | Against a real account: `item_add` with `temp_id` + command `uuid` idempotency; `item_uncomplete` + `item_update` on a closed managed task; `item_close` on a native recurring task and the resulting `due.date`; `item_update` with `due: {date, string}` keeping the recurrence; activity-log `item:completed` pagination since a cursor; `sync_token` continuity; the 401/477 shape. | Phase 5 |
| S7 | Auto Backup | Verify `dataExtractionRules` exclude the secret file and that a same-signature reinstall restores the Room DB. **Note (2026-10-03, #16, R16-5):** no exclusion rule is written for the secret file: it lives in `noBackupFilesDir`, which Auto Backup never copies. A restored Home Assistant binding reads `NEEDS_TOKEN` and sends nothing until a token is entered. | Phase 1A |
| G1 | Visual-design gate (a review, not a spike) | Do 3–4 representative screens (asset detail, dashboard, water-test entry, NFC scan/write) drawn from D12 work as a real Android interface with the Apollo influence restrained? Produced only when the owner requests the exercise; never during Phase 0. | Phase 1C |
| S8 | FIXED rules as native recurrence | For a plain `every N days/months/years` task: what does Todoist compute after (a) an early completion, (b) a late completion, (c) a very-late completion, (d) a manual reschedule followed by completion? Compare with D5 §2.1. Documented behaviour for (d) already contradicts the FIXED model (next date from completion date), so FIXED stays `MANAGED_OCCURRENCE` unless the spike shows the verification step corrects every case within one sync without user-visible surprise. | Phase 5 (optional widening) |

## 3. Rulings received (2026-09-14)

| Id | Ruling | Consequence in the design |
|---|---|---|
| R-1 | Plan for no signing key; perform a non-destructive signature/keystore investigation before Phase 1 and identify the installed APK/certificate; in-place migration is a bonus | Investigation done in Phase 0 (`phase-0-evidence.md`): no key on this machine. **Closed by R-14/R-15**: in-place update is no longer a goal |
| R-14 (2026-09-14, post-Phase 0) | Legacy compatibility is best-effort, non-blocking; the old app is not a behavioural contract; existing tags will be re-provisioned | D13; D6 partly superseded; D7 Phase 1 criteria rewritten; issues #2/#30/#31 updated, #29 closed |
| R-15 (2026-09-14) | Normalise application id / package root to `com.loosecannon.notenfc` before the first Room schema | D13 §4; first commit of Phase 1A; new keystore; version restart 1 / "2.0" |
| R-2 | Tag identity + binding table approved | A3 stands |
| R-3 | Personal Todoist API token first; keep auth abstracted | D3 §8 auth row; `integration_account.auth_kind` |
| R-4 | Design for verified HTTPS App Links and PKCE later; not a Phase 0–5 blocker; revisit before Phase 5 | A17 note; Phase 5 prerequisites; Phase 7 optional item |
| R-5 | Unencrypted backups by default, optional passphrase later; explicit warnings; never export secrets | A16 stands |
| R-6 | Execution order after Phase 3: Attachments (4) → Todoist (5) → Supplies (6); keep them independent | Phases renumbered in D2, D3, D4, D7, security and testing docs; Room version plan v4 attachments, v5 projections, v6 supplies |
| R-7 | Standalone link tags launch immediately; card-first as a preference | D3 §10 stands |
| R-8 | AT_START(+offset) and RESUME_CLAMPED only for MVP | A11 stands |
| R-9 | Archive-first; explicit hard delete with typed confirmation and automatic pre-delete snapshot | D4 §13 stands |
| R-10 | Ignore Evernote-era tag types unless physical examples are known | D6 §1 stands |
| R-11 | Keep minSdk 26 | D3 §15 stands |
| R-12 | Single-choice provider in MVP UI; model permits multiple providers | `schedule_provider` join table replaces the `reminder_provider` enum column (D4 §8); A19; D3 §7.1 |
| R-13 | Apply the D2 issue restructuring after incorporating the Room 3 and Todoist-representation corrections | D2 §5 updated with an "incorporate review-1 corrections" row; issues still untouched pending final approval |

## 4. Risk register (ranked by likelihood × impact)

| # | Risk | L | I | Mitigation | Owner phase |
|---|---|---|---|---|---|
| 1 | **Legacy install/tags lost at cutover** — *downgraded to historical information by R-14* | High | Low | Accepted: clean install of the new package; old tags re-provisioned in format v1; best-effort legacy recognition only | — |
| 2 | **Recurrence correctness** (early/late/skip, month-end, leap, season re-entry) | Med | High | Pure engine, exhaustive deterministic suite, worked examples as tests, `rebuild` as the only writer | 3 |
| 3 | **Android background delivery** (OEM battery killers, Doze, force-stop, Android 17 stopped-state NFC) | High | Med | Inexact alarm + WorkManager backstop + boot receivers; health screen explains and repairs; S4 | 3 |
| 4 | **Toolchain churn** (AGP 9 built-in Kotlin, Room 3.0 is new, Navigation 3 1.2 pending) | Med | Med | S1 first; pin versions in the catalog; fallbacks named (Room 2.8.5 only on evidence) | 0 |
| 5 | **Todoist projection drift / duplicates / conflicts**, now including native-recurrence divergence (Todoist recomputes from the completion date after a manual reschedule; time-zone date differences) | Med | Med | Native eligibility restricted to COMPLETION-basis, time-only, year-round rules where Todoist's semantics match ours exactly; mandatory canonical verification after every completion (`SYNC_DUE`); `PROJECTION_DUE_DRIFT` finding; outbox with command uuid; `UNIQUE` guards; conflicts surfaced not guessed; S6, S8 | 5 |
| 6 | **Todoist deep links not tappable on Android** | Med | Low | S3; description fallback; R-4 | 5 |
| 7 | **Schema flexibility vs complexity** (profile layer grows into a form framework) | Med | Med | Hard boundary in D3 §6: no expressions, no conditionals; templates as data only | 2 |
| 8 | **Backup/restore mistakes** (partial import, format drift) | Low | High | Transactional Replace; manifest hash; format version + upgrader; round-trip tests; auto snapshots | 1A |
| 9 | **SAF provider inconsistency** (cloud tree unsupported, grants lost) | High | Low | The managed store is one SAF tree (S5 proved the primary-storage provider; a Syncthing-replicated folder gives the off-device copy); a lost grant is a visible Settings state, never a crash; no app-private fallback is made primary silently | 4A |
| 10 | **Room 3.0 early-adopter issues** (e.g. the `@Transaction` deadlock fixed in 3.0.2) | Med | Low | Stay on the latest 3.0.x patch; S1 exercises transactions and migrations; the repository port isolates the domain | 0–1 |
| 11 | **Legacy key collision on re-link** | Low | Low | `UNIQUE` refuses; explained; new-format rewrite offered | 1B |
| 12 | **Compose learning curve / Navigation 3 rough edges** | Med | Low | S1; Navigation-Compose fallback | 1C |
| 13 | **Scope creep from 16 issues** | High | Med | D2 re-tiering; each phase has falsifiable exits; no open-ended bucket | all |
| 14 | **Meter baseline missing (NO_DATA) confuses users** | Med | Low | Required meter field on completion; `anchor_meter` at creation; health finding | 3 |
| 15 | **Attachment bytes lost when a store changes** | Low | Med | Provider-relative locators; migrate-store loop; backup includes managed bytes | 4 |
| 16 | **Custom-scheme hijack** | Low | Low | Navigation-only links; App Links if R-4 | 1 |
| 17 | **Double notifications if both providers are enabled later** | Low | Low | MVP UI single-choice; when multi-provider UI ships, the local provider suppresses items already delivered by another enabled provider that day (design note for Phase 7) | 7 |

## 5. Facts that would change the plan

- R-1 is closed (R-14/R-15); finding the key would change nothing.
- If a real user base appears before Phase 1A ships, R-14/R-15 must be revisited (A21).
- R-4 domain becomes available → App Links and PKCE OAuth join Phase 5/7 and risks 6 and 16 drop.
- S1 fails on Room 3.0 specifically → Room 2.8.5 with the same schema; the domain is unaffected.
- S8 passes → FIXED rules become eligible for `NATIVE_RECURRING`; A13's eligibility list widens.
- S4 shows the OEM kills inexact alarms → the digest moves to WorkManager-only with a wider window and the health screen recommends whitelisting; exact alarms remain excluded.
