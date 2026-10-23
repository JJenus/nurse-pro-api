# Nurse Pro: Shifts & Schedules Overhaul Plan

Covers `nurse-pro` (React frontend) and `nurse-pro-api` (Spring Boot backend).
Goal: make the schedule flow correct end to end, including the generation algorithm, rescheduling, idempotency and duplicate protection, edge cases, and a frontend/backend contract that cannot drift.

## Database

PostgreSQL (AlwaysData in production). Migrations V1 to V5 are PostgreSQL SQL; each runs in a single transaction, so a failure leaves the schema untouched. `scripts/verify-migrations.sh` rehearses them on a scratch database with deliberately dirty legacy data (duplicate schedules, shifts, workload rows and pending swaps, orphan shifts, Hibernate-style enum CHECK constraints) and asserts the outcome and every new constraint; it passed on PostgreSQL 16, and CI runs it on every push.

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

- [x] Flyway added (`flyway-core`, `flyway-database-postgresql`); `ddl-auto` set to `none` in dev and prod (Flyway owns the schema; `validate` is used in CI to check the entities against the migrated schema).
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

Status: done on `feature/schedule-overhaul`. `ScheduleGenerator` replaces the day-by-day random greedy loop. It was compiled and unit-tested in a harness (JDK 21, JUnit, Lombok) against stubs for Spring/JPA: 36 tests pass. The full Maven build and the database-backed paths have still not been run.

- [x] Deterministic and seedable; the seed used is returned in the generate response (`seed`) and accepted in the params to reproduce a schedule.
- [x] Hard constraints (never violated, checked by an independent re-implementation in the tests): nurse unavailable dates including the morning after a night shift, one shift per nurse per day, minimum rest hours (default 11, so Evening to Day and Night to Day are blocked), max consecutive working days, max consecutive day-type and night-type shifts, weekly hours cap per ISO week (plus `maxOvertimeHours`).
- [x] Continuity: the last 14 days of the previous month's schedule count for rest, consecutive-day and weekly-hours rules.
- [x] Method: (1) fill every shift to the minimum, most constrained first; (2) raise all shifts evenly towards the maximum; (3) repair short shifts by taking a nurse from a shift that has surplus; (4) balance hours between the busiest and quietest nurses. Scoring uses fairness, nights and weekends, stated shift preferences and working in blocks.
- [x] Impossible shifts are left short and reported as conflicts; the generator never throws for that.
- [x] Ready for Phase 3: the request supports `locked` shifts (kept as they are), `excludedNurseIds`, a partial date range, and `history`.
- Measured on a 30-day month: 10+ identical nurses fill every shift with at most one shift of difference between nurses; 8 nurses leave 8 positions short (capacity at 40h/week is below the minimum demand); 80 nurses generate in well under a second.
- Behaviour to know: shifts are filled towards `maxNursesPerShift` (as before), limited by the weekly hours cap, so the roster is used as fully as the caps allow.
- [x] Shift `requirements` are matched against nurse `specializations` (case-insensitive, all required) when filling existing shifts; the validator reports `qualification` conflicts for manual assignments.
- [ ] Deliberately not implemented: the request's `rules` list (its meaning for the generator was never specified) and matching nurse department to shift department (departments are free text and generated shifts are all "General").

## Phase 3: Rescheduling

Status: backend and frontend done on `feature/schedule-overhaul`. The planning logic (`ReschedulePlanner`, `ScheduleGenerator`) is unit-tested (46 tests passing in the harness); the service wiring type-checks against stubs but has not run against a database.

