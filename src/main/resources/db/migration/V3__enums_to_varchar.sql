-- V3: let the Java enums evolve.
-- Hibernate 6 created enum-backed columns on PostgreSQL as VARCHAR with a CHECK constraint listing the values known at
-- the time, so a new constant (ScheduleConflictType.overlap / rest / consecutive, SwapRequestStatus.Cancelled) would be
-- rejected on insert. The only CHECK constraints on these tables are those enum checks, so they are all dropped.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname, c.conrelid::regclass AS tbl
        FROM pg_constraint c
        WHERE c.contype = 'c'
          AND c.conrelid IN ('schedules'::regclass, 'shifts'::regclass, 'schedule_conflicts'::regclass,
                             'swap_requests'::regclass, 'schedule_rules'::regclass, 'nurses'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;
END $$;
