# Roadmapa rozwoju systemu pomiaru promieniowania (po pracy magisterskiej)

Ten plik zbiera **uzgodnione decyzje** i **plan prac**, żeby nic nie umknęło między sesjami.
Aktualizujemy go po każdym etapie (odhaczamy `[x]`, dopisujemy decyzje).

Repozytoria: `iot_spring` (backend + `deploy/` + ten plik), `iot_angular` (frontend), `iot_esp32` (firmware).
Praca: `iot_thesis` (tylko kontekst).

Sposób pracy: zmiany idą **bezpośrednio na `main`** (repo prywatne, jeden autor).
Claude przygotowuje zmiany etapami → Dominik przegląda, pushuje → **CI na GitHubie** weryfikuje
build i testy (środowisko Claude nie ma dostępu do Mavena/npm).

Charakter projektu: **edukacyjno-rozwojowy prototyp**, który ma docelowo stać się pełnoprawnym
produktem. Zmiany łamiące zgodność są dozwolone (nie ma jeszcze użytkowników ani wielu urządzeń).

**Aktualna faza: DESIGN & ANALIZA** – projektujemy i dokumentujemy, **bez implementacji**,
dopóki Dominik nie da zielonego światła.

---

## 1. Decyzje (stan na 2026-10-08)

| # | Temat | Decyzja |
|---|---|---|
| D1 | Hosting | Oracle Cloud Always Free, ARM A1 (2 OCPU / 12 GB), Docker Compose. Domena `iot-manager.duckdns.org`. Instrukcja: `deploy/README.md`. |
| D2 | Kolejność | Najpierw doprowadzamy system do „produkcyjnego” stanu (etapy 0–5), dopiero potem wdrażamy na Oracle. |
| D3 | Schemat bazy | **Flyway** (wersjonowane migracje) zamiast `ddl-auto=update`. |
| D4 | Identyfikacja urządzeń | Każde urządzenie ma **ID** (np. `esp32-1`) = CN certyfikatu = MQTT client id = segment w topicu. Zmiana obejmuje też firmware ESP32. |
| D5 | Trasy | **ID trasy w bazie nadaje serwer.** Urządzenie wysyła `(deviceId, lokalny numer trasy)`; backend mapuje tę parę na trasę. Zachowujemy semantykę z pracy: **reset przyciskiem = nowa trasa**. |
| D6 | Wersje | Java **25 LTS**, Spring Boot **4.1.x**, Angular **22**, PostgreSQL **18**. |
| D7 | Baza czasowa | **TimescaleDB** (rozszerzenie PostgreSQL 18): hypertable po czasie zamiast partycji „na trasę”. |
| D8 | Próbki bez fixu GPS | **Zapisujemy** z `gps_fix=false` – widoczne na wykresie czasowym (CPM, µSv/h), pomijane na mapie. |
| D9 | Zmiana protokołu | **Jednorazowe przełączenie** backendu i ESP32 na nowy format (JSON v1). Bez okresu przejściowego CSV. |
| D10 | Czas | Znacznik czasu **UTC z GNSS** (fallback: czas sieci GSM, oznaczony). W bazie `TIMESTAMPTZ`. |
| D11 | MQTT client id | Unikalny per urządzenie = `deviceId` (koniec z `"esp32_client"`). |
| D12 | Opis trasy | Później: użytkownik może dodać **opis trasy** w UI (pole `description`). |
| D13 | Start trasy | Mechanizm z sekcji 3.1: zasilanie/RST + **zewnętrzny przycisk** + komenda z UI; crash/brownout/watchdog **nie** tworzą nowej trasy. *(szczegóły przycisku do potwierdzenia – Q5)* |

---

## 2. Jak system działa dziś (analiza kodu)

### ESP32 (`iot_esp32/iot_esp32/iot_esp32.ino`)

- **Nowa trasa = każdy start mikrokontrolera.** `setup()` → `initRouteId()` zwiększa licznik `lastRoute`
  w NVS i zapisuje go. Dzieje się to przy **każdym** restarcie: włącznik, przycisk RESET,
  ale też **spadek napięcia (brownout), crash (panic) i watchdog**. Awaria w trakcie pomiaru
  dzieli więc trasę na dwie.
- **Podwójny reset (< 5 s)** → tryb offline (zapis na SD). Licznik trasy rośnie wtedy dwukrotnie
  (przy każdym z dwóch startów), więc w numeracji powstaje „dziura”.