- [x] `POST /schedules/{id}/reschedule` with `fromDate`, `toDate`, `mode` (`fill_gaps` | `rebuild_range`), `excludeNurseIds`, `lockedShiftIds`, `reason`, optional `constraints`.
- [x] Dry run by default: returns the diff (per shift: nurses added and removed, new shifts), totals, positions still unfilled, and the conflicts the schedule would have. Applying needs `dryRun=false` plus the `revision` and `seed` from the preview. The revision is a fingerprint of every shift's version and assignments, so a schedule that changed after the preview returns 409 and the preview must be redone.
- [x] Past dates are never touched (the start is clamped to today). Archived schedules are rejected (409).
- [x] Fill gaps keeps every assignment that is still valid, drops nurses who are excluded, unavailable or no longer exist, and only staffs shifts below their required staff; it never creates shifts. Rebuild range regenerates the range except locked shifts and may create missing shifts.
- [x] Shifts of the neighbouring months (14 days either side) constrain the edges of the range, so rest and consecutive-day rules hold across month boundaries.
- [x] Audit log: `GET /schedules/{id}/changes`. Every reschedule and every status change is recorded with actor and reason; shift edits, moves and approved swaps on a Published schedule are recorded too.
- [x] Changing a nurse's availability re-checks the conflicts of the open schedules where they still have upcoming shifts. Deleting a nurse who still has upcoming shifts returns 409 asking to reschedule first (soft delete was not added).
- [x] Frontend: a Reschedule dialog (range, mode, excluded nurses, reason, preview, apply) and Publish / Archive buttons with confirmation and a status badge on the schedule page.
- [x] The constraints a schedule was generated with are stored on it (V5), so conflict checks, previews, swaps and reschedules use the same rules; a reschedule request may override them.
- [x] History button and modal on the schedule page (audit log).

## Phase 4: Swaps, idempotency, duplicates

Status: backend and frontend done on `feature/schedule-overhaul` (backend not compiled or run; frontend type-checks with no new errors and `vite build` passes).

- [x] Swap state machine: Pending to Approved / Rejected / Cancelled. Repeating the same action is a no-op; any other transition is a 409. New `PUT /swap-requests/{id}/cancel`.
- [x] Atomic approve: row lock on the request, both shifts re-checked, nurses swapped, request marked approved, conflicts and workload refreshed, other pending requests on those shifts cancelled as superseded.
- [x] Validation at create and again at approve: requester must own the shift, no self-swap, past shifts and archived schedules are rejected, target must not already be on the shift, and the swap must not introduce new conflicts (unavailable, double-booked) under the shared `ScheduleValidator`. `SwapPlanner` computes the outcome as a pure function.
- [x] One pending swap per `(shiftId, requesterId)`: checked in the service and enforced by a unique key over a generated column (V4).
- [x] `Idempotency-Key` on generate: same key and same request returns the original schedule; same key with a different request is a 409; keys expire after 24 hours. The generator sends one key per attempt and renews it when the form changes.
- [x] Atomic move endpoint (`POST /schedules/shifts/move`) replaces the two separate updates behind drag and drop; repeating a completed move is a no-op.
- [x] Client retries only GET/HEAD/OPTIONS.
- [x] Swap screen: the details view asks the server whether the swap can be approved right now (`GET /swap-requests/{id}/check`) and shows why not; Approve is disabled when it cannot; Cancel / Withdraw buttons added. Server errors are no longer replaced by a generic toast.


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
- [x] V3 migration drops the CHECK constraints Hibernate 6 puts on enum columns, so new enum values (`overlap`, `rest`, `consecutive`, `Cancelled`) can be stored.
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
- [x] "Replace the existing draft" option on the generator (atomic: the old draft goes and the new one is created in one transaction; published and archived schedules are never replaced).
- [x] Generator preview step: "Preview first" generates without saving (`preview` flag) and shows shifts, short shifts, other conflicts and the spread of shifts per nurse; "Save this schedule" saves exactly that result using the returned seed.
- [x] Shift form: "Check for conflicts" shows what saving the shift would cause, before saving.
- [ ] Publish / archive buttons (store action exists).
- [x] Reschedule dialog and Publish / Archive buttons (Phase 3).
- [x] Swap approval screen (see Phase 4).


- Types: dates as ISO strings, `scheduleId`, real conflict shape, unified constraints.
- Store: fix endpoints, add `fetchSwapRequests`, re-fetch after mutations, drop success toast on fetch, per-action loading, distinct 409 handling.
- Generator: align fields with the DTO, preview before saving, "open existing" / "regenerate draft" on 409.
- Editor and shift form: call validate, show conflicts inline.
- New UI: reschedule dialog with diff view; swap approval screen showing validation.
- Export: fix `.excel` extension fallback; confirm the raw axios call carries auth.

