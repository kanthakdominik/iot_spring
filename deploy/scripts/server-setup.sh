#!/usr/bin/env bash
# One-command installation of Radmon on a fresh Ubuntu 24.04 server (Oracle Always Free).
# Run as the normal user (ubuntu), NOT with sudo:
#
#   git clone https://github.com/kanthakdominik/iot_spring.git ~/iot/iot_spring
#   bash ~/iot/iot_spring/deploy/scripts/server-setup.sh
#
# Steps (all of README sections 5-11; safe to run again - finished steps are skipped):
#   1. system update, firewall on the machine (iptables: 80, 443, 8883)
#   2. Docker
#   3. code: ~/iot/iot_spring + ~/iot/iot_angular
#   4. deploy/.env - asks for the DuckDNS token, admin password and e-mail; generates the DB password
#   5. DuckDNS points to this server, MQTT certificates
#   6. docker compose up --build (first time 5-15 min), smoke test
#   7. import of the thesis measurements, if deploy/backups/initial.dump is already on the server
set -euo pipefail

if [ "$(id -u)" = 0 ]; then echo "Uruchom bez sudo (jako użytkownik ubuntu)."; exit 1; fi

ROOT=~/iot
DEPLOY="$ROOT/iot_spring/deploy"
GITHUB=https://github.com/kanthakdominik
say() { printf '\n\033[1m>>> %s\033[0m\n' "$*"; }

# ---------- 1. system + firewall ----------
say "1/7 Aktualizacja systemu"
sudo apt-get update -q
sudo DEBIAN_FRONTEND=noninteractive apt-get -y -q -o Dpkg::Options::=--force-confold upgrade
sudo DEBIAN_FRONTEND=noninteractive apt-get -y -q install git curl openssl iptables-persistent

say "Firewall na maszynie (Oracle blokuje domyślnie wszystko poza SSH)"
open_port() {   # open_port <tcp|udp> <port>
    if ! sudo iptables -C INPUT -p "$1" --dport "$2" -m state --state NEW -j ACCEPT 2>/dev/null; then
        pos=$(sudo iptables -L INPUT --line-numbers -n | awk '/REJECT/ {print $1; exit}')
        sudo iptables -I INPUT "${pos:-1}" -p "$1" --dport "$2" -m state --state NEW -j ACCEPT
        echo "otwarto $1/$2"
    fi
}
open_port tcp 80; open_port tcp 443; open_port udp 443; open_port tcp 8883
sudo netfilter-persistent save >/dev/null

# ---------- 2. Docker ----------
say "2/7 Docker"
if ! command -v docker >/dev/null; then
    curl -fsSL https://get.docker.com | sudo sh
fi
sudo usermod -aG docker "$USER"     # 'docker' without sudo after the next login
DOCKER="sudo docker"

# ---------- 3. code ----------
say "3/7 Kod z GitHuba"
mkdir -p "$ROOT"
for repo in iot_spring iot_angular; do
    if [ -d "$ROOT/$repo/.git" ]; then
        git -C "$ROOT/$repo" pull --ff-only -q
    else
        git clone -q "$GITHUB/$repo.git" "$ROOT/$repo"
    fi
done

# ---------- 4. .env ----------
cd "$DEPLOY"
if [ ! -f .env ]; then
    say "4/7 Konfiguracja (.env) - odpowiedz na kilka pytań"
    read -rp "Subdomena DuckDNS [iot-manager]: " SUB; SUB=${SUB:-iot-manager}
    read -rsp "Token DuckDNS (z duckdns.org, nie będzie widoczny): " TOKEN; echo
    while :; do
        read -rp "E-mail do Let's Encrypt (powiadomienia o certyfikacie HTTPS): " EMAIL
        [[ "$EMAIL" == *@* ]] && break
        echo "podaj poprawny adres e-mail"
    done
    while :; do
        read -rsp "Hasło administratora aplikacji (min. 10 znaków): " PASS1; echo
        read -rsp "Powtórz hasło: " PASS2; echo
        [ "$PASS1" = "$PASS2" ] && [ ${#PASS1} -ge 10 ] && [[ "$PASS1" != *"'"* ]] && break
        echo "hasła różne, za krótkie albo zawierają apostrof (') - jeszcze raz"
    done
    read -rp "Adres ntfy do powiadomień push (Enter = pomiń, można dodać później): " NTFY
    DBPASS=$(openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32)

    umask 077
    # values in single quotes: no $-interpolation by docker compose, no word splitting in bash
    cat > .env <<ENV
DOMAIN='$SUB.duckdns.org'
ACME_EMAIL='$EMAIL'
DUCKDNS_SUBDOMAIN='$SUB'
DUCKDNS_TOKEN='$TOKEN'
POSTGRES_PASSWORD='$DBPASS'
ADMIN_USERNAME='admin'
ADMIN_PASSWORD='$PASS1'
NTFY_URL='$NTFY'
NTFY_TOKEN=''
ENV
    umask 022
    echo "zapisano $DEPLOY/.env (tylko do odczytu dla Ciebie)"
else
    say "4/7 .env już istnieje - zostawiam bez zmian"
fi
set -a; . ./.env; set +a

# ---------- 5. DuckDNS + certificates ----------
say "5/7 DuckDNS -> ten serwer, certyfikaty MQTT"
RESULT=$(curl -fsS "https://www.duckdns.org/update?domains=$DUCKDNS_SUBDOMAIN&token=$DUCKDNS_TOKEN&ip=" || echo ERR)
if [ "$RESULT" != "OK" ]; then
    echo "DuckDNS odpowiedział '$RESULT' - sprawdź token i subdomenę w $DEPLOY/.env, potem uruchom skrypt ponownie."
    exit 1
fi
echo "$DOMAIN wskazuje teraz na $(curl -fsS https://api.ipify.org || echo 'ten serwer')"
chmod +x scripts/*.sh
./scripts/gen-certs.sh >/dev/null
echo "certyfikaty: $DEPLOY/certs (certyfikat urządzenia: certs/esp32-1/secrets_certs.h)"

# ---------- 6. start ----------
say "6/7 Budowanie i start (pierwszy raz 5-15 minut)"
$DOCKER compose up -d --build
$DOCKER image prune -f >/dev/null

echo "czekam, aż backend wystartuje i Caddy pobierze certyfikat HTTPS..."
for _ in $(seq 1 60); do
    curl -fsk "https://$DOMAIN/api/actuator/health" 2>/dev/null | grep -q UP && break
    sleep 5
done
sudo bash ./scripts/smoke-test.sh || echo "(część testów nie przeszła - logi: sudo docker compose logs backend / caddy)"

# ---------- 7. thesis data ----------
if [ -f backups/initial.dump ]; then
    say "7/7 Import pomiarów z pracy magisterskiej"
    sudo bash ./scripts/import-legacy.sh
else
    say "7/7 Pomiary z pracy: brak pliku backups/initial.dump - pomijam (instrukcja w README, krok 9)"
fi

say "GOTOWE: https://$DOMAIN   (login: admin + hasło podane wyżej)"
echo "Po ponownym zalogowaniu przez SSH komendy 'docker' działają bez sudo."
