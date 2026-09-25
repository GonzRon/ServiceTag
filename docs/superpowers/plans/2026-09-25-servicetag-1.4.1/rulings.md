# ServiceTag 1.4.1 — the owner's rulings of 2026-09-25 (binding on every brief)

Recorded from the owner's two messages of 2026-09-25 that accepted the #80 / #81 direction and
then revised it on architecture. Where the second message revises the first, the second stands.
Issue bodies: #80 (the owner's, with rulings), #81 (filed 2026-09-25 per ruling). Coordinator: #76.

## R1 — Reminder Health strings: REOPENED, not ratified

The first message ratified `<n> schedules have reminders on but are not assigned to Local
reminders.` / `Assign Local reminders to all <n>`. The second message **withdrew** that pair: it leaks
the retired multi-provider architecture into the product ("What is a Local reminder? Is there
another kind?" — today the honest answer is no). Provider terminology is an implementation term,
never product vocabulary. The owner's candidates:

| pair | finding sentence | action label |
|---|---|---|
| A | `<n> schedules have reminders turned on, but reminder delivery isn't configured.` | `Fix all <n> reminders` |
| B | `<n> schedules have reminders turned on, but they can't send notifications.` | `Fix reminder delivery` |

Planner alternatives recorded for the owner's choice: pair B's sentence with pair A's label; `Log it as` in place of `Quick action` for the editor's One-tap picker label (plan.md §4).

An optional detail line was offered for a detailed screen: `ServiceTag will restore local
notification delivery for these schedules.` The plan proposes the exact strings in plan.md §4; they
ship only once ratified. `NOTIFICATIONS_BLOCKED` stays entirely separate.

## R2 — repair surface

One canonical `RepairScheduleProviders` use case; the in-app Reminder Health batch action and the
API/MCP plan/apply tooling are adapters over it. **There is only one repair policy.** Plan is
non-mutating and reports at least matched / would-repair / skipped counts. Apply re-evaluates the
current state; it never replays a stale plan.

## R3 — the automatic repair predicate

```text
status == ACTIVE && remindersEnabled == true && providers.isEmpty()
```

The repair adds exactly `LOCAL enabled=true`. It never changes automatically: PAUSED schedules;
ARCHIVED schedules; a non-empty provider set whose providers are disabled (a deliberately disabled
provider is not for a bulk integrity repair to second-guess). Prevention handles future creates.

## R4 — API create semantics

On CREATE only: `providers` absent (or null-equivalent) → one LOCAL row with
`enabled = remindersEnabled`; explicit `providers: []` → the explicit empty set is preserved; an
explicit list → preserved and validated as today. PATCH / full-replace omission semantics are **not**
altered by #80. The MCP `create_schedule` and the Stage-B schedules loader stop relying on the
accidental server default: the loader deliberately sends the normal LOCAL provider derived from
`remindersEnabled`; no manifest expansion unless planning finds a genuine user-facing reason (it
did not).

## R5 — `updatedAt`

Accepted: the repair is a real canonical configuration change, so ordinary `updatedAt` movement is
correct. `ruleChangedAt`, recurrence, service policy, postponement, `profileId`, occurrence identity
and maintenance history remain unchanged. After each real phone repair, a fresh post-repair
canonical backup is created and proven. Older providerless backups are historical snapshots, not
state expected to merge IDENTICAL with a repaired phone.

## R6 — the release vehicle

#80 and #81 constitute **ServiceTag 1.4.1, versionCode 17**, a PATCH. Scope is limited to: (1) #80
reminder-provider creation defaults, existing-data repair and Reminder Health UX; (2) #81
preserving a valid QUICK-schedule `profileId` through editor load / edit / save. No Phase 1A or
other Phase 1B work rides in build 17. After 1.4.1 closes, Phase 1A resumes exactly as planned at
`a8717c2`.

## The architecture ruling (second message)

- For the current product: `remindersEnabled = true` normally implies an enabled LOCAL row;
  `remindersEnabled = false` normally implies LOCAL disabled. "Reminders enabled but routed nowhere"
  is no longer an ordinary valid state, only an explicitly requested exceptional one (an explicit
  empty list through the API).
- The provider-neutral internal `ReminderProvider` interface stays: it keeps WorkManager, channels,
  permissions and alarms out of the domain.
- The per-schedule `schedule_provider` table stays **for now**: removing it in a patch would create
  schema / backup / merge churn while fixing a production bug. A later deliberate schema cleanup may
  remove it.
- Provider selection is not an active product concept. LOCAL is implicit / default. Provider
  terminology is hidden from the normal UX.

## #81

Its own brief after #80, unless planning proves a clean two-lane arrangement within the two-lane
ceiling (plan.md §2 does). A QUICK asset schedule with `profileId` is valid domain state; loading and
saving must preserve the profile, including when unrelated fields are edited. The profile picker /
relationship must remain representable in QUICK mode; a profile is never silently cleared merely
because `completionMode == QUICK`.

## Brief structure

P80-A and P80-B are approved in principle, but not assumed operationally disjoint: P80-B consumes
the `RepairScheduleProviders` contract P80-A creates. Before dispatch: freeze the use-case
input/output contract; enumerate the DI / AppGraph / navigation / shared files both need; assign
shared wiring to one lane or the controller; otherwise serialize. Two lanes are a ceiling, not a
target.

## The 1.4.1 proof sequence (verbatim order)

Before any production-phone mutation: (1) the unit / JVM / Python / Android gates; (2) task reviews
under the standing review policy; (3) the whole-patch review at the final 1.4.1 tip; (4) the
emulator repair proof; (5) the emulator / preserved-data proof of a **direct 1.3.0 → 1.4.1 build 17**
upgrade, the path the production phone will take; (6) release / tag / artifact verification;
(7) development phone 1.4.0 → 1.4.1; (8) repair plan proving the expected count 43 before apply;
(9) apply; (10) re-plan = 0; (11) every affected schedule carries LOCAL with ids / profileIds /
rules / policies / postponements / history unchanged; (12) representative LOCAL reminder subjects
exist and `SCHEDULE_NO_PROVIDER` is gone; (13) a fresh post-repair backup, proven; (14) **STOP for
owner approval**. Only after approval: (15) production phone direct 1.3.0 → 1.4.1; (16) read-only
repair plan and expected count; (17) the same idempotent apply; (18) re-plan = 0 and the same
invariants; (19) fresh post-repair backup and evidence. The production phone is untouched until
that owner stop.

## #76

The explicit immediate order stays **#80 → #81 → Phase 1A → #78 → remaining Phase 1B → the rest of
the roadmap** (reconciled in #76's body on 2026-09-25).
