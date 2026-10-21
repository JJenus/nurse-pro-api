# Nurse Pro: Shifts & Schedules Overhaul Plan

Covers `nurse-pro` (React frontend) and `nurse-pro-api` (Spring Boot backend).
Goal: make the schedule flow correct end to end, including the generation algorithm, rescheduling, idempotency and duplicate protection, edge cases, and a frontend/backend contract that cannot drift.

## Guiding principles

1. **One rules engine.** A single `ScheduleValidator` is used by generation, manual edits, swaps and rescheduling.
2. **Backend is the source of truth.** The frontend re-syncs from server responses instead of patching local state by hand.
3. **Generated contract.** TypeScript types are generated from the springdoc OpenAPI spec, and CI fails if they are stale.

## Findings that drive the plan

- Frontend calls `GET /schedules/{id}` and `GET /schedules/{id}/conflicts`; neither exists on the backend.
- Generation params differ: the frontend sends `minNursesPerShift`, `maxNursesPerShift`, `maxConsecutiveDay`, `maxConsecutiveNight`; the backend DTO expects `minStaffPerShift` (map), `maxConsecutiveShifts`, `minRestHours`, `maxOvertimeHours`, and the service ignores all of it (hardcoded constants).
- `approveSwapRequest` only flips a status; it never swaps nurses. Approve/reject can be applied repeatedly and in any order. The frontend never loads swap requests.
- Generator bugs: day-by-day greedy fill starves later days, 0-based vs 1-based day math, `assignments[31]`, `numToAssign` can exceed eligible nurses, no seed, `unavailableDates` can be null.
- Conflicts have no `scheduleId`, so they cannot be queried per schedule. `WorkloadData` has no uniqueness and can duplicate.
- No DB uniqueness on schedules or shifts; `ddl-auto: update` in dev and prod; no migrations.
- Frontend API client auto-retries requests, which can double-submit a POST.
- `fetchSchedules` returns every schedule with every shift (no filtering or pagination).
- Types say `Date`, but JSON delivers ISO strings.

## Phase 1: Data model and migrations (backend)

Status: code written on branch `feature/schedule-overhaul`; **not yet compiled or run** (no Maven access in the authoring environment). Test against a copy of the database before merging.

- [x] Flyway added (`flyway-core`, `flyway-mysql`); `ddl-auto` set to `none` in dev and prod. `validate` is deliberately not used yet: Hibernate 6 + MySQL enum columns trip schema validation, so switch to `validate` only after verifying against a real DB.
- [x] `V1__baseline_schema.sql`: `CREATE TABLE IF NOT EXISTS` for the legacy schema (no-op on existing databases, which are baselined at version 0).
- [x] `V2__schedule_integrity.sql`: dedupe, then constraints. Keeps the newest schedule per month, the lowest-id shift per slot, the most recently updated workload row.
- [x] `Schedule`: unique `(year, month)`, `@Version`; `shifts` is now the inverse side (`mappedBy = "schedule"`, `orphanRemoval`).
- [x] `Shift`: `schedule_id` foreign key (replaces the `schedules_shifts` join table, which is renamed to `schedules_shifts_legacy`), `@Version`, unique `(schedule_id, date, type, department)`, `assignedNurses` is a Set (primary key on `shift_assigned_nurses`).
- [x] `ScheduleConflict`: `scheduleId`, `date`, `resolved`; recomputed (delete then insert) per schedule.
- [x] `WorkloadData`: unique `(nurse_id, month, year)`; recomputed as an upsert; hours derived from real start/end times (night shifts cross midnight).
- [x] New tables and entities: `schedule_changes` (audit log) and `idempotency_keys`. They are created now and used from Phases 3 and 4.
- [x] `ShiftRequest` DTO (validated) replaces the `Shift` entity as the body of shift create/update. `scheduleId` is optional and defaults to the schedule of the date's month; optional `version` gives optimistic locking.
- [x] HTTP 409 for duplicate schedule, duplicate shift slot, stale version and constraint violations; shift delete is idempotent.
- [ ] Remaining DTO work (swap requests, generation params) lands with Phases 4 and 5.
- [ ] Follow-up migration once verified: make `shifts.schedule_id` NOT NULL (shifts created via `POST /shifts` before this change may be unattached) and drop `schedules_shifts_legacy`.

## Phase 2: Algorithm rewrite

Deterministic, seedable, constraint-based.

- **Inputs:** nurses, unavailable dates, preferred shifts, `maxHoursPerWeek`, specializations vs shift requirements, last days of the previous month for continuity.
- **Hard constraints:** min rest hours (default 11), max consecutive days, max consecutive Day/Night, weekly hours cap by ISO week (across month boundaries), no double-booking, night shifts respect next-day unavailability, min/max staff per shift.
- **Soft goals:** even share of shifts, nights and weekends; preferences; forward rotation (Day, Evening, Night); avoid isolated single days off.
- **Method:** (1) fill every shift to minimum, most constrained first; (2) fill to target with fairness scoring; (3) local-search repair pass; (4) anything unfixable becomes a conflict, never an exception or silent hole.
- **Output:** schedule + conflicts + per-nurse stats.

