# Wdrożenie systemu na Oracle Cloud (Always Free)

Instrukcja krok po kroku: od pustego konta Oracle do działającego systemu pod
`https://iot-manager.duckdns.org`, z Twoimi pomiarami z pracy magisterskiej i urządzeniem ESP32
wysyłającym dane przez MQTT.

Szacowany czas: **1–2 godziny** za pierwszym razem.

---

## 0. Co powstanie

```
                        Internet
                           │
          ┌────────────────┼──────────────────────────┐
          │  443 / 80      │ 8883 (MQTT + TLS)        │   maszyna Oracle A1
          ▼                ▼                          │   (Ubuntu 24.04, Docker)
     ┌─────────┐      ┌───────────┐                   │
     │  caddy  │      │ mosquitto │◄── ESP32 (LTE)    │
     │ frontend│      └─────┬─────┘                   │
     │ + HTTPS │            │ radmon/v1/+/telemetry   │
     └────┬────┘            ▼                         │
          │ /api/*    ┌───────────┐     ┌──────────┐  │
          └──────────►│  backend  │────►│ postgres │  │
                      │ (Spring)  │     │ 18 + TSDB│  │
                      └───────────┘     └──────────┘  │
          + duckdns (pilnuje, by domena wskazywała na tę maszynę)
          └───────────────────────────────────────────┘
```

