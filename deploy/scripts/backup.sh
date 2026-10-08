#!/usr/bin/env bash
# Daily database backup (custom pg_dump format - restore with pg_restore).
# Installed into cron by the README:  0 3 * * * ~/iot/iot_spring/deploy/scripts/backup.sh
# Keeps the last 14 days in deploy/backups/.
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p backups
FILE="backups/iot_$(date +%F_%H%M).dump"

docker compose exec -T postgres pg_dump -U postgres -Fc iot_database > "$FILE"
echo "Backup written: $FILE ($(du -h "$FILE" | cut -f1))"

find backups -name 'iot_*.dump' -mtime +14 -delete
