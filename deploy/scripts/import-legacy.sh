#!/usr/bin/env bash
# One-time import of the measurements from the master's thesis (old schema, pg_dump custom format)
# into the new schema (TimescaleDB, docs/PROTOCOL.md).
#
#   ./scripts/import-legacy.sh                    # uses backups/initial.dump
#   ./scripts/import-legacy.sh backups/other.dump
#
# Requirements: the stack is running (docker compose up -d) and the backend has started at least once
# (Flyway created the new tables).
#
# Mapping:
#   old routes        -> device "esp32-legacy", device_route_no = old id, same id and name
#   old iot_data      -> measurements; old local time is interpreted as Europe/Warsaw; seq = order in time
#   old users         -> users (only usernames that do not exist yet)
set -euo pipefail

cd "$(dirname "$0")/.."
DUMP="${1:-backups/initial.dump}"
PSQL=(docker compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -q)

if [ ! -f "$DUMP" ]; then
    echo "Dump not found: $DUMP"
    exit 1
fi

if [ "$("${PSQL[@]}" -d iot_database -tAc "SELECT count(*) FROM devices WHERE id = 'esp32-legacy'")" != "0" ]; then
    echo "Legacy data already imported (device esp32-legacy exists) - nothing to do."
    exit 0
fi

echo ">>> Restoring the old database into a temporary database iot_legacy"
docker compose exec -T postgres dropdb -U postgres --if-exists iot_legacy
docker compose exec -T postgres createdb -U postgres iot_legacy
docker compose exec -T postgres pg_restore -U postgres -d iot_legacy --no-owner --no-privileges < "$DUMP" \
    || echo "    (pg_restore reported warnings - usually harmless)"

echo ">>> Copying rows into staging tables"
"${PSQL[@]}" -d iot_database <<'SQL'
DROP TABLE IF EXISTS legacy_routes, legacy_data, legacy_users;
CREATE TABLE legacy_routes (id INT, name TEXT);
CREATE TABLE legacy_data (id INT, route_id INT, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
                          ts TIMESTAMP, cps INT, cpm INT, usv DOUBLE PRECISION, mode TEXT);
CREATE TABLE legacy_users (username TEXT, password TEXT, role TEXT);
SQL

copy() {  # copy <select in iot_legacy> <staging table in iot_database>
    docker compose exec -T postgres sh -c \
        "psql -U postgres -d iot_legacy -c \"\\copy ($1) TO STDOUT\" | psql -U postgres -d iot_database -c \"\\copy $2 FROM STDIN\""
}
copy "SELECT id, name FROM routes" legacy_routes
copy "SELECT id, route_id, latitude, longitude, \\\"timestamp\\\", cps, cpm, usv_per_hr, mode FROM iot_data" legacy_data
copy "SELECT username, password, role FROM users" legacy_users

echo ">>> Converting to the new schema"
"${PSQL[@]}" -d iot_database <<'SQL'
BEGIN;

INSERT INTO devices (id, name, online)
VALUES ('esp32-legacy', 'ESP32 (master''s thesis)', false);

-- only routes that contain data; keep the old id, so old links /routes/<id>/map still work
INSERT INTO routes (id, device_id, device_route_no, name, started_at, ended_at, sample_count)
SELECT r.id, 'esp32-legacy', r.id, r.name,
       min(d.ts) AT TIME ZONE 'Europe/Warsaw',
       max(d.ts) AT TIME ZONE 'Europe/Warsaw',
       count(d.id)
FROM legacy_routes r
JOIN legacy_data d ON d.route_id = r.id
GROUP BY r.id, r.name;

INSERT INTO measurements (route_id, ts, seq, time_source, gps_fix, latitude, longitude, cps, cpm, usv_per_hr, mode)
SELECT d.route_id,
       d.ts AT TIME ZONE 'Europe/Warsaw',
       (row_number() OVER (PARTITION BY d.route_id ORDER BY d.ts, d.id) - 1)::INT,
       'net', true, d.latitude, d.longitude, d.cps, d.cpm, d.usv, d.mode
FROM legacy_data d
WHERE d.route_id IN (SELECT id FROM routes WHERE device_id = 'esp32-legacy')
ON CONFLICT DO NOTHING;

-- new routes must get ids above the imported ones
SELECT setval(pg_get_serial_sequence('routes', 'id'), (SELECT COALESCE(max(id), 1) FROM routes));

INSERT INTO users (username, password, role)
SELECT username, password, role FROM legacy_users
ON CONFLICT (username) DO NOTHING;

DROP TABLE legacy_routes, legacy_data, legacy_users;
COMMIT;
SQL

docker compose exec -T postgres dropdb -U postgres iot_legacy

"${PSQL[@]}" -d iot_database -tAc \
    "SELECT 'Imported ' || count(*) || ' routes, ' || COALESCE(sum(sample_count), 0) || ' measurements' FROM routes WHERE device_id = 'esp32-legacy'"
