#!/bin/bash
# Applies the Flyway migrations (V1..V5) to a scratch PostgreSQL database, in order, with deliberately dirty legacy data
# between V1 and V2, and checks the result. Use it to rehearse the migration before running it on real data.
#
#   PSQL_ARGS="-h localhost -U postgres" PGPASSWORD=postgres scripts/verify-migrations.sh
#
# Needs the psql client and a PostgreSQL server. It drops and recreates the database named by DB
# (default nursepro_migration_check): never point it at a real database.
set -uo pipefail

MIGRATIONS="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/db/migration"
DB="${DB:-nursepro_migration_check}"
PSQL="psql -X -q -v ON_ERROR_STOP=1 ${PSQL_ARGS:-}"
FAILED=0

check() {  # check <description> <expected> <sql>
  local actual
  actual=$($PSQL -d "$DB" -At -c "$3")
  if [ "$actual" == "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (expected '$2', got '$actual')"; FAILED=1; fi
}
rejects() {  # rejects <description> <sql>: the statement must fail
  if $PSQL -d "$DB" -c "$2" >/dev/null 2>&1; then echo "  FAIL $1 (was accepted)"; FAILED=1; else echo "  ok   $1"; fi
}
migrate() {  # like Flyway on PostgreSQL: one transaction per migration
  $PSQL -d "$DB" --single-transaction -f "$MIGRATIONS/$1.sql" && echo "$1 applied" || { echo "$1 FAILED"; exit 1; }
}

$PSQL -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
migrate V1__baseline_schema

echo "seeding legacy data (with the CHECK constraints Hibernate 6 puts on enum columns)"
$PSQL -d "$DB" <<'SQL'
ALTER TABLE schedules ADD CONSTRAINT schedules_status_check CHECK (status IN ('Draft','Published','Archived'));
ALTER TABLE shifts ADD CONSTRAINT shifts_type_check CHECK (type IN ('Day','Evening','Night'));
ALTER TABLE schedule_conflicts ADD CONSTRAINT schedule_conflicts_type_check CHECK (type IN ('understaffed','overstaffed','qualification','availability','overtime'));
ALTER TABLE schedule_conflicts ADD CONSTRAINT schedule_conflicts_severity_check CHECK (severity IN ('low','medium','high','critical'));
ALTER TABLE swap_requests ADD CONSTRAINT swap_requests_status_check CHECK (status IN ('Pending','Approved','Rejected'));

INSERT INTO nurses (id, first_name, last_name, max_hours_per_week) VALUES ('n1','A','One',40),('n2','B','Two',40),('n3','C','Three',40);
INSERT INTO schedules (id, month, year, generated_at, status) VALUES
 ('sA', 11, 2026, '2026-10-01 10:00:00', 'Draft'), ('sB', 11, 2026, '2026-10-05 10:00:00', 'Draft'), ('sC', 12, 2026, '2026-10-06 10:00:00', 'Published');
INSERT INTO shifts (id, date, start_time, end_time, type, department, required_staff) VALUES
 ('sh1','2026-11-02','07:00','15:00','Day','General',2), ('sh2','2026-11-02','23:00','07:00','Night','General',2),
 ('sh3','2026-11-02','07:00','15:00','Day','General',2), ('sh4','2026-11-02','07:00','15:00','Day','General',2),
 ('sh5','2026-11-03','07:00','15:00','Day','General',2), ('sh6','2026-12-01','07:00','15:00','Day','General',2),
 ('sh7','2026-11-10','07:00','15:00','Day','General',2), ('sh8','2027-01-05','07:00','15:00','Day','General',2);
INSERT INTO schedules_shifts (schedule_id, shifts_id) VALUES ('sA','sh1'),('sA','sh2'),('sB','sh3'),('sB','sh4'),('sB','sh5'),('sC','sh6');
INSERT INTO shift_assigned_nurses (shift_id, assigned_nurses) VALUES ('sh1','n1'),('sh3','n1'),('sh3','n1'),('sh3','n2'),('sh4','n3'),('sh6','n2');
INSERT INTO schedule_conflicts (id, type, severity, shift_id, message) VALUES ('c1','understaffed','high','sh1','doomed'),('c2','understaffed','high','sh3','kept');
INSERT INTO schedule_conflict_suggestions (schedule_conflict_id, suggestions) VALUES ('c1','x'),('c2','y');
INSERT INTO workload_data (id, nurse_id, total_hours, shifts_count, overtime_hours, month, year, updated_at) VALUES
 ('w1','n1',8,1,0,11,2026,'2026-10-01 10:00:00'),('w2','n1',16,2,0,11,2026,'2026-10-05 10:00:00');
INSERT INTO swap_requests (id, requester_id, target_id, shift_id, status, created_at, auto_matched) VALUES
 ('r1','n1','n2','sh3','Pending','2026-10-10 10:00:00',false), ('r2','n1','n3','sh3','Pending','2026-10-11 10:00:00',false), ('r3','n1','n2','sh5','Approved','2026-10-12 10:00:00',false);
SQL

for v in V2__schedule_integrity V3__enums_to_varchar V4__swap_integrity V5__schedule_rules; do migrate "$v"; done

echo "Checking the result"
check "older duplicate schedule removed, newest kept"          "sB,sC"        "SELECT string_agg(id, ',' ORDER BY id) FROM schedules"
check "duplicate slot and doomed shifts removed"               "sh3,sh5,sh6,sh7,sh8" "SELECT string_agg(id, ',' ORDER BY id) FROM shifts"
check "shifts attached to their schedule (orphan by month)"    "sB,sB,sC,sB,NULL"    "SELECT string_agg(COALESCE(schedule_id,'NULL'), ',' ORDER BY id) FROM shifts"
check "duplicate nurse rows removed from a shift"              "n1,n2"        "SELECT string_agg(assigned_nurses, ',' ORDER BY assigned_nurses) FROM shift_assigned_nurses WHERE shift_id='sh3'"
check "conflict of a removed shift deleted, other one linked"  "c2:sB"        "SELECT string_agg(id || ':' || schedule_id, ',') FROM schedule_conflicts"
check "only the newest workload row kept"                      "w2"           "SELECT string_agg(id, ',') FROM workload_data"
check "older duplicate pending swap cancelled"                 "r1:Cancelled,r2:Pending,r3:Approved" "SELECT string_agg(id || ':' || status, ',' ORDER BY id) FROM swap_requests"
check "enum CHECK constraints dropped"                         "0"            "SELECT count(*) FROM pg_constraint WHERE contype='c' AND conrelid IN ('shifts'::regclass,'schedules'::regclass,'schedule_conflicts'::regclass,'swap_requests'::regclass)"
check "legacy join table kept for rollback"                    "schedules_shifts_legacy" "SELECT string_agg(table_name, ',') FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'schedules_shifts%'"

rejects "second schedule for the same month"   "INSERT INTO schedules (id, month, year, status, version) VALUES ('dup', 11, 2026, 'Draft', 0)"
rejects "duplicate shift slot"                 "INSERT INTO shifts (id, schedule_id, date, type, department, required_staff, version) VALUES ('d','sB','2026-11-02','Day','General',2,0)"
rejects "nurse twice on one shift"             "INSERT INTO shift_assigned_nurses VALUES ('sh3','n1')"
rejects "second pending swap for the same shift and requester" "INSERT INTO swap_requests (id, requester_id, target_id, shift_id, status, auto_matched, version) VALUES ('x','n1','n3','sh3','Pending',false,0)"
rejects "second workload row for a nurse and month" "INSERT INTO workload_data (id, nurse_id, total_hours, shifts_count, overtime_hours, month, year) VALUES ('x','n1',1,1,0,11,2026)"
rejects "reused idempotency key" "INSERT INTO idempotency_keys (id, scope, key_value, created_at) VALUES ('a','s','k',now()), ('b','s','k',now())"
if $PSQL -d "$DB" -c "INSERT INTO schedule_conflicts (id, type, severity, schedule_id, message, resolved) VALUES ('n1','rest','high','sB','m',false),('n2','consecutive','high','sB','m',false),('n3','overlap','high','sB','m',false); UPDATE swap_requests SET status='Cancelled' WHERE id='r2'; INSERT INTO swap_requests (id, requester_id, target_id, shift_id, status, auto_matched, version) VALUES ('ok','n1','n3','sh3','Pending',false,0);" >/dev/null 2>&1; then
  echo "  ok   new conflict types and a new pending swap after cancelling are accepted"; else echo "  FAIL new conflict types / pending swap after cancel"; FAILED=1; fi

echo "Re-running the later migrations is harmless (V2 is not re-runnable: it consumes the legacy join table)"
for v in V3__enums_to_varchar V4__swap_integrity V5__schedule_rules; do
  $PSQL -d "$DB" --single-transaction -f "$MIGRATIONS/$v.sql" >/dev/null 2>&1 && echo "  ok   $v re-run" || { echo "  FAIL $v re-run"; FAILED=1; }
done

echo "Fresh (empty) database"
$PSQL -d postgres -c "DROP DATABASE $DB" -c "CREATE DATABASE $DB"
for v in V1__baseline_schema V2__schedule_integrity V3__enums_to_varchar V4__swap_integrity V5__schedule_rules; do migrate "$v"; done
check "all tables created" "17" "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"

$PSQL -d postgres -c "DROP DATABASE $DB"
if [ "$FAILED" -ne 0 ]; then echo "MIGRATION CHECK FAILED"; exit 1; fi
echo "Migration check passed"
