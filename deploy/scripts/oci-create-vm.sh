#!/usr/bin/env bash
# Creates the Always Free server for Radmon - run it in Oracle CLOUD SHELL (the ">_" icon in the
# Oracle Cloud console, top right). The OCI CLI there is already logged in to your account.
#
#   git clone https://github.com/kanthakdominik/iot_spring.git
#   bash iot_spring/deploy/scripts/oci-create-vm.sh
#
# What it does (safe to run again - existing pieces are reused):
#   1. network: VCN "iot-vcn" + internet gateway + public subnet
#   2. firewall in Oracle (security list): ports 22, 80, 443 (TCP+UDP), 8883 (MQTT)
#   3. VM "iot-server": VM.Standard.A1.Flex (ARM), 2 OCPU, 12 GB RAM, Ubuntu 24.04, 100 GB disk
#      - tries every availability domain when Oracle reports "Out of host capacity"
#   4. prints the public IP and the ssh command
set -euo pipefail

C="${OCI_TENANCY:?OCI_TENANCY is not set - run this script in Oracle Cloud Shell}"   # root compartment
NAME="${NAME:-iot-server}"
OCPUS="${OCPUS:-2}"
MEMORY_GB="${MEMORY_GB:-12}"
BOOT_GB="${BOOT_GB:-100}"
CS_KEY=~/.ssh/iot_server        # key of this Cloud Shell (RSA - Cloud Shell runs in FIPS mode, no ed25519)

say() { printf '\n\033[1m>>> %s\033[0m\n' "$*"; }
# prints the value or nothing (the OCI CLI complains on empty results)
q() { oci "$@" 2>/dev/null | sed '/^null$/d' || true; }

say "Region: ${OCI_REGION:-?} (Always Free ARM works only in your HOME region)"

# ---------- SSH keys ----------
mkdir -p ~/.ssh && chmod 700 ~/.ssh
[ -f "$CS_KEY" ] || ssh-keygen -q -t rsa -b 4096 -N "" -C "oracle-cloud-shell" -f "$CS_KEY"
KEYS=$(mktemp)
cat "$CS_KEY.pub" > "$KEYS"
echo
echo "Wklej KLUCZ PUBLICZNY ze swojego komputera (jedna linia 'ssh-ed25519 AAAA...'),"
echo "albo naciśnij Enter, jeśli chcesz łączyć się z serwerem tylko z Cloud Shell:"
read -r PC_KEY || true
if [ -n "${PC_KEY// /}" ]; then
    case "$PC_KEY" in
        ssh-ed25519\ *|ssh-rsa\ *|ecdsa-*) echo "$PC_KEY" >> "$KEYS"; echo "OK - klucz z komputera dodany." ;;
        *) echo "To nie wygląda na klucz publiczny (powinien zaczynać się od ssh-ed25519) - przerywam."; exit 1 ;;
    esac
fi

# ---------- network ----------
say "Sieć (VCN, internet gateway, podsieć)"
VCN=$(q network vcn list --compartment-id "$C" --display-name iot-vcn --lifecycle-state AVAILABLE \
        --query 'data[0].id' --raw-output)
if [ -z "$VCN" ]; then
    VCN=$(oci network vcn create --compartment-id "$C" --display-name iot-vcn --dns-label iotvcn \
            --cidr-blocks '["10.0.0.0/16"]' --wait-for-state AVAILABLE --query 'data.id' --raw-output)
    echo "utworzono VCN"
else
    echo "VCN iot-vcn już istnieje - używam"
fi

IGW=$(q network internet-gateway list --compartment-id "$C" --vcn-id "$VCN" --query 'data[0].id' --raw-output)
if [ -z "$IGW" ]; then
    IGW=$(oci network internet-gateway create --compartment-id "$C" --vcn-id "$VCN" --is-enabled true \
            --display-name iot-igw --wait-for-state AVAILABLE --query 'data.id' --raw-output)
fi

RT=$(oci network vcn get --vcn-id "$VCN" --query 'data."default-route-table-id"' --raw-output)
oci network route-table update --rt-id "$RT" --force --wait-for-state AVAILABLE \
    --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$IGW\"}]" \
    >/dev/null

