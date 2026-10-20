-- V2: schedule integrity (Phase 1 of docs/SCHEDULE_OVERHAUL_PLAN.md)
--   * removes duplicate schedules / shifts / workload rows (keeps the newest schedule per month,
--     the lowest-id shift per slot, the most recently updated workload row)
--   * Shift gets a real schedule_id foreign key (replaces the schedules_shifts join table)
--   * optimistic-locking version columns
--   * unique constraints: schedules(year, month), shifts(schedule_id, date, type, department),
--     workload_data(nurse_id, month, year), shift_assigned_nurses(shift_id, assigned_nurses)
--   * schedule_conflicts becomes queryable per schedule
--   * new tables: schedule_changes (audit log), idempotency_keys
-- BACK UP THE DATABASE BEFORE RUNNING: MySQL DDL is not transactional, so a failure part-way
-- leaves the schema partially migrated.

DROP TABLE IF EXISTS tmp_mig_doomed_schedules;
DROP TABLE IF EXISTS tmp_mig_doomed_shifts;
DROP TABLE IF EXISTS tmp_mig_assigned;

-- 1. Duplicate schedules per (year, month): keep the most recently generated one ------------
CREATE TABLE tmp_mig_doomed_schedules (id VARCHAR(255) NOT NULL PRIMARY KEY);

INSERT INTO tmp_mig_doomed_schedules (id)
SELECT s1.id
FROM schedules s1
WHERE EXISTS (
    SELECT 1 FROM schedules s2
    WHERE s2.`year` = s1.`year` AND s2.`month` = s1.`month` AND s2.id <> s1.id
      AND (COALESCE(s2.generated_at, '1970-01-01') > COALESCE(s1.generated_at, '1970-01-01')
           OR (COALESCE(s2.generated_at, '1970-01-01') = COALESCE(s1.generated_at, '1970-01-01')
               AND s2.id > s1.id))
);

CREATE TABLE tmp_mig_doomed_shifts (id VARCHAR(255) NOT NULL PRIMARY KEY);

INSERT INTO tmp_mig_doomed_shifts (id)
SELECT ss.shifts_id
FROM schedules_shifts ss
JOIN tmp_mig_doomed_schedules d ON d.id = ss.schedule_id;

DELETE cs FROM schedule_conflict_suggestions cs
JOIN schedule_conflicts c ON c.id = cs.schedule_conflict_id
JOIN tmp_mig_doomed_shifts t ON t.id = c.shift_id;
DELETE c FROM schedule_conflicts c JOIN tmp_mig_doomed_shifts t ON t.id = c.shift_id;
DELETE a FROM shift_assigned_nurses a JOIN tmp_mig_doomed_shifts t ON t.id = a.shift_id;
DELETE r FROM shift_requirements r JOIN tmp_mig_doomed_shifts t ON t.id = r.shift_id;
DELETE ss FROM schedules_shifts ss JOIN tmp_mig_doomed_shifts t ON t.id = ss.shifts_id;
DELETE sh FROM shifts sh JOIN tmp_mig_doomed_shifts t ON t.id = sh.id;
DELETE s FROM schedules s JOIN tmp_mig_doomed_schedules d ON d.id = s.id;

-- 2. shifts.schedule_id + version, backfilled from the legacy join table ------------------------
ALTER TABLE shifts
    ADD COLUMN schedule_id VARCHAR(255) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE shifts sh
JOIN schedules_shifts ss ON ss.shifts_id = sh.id
SET sh.schedule_id = ss.schedule_id;

-- Shifts created through POST /shifts were never attached to a schedule: attach them to the
-- schedule of their month. Any left over (no schedule for that month) keep schedule_id NULL.
UPDATE shifts sh
JOIN schedules s ON s.`year` = YEAR(sh.`date`) AND s.`month` = MONTH(sh.`date`)
SET sh.schedule_id = s.id
WHERE sh.schedule_id IS NULL;

-- 3. Duplicate shifts inside one schedule: keep the lowest id per slot ---------------------------
TRUNCATE TABLE tmp_mig_doomed_shifts;

INSERT INTO tmp_mig_doomed_shifts (id)
SELECT a.id
FROM shifts a
WHERE a.schedule_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM shifts b
      WHERE b.schedule_id = a.schedule_id AND b.`date` = a.`date` AND b.`type` = a.`type`
        AND b.department <=> a.department AND b.id < a.id
  );

