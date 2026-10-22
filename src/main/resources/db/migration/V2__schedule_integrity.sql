-- V2: schedule integrity
--   * removes duplicate schedules / shifts / workload rows (keeps the newest schedule per month, the lowest-id shift per
--     slot, the most recently updated workload row)
--   * shifts get a real schedule_id foreign key (replaces the schedules_shifts join table)
--   * optimistic-locking version columns
--   * unique constraints: schedules(year, month), shifts(schedule_id, date, type, department),
--     workload_data(nurse_id, month, year), shift_assigned_nurses(shift_id, assigned_nurses)
--   * schedule_conflicts becomes queryable per schedule
--   * new tables: schedule_changes (audit log), idempotency_keys
-- PostgreSQL runs a migration in one transaction, so a failure leaves the schema untouched.

-- 1. Duplicate schedules per (year, month): keep the most recently generated one ------------------------------------
CREATE TEMP TABLE tmp_doomed_schedules ON COMMIT DROP AS
SELECT s1.id
FROM schedules s1
WHERE EXISTS (
    SELECT 1 FROM schedules s2
    WHERE s2.year = s1.year AND s2.month = s1.month AND s2.id <> s1.id
      AND (COALESCE(s2.generated_at, TIMESTAMP '1970-01-01') > COALESCE(s1.generated_at, TIMESTAMP '1970-01-01')
           OR (COALESCE(s2.generated_at, TIMESTAMP '1970-01-01') = COALESCE(s1.generated_at, TIMESTAMP '1970-01-01')
               AND s2.id > s1.id))
);

CREATE TEMP TABLE tmp_doomed_shifts ON COMMIT DROP AS
SELECT ss.shifts_id AS id
FROM schedules_shifts ss
WHERE ss.schedule_id IN (SELECT id FROM tmp_doomed_schedules);

DELETE FROM schedule_conflict_suggestions
 WHERE schedule_conflict_id IN (SELECT c.id FROM schedule_conflicts c WHERE c.shift_id IN (SELECT id FROM tmp_doomed_shifts));
DELETE FROM schedule_conflicts WHERE shift_id IN (SELECT id FROM tmp_doomed_shifts);
DELETE FROM shift_assigned_nurses WHERE shift_id IN (SELECT id FROM tmp_doomed_shifts);
DELETE FROM shift_requirements WHERE shift_id IN (SELECT id FROM tmp_doomed_shifts);
DELETE FROM schedules_shifts WHERE shifts_id IN (SELECT id FROM tmp_doomed_shifts);
DELETE FROM shifts WHERE id IN (SELECT id FROM tmp_doomed_shifts);
DELETE FROM schedules WHERE id IN (SELECT id FROM tmp_doomed_schedules);

-- 2. shifts.schedule_id + version, backfilled from the legacy join table -----------------------------------------------
ALTER TABLE shifts
    ADD COLUMN IF NOT EXISTS schedule_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE shifts sh
   SET schedule_id = ss.schedule_id
  FROM schedules_shifts ss
 WHERE ss.shifts_id = sh.id;

-- Shifts created through POST /shifts were never attached to a schedule: attach them to the schedule of their month.
-- Any left over (no schedule for that month) keep schedule_id NULL.
UPDATE shifts sh
   SET schedule_id = s.id
  FROM schedules s
 WHERE sh.schedule_id IS NULL
   AND s.year = EXTRACT(YEAR FROM sh.date)::INTEGER
   AND s.month = EXTRACT(MONTH FROM sh.date)::INTEGER;

-- 3. Duplicate shifts inside one schedule: keep the lowest id per slot ------------------------------------------------
CREATE TEMP TABLE tmp_dup_shifts ON COMMIT DROP AS
SELECT a.id
FROM shifts a
WHERE a.schedule_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM shifts b
      WHERE b.schedule_id = a.schedule_id AND b.date = a.date AND b.type = a.type
        AND b.department IS NOT DISTINCT FROM a.department AND b.id < a.id
  );

DELETE FROM schedule_conflict_suggestions
 WHERE schedule_conflict_id IN (SELECT c.id FROM schedule_conflicts c WHERE c.shift_id IN (SELECT id FROM tmp_dup_shifts));