- **Tryb offline zapisuje tylko** `routeId, <surowa linia z licznika>` – **bez czasu i GPS**,
  więc takich danych nie da się później pokazać na mapie.
- **Czas** pochodzi z sieci GSM (`getNetworkTime`), strefa czasowa jest ignorowana → backend zapisuje
  czas lokalny bez strefy (`LocalDateTime`).
- **GPS:** `readGpsData()` co sekundę wysyła `AT+SGPIO` i `enableGPS()` (zbędny narzut).
  Do czasu fixu wysyłane są `lat=0, lon=0` → backend **odrzuca** takie ramki (te pomiary przepadają).
- **MQTT:** `PubSubClient`, client id na sztywno `"esp32_client"` (dwa urządzenia by się wzajemnie
  rozłączały), publikacja QoS 0, brak Last Will. Topiki: `esp32/data`, `esp32/config` (komendy,
  obsługiwana tylko `battery`), `esp32/battery`.
- **Ramka:** CSV, 11 pól: `routeId, lat, lon, "YYYY-MM-DD HH:MM:SS", CPS, n, CPM, n, uSv/hr, x.xx, SLOW|FAST|INST`
  (ostatnie 7 pól to surowa linia z MightyOhm).
- Drobne: zduplikowany blok migania LED w `loop()`; `setup()` robi `return` przy braku sieci
  (dalej ratuje to `checkNetworkAndGPRS()` w pętli).

### Backend (`iot_spring`)

- `ProcessService` trzyma `currentRouteId` w pamięci → po restarcie backendu `save(new Route(id, "Route-<id>"))`
  **nadpisuje nazwę** nadaną ręcznie. `@Transactional` na metodzie prywatnej nie działa.
- Tabela `iot_data` partycjonowana `LIST(route_id)` z **ręcznie utworzonymi partycjami 1..1000** →
  trasa nr 1001 nie zapisze się.
- `findByRouteId` bez `ORDER BY`. Usuwanie dostępne dla każdego zalogowanego (bez sprawdzania roli).
- Hasło produkcyjne bazy jest w historii gita (`application-prod.properties`) → do zmiany.

---

## 3. Docelowy projekt

### 3.1 Urządzenia i trasy

#### Sprzęt – co mamy na płytce LILYGO T-SIM7600G-H (analiza zdjęć + dokumentacji LilyGo)

| Element | Funkcja | Czy nadaje się do sterowania trasą |
|---|---|---|
| Przycisk **RST** (obok modemu) | reset ESP32 (pin EN) | tak – jak dziś; ale restart = ponowne logowanie do sieci (~8–38 s) i **utrata fixu GPS** (~55 s) |
| Przycisk **PWR** (obok modemu) | aktywacja zasilania z akumulatora po jego podłączeniu | **nie** – funkcja zasilania |
| Przełącznik **Power** | odcina zasilanie całej płytki | tak (włączenie = nowa trasa), ale jest **wewnątrz obudowy** |
| Przycisk **BOOT** | brak na tej wersji płytki (programowanie przez USB robi auto-reset) | – |
| Przycisk S2 na MightyOhm | obsługiwany przez mikrokontroler licznika, niepodłączony do ESP32 | nie |

Wolne piny wg LilyGo: tylko **IO21 (SDA)** i **IO22 (SCL)** – warto je zostawić na **I²C dla przyszłego OLED**.
Dodatkowo **IO0** jest dziś zajęty jako `SENSOR_TX`, ale ESP32 **nic nie wysyła** do licznika (tylko czyta),
więc TX można wyłączyć (`-1`) i zwolnić IO0. IO0 to pin rozruchowy (*strapping*) – klasyczne miejsce na przycisk
„BOOT/USER” (zwarcie do GND, wewnętrzny pull-up). Jedyny skutek uboczny: przycisk **wciśnięty podczas resetu**
uruchamia tryb wgrywania firmware – to akurat bywa przydatne.

#### Proponowany mechanizm startu trasy

Urządzenie jest właścicielem **lokalnego numeru trasy `r`** (licznik w NVS). Nowa trasa powstaje **tylko świadomie**:

