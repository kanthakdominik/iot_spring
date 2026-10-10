#!/usr/bin/env bash
# Quick post-deployment check. Exits with code 1 if anything is broken,
# so it can later be used as the last step of a CI/CD pipeline.
#
#   ./scripts/smoke-test.sh
set -uo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
DOMAIN="${DOMAIN:?DOMAIN is not set}"
BASE="https://$DOMAIN"
FAILED=0

check() {   # check <description> <command...>
    local desc=$1; shift
    if "$@" >/dev/null 2>&1; then
        echo "  OK    $desc"
    else
        echo "  FAIL  $desc"
        FAILED=1
    fi
}

http_ok() { [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1")" = "200" ]; }

echo "Waiting for the backend to start..."
for _ in $(seq 1 30); do
    http_ok "$BASE/api/actuator/health" && break
    sleep 5
done

echo "Smoke test for $BASE"
check "all containers running" \
    bash -c '[ -z "$(docker compose ps --status exited --status restarting -q)" ]'
check "frontend (GET /)"                 http_ok "$BASE/"
check "SPA routing (GET /routes)"        http_ok "$BASE/routes"
check "health endpoint reports UP"       bash -c "curl -s --max-time 10 '$BASE/api/actuator/health' | grep -q '\"UP\"'"
check "reading routes needs login"       bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 '$BASE/api/routes')\" = 401 ]"
check "protected endpoint needs login"   bash -c "case \"\$(curl -s -o /dev/null -w '%{http_code}' -X DELETE '$BASE/api/routes/999999')\" in 401|403) true;; *) false;; esac"
check "unknown share link gives 404"     bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 '$BASE/api/public/routes/AAAAAAAAAAAAAAAAAAAAAA')\" = 404 ]"
check "security headers (CSP)"           bash -c "curl -sI --max-time 10 '$BASE/' | grep -qi '^content-security-policy:'"
check "MQTT TLS handshake with client certificate (port 8883)" \
    bash -c "echo | openssl s_client -connect '$DOMAIN:8883' -servername '$DOMAIN' \
             -CAfile certs/ca/ca.crt -cert certs/backend/client-cert.pem -key certs/backend/client-key.pem \
             -verify_return_error 2>/dev/null | grep -q 'Verify return code: 0'"
check "backend connected to MQTT broker" \
    bash -c "docker compose logs backend | grep -q 'Subscribed to MQTT topics'"

if [ "$FAILED" = 0 ]; then
    echo "All checks passed."
else
    echo "Some checks FAILED - see: docker compose logs <service>"
    exit 1
fi
