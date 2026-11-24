#!/usr/bin/env bash
# Run in Oracle Cloud Shell: does Oracle see the server as active (protection against idle reclamation)?
#
#   bash iot_spring/deploy/scripts/oci-check-vm.sh
#
# Oracle may reclaim an Always Free VM when for 7 days CPU (95th percentile), network AND memory are all
# below 20 %. Memory is measured by the "Compute Instance Monitoring" plugin of Oracle Cloud Agent - it must
# be RUNNING, and MemoryUtilization should stay above 20 % (the stack keeps ~30-40 %).
set -euo pipefail

C="${OCI_TENANCY:?run this script in Oracle Cloud Shell}"
NAME="${NAME:-iot-server}"

INST=$(oci compute instance list --compartment-id "$C" --display-name "$NAME" --lifecycle-state RUNNING \
         --query 'data[0].id' --raw-output 2>/dev/null || true)
if [ -z "$INST" ] || [ "$INST" = "null" ]; then echo "Nie znaleziono działającej maszyny $NAME"; exit 1; fi

echo ">>> Oracle Cloud Agent - wtyczki (szukaj: Compute Instance Monitoring = RUNNING)"
oci instance-agent plugin list --compartment-id "$C" --instanceagent-id "$INST" \
    --query 'data[].{plugin:name,status:status}' --output table

START=$(date -u -d '-6 hours' +%Y-%m-%dT%H:%M:%SZ)
for METRIC in MemoryUtilization CpuUtilization; do
    echo ">>> $METRIC (%), średnie godzinowe z ostatnich 6 godzin"
    oci monitoring metric-data summarize-metrics-data --compartment-id "$C" --namespace oci_computeagent \
        --start-time "$START" --query-text "$METRIC[1h]{resourceId = \"$INST\"}.mean()" \
        --query 'data[0]."aggregated-datapoints"[].{czas:timestamp,procent:value}' --output table \
        || echo "(brak danych - agent zbiera je dopiero kilkanaście minut po starcie)"
done