DELETE cs FROM schedule_conflict_suggestions cs
JOIN schedule_conflicts c ON c.id = cs.schedule_conflict_id
JOIN tmp_mig_doomed_shifts t ON t.id = c.shift_id;
DELETE c FROM schedule_conflicts c JOIN tmp_mig_doomed_shifts t ON t.id = c.shift_id;
DELETE a FROM shift_assigned_nurses a JOIN tmp_mig_doomed_shifts t ON t.id = a.shift_id;
DELETE r FROM shift_requirements r JOIN tmp_mig_doomed_shifts t ON t.id = r.shift_id;
DELETE ss FROM schedules_shifts ss JOIN tmp_mig_doomed_shifts t ON t.id = ss.shifts_id;
DELETE sh FROM shifts sh JOIN tmp_mig_doomed_shifts t ON t.id = sh.id;

-- 4. A nurse can only appear once per shift ----------------------------------------------------
CREATE TABLE tmp_mig_assigned (
    shift_id VARCHAR(255) NOT NULL,
    assigned_nurses VARCHAR(255) NOT NULL
);
INSERT INTO tmp_mig_assigned (shift_id, assigned_nurses)
SELECT DISTINCT shift_id, assigned_nurses
FROM shift_assigned_nurses
WHERE shift_id IS NOT NULL AND assigned_nurses IS NOT NULL;
DELETE FROM shift_assigned_nurses WHERE 1 = 1;
INSERT INTO shift_assigned_nurses (shift_id, assigned_nurses)
SELECT shift_id, assigned_nurses FROM tmp_mig_assigned;
ALTER TABLE shift_assigned_nurses ADD PRIMARY KEY (shift_id, assigned_nurses);

-- 5. Workload rows: one per (nurse, month, year), keep the most recently updated ------------------
DELETE w1 FROM workload_data w1
JOIN workload_data w2
  ON w1.nurse_id = w2.nurse_id AND w1.`month` = w2.`month` AND w1.`year` = w2.`year`
 AND w1.id <> w2.id
 AND (COALESCE(w2.updated_at, '1970-01-01') > COALESCE(w1.updated_at, '1970-01-01')
      OR (COALESCE(w2.updated_at, '1970-01-01') = COALESCE(w1.updated_at, '1970-01-01')
          AND w2.id > w1.id));

-- 6. Conflicts become queryable per schedule ----------------------------------------------------
ALTER TABLE schedule_conflicts
    ADD COLUMN schedule_id VARCHAR(255) NULL,
    ADD COLUMN `date` DATE NULL,
    ADD COLUMN resolved BIT(1) NOT NULL DEFAULT b'0';

UPDATE schedule_conflicts c
JOIN shifts sh ON sh.id = c.shift_id
SET c.schedule_id = sh.schedule_id, c.`date` = sh.`date`;

-- 7. Constraints and indexes ------------------------------------------------------------------------
ALTER TABLE schedules
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_schedules_year_month UNIQUE (`year`, `month`);

ALTER TABLE shifts
    ADD CONSTRAINT fk_shifts_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id),
    ADD CONSTRAINT uk_shift_slot UNIQUE (schedule_id, `date`, `type`, department);

ALTER TABLE workload_data
    ADD CONSTRAINT uk_workload_nurse_period UNIQUE (nurse_id, `month`, `year`);

CREATE INDEX idx_schedule_conflicts_schedule ON schedule_conflicts (schedule_id);
CREATE INDEX idx_schedule_conflicts_shift ON schedule_conflicts (shift_id);
CREATE INDEX idx_shifts_date ON shifts (`date`);

-- 8. New tables ------------------------------------------------------------------------------------------
CREATE TABLE schedule_changes (
    id VARCHAR(255) NOT NULL,
    schedule_id VARCHAR(255),
    shift_id VARCHAR(255),
    nurse_id VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    actor VARCHAR(255),
    reason VARCHAR(1000),
    details TEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_schedule_changes_schedule (schedule_id, created_at)
);

CREATE TABLE idempotency_keys (
    id VARCHAR(255) NOT NULL,
    scope VARCHAR(64) NOT NULL,
    key_value VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128),
    resource_id VARCHAR(255),
    response_status INT,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, key_value)
);

-- 9. Retire the legacy join table (renamed, not dropped, so a rollback stays possible) --------------
RENAME TABLE schedules_shifts TO schedules_shifts_legacy;

DROP TABLE tmp_mig_assigned;
DROP TABLE tmp_mig_doomed_shifts;
DROP TABLE tmp_mig_doomed_schedules;
