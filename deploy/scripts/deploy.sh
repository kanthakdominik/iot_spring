#!/usr/bin/env bash
# Update and restart the whole system with the latest code from GitHub.
#
#   ~/iot/iot_spring/deploy/scripts/deploy.sh
#
# 1. git pull in both repositories
# 2. rebuild the images that changed and restart their containers
# 3. run the smoke test
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DEPLOY_DIR/../.." && pwd)"

echo ">>> Pulling latest code"
git -C "$ROOT/iot_spring" pull --ff-only
git -C "$ROOT/iot_angular" pull --ff-only

echo ">>> Building and starting containers"
cd "$DEPLOY_DIR"
docker compose up -d --build
docker image prune -f >/dev/null

echo ">>> Smoke test"
bash ./scripts/smoke-test.sh
