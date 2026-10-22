-- V1: baseline of the schema as Hibernate (ddl-auto=update) produced it before Flyway.
-- Every statement is IF NOT EXISTS, so on an existing database this is a no-op, and on an empty database it creates the
-- legacy schema that V2 then evolves. Table and column names follow Spring Boot's naming strategy (snake_case);
-- element collection tables are named <entity>_<attribute> (e.g. shift_assigned_nurses).

CREATE TABLE IF NOT EXISTS nurses (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(255),
    department VARCHAR(255),
    experience_level VARCHAR(255),
    max_hours_per_week INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS nurse_specializations (
    nurse_id VARCHAR(255) NOT NULL REFERENCES nurses (id),
    specializations VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS nurse_preferred_shifts (
    nurse_id VARCHAR(255) NOT NULL REFERENCES nurses (id),
    preferred_shifts VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS nurse_unavailable_dates (
    nurse_id VARCHAR(255) NOT NULL REFERENCES nurses (id),
    unavailable_dates DATE
);

CREATE TABLE IF NOT EXISTS schedules (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    month INTEGER NOT NULL,
    year INTEGER NOT NULL,
    generated_at TIMESTAMP(6),
    status VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS shifts (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    date DATE,
    start_time VARCHAR(255),
    end_time VARCHAR(255),
    type VARCHAR(255),
    department VARCHAR(255),
    required_staff INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS shift_assigned_nurses (
    shift_id VARCHAR(255) NOT NULL REFERENCES shifts (id),
    assigned_nurses VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS shift_requirements (
    shift_id VARCHAR(255) NOT NULL REFERENCES shifts (id),
    requirements VARCHAR(255)
);

-- Join table of the old unidirectional Schedule -> Shift mapping (retired in V2).
CREATE TABLE IF NOT EXISTS schedules_shifts (
    schedule_id VARCHAR(255) NOT NULL REFERENCES schedules (id),
    shifts_id VARCHAR(255) NOT NULL UNIQUE REFERENCES shifts (id)
);

CREATE TABLE IF NOT EXISTS schedule_conflicts (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    type VARCHAR(255),
    severity VARCHAR(255),
    shift_id VARCHAR(255),
    nurse_id VARCHAR(255),
    message VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS schedule_conflict_suggestions (
    schedule_conflict_id VARCHAR(255) NOT NULL REFERENCES schedule_conflicts (id),
    suggestions VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS workload_data (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    nurse_id VARCHAR(255),
    total_hours DOUBLE PRECISION NOT NULL DEFAULT 0,
    shifts_count INTEGER NOT NULL DEFAULT 0,
    overtime_hours DOUBLE PRECISION NOT NULL DEFAULT 0,
    month INTEGER NOT NULL,
    year INTEGER NOT NULL,
    night_shifts INTEGER NOT NULL DEFAULT 0,
    weekend_shifts INTEGER NOT NULL DEFAULT 0,
    consecutive_days INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6),
    created_at TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS swap_requests (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    requester_id VARCHAR(255),
    target_id VARCHAR(255),
    shift_id VARCHAR(255),
    target_shift_id VARCHAR(255),
    reason VARCHAR(255),
    status VARCHAR(255),
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6),
    reviewed_by VARCHAR(255),
    review_notes VARCHAR(255),
    auto_matched BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS schedule_rules (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    type VARCHAR(255),
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS schedule_rule_parameters (
    schedule_rule_id VARCHAR(255) NOT NULL REFERENCES schedule_rules (id),
    parameters VARCHAR(255),
    parameters_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (schedule_rule_id, parameters_key)
);
