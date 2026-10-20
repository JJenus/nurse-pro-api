-- V1: baseline of the schema as Hibernate (ddl-auto=update) produced it before Flyway.
-- Every statement is IF NOT EXISTS, so on an existing database this is a no-op and on an
-- empty database it creates the legacy schema that V2 then evolves.
-- Table and column names follow Spring Boot's naming strategy (snake_case); element
-- collection tables are named <entity>_<attribute> (e.g. shift_assigned_nurses).

CREATE TABLE IF NOT EXISTS nurses (
    id VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(255),
    department VARCHAR(255),
    experience_level VARCHAR(32),
    max_hours_per_week INT NOT NULL DEFAULT 0,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS nurse_specializations (
    nurse_id VARCHAR(255) NOT NULL,
    specializations VARCHAR(255),
    CONSTRAINT fk_nurse_specializations_nurse FOREIGN KEY (nurse_id) REFERENCES nurses (id)
);

CREATE TABLE IF NOT EXISTS nurse_preferred_shifts (
    nurse_id VARCHAR(255) NOT NULL,
    preferred_shifts VARCHAR(255),
    CONSTRAINT fk_nurse_preferred_shifts_nurse FOREIGN KEY (nurse_id) REFERENCES nurses (id)
);

CREATE TABLE IF NOT EXISTS nurse_unavailable_dates (
    nurse_id VARCHAR(255) NOT NULL,
    unavailable_dates DATE,
    CONSTRAINT fk_nurse_unavailable_dates_nurse FOREIGN KEY (nurse_id) REFERENCES nurses (id)
);

CREATE TABLE IF NOT EXISTS schedules (
    id VARCHAR(255) NOT NULL,
    `month` INT NOT NULL,
    `year` INT NOT NULL,
    generated_at DATETIME(6),
    status VARCHAR(32),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS shifts (
    id VARCHAR(255) NOT NULL,
    `date` DATE,
    start_time VARCHAR(255),
    end_time VARCHAR(255),
    `type` VARCHAR(32),
    department VARCHAR(255),
    required_staff INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS shift_assigned_nurses (
    shift_id VARCHAR(255) NOT NULL,
    assigned_nurses VARCHAR(255),
    CONSTRAINT fk_shift_assigned_nurses_shift FOREIGN KEY (shift_id) REFERENCES shifts (id)
);

CREATE TABLE IF NOT EXISTS shift_requirements (
    shift_id VARCHAR(255) NOT NULL,
    requirements VARCHAR(255),
    CONSTRAINT fk_shift_requirements_shift FOREIGN KEY (shift_id) REFERENCES shifts (id)
);

-- Legacy join table of the old unidirectional Schedule -> Shift mapping (replaced in V2).
CREATE TABLE IF NOT EXISTS schedules_shifts (
    schedule_id VARCHAR(255) NOT NULL,
    shifts_id VARCHAR(255) NOT NULL,
    UNIQUE KEY uk_schedules_shifts_shifts_id (shifts_id),
    CONSTRAINT fk_schedules_shifts_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id),
    CONSTRAINT fk_schedules_shifts_shift FOREIGN KEY (shifts_id) REFERENCES shifts (id)
);

CREATE TABLE IF NOT EXISTS schedule_conflicts (
    id VARCHAR(255) NOT NULL,
    `type` VARCHAR(32),
    severity VARCHAR(32),
    shift_id VARCHAR(255),
    nurse_id VARCHAR(255),
    message VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS schedule_conflict_suggestions (
    schedule_conflict_id VARCHAR(255) NOT NULL,
    suggestions VARCHAR(255),
    CONSTRAINT fk_conflict_suggestions_conflict FOREIGN KEY (schedule_conflict_id) REFERENCES schedule_conflicts (id)
);

CREATE TABLE IF NOT EXISTS workload_data (
    id VARCHAR(255) NOT NULL,
    nurse_id VARCHAR(255),
    total_hours DOUBLE NOT NULL DEFAULT 0,
    shifts_count INT NOT NULL DEFAULT 0,
    overtime_hours DOUBLE NOT NULL DEFAULT 0,
    `month` INT NOT NULL,
    `year` INT NOT NULL,
    night_shifts INT NOT NULL DEFAULT 0,
    weekend_shifts INT NOT NULL DEFAULT 0,
    consecutive_days INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6),
    created_at DATETIME(6),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS swap_requests (
    id VARCHAR(255) NOT NULL,
    requester_id VARCHAR(255),
    target_id VARCHAR(255),
    shift_id VARCHAR(255),
    target_shift_id VARCHAR(255),
    reason VARCHAR(255),
    status VARCHAR(32),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    reviewed_by VARCHAR(255),
    review_notes VARCHAR(255),
    auto_matched BIT(1) NOT NULL DEFAULT b'0',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS schedule_rules (
    id VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    `type` VARCHAR(32),
    priority INT NOT NULL DEFAULT 0,
    enabled BIT(1) NOT NULL DEFAULT b'0',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS schedule_rule_parameters (
    schedule_rule_id VARCHAR(255) NOT NULL,
    parameters VARCHAR(255),
    parameters_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (schedule_rule_id, parameters_key),
    CONSTRAINT fk_rule_parameters_rule FOREIGN KEY (schedule_rule_id) REFERENCES schedule_rules (id)
);