## Phase 3: Rescheduling

- `POST /schedules/{id}/reschedule` with `fromDate`, optional `toDate`, `excludeNurseIds`, `mode` (`fill_gaps` | `rebuild_range`), `lockedShiftIds`, `reason`.
- `dryRun=true` returns a diff without saving; applying requires the schedule `version`.
- Past dates are immutable; drafts can be regenerated; edits to published schedules are written to `ScheduleChange`.
- Changing a nurse's `unavailableDates` or removing a nurse flags affected future shifts as conflicts (no auto-mutation). Block deleting a nurse with future assignments, or soft-delete.

## Phase 4: Swaps, idempotency, duplicates

Status: backend and frontend done on `feature/schedule-overhaul` (backend not compiled or run; frontend type-checks with no new errors and `vite build` passes).

- [x] Swap state machine: Pending to Approved / Rejected / Cancelled. Repeating the same action is a no-op; any other transition is a 409. New `PUT /swap-requests/{id}/cancel`.
- [x] Atomic approve: row lock on the request, both shifts re-checked, nurses swapped, request marked approved, conflicts and workload refreshed, other pending requests on those shifts cancelled as superseded.
- [x] Validation at create and again at approve: requester must own the shift, no self-swap, past shifts and archived schedules are rejected, target must not already be on the shift, and the swap must not introduce new conflicts (unavailable, double-booked) under the shared `ScheduleValidator`. `SwapPlanner` computes the outcome as a pure function.
- [x] One pending swap per `(shiftId, requesterId)`: checked in the service and enforced by a unique key over a generated column (V4).
- [x] `Idempotency-Key` on generate: same key and same request returns the original schedule; same key with a different request is a 409; keys expire after 24 hours. The generator sends one key per attempt and renews it when the form changes.
- [x] Atomic move endpoint (`POST /schedules/shifts/move`) replaces the two separate updates behind drag and drop; repeating a completed move is a no-op.
- [x] Client retries only GET/HEAD/OPTIONS.
- [ ] Swap approval screen showing the validation result, and a cancel button in the UI (the store action exists).


- Swap state machine: Pending to Approved / Rejected / Cancelled; invalid transitions return 409.
- Approve is atomic: lock both shifts, validate, swap `assignedNurses`, mark approved, refresh conflicts and workload. Re-approve returns the same result.
- Swap validation: owner-only request, no self-swap, no past shifts, no same-shift swap, target not already working or unavailable. One pending swap per `(shiftId, requesterId)`.
- Generate: unique `(year, month)` plus locked transaction returns 409; optional `Idempotency-Key` header returns the original result on retry.
- Frontend: no auto-retry of POST/PUT unless a key is attached; disable submit while in flight.
- Shifts: PUT uses `@Version`/ETag (409 on stale); DELETE is idempotent and cancels pending swaps and clears conflicts.
- Publish/archive transitions are idempotent.

## Phase 5: API surface

Status: backend and frontend done on `feature/schedule-overhaul` (backend not compiled or run; frontend type-checks with no new errors and `vite build` passes).

- [x] Endpoints added: `GET /schedules?month&year&status` (filtered; one schedule per month so no paging), `GET /schedules/{id}`, `GET /schedules/{id}/conflicts`, `POST /schedules/{id}/validate`, `PUT /schedules/{id}/status`, `GET /schedules/swap-requests`, `POST /schedules/shifts/validate`.
- [x] `ScheduleValidator` is the single rules engine (understaffed, nurse unavailable incl. night shifts spilling into the next day, double-booked same day, nurse no longer exists). Min-rest and consecutive-day rules join it in Phase 2.
- [x] Generation params unified (`minNursesPerShift`, `maxNursesPerShift`, `maxConsecutiveShifts`, `maxConsecutiveDay`, `maxConsecutiveNight`, `minRestHours`, `maxOvertimeHours`, `seed`, `rules`) with validation and defaults; min/max nurses and consecutive limits now drive the existing generator. Generate returns `{schedule, conflicts, stats}`.
- [x] Conflicts and workload are refreshed whenever shifts change. Archived schedules reject edits (409). Status changes are idempotent (Draft, Published, Archived).
- [x] `reviewedBy` comes from the authenticated principal (falls back to `system`).
- [x] V3 migration converts enum columns to VARCHAR so new enum values (`overlap`, later `Cancelled`) can be stored.
- [ ] Role checks on approve/reject: **blocked**, see "Findings added during implementation".


