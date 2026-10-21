-- V5: remember the constraints a schedule was generated with, so later checks and reschedules use the same rules.
ALTER TABLE schedules ADD COLUMN rules_json TEXT NULL;