## Phase 7: Edge cases

Status: done where it can be done without a running database; covered by unit tests unless noted.

- [x] Calendar: February and leap years, 28 to 31 day months, nights that end after midnight (generator, validator and workload hours all use real start/end times).
- [x] Continuity across month and week boundaries (previous and next month's shifts constrain generation and reschedules; weekly hours use ISO weeks).
- [x] Staffing: fewer nurses than required, all nurses unavailable, a nurse unavailable all month, required staff of 0 or above the roster size: never an exception; short shifts are reported as conflicts.
- [x] Data hygiene: null `unavailableDates` / `preferredShifts`, nurse ids that no longer exist (flagged), shift dates outside the schedule month (400), bad time formats (400), duplicate shifts and duplicate nurses in a shift (blocked by the database and the API).
- [x] Validator now enforces the work-pattern rules as well, with the schedule's own settings: minimum rest between shifts (`rest`), consecutive working days and consecutive day/night shifts (`consecutive`), weekly hours with the overtime allowance (`overtime`). Manual edits, moves and swaps are checked against the same rules as generation.
- [x] Swaps across schedules or months and double-booking after a swap are checked (only conflicts a swap would newly introduce block it).
- [x] Nurse deletion with upcoming shifts is blocked (409); availability changes re-check open schedules.
- [x] Nurse on-duty status: handled night shifts that cross midnight, uses local time instead of the server's timezone (`app.timezone`, default `Africa/Lagos`), and no longer loads every shift.
- [x] Unavailable dates: the nurse form now has an editor (there was none, so the availability rules could never be exercised from the UI); dates are plain `yyyy-MM-dd` strings, avoiding the UTC off-by-one.
- [x] The nurse list filter by specialization applied `lower()` to a collection; it now joins the collection.
- [ ] Known and not changed: the frontend still has 37 type errors that predate this work (`npx tsc -p tsconfig.app.json`; `npm run build` does not type-check); departments are not matched (see Phase 2).

## Phase 8: Tests

Status: done. CI runs everything on every push to a branch and on pull requests (GitHub Actions, Postgres 17 service).

- [x] 67 backend tests, all passing in CI on real Spring Boot and PostgreSQL:
  - Unit tests (no Spring, no database): `ScheduleValidator` (every rule, including qualifications), `ScheduleGenerator` (an independent re-check of every hard rule across seeds, a leap-year February, 30- and 31-day months, determinism, continuity, locked shifts, excluded nurses, speed), `ReschedulePlanner`, `SwapPlanner`, `ShiftTimes`.
  - `NurseProApiApplicationTests`: the whole application starts, Flyway migrates an empty database, and Hibernate (`ddl-auto: validate`) checks every entity against the migrated schema.
  - `ScheduleApiIntegrationTest` over HTTP against PostgreSQL: concurrent generate for one month creates exactly one schedule (the rest get 409); an `Idempotency-Key` retry returns the first schedule and a reused key with a different request is a 409; preview saves nothing and saving with the returned seed gives the same shifts; an existing draft is only replaced when asked; a second shift in the same slot is a 409; four concurrent approvals of one swap swap once and later approve/reject calls are no-ops or 409; reschedule needs the preview's revision, removes the excluded nurse, goes stale after applying and is written to the audit log; a nurse with upcoming shifts cannot be deleted; the OpenAPI document lists every route the frontend calls.
- [x] `scripts/verify-migrations.sh` (run in CI): migrations on a scratch PostgreSQL database with dirty legacy data, and every new constraint.
- [x] Frontend CI: `npm run build`.
- [ ] Generating the TypeScript types from the OpenAPI document (and failing CI when they are stale) was not done: the types are still hand-written. The integration test guards the part that broke before (routes the frontend calls that do not exist), not field names.

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
- The database is PostgreSQL (main moved from MySQL to PostgreSQL while this work was in progress, so migrations V1 to V5 were rewritten for PostgreSQL).