| Endpoint | Purpose |
|---|---|
| `GET /schedules?month&year&status` | Filtered, paginated list |
| `GET /schedules/{id}` | Schedule detail |
| `GET /schedules/{id}/conflicts` | Conflicts |
| `POST /schedules/{id}/validate` | Recompute conflicts |
| `PUT /schedules/{id}/status` | Publish / archive |
| `POST /schedules/{id}/reschedule` | Phase 3 |
| `GET /schedules/swap-requests` | List swaps |
| `POST /schedules/shifts/validate` | Pre-check a manual edit |

Unified generation params: `minNursesPerShift`, `maxNursesPerShift`, `maxConsecutiveDay`, `maxConsecutiveNight`, `minRestHours`, `seed`, `rules` (defaults and `@Min`/`@Max` on the backend). Generate returns `{schedule, conflicts, stats}`. `reviewedBy` comes from the authenticated principal, with role checks on approve/reject.

## Phase 6: Frontend reconciliation

Status: first pass done together with Phase 5; the UI items still open are marked below.

- [x] Types: `Shift.date`/`Schedule.generatedAt` are ISO strings, `scheduleId`/`version` added, `ShiftInput`, `ScheduleGenerationResult`, `ScheduleFilters`, conflict fields and the `overlap` type.
- [x] Store: real endpoints, `fetchSwapRequests` (called from the swap list and dashboard), `fetchConflicts`, `updateScheduleStatus`, `previewShift`; no success toast on reads; shift edits send the full shift plus `version` (409 on stale); conflicts re-fetched after every shift change.
- [x] Dates are sent as local `yyyy-MM-dd` (`utils/dates.ts`).
- [x] Generator shows the server's message on 409 (schedule already exists).
- [x] Schedule editor uses server conflicts instead of its local mock.
- [x] Export fallback filename uses `.xlsx` for Excel.
- [ ] Generator preview step and "open existing / regenerate draft" on 409.
- [ ] Shift form: show `previewShift` results before saving.
- [ ] Publish / archive buttons (store action exists).
- [ ] Reschedule dialog and swap approval screen (Phases 3 and 4).


- Types: dates as ISO strings, `scheduleId`, real conflict shape, unified constraints.
- Store: fix endpoints, add `fetchSwapRequests`, re-fetch after mutations, drop success toast on fetch, per-action loading, distinct 409 handling.
- Generator: align fields with the DTO, preview before saving, "open existing" / "regenerate draft" on 409.
- Editor and shift form: call validate, show conflicts inline.
- New UI: reschedule dialog with diff view; swap approval screen showing validation.
- Export: fix `.excel` extension fallback; confirm the raw axios call carries auth.

## Phase 7: Edge cases

- Calendar: February and leap years, 28 to 31 day months, nights ending after midnight.
- Continuity across month and week boundaries.
- Staffing: fewer nurses than required, all nurses unavailable, `requiredStaff` of 0 or above roster size, nurses added or removed mid-month.
- Data hygiene: null `unavailableDates`/`preferredShifts`, dangling nurse IDs, shift dates outside the schedule month, bad time formats.
- Swaps across schedules or months; double-booking after a swap.

## Phase 8: Tests

- Unit tests per validator rule.
- Property tests: hundreds of seeds across several months never violate a hard constraint; same seed gives same output.
- Testcontainers integration tests: concurrent double-generate yields exactly one schedule; swap approve/reject races resolve correctly.
- CI contract check that fails when generated TypeScript types are stale.

## Findings added during implementation

- **Security:** `SecurityConfig` ends with `.anyRequest().permitAll()`, so every endpoint (including approve/reject and delete) is open to unauthenticated callers even though a JWT filter exists. Role checks and `authenticated()` were not switched on because that changes behaviour for every client; decide this deliberately.
- **Timezone bug (fixed):** the shift form sent a JS `Date`, which serialises to UTC, so for UTC+1 users a shift created for a day landed on the previous day.
- **Partial updates (fixed):** the editor sent `{assignedNurses}` only; the API now validates a full shift, so the store merges the change into the stored shift first.
- **Retry logic (fixed):** the client's retry guard compared `undefined < 3` (never true), so nothing retried. It now retries only GET/HEAD/OPTIONS.
- **Drag and drop is not atomic:** moving a nurse between shifts issues two separate updates; a failure in between leaves the nurse unassigned. Needs a single move/swap endpoint (Phase 4).
- **Nurse dates:** `Nurse.unavailableDates` is also a `Date[]` sent as UTC timestamps; same class of bug, to fix in Phase 7.

## Order of work

1. Phases 1 and 5 (missing endpoints, contract mismatches, constraints).
2. Phase 4 (no duplicates, no double-approvals).
3. Phase 2, then Phase 3 (both build on the shared validator).
4. Phase 6 alongside each backend step, then Phases 7 and 8.

## Assumptions

- Min rest is 11 hours; single "General" department for now, model ready for more.
- A schedule is unique per month; regenerating is allowed only for drafts.
- Existing data is small enough for a one-off dedupe script.
- DB is Postgres or MySQL, so Flyway plus unique constraints work.