| Zdarzenie | Skutek | Jak rozpoznać |
|---|---|---|
| Włączenie zasilania / przycisk RST | **nowa trasa** (`r+1`) – semantyka z pracy zachowana | `esp_reset_reason() == ESP_RST_POWERON` (na klasycznym ESP32 reset pinem EN raportuje się tak samo jak włączenie zasilania – **do potwierdzenia testem, Q4**) |
| **Zewnętrzny przycisk – krótkie wciśnięcie** | **nowa trasa bez restartu** – modem i fix GPS zostają, brak przerwy w pomiarze | przerwanie na IO0 + debouncing |
| **Zewnętrzny przycisk – długie wciśnięcie (≥ 2 s)** | **znacznik (marker)** w trasie, np. „tu stoi źródło” – w UI widoczny na mapie i wykresie | jw. |
| Komenda z UI (`radmon/v1/{id}/cmd` → `new-route`) | nowa trasa zdalnie | MQTT |
| Brownout, crash (panic), watchdog, restart programowy | **ta sama trasa**, kontynuacja numeracji `seq` | `ESP_RST_BROWNOUT / PANIC / *_WDT / SW` |

- Przycisk: hermetyczny przycisk panelowy (IP67, np. 12–16 mm) w ściance obudowy Kradex, przewód do IO0 i GND.
  Daje obsługę **bez otwierania obudowy** (dziś trzeba ją otworzyć, żeby nacisnąć RST lub wyłączyć zasilanie).
- Sygnalizacja LED (IO12): miganie = brak fixu, ciągłe = fix; **3 szybkie mignięcia** = przyjęto nową trasę,
  **długie mignięcie** = zapisano znacznik.
- `seq` – numer próbki w trasie, w pamięci RTC (przetrwa restart programowy/crash) + okresowo w NVS.
- **Tryb offline przez podwójny reset znika.** Zastępuje go automatyczne *store-and-forward*:
  brak sieci → próbki (z czasem i GPS) na kartę SD → wysyłka po powrocie zasięgu (etap 7).
  Biblioteka DoubleResetDetector przestaje być potrzebna.

#### Backend

- Tabela `devices` + tabela `routes` z `id` generowanym przez bazę i unikalną parą
  `(device_id, device_route_no)`. Ingest robi *find-or-create* (upsert) – **nigdy nie nadpisuje nazwy**.
- Trasa ma: `name`, `description` (D12), `started_at`, `ended_at`, `samples`.
- Tabela `route_markers(route_id, ts, lat, lon, label)` – znaczniki z długiego wciśnięcia (lub dodane w UI).

#### Dodatkowe uwagi sprzętowe (backlog)
- `SENSOR_TX` na IO0 → do usunięcia (zwolnienie pinu, brak ryzyka przy rozruchu).
- Wyłącznik zasilania na zewnątrz obudowy (hermetyczny przełącznik w linii akumulatora).

### 3.2 Topiki MQTT (wersjonowane, ID urządzenia w topicu)

Zgodnie z dobrymi praktykami: hierarchia od ogólnego do szczegółowego, małe litery, ID urządzenia w każdym
topicu, rozdzielenie telemetrii i komend, wersja w ścieżce (łatwa zmiana formatu w przyszłości).

| Topic | Kierunek | QoS / retain | Treść |
|---|---|---|---|
| `radmon/v1/{deviceId}/telemetry` | urządzenie → backend | 0 (na start) | próbka pomiaru (JSON, niżej) |
| `radmon/v1/{deviceId}/status` | urządzenie → wszyscy | 1, **retained** | `{"online":true,"fw":"1.0.0","r":42}`; **Last Will**: `{"online":false}` |
| `radmon/v1/{deviceId}/cmd` | backend → urządzenie | 1 | `{"cmd":"battery"}`, później `new-route`, `set-interval` |
| `radmon/v1/{deviceId}/event` | urządzenie → backend | 0 | odpowiedzi na komendy, np. bateria, błędy |

- Backend subskrybuje **`$share/backend/radmon/v1/+/telemetry`** (shared subscription). Dziś jedna
  instancja, ale w przyszłości kilka instancji backendu podzieli się wiadomościami bez duplikatów.
