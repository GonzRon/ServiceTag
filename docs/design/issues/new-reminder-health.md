---
action: create
title: "[MVP] Reminder health and integrity checks with idempotent repair"
milestone: "Phase 3 — Scheduling + local reminders"
labels: []
---

Split from #14 (D2 §5: "#14 (seasons · reminder health)"). The original issue carried two unrelated features; this is the second.

## Goal

Because maintenance reminders are a reliability mechanism, the app must be able to tell the user when a reminder path is broken — and repair the unambiguous cases itself.

Local findings ship in Phase 3; the Todoist findings arrive with Phase 5 (#9, #10).

## Scope (D3 §7.3)

`ReminderHealthCheck.run()` returns findings, each with a severity, a plain explanation, and an optional repair action.

### Phase 3 (local)

| Finding | Detection | Repair |
|---|---|---|
| `NOTIFICATIONS_BLOCKED` | notifications disabled, or channel importance NONE | open system settings |
| `DIGEST_ALARM_MISSING` | `PendingIntent.getBroadcast(FLAG_NO_CREATE)` is null while reminders are enabled | automatic re-arm (safe, idempotent) |
| `BACKSTOP_WORK_MISSING` | `getWorkInfosForUniqueWork` empty | automatic re-enqueue |
| `APP_RESTRICTED` | standby bucket RESTRICTED or battery optimisation "restricted" | explain and open settings |
| `REMINDERS_GLOBALLY_OFF` | preference | one tap to enable |
| `SCHEDULE_NO_PROVIDER` | an active schedule with `reminders_enabled` but no enabled `schedule_provider` row | open the editor — **superseded in 1.4.1:** the providerless shape offers "Fix reminder delivery" (the canonical `RepairScheduleProviders`); "open the editor" remains only for a non-empty set with nothing enabled, now `SCHEDULE_PROVIDER_DISABLED` |
| `NO_DATA` schedules | meter baseline missing | open the completion / anchor form |

### Phase 5 (Todoist, arrives with #9 and #10)

`TODOIST_DISCONNECTED`, `PROJECTION_MISSING`, `PROJECTION_CONFLICT`, `PROJECTION_DUE_DRIFT` (the native task's date disagrees with the canonical one after verification), `SYNC_STALE` (> 48 h), `OUTBOX_FAILING`.

### Repair policy

- Repair only what is unambiguous and idempotent: re-arming an alarm, re-enqueuing a worker, recreating a remote task **only when it is confirmed absent**.
- A due-date drift is corrected automatically — canonical wins.
- **Conflicts are never auto-repaired.** The user chooses.

### Where it runs

On app launch, after every sync, inside the backstop worker, and on the Health screen itself. A red badge appears on Home when any finding has severity ≥ WARN.

## Relationship to the rest

The per-schedule "reminders enabled" flag the original issue assumed is `maintenance_schedule.reminders_enabled`, defined in **#4** (D4 §8). Findings are produced through `ReminderProvider.health()` (`new-reminder-provider-interface`) for provider-specific conditions, and directly for app-level conditions. Android's own constraints are `new-platform-permissions-scheduling`.

The Health screen is also where the platform realities are explained rather than hidden: an OEM that restricts the app, and Android 17 not dispatching NFC to a force-stopped app.

## Visual design

D12 §5 semantic states — **REMINDER FAILED** (`notifications_off`, error family) and **SYNC ISSUE** (`cloud_off`, provider name plus retry state), alongside **OUT OF SEASON** and **PAUSED**; every finding carries icon plus explicit wording plus position, never colour alone.

## Acceptance criteria

1. Every Phase 3 finding has a test that produces it under the simulated condition **and** a negative control asserting the detector is silent when the condition is absent (testing doc §5).
2. Revoking notification permission turns the health screen red, and Repair opens system settings (D7 Phase 3 exit criterion 3).
3. Deliberately cancelling the digest alarm produces `DIGEST_ALARM_MISSING`, and Repair re-arms it; running Repair twice changes nothing the second time.
4. A schedule with `reminders_enabled` and no enabled provider row produces `SCHEDULE_NO_PROVIDER`.
5. A schedule with a meter rule and no baseline produces `NO_DATA` with a repair that opens the anchor form.
6. No conflict finding is ever repaired automatically.

## Design references

D2 §5 (split of #14) · D3 §7.1 (`health()` on the port), §7.3 (findings and repairs), §8 (Phase 5 findings), §9 (Android 17 stopped state) · D4 §8 (`reminders_enabled`, `schedule_provider`) · D5 §3 (`NO_DATA`) · D7 Phase 3, Phase 5 · D8 risk 3, risk 14 · testing doc §4, §5 · D12 §5.
