# B01 — the close-round window guard (Lane A of 1.2.1)

Read `plan.md` in this folder first (owner rulings, global constraints, the Lane A contract and test hazards). This brief adds anchors.

- `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/CloseRound.kt` — the guard goes after `if (!occurrence.isActionable) throw OccurrenceNotCloseable(id, key)` and before `val todayOn = today.localDate()` is used for the range (reuse `todayOn`). `recompute.stateOf(schedule)` already exists in `RecomputeSchedules.kt` ("the schedule's derived state as it is now"). `schedule.leadDays` is on `MaintenanceSchedule` (nullable → treat null as 0).
- `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/GroupCommands.kt` — add `class OccurrenceNotYetOpen(val id: ScheduleId, val occurrenceOn: String, val opensOn: String)` beside `OccurrenceNotCloseable`, same base type.
- `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiJson.kt:225-236` — add the `is OccurrenceNotYetOpen -> errorResponse(409, "Conflict", "OCCURRENCE_NOT_YET_OPEN", …)` arm beside the others.
- `docs/api/v1.md` — the 409 table (rows ~415-421) and the close-round paragraph (~lines 187-192).
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleDetailViewModel.kt:183-196` — `canClose`; the state carries `effectiveDueOn: String?` (ISO) and `today: LocalDate`; add the schedule's `leadDays` to the state if it is not already there (it is set where `effectiveDueOn` is set, ~line 320).
- Spec `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.9 (the blockquote at ~line 480): append a dated "**1.2.1 amendment (owner ruling 2026-09-23)**" paragraph, do not edit the original text.
- `tools/servicetag-mcp/src/servicetag_mcp/server.py:1515` — the `close_round` docstring: one sentence.
- Tests: `core/src/test/.../usecase/CloseRoundTest.kt` (fixtures there show how to set today and the schedule's anchor/lead), `app/src/test/.../api/MaintenanceRoutesTest.kt` (`closingARoundThatObligesNobodyIs409OccurrenceNotCloseable` is the pattern), `app/src/test/.../ui/maintenance/ScheduleDetailViewModelTest.kt`. If a test enumerates the documented 409 codes from `docs/api/v1.md`, it must still pass.
- Gate: `./gradlew :core:test :app:testDebugUnitTest --console=plain` green (no connected runs; no device). Report: `.superpowers/sdd/2026-09-23-servicetag-1.2.1/B01-report.md`.