- **ACL w Mosquitto** (z `use_identity_as_username` → username = CN certyfikatu):
  ```
  # każde urządzenie pisze/czyta tylko SWOJE topiki
  pattern write radmon/v1/%u/telemetry
  pattern write radmon/v1/%u/status
  pattern write radmon/v1/%u/event
  pattern read  radmon/v1/%u/cmd
  # backend
  user iot_spring
  topic read  radmon/v1/+/telemetry
  topic read  radmon/v1/+/status
  topic read  radmon/v1/+/event
  topic write radmon/v1/+/cmd
  ```
  Dodatkowo wymuszamy `clientId == deviceId` (backend sprawdza, że `deviceId` z topicu istnieje
  w tabeli `devices`). *(Do przetestowania: podstawianie `%u` przy uwierzytelnianiu certyfikatem.)*

### 3.3 Format próbki (payload v1)

JSON z krótkimi kluczami (czytelny, rozszerzalny, wersjonowany; ~150 B zamiast ~80 B dla CSV,
przy 1 próbce/s to ok. 13 MB/dobę transferu LTE):

```json
{"v":1,"r":42,"seq":1537,"ts":"2026-10-08T19:56:03Z",
 "lat":52.218269,"lon":21.011198,"fix":true,"sats":9,"hdop":0.9,"spd":4.1,
 "cps":0,"cpm":18,"usv":0.10,"mode":"SLOW"}
```

- `ts` – **czas UTC z GNSS** (dokładniejszy niż czas z sieci; gdy brak fixu – czas z sieci, oznaczony).
- `fix:false` → próbka **jest zapisywana** (przydatna na wykresie w czasie), ale nie trafia na mapę.
- `cps` – surowe zliczenia z ostatniej sekundy. To one pozwolą w przyszłości liczyć własną średnią
  kroczącą po stronie serwera i **zmniejszyć przesunięcie maksimum względem źródła** (problem z rozdz. 7.5 pracy).
- PubSubClient: zwiększyć bufor (`mqtt.setBufferSize(512)`), domyślnie 256 B.
- Backend: walidacja JSON (zakresy jak w obecnym `SensorDataValidator`), deduplikacja po
  `(route, seq)` → `ON CONFLICT DO NOTHING`.

### 3.4 Baza danych

**Decyzja: TimescaleDB** (D7). Porównanie, na podstawie którego wybraliśmy:

| | TimescaleDB | InfluxDB 3 | Zwykły PostgreSQL |
|---|---|---|---|
| Co to jest | rozszerzenie PostgreSQL | osobna baza czasowa | – |
| Relacje (użytkownicy, urządzenia, trasy) + pomiary w jednej bazie, JOIN-y | ✅ | ❌ (potrzebna druga baza) | ✅ |
| Spring Data JPA, Flyway, obecny kod | ✅ bez zmian | ❌ osobny klient | ✅ |
| Automatyczny podział na partycje po czasie (hypertable) | ✅ | ✅ | ręcznie |
| Kompresja starych danych, agregaty ciągłe (np. średnie minutowe do mapy) | ✅ | ✅ | ❌ |
| Grafana | ✅ (źródło PostgreSQL) | ✅ | ✅ |
| PostGIS (zapytania przestrzenne, np. „pomiary w promieniu 50 m od punktu”) | ✅ razem | ❌ | ✅ |
| Obraz Docker na ARM, PostgreSQL 18 | ✅ | ✅ | ✅ |
| Licencja | rdzeń Apache 2.0; kompresja/agregaty na licencji TSL – darmowe przy self-hostingu | MIT/Apache (wersja Core ma ograniczenia) | PostgreSQL |

Wniosek: TimescaleDB daje zalety bazy czasowej **bez drugiej bazy i bez przepisywania backendu**.
To odpowiada też na pytanie o partycje: zamiast 1000 partycji „na trasę” → **hypertable z automatycznymi
partycjami po czasie** (np. 7-dniowe) + indeks `(route_id, ts)`.

Model (szkic):

```
users(id, username, password_hash, role)
devices(id TEXT PK /* esp32-1 */, name, created_at, last_seen_at, fw_version)
routes(id BIGINT IDENTITY PK, device_id FK, device_route_no INT, name, description, started_at, ended_at, samples,
       UNIQUE(device_id, device_route_no))
route_markers(id, route_id FK, ts TIMESTAMPTZ, lat, lon, label)
measurements(route_id FK, ts TIMESTAMPTZ, seq INT, lat, lon, gps_fix BOOL, sats, hdop, speed,
             cps, cpm, usv_per_hr, mode,
             UNIQUE(route_id, seq, ts))         -- hypertable po ts
```

