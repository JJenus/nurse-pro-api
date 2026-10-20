-- V3: store enum-backed columns as VARCHAR.
-- Hibernate 6 created these as native MySQL ENUM columns on existing databases, so adding a new
-- enum constant (e.g. ScheduleConflictType.overlap, SwapRequestStatus.Cancelled) would fail on insert
-- ("Data truncated"). VARCHAR keeps every existing value and lets the Java enums evolve.
ALTER TABLE schedules MODIFY `status` VARCHAR(32) NULL;
ALTER TABLE shifts MODIFY `type` VARCHAR(32) NULL;
ALTER TABLE schedule_conflicts MODIFY `type` VARCHAR(32) NULL, MODIFY severity VARCHAR(32) NULL;
ALTER TABLE swap_requests MODIFY `status` VARCHAR(32) NULL;
ALTER TABLE schedule_rules MODIFY `type` VARCHAR(32) NULL;
ALTER TABLE nurses MODIFY experience_level VARCHAR(32) NULL;