DELETE FROM schedule_conflicts WHERE shift_id IN (SELECT id FROM tmp_dup_shifts);
DELETE FROM shift_assigned_nurses WHERE shift_id IN (SELECT id FROM tmp_dup_shifts);
DELETE FROM shift_requirements WHERE shift_id IN (SELECT id FROM tmp_dup_shifts);
DELETE FROM schedules_shifts WHERE shifts_id IN (SELECT id FROM tmp_dup_shifts);
DELETE FROM shifts WHERE id IN (SELECT id FROM tmp_dup_shifts);

-- 4. A nurse can only appear once per shift -------------------------------------------------------------------------------
DELETE FROM shift_assigned_nurses WHERE shift_id IS NULL OR assigned_nurses IS NULL;
DELETE FROM shift_assigned_nurses a
 USING shift_assigned_nurses b
 WHERE a.ctid > b.ctid AND a.shift_id = b.shift_id AND a.assigned_nurses = b.assigned_nurses;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'shift_assigned_nurses'::regclass AND contype = 'p') THEN
        ALTER TABLE shift_assigned_nurses ADD PRIMARY KEY (shift_id, assigned_nurses);
    END IF;
END $$;

-- 5. Workload rows: one per (nurse, month, year), keep the most recently updated ------------------------------------------
DELETE FROM workload_data w1
 USING workload_data w2
 WHERE w1.nurse_id = w2.nurse_id AND w1.month = w2.month AND w1.year = w2.year AND w1.id <> w2.id
   AND (COALESCE(w2.updated_at, TIMESTAMP '1970-01-01') > COALESCE(w1.updated_at, TIMESTAMP '1970-01-01')
        OR (COALESCE(w2.updated_at, TIMESTAMP '1970-01-01') = COALESCE(w1.updated_at, TIMESTAMP '1970-01-01')
            AND w2.id > w1.id));

-- 6. Conflicts become queryable per schedule ----------------------------------------------------------------------------------
ALTER TABLE schedule_conflicts
    ADD COLUMN IF NOT EXISTS schedule_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS date DATE,
    ADD COLUMN IF NOT EXISTS resolved BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE schedule_conflicts c
   SET schedule_id = sh.schedule_id, date = sh.date
  FROM shifts sh
 WHERE sh.id = c.shift_id;

-- 7. Constraints and indexes ------------------------------------------------------------------------------------------------------
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_schedules_year_month') THEN
        ALTER TABLE schedules ADD CONSTRAINT uk_schedules_year_month UNIQUE (year, month);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_shifts_schedule') THEN
        ALTER TABLE shifts ADD CONSTRAINT fk_shifts_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_shift_slot') THEN
        ALTER TABLE shifts ADD CONSTRAINT uk_shift_slot UNIQUE (schedule_id, date, type, department);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_workload_nurse_period') THEN
        ALTER TABLE workload_data ADD CONSTRAINT uk_workload_nurse_period UNIQUE (nurse_id, month, year);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_schedule_conflicts_schedule ON schedule_conflicts (schedule_id);
CREATE INDEX IF NOT EXISTS idx_schedule_conflicts_shift ON schedule_conflicts (shift_id);
CREATE INDEX IF NOT EXISTS idx_shifts_date ON shifts (date);

-- 8. New tables ------------------------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schedule_changes (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    schedule_id VARCHAR(255),
    shift_id VARCHAR(255),
    nurse_id VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    actor VARCHAR(255),
    reason VARCHAR(1000),
    details TEXT,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_schedule_changes_schedule ON schedule_changes (schedule_id, created_at);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    scope VARCHAR(64) NOT NULL,
    key_value VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128),
    resource_id VARCHAR(255),
    response_status INTEGER,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6),
    CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, key_value)
);

-- 9. Retire the legacy join table (renamed, not dropped, so a rollback stays possible) ----------------------------------------------
DO $$
BEGIN
    IF to_regclass('schedules_shifts') IS NOT NULL THEN
        ALTER TABLE schedules_shifts RENAME TO schedules_shifts_legacy;
    END IF;
END $$;