Migracja danych z pracy (Flyway `V2__import_legacy.sql` lub skrypt jednorazowy):
stare trasy → urządzenie `esp32-legacy`, `device_route_no = stary id`, nazwy bez zmian;
czasy interpretowane jako `Europe/Warsaw` → `TIMESTAMPTZ`; `seq` = kolejność po czasie.

---

## 4. Plan etapów

### Etap 0 – Siatka bezpieczeństwa (CI + testy)
- [ ] Wypchnąć CI z `deploy/ci/` do `.github/workflows/` w obu repo (instrukcja: `deploy/README.md`, krok 1.1)
- [ ] Testy jednostkowe walidatora ramek (poprawne / błędne / brzegowe)
- [ ] Testy integracyjne ingestu i REST API na prawdziwej bazie (**Testcontainers**)
- [ ] Zielone CI na `main`

### Etap 1 – Baza: Flyway + TimescaleDB + nowy model
- [ ] Flyway: `V1__schema.sql` (users, devices, routes, measurements jako hypertable)
- [ ] Migracja danych z pracy (legacy)
- [ ] Encje JPA + repozytoria bez natywnych zapytań do partycji; sortowanie po czasie
- [ ] `ddl-auto=validate`; usunięcie `schema.sql`/`data.sql` z partycjami
- [ ] `deploy/`: obraz `timescale/timescaledb` (pg18) zamiast `postgres:18`, init przez Flyway

### Etap 2 – Protokół MQTT v1 (backend + ESP32 razem)
- [ ] Backend: subskrypcja `$share/backend/radmon/v1/+/telemetry`, parser JSON, find-or-create trasy, deduplikacja
- [ ] Backend: status urządzeń (`status` retained + LWT) → `devices.last_seen_at`, online/offline
- [ ] Backend: komendy przez `radmon/v1/{id}/cmd` (bateria), odpowiedzi z `event`
- [ ] Mosquitto: ACL jak w 3.2; certyfikaty per urządzenie (`gen-certs.sh esp32-N`)
- [ ] ESP32: `DEVICE_ID`, client id = `DEVICE_ID`, nowe topiki, Last Will, JSON, `seq`,
      czas UTC z GNSS, zapis próbek bez fixu, `setBufferSize`
- [ ] ESP32: nowa trasa tylko przy POWERON/RESET (`esp_reset_reason()`), kontynuacja po crash/brownout
- [ ] ESP32: przycisk zewnętrzny na IO0 (krótko = nowa trasa, długo = znacznik), `SENSOR_TX` = -1, usunięcie DoubleResetDetector
- [ ] Sprzęt: przycisk panelowy IP67 w obudowie
- [ ] ESP32: GPS włączany raz w `setup()`; usunięcie zduplikowanego bloku LED
- [ ] (opcjonalnie, okres przejściowy) backend przyjmuje też stary format CSV na `esp32/data`

### Etap 3 – Java 25 + Spring Boot 4.1
- [ ] Spring Boot 3.4 → 3.5 (krok pośredni, usunięcie deprecjacji) → 4.0 → 4.1
- [ ] Spring Security 7, Jackson 3, Spring Integration 7 (MQTT), Lombok, Hibernate 7
- [ ] Docker: `eclipse-temurin:25-jre`, CI: Java 25

### Etap 4 – Angular 19 → 22
- [ ] `ng update` krok po kroku 19→20→21→22; ng-bootstrap, ng2-charts, Leaflet, Chart.js
- [ ] Widoki pod nowy model: urządzenie przy trasie, status online/offline, próbki bez fixu tylko na wykresie
- [ ] Mapa: renderer canvas (`preferCanvas`) – płynność przy tysiącach punktów

### Etap 5 – Bezpieczeństwo
- [ ] Zmiana hasła bazy z historii gita; hasła tylko w `.env` / zmiennych środowiskowych
- [ ] Ciasteczko sesji `Secure`, `HttpOnly`, `SameSite=Lax`; włączone CSRF (Angular `withXsrfConfiguration`)
- [ ] Autoryzacja ról: operacje usuwania/edycji tylko `ADMIN`
- [ ] Limit prób logowania
- [ ] Spring Boot Actuator: `/actuator/health` (do smoke testu i monitoringu), reszta zamknięta
- [ ] Dependabot (Maven, npm, Docker, GitHub Actions)
- [ ] Mosquitto ACL (z etapu 2)