say "Firewall Oracle (security list): 22, 80, 443, 8883"
SL=$(oci network vcn get --vcn-id "$VCN" --query 'data."default-security-list-id"' --raw-output)
tcp() { echo "{\"protocol\":\"6\",\"source\":\"0.0.0.0/0\",\"tcpOptions\":{\"destinationPortRange\":{\"min\":$1,\"max\":$1}}}"; }
INGRESS="[$(tcp 22),$(tcp 80),$(tcp 443),$(tcp 8883),
 {\"protocol\":\"17\",\"source\":\"0.0.0.0/0\",\"udpOptions\":{\"destinationPortRange\":{\"min\":443,\"max\":443}}},
 {\"protocol\":\"1\",\"source\":\"0.0.0.0/0\",\"icmpOptions\":{\"type\":3,\"code\":4}},
 {\"protocol\":\"1\",\"source\":\"10.0.0.0/16\",\"icmpOptions\":{\"type\":3}}]"
oci network security-list update --security-list-id "$SL" --force --wait-for-state AVAILABLE \
    --ingress-security-rules "$INGRESS" >/dev/null

SUBNET=$(q network subnet list --compartment-id "$C" --vcn-id "$VCN" --display-name iot-subnet \
           --query 'data[0].id' --raw-output)
if [ -z "$SUBNET" ]; then
    SUBNET=$(oci network subnet create --compartment-id "$C" --vcn-id "$VCN" --display-name iot-subnet \
               --dns-label iotsub --cidr-block 10.0.0.0/24 --wait-for-state AVAILABLE --query 'data.id' --raw-output)
fi

# ---------- VM ----------
INST=$(q compute instance list --compartment-id "$C" --display-name "$NAME" --lifecycle-state RUNNING \
         --query 'data[0].id' --raw-output)
if [ -n "$INST" ]; then
    say "Maszyna $NAME już działa - nie tworzę nowej"
else
    say "Obraz Ubuntu 24.04 (ARM)"
    IMAGE=$(oci compute image list --compartment-id "$C" --operating-system "Canonical Ubuntu" \
              --operating-system-version "24.04" --shape VM.Standard.A1.Flex \
              --sort-by TIMECREATED --sort-order DESC --query 'data[0].id' --raw-output)
    echo "$IMAGE"

    say "Tworzę maszynę $NAME ($OCPUS OCPU, $MEMORY_GB GB RAM, dysk $BOOT_GB GB) - 1-3 minuty"
    ERR=$(mktemp)
    for AD in $(oci iam availability-domain list --compartment-id "$C" --query 'data[].name' --raw-output | jq -r '.[]'); do
        echo "próbuję: $AD"
        if INST=$(oci compute instance launch --compartment-id "$C" --availability-domain "$AD" \
                    --display-name "$NAME" --shape VM.Standard.A1.Flex \
                    --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY_GB}" \
                    --image-id "$IMAGE" --boot-volume-size-in-gbs "$BOOT_GB" \
                    --subnet-id "$SUBNET" --assign-public-ip true \
                    --ssh-authorized-keys-file "$KEYS" \
                    --wait-for-state RUNNING --query 'data.id' --raw-output 2>"$ERR"); then
            break
        fi
        INST=""
        if grep -qi "capacity" "$ERR"; then
            echo "   brak wolnych maszyn ARM w $AD"
        else
            cat "$ERR"; exit 1
        fi
    done
    if [ -z "$INST" ]; then
        echo
        echo "Oracle nie ma teraz wolnych maszyn ARM w żadnej strefie (Out of host capacity)."
        echo "To częste na kontach Always Free - uruchom skrypt ponownie za kilka godzin"
        echo "(sieć jest już gotowa, skrypt jej nie zdubluje)."
        exit 1
    fi
fi

IP=$(oci compute instance list-vnics --instance-id "$INST" --query 'data[0]."public-ip"' --raw-output)

say "GOTOWE"
cat <<INFO
Publiczny adres IP serwera:   $IP

Logowanie z Twojego komputera (Git Bash):
    ssh -i ~/.ssh/oracle_iot ubuntu@$IP

Logowanie z Cloud Shell:
    ssh -i $CS_KEY ubuntu@$IP

Maszyna potrzebuje ~1 minuty po starcie, zanim przyjmie połączenie SSH.
INFO