| Kontener    | Rola                                                                       | Dostęp z zewnątrz |
|-------------|----------------------------------------------------------------------------|-------------------|
| `caddy`     | serwuje Angulara, przekazuje `/api/*` do backendu, sam robi HTTPS (Let's Encrypt) | 80, 443 |
| `backend`   | Spring Boot                                                                | brak (tylko przez caddy) |
| `postgres`  | PostgreSQL 18 + TimescaleDB (zamiast AWS RDS); schemat tworzy backend (Flyway) | brak |
| `mosquitto` | broker MQTT, wymaga certyfikatu klienta                                    | 8883 |
| `duckdns`   | co 5 min aktualizuje IP domeny                                             | brak |

Pliki w tym folderze:

| Plik | Do czego |
|------|----------|
| `docker-compose.yml` | definicja wszystkich kontenerów |
| `.env.example` | wzór konfiguracji (hasła, domena) → kopiujesz do `.env` |
| `Caddyfile` | konfiguracja serwera WWW / HTTPS |
| `mosquitto/mosquitto.conf`, `mosquitto/acl` | konfiguracja brokera MQTT i uprawnienia do topiców |
| `scripts/import-legacy.sh` | jednorazowy import pomiarów z pracy magisterskiej |
| `scripts/gen-certs.sh` | generuje CA i certyfikaty MQTT (broker, backend, ESP32) |
| `scripts/deploy.sh` | aktualizacja: `git pull` + przebudowa + test |
| `scripts/smoke-test.sh` | szybki test, czy wszystko działa |
| `scripts/backup.sh` | kopia zapasowa bazy (codziennie z crona) |

---

## 1. Przygotowanie kodu (na Twoim komputerze)

Serwer pobiera kod z GitHuba, więc najpierw wypchnij zmiany we **wszystkich trzech** repozytoriach.
Opis zmian wersji 2.0: `iot_spring/docs/ROADMAP.md`, kontrakty: `iot_spring/docs/PROTOCOL.md`.

**1.1 Pliki CI (jednorazowo, ręcznie).** Workflowy GitHub Actions leżą w `deploy/ci/` – skopiuj je
na właściwe miejsca (Git Bash):

```bash
# w iot_spring jest pusty plik .github/workflows/test.yml - usuń go
rm -f /d/projects/iot_spring/.github/workflows/test.yml
mkdir -p /d/projects/iot_spring/.github/workflows /d/projects/iot_angular/.github/workflows
cp /d/projects/iot_spring/deploy/ci/backend-ci.yml  /d/projects/iot_spring/.github/workflows/ci.yml
cp /d/projects/iot_spring/deploy/ci/frontend-ci.yml /d/projects/iot_angular/.github/workflows/ci.yml
```

**1.2 Przegląd, commit i push** – przejrzyj zmiany (`git status`, `git diff`) i wypchnij:

```bash
# backend + wdrożenie
cd /d/projects/iot_spring
git add -A
git add --chmod=+x deploy/scripts/*.sh      # skrypty wykonywalne także po git pull na Linuksie
git commit -m "v2.0: Spring Boot 4.1 / Java 25, TimescaleDB + Flyway, MQTT v1, security, Docker deployment"
git push

# frontend
cd /d/projects/iot_angular
git add -A
git commit -m "v2.0: devices, markers, route descriptions, MQTT v1 API, CSRF, Docker image"
git push

# firmware - secrets.h przestaje być śledzony (zostaje tylko u Ciebie na dysku)
cd /d/projects/iot_esp32
git rm --cached iot_esp32/secrets.h
git add -A
git commit -m "v2.0: MQTT v1 (JSON, device id, LWT), UTC time from GNSS, routes survive crashes"
git push
```

Po pushu wejdź na GitHub → zakładka **Actions** w `iot_spring` i `iot_angular`. Oba buildy powinny
być zielone (backend uruchamia też testy integracyjne z TimescaleDB w Dockerze – ok. 3–5 min).
Jeśli coś jest czerwone: kliknij build → krok z błędem; raporty testów są w *Artifacts → test-reports*.

> Repozytoria **prywatne**? Na serwerze będzie potrzebny dostęp – patrz krok 6.

---

## 2. Utworzenie maszyny w Oracle Cloud

1. Zaloguj się na <https://cloud.oracle.com>.
2. Sprawdź region w prawym górnym rogu – musi to być Twój **home region** (tylko tam działa darmowe A1).
3. Menu (☰) → **Compute** → **Instances** → **Create instance**.
4. **Name:** `iot-server`
   **Compartment:** zostaw domyślny (root).
5. **Placement:** zostaw domyślną *Availability domain* (AD-1). Jeśli później dostaniesz błąd
   *Out of capacity*, wróć tutaj i wybierz AD-2 / AD-3.
6. **Image and shape** → **Edit**:
   - **Change image** → **Canonical Ubuntu** → wersja **24.04** (nie „Minimal”) → *Select image*.
   - **Change shape** → **Virtual machine** → **Ampere** → zaznacz **VM.Standard.A1.Flex** →
     rozwiń strzałkę i ustaw **Number of OCPUs: 2**, **Amount of memory (GB): 12** → *Select shape*.
   - Przy kształcie powinna być etykieta **Always Free-eligible**.
7. **Networking**:
   - **Primary network:** *Create new virtual cloud network* (nazwa np. `iot-vcn`).
   - **Subnet:** *Create new public subnet*.
   - **Primary VNIC IP addresses:** zaznacz **Automatically assign public IPv4 address**.
8. **Add SSH keys**: wybierz **Generate a key pair for me** i kliknij
   **Save private key** (i najlepiej też *Save public key*).
   Zapisz plik np. jako `C:\Users\<Ty>\.ssh\oracle_iot.key`. **Bez niego nie zalogujesz się na serwer.**
9. **Boot volume**: zostaw domyślne (~47 GB). Darmowy limit to 200 GB łącznie.
10. **Advanced options** (na dole, jeśli widoczne) → **Oracle Cloud Agent** → upewnij się, że
    **Compute Instance Monitoring** jest **włączone**. Dzięki temu Oracle widzi zużycie pamięci,
    co chroni maszynę przed uznaniem jej za „bezczynną” (więcej w kroku 14).
11. Kliknij **Create**. Po 1–2 minutach status zmieni się na **Running**.
12. Na stronie instancji skopiuj **Public IP address** – będzie potrzebny wielokrotnie.
    Dalej w instrukcji: `<IP>`.

> **Out of capacity for shape VM.Standard.A1.Flex** – to częsty komunikat, Oracle nie ma akurat
> wolnych maszyn ARM. Spróbuj inną Availability Domain albo ponów za kilka godzin.

---

## 3. Otwarcie portów w Oracle (Security List)

Oracle ma dwie warstwy firewalla: w chmurze (Security List) i na samej maszynie (iptables, krok 5).
Trzeba otworzyć obie.

1. Na stronie instancji kliknij nazwę **Subnet** (sekcja *Primary VNIC*).
2. Zakładka **Security** (lub **Security Lists**) → kliknij **Default Security List for iot-vcn**.
3. **Security rules** / **Ingress Rules** → **Add Ingress Rules**. Dodaj 3 reguły
   (każda: *Source Type* = CIDR, *Source CIDR* = `0.0.0.0/0`, *IP Protocol* = TCP):

   | Destination Port Range | Opis |
   |---|---|
   | `80`   | HTTP (potrzebny do wystawienia certyfikatu Let's Encrypt) |
   | `443`  | HTTPS – aplikacja |
   | `8883` | MQTT z TLS – urządzenie ESP32 |

   Port `22` (SSH) jest już otwarty domyślnie. **Nie otwieraj** 5432 (Postgres) ani 1883.

---

## 4. DuckDNS – podpięcie domeny

1. Wejdź na <https://www.duckdns.org> i zaloguj się.
2. Przy domenie `iot-manager` wpisz w pole **current ip** adres `<IP>` i kliknij **update ip**.
3. Skopiuj **token** z góry strony – wpiszesz go sam w `.env` w kroku 7 (nie wklejaj go nigdzie indziej).

Sprawdzenie (na swoim komputerze, po minucie):
```bash
nslookup iot-manager.duckdns.org     # powinien zwrócić <IP>
```

---

## 5. Pierwsze logowanie i przygotowanie serwera

Na Windowsie użyj **Git Bash** (tak jak przy AWS).

```bash
chmod 600 ~/.ssh/oracle_iot.key
ssh -i ~/.ssh/oracle_iot.key ubuntu@<IP>
```

Przy pierwszym połączeniu wpisz `yes`. Od teraz komendy wykonujesz **na serwerze**.

### 5.1 Aktualizacja systemu

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git curl openssl
```

Jeśli pojawi się komunikat o wymaganym restarcie: `sudo reboot`, odczekaj minutę i zaloguj się ponownie.

### 5.2 Firewall na maszynie (iptables) – zrób to PRZED instalacją Dockera

Obrazy Ubuntu w Oracle mają własne reguły iptables, które blokują wszystko poza SSH.

```bash
sudo iptables -L INPUT --line-numbers -n
```

Zobaczysz listę reguł; jedna z ostatnich to `REJECT ... reject-with icmp-host-prohibited`
(zwykle numer **6**). Wstaw nowe reguły **przed nią** (podmień `6`, jeśli u Ciebie numer jest inny):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80   -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443  -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8883 -j ACCEPT
sudo netfilter-persistent save
sudo iptables -L INPUT --line-numbers -n    # nowe reguły muszą być nad REJECT
```

### 5.3 Instalacja Dockera

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit
```

Zaloguj się ponownie (`ssh -i ...`), żeby zadziałała grupa `docker`, i sprawdź:

```bash
docker run --rm hello-world
docker compose version
```

---

## 6. Pobranie kodu na serwer

```bash
mkdir -p ~/iot && cd ~/iot
git clone https://github.com/kanthakdominik/iot_spring.git
git clone https://github.com/kanthakdominik/iot_angular.git
```

Oba foldery **muszą** leżeć obok siebie w `~/iot` – docker-compose buduje frontend z `../../iot_angular`.

> **Repozytoria prywatne?** Najprościej: na GitHubie *Settings → Developer settings →
> Personal access tokens → Fine-grained tokens* → token z dostępem *Contents: Read-only* do obu repo.
> Przy `git clone` podaj login i jako hasło ten token. Żeby nie wpisywać go przy każdym `git pull`:
> `git config --global credential.helper store` (token zapisze się w `~/.git-credentials`).

---

## 7. Konfiguracja `.env`

```bash
cd ~/iot/iot_spring/deploy
cp .env.example .env
openssl rand -base64 24        # skopiuj wynik – to będzie hasło do bazy
nano .env
```

Uzupełnij:

```ini
DOMAIN=iot-manager.duckdns.org
ACME_EMAIL=twoj@email          # powiadomienia Let's Encrypt (opcjonalne)
DUCKDNS_SUBDOMAIN=iot-manager
DUCKDNS_TOKEN=<token z duckdns.org>
POSTGRES_PASSWORD=<wynik openssl rand>
ADMIN_PASSWORD=<hasło do logowania w aplikacji>   # konto admin tworzone przy pierwszym starcie
```

Zapis w nano: `Ctrl+O`, `Enter`, wyjście: `Ctrl+X`. Zabezpiecz plik:

```bash
chmod 600 .env
```

---

## 8. Certyfikaty MQTT

```bash
cd ~/iot/iot_spring/deploy
chmod +x scripts/*.sh
./scripts/gen-certs.sh
```

Powstanie folder `certs/` (nie trafia do gita):

| Folder | Zawartość | Użycie |
|---|---|---|
| `certs/ca/` | `ca.key`, `ca.crt` | Twój urząd certyfikacji – **`ca.key` nigdy nie udostępniaj** |
| `certs/mosquitto/` | certyfikat brokera dla `iot-manager.duckdns.org` | montowany do mosquitto |
| `certs/backend/` | certyfikat klienta `iot_spring` | montowany do backendu |
| `certs/esp32-1/` | certyfikat urządzenia + gotowy `secrets_certs.h` | do wgrania w ESP32 (krok 12) |

Kolejne urządzenie w przyszłości: `./scripts/gen-certs.sh esp32-2`.

---

## 9. Kopia pomiarów z pracy – przesłanie na serwer

Kopia z pracy (`iot_database_measurements_backup.sql`) to zrzut `pg_dump` w formacie *custom*
z PostgreSQL 18 (stary schemat). Wyślij ją z **Twojego komputera** (nowe okno Git Bash, nie na serwerze):

```bash
scp -i ~/.ssh/oracle_iot.key \
  "/d/projects/iot_thesis/Dominik_Kanthak_praca_magisterska/database_configuration/iot_database_measurements_backup.sql" \
  ubuntu@<IP>:~/iot/iot_spring/deploy/backups/initial.dump
```

Zaimportujesz ją po uruchomieniu systemu (krok 10.1) – trzeba najpierw, żeby backend utworzył nowy schemat.

---

## 10. Uruchomienie

```bash
cd ~/iot/iot_spring/deploy
docker compose up -d --build
```

Pierwsze budowanie trwa **5–15 minut** (pobieranie obrazów, zależności Mavena i npm, build Angulara).
Kolejne są dużo szybsze.

Podgląd:

```bash
docker compose ps                       # wszystkie kontenery powinny mieć status "running"/"healthy"
docker compose logs -f backend          # szukaj: "Successfully applied 1 migration" (Flyway),
                                        #         "Created initial administrator account 'admin'",
                                        #         "Subscribed to MQTT topics"
docker compose logs -f caddy            # szukaj: "certificate obtained successfully"
```

Wyjście z podglądu logów: `Ctrl+C` (kontenery działają dalej).

### 10.1 Import pomiarów z pracy (jednorazowo)

```bash
./scripts/import-legacy.sh
```

Skrypt odtwarza starą bazę do tymczasowej bazy `iot_legacy`, przenosi dane do nowego schematu i ją usuwa:
- stare trasy → urządzenie **`esp32-legacy`** („ESP32 (master's thesis)”), te same numery i nazwy
  (stare linki `/routes/<id>/map` działają dalej),
- stare czasy (lokalne z sieci GSM) są przeliczane z `Europe/Warsaw` na UTC,
- konta użytkowników – tylko te, których nazwy jeszcze nie ma (Twój nowy `admin` z `.env` zostaje).

Ponowne uruchomienie niczego nie zdubluje (skrypt wykrywa, że import już był).

---

## 11. Sprawdzenie

### 11.1 Automatyczny test

```bash
./scripts/smoke-test.sh
```

Wszystkie pozycje powinny mieć `OK`.

### 11.2 W przeglądarce

Otwórz **https://iot-manager.duckdns.org** – powinna być lista Twoich tras z pracy.
Sprawdź mapę i wykres dowolnej trasy, potem zaloguj się jako `admin`.

**Konto admina** to `ADMIN_USERNAME` / `ADMIN_PASSWORD` z `.env` (tworzone tylko przy pierwszym starcie,
gdy w bazie nie ma żadnego użytkownika).

Zmiana hasła później:
```bash
# 1) hash BCrypt (zamień NoweHaslo123)
docker run --rm httpd:2-alpine htpasswd -nbBC 10 admin 'NoweHaslo123'
#    wynik: admin:$2y$10$....  -> skopiuj część PO "admin:"
# 2) wpisz go do bazy
docker compose exec postgres psql -U postgres -d iot_database
```
```sql
UPDATE users SET password = '$2y$10$...wklej...' WHERE username = 'admin';
\q
```

Po 5 nieudanych próbach logowania konto jest blokowane na 15 minut dla danego adresu IP.

---

## 12. Urządzenie ESP32

Szczegóły firmware: `iot_esp32/README.md`.

1. Skopiuj plik z certyfikatami na swój komputer (Git Bash na komputerze):
   ```bash
   scp -i ~/.ssh/oracle_iot.key ubuntu@<IP>:~/iot/iot_spring/deploy/certs/esp32-1/secrets_certs.h .
   ```
2. W repozytorium `iot_esp32` skopiuj `iot_esp32/secrets.example.h` → `iot_esp32/secrets.h` i uzupełnij:
   `deviceId = "esp32-1"` (**musi** być równy nazwie certyfikatu), `apn` operatora, `broker = "iot-manager.duckdns.org"`,
   port `8883` i trzy bloki certyfikatów z `secrets_certs.h`.
3. Zainstaluj biblioteki (TinyGSM, SSLClient, PubSubClient, **ArduinoJson 7**), wgraj firmware i obserwuj:
   ```bash
   docker compose logs -f mosquitto backend
   ```
   Powinno pojawić się połączenie klienta `esp32-1`, w aplikacji urządzenie „online”, a po złapaniu
   pomiarów – nowa trasa `esp32-1 #N`.
4. Test: w aplikacji (jako admin) kliknij **New route** przy urządzeniu – dioda mignie 3 razy,
   a pomiary zaczną trafiać do nowej trasy bez restartu urządzenia.

Kolejne urządzenie: `./scripts/gen-certs.sh esp32-2` i `deviceId = "esp32-2"` w jego `secrets.h`.

> `secrets_certs.h` i `secrets.h` zawierają klucz prywatny – nie commituj ich do repozytorium.

---

## 13. Kopie zapasowe bazy

Codzienna kopia o 3:00, przechowywana 14 dni w `deploy/backups/`:

```bash
crontab -e      # przy pierwszym razie wybierz edytor nano (1)
```
Dopisz na końcu linię:
```
0 3 * * * bash /home/ubuntu/iot/iot_spring/deploy/scripts/backup.sh >> /home/ubuntu/iot/backup.log 2>&1
```

Ręcznie: `./scripts/backup.sh`. Od czasu do czasu ściągnij kopię na swój komputer:
```bash
scp -i ~/.ssh/oracle_iot.key "ubuntu@<IP>:~/iot/iot_spring/deploy/backups/*.dump" .
```

Odtworzenie kopii (TimescaleDB wymaga trybu odtwarzania; **nadpisuje dane!**):
```bash
docker compose stop backend
docker compose exec -T postgres dropdb -U postgres iot_database
docker compose exec -T postgres createdb -U postgres iot_database
docker compose exec -T postgres psql -U postgres -d iot_database -c "CREATE EXTENSION IF NOT EXISTS timescaledb; SELECT timescaledb_pre_restore();"
docker compose exec -T postgres pg_restore -U postgres -d iot_database --no-owner < backups/iot_XXXX.dump
docker compose exec -T postgres psql -U postgres -d iot_database -c "SELECT timescaledb_post_restore();"
docker compose start backend
```

---

## 14. Ochrona przed „bezczynnością” (Always Free)

Oracle może odebrać darmową maszynę, jeśli przez 7 dni **jednocześnie**: CPU (95. percentyl) < 20%,
sieć < 20% **i** pamięć < 20%. Wystarczy, że jeden warunek nie jest spełniony.

W tej konfiguracji backend rezerwuje na stałe 3 GB pamięci (`-Xms3g -Xmx3g -XX:+AlwaysPreTouch`),
a Postgres 1 GB buforów – razem ok. 35% z 12 GB.

Sprawdzenie: konsola Oracle → instancja → **Metrics** → wykres **Memory Utilization** powinien
stale pokazywać > 20%. Jeśli wykresu nie ma: zakładka **Oracle Cloud Agent** → włącz
**Compute Instance Monitoring**.

---

## 15. Aktualizacje (nowa wersja kodu)

Po wypchnięciu zmian na GitHub:

```bash
ssh -i ~/.ssh/oracle_iot.key ubuntu@<IP>
bash ~/iot/iot_spring/deploy/scripts/deploy.sh
```

Skrypt: `git pull` w obu repo → przebudowanie zmienionych obrazów → restart → `smoke-test.sh`.

---

## 16. Przydatne komendy

```bash
cd ~/iot/iot_spring/deploy
docker compose ps                      # stan kontenerów
docker compose logs -f backend         # logi (backend / caddy / mosquitto / postgres / duckdns)
docker compose restart backend         # restart jednego kontenera
docker compose down                    # zatrzymanie wszystkiego (dane zostają w wolumenach)
docker compose up -d                   # ponowne uruchomienie
docker compose exec postgres psql -U postgres -d iot_database    # konsola SQL
docker stats                           # zużycie CPU / RAM
df -h                                  # miejsce na dysku
```

> **Uwaga:** `docker compose down -v` usuwa także **wolumeny, czyli bazę danych**. Nie używaj `-v`,
> chyba że świadomie chcesz zacząć od zera (potem ponownie: krok 10.1 – import danych z pracy).

---

## 17. Rozwiązywanie problemów

| Objaw | Co sprawdzić |
|---|---|
| Strona się nie otwiera / timeout | Security List (krok 3) **i** iptables (krok 5.2); `docker compose ps`; `nslookup iot-manager.duckdns.org` zwraca `<IP>`? |
| Błąd certyfikatu HTTPS w przeglądarce | `docker compose logs caddy` – Let's Encrypt potrzebuje otwartego portu **80** i poprawnego DNS. Po poprawce: `docker compose restart caddy` |
| Lista tras pusta / „Failed to load routes” | `docker compose logs backend` (błąd Flyway / bazy?); czy wykonano import (krok 10.1) |
| ESP32 połączone, a pomiary nie przychodzą | uprawnienia ACL: `deviceId` w `secrets.h` musi być równy CN certyfikatu (`esp32-1`); `docker compose logs mosquitto` (wpisy *denied*); test bez ACL: zakomentuj `acl_file` w `mosquitto.conf` i `docker compose restart mosquitto` |
| Backend odrzuca wiadomości | `docker compose logs backend \| grep Rejected` – pokazuje powód i treść ramki (format: `docs/PROTOCOL.md`) |
| Backend się restartuje | `docker compose logs backend` – najczęściej brak certyfikatów w `certs/backend` (uruchom `gen-certs.sh`) albo złe hasło do bazy w `.env` |
| ESP32 nie łączy się z MQTT | port 8883 w Security List i iptables; `broker` w `secrets.h` = `iot-manager.duckdns.org`; certyfikaty z `certs/esp32-1`; `docker compose logs mosquitto` |
| Zmieniłem hasło w `.env`, a baza go nie przyjmuje | hasło Postgresa ustawia się tylko przy pierwszym starcie. Zmiana później: `ALTER USER postgres PASSWORD '...';` w psql |
| Brak miejsca na dysku | `docker system prune -a` (usuwa nieużywane obrazy; dane w wolumenach zostają) |

---

## 18. Co dalej: CI/CD

Już działa (**CI**): przy każdym `git push` GitHub Actions buduje backend (z testem na prawdziwym
PostgreSQL) i frontend – zakładka **Actions** w każdym repo. Dla repozytoriów publicznych GitHub
Actions jest darmowe bez limitu, dla prywatnych – 2000 minut miesięcznie.

Kolejne kroki (do zrobienia razem, gdy będziesz gotowy):

1. **Więcej testów** – testy jednostkowe `SensorDataValidator` (poprawne/błędne ramki z ESP32),
   testy `RouteService`, testy integracyjne z Testcontainers.
2. **CD (automatyczne wdrożenie)** – po zielonym CI na `main` GitHub łączy się przez SSH z serwerem
   i uruchamia `scripts/deploy.sh` (klucz SSH trzymany w *GitHub Secrets*).
3. **Test po wdrożeniu** – `smoke-test.sh` już jest ostatnim krokiem `deploy.sh`; w CD jego wynik
   zdecyduje, czy wdrożenie jest „zielone”.
4. **Obrazy w rejestrze** – zamiast budować na serwerze, GitHub buduje obrazy ARM i wysyła je do
   GitHub Container Registry (darmowe), a serwer tylko je pobiera.