### Etap 6 – Wdrożenie na Oracle Cloud
- [ ] Aktualizacja `deploy/` pod etapy 1–5 (TimescaleDB, Flyway, healthcheck)
- [ ] Postawienie maszyny i wdrożenie wg `deploy/README.md`
- [ ] Nowy firmware + certyfikat na ESP32, test terenowy

### Etap 7 – Dalej (backlog)
- [ ] **CD:** po zielonym CI GitHub Actions łączy się przez SSH i uruchamia `deploy.sh` + smoke test
- [ ] Obrazy budowane w CI (ARM) → GitHub Container Registry
- [ ] **Store-and-forward:** brak sieci → próbki (z czasem i GPS!) na SD → automatyczna wysyłka po powrocie zasięgu (dzięki `seq` bez duplikatów)
- [ ] Import tras z karty SD przez UI (np. lot samolotem)
- [ ] QoS 1 dla telemetrii (wymaga zmiany biblioteki MQTT na ESP32, np. `arduino-mqtt`; PubSubClient publikuje tylko QoS 0)
- [ ] Monitoring baterii: okresowy raport w `status`, powiadomienie o niskim poziomie
- [ ] Alerty progowe (np. > 0,3 µSv/h)
- [ ] Kompensacja przesunięcia maksimum: własne okno uśredniania z `cps` po stronie serwera
- [ ] Sieć hybrydowa: czujniki stacjonarne na wspólnej mapie z mobilnymi (bez tras)
- [ ] Wyświetlacz OLED, panel solarny
- [ ] Grafana (dashboardy na TimescaleDB)

---

## 5. Otwarte pytania

Rozstrzygnięte: ~~Q1 baza~~ → TimescaleDB (D7), ~~Q2 próbki bez GPS~~ → zapisujemy (D8),
~~Q3 okres przejściowy~~ → przełączenie naraz (D9).

- **Q4** – Jaki kod zwraca `esp_reset_reason()` po wciśnięciu RST na tej płytce (oczekiwane: `POWERON`) –
  test na sprzęcie (5 minut, wystarczy wypisać na Serial).
- **Q5** – Zewnętrzny przycisk na IO0 w obudowie: akceptujesz dokupienie hermetycznego przycisku i
  przewiercenie obudowy? Znaczenie długiego wciśnięcia: znacznik (propozycja) czy coś innego?
- **Q6** – Czy zostawić jakąkolwiek możliwość ręcznego wymuszenia trybu „tylko SD” (np. lot samolotem,
  tryb samolotowy modemu), czy wystarczy automatyczny store-and-forward?

## 5a. Kolejne elementy fazy design (do opracowania)

- [x] Architektura i decyzje (ten plik)
- [ ] Specyfikacja protokołu MQTT v1 (topiki, payloady wszystkich wiadomości, komendy, kody błędów)
- [ ] Maszyna stanów firmware ESP32 (start, sieć, GPS, trasa, przycisk, store-and-forward, LED)
- [ ] Model danych SQL (DDL TimescaleDB, indeksy, polityki kompresji/retencji) + migracja danych z pracy
- [ ] Specyfikacja REST API (endpointy, DTO, uprawnienia ról)
- [ ] Widoki frontendu (lista tras z urządzeniem, mapa, wykres, znaczniki, opis trasy, urządzenia online)

## 6. Źródła

- AWS – MQTT design best practices: <https://docs.aws.amazon.com/whitepapers/latest/designing-mqtt-topics-aws-iot-core/mqtt-design-best-practices.html>
- pi3g – MQTT topic tree design: <https://pi3g.com/mqtt-topic-tree-design-best-practices-tips-examples/>
- Client ID i nazewnictwo topiców MQTT: <https://tuneluj.pl/en/blog/client-id-i-nazewnictwo-topicow-mqtt>
- Time-series w 2026 (TimescaleDB, InfluxDB 3, QuestDB…): <https://pdpspectra.com/blog/timescaledb-vs-influxdb-2026>
- Spring Boot – wersje i wsparcie: <https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026>
- LilyGo T-SIM7600X (ESP32) – piny, przyciski: <https://github.com/Xinyuan-LilyGO/LilyGo-Modem-Series/blob/main/docs/en/esp32/sim7600-esp32/README.MD>
- Arduino-ESP32 – Reset Reason: <https://docs.espressif.com/projects/arduino-esp32/en/latest/api/reset_reason.html>
