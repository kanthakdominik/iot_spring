# Kontrakty systemu: MQTT v1 i REST API

Wspólna specyfikacja dla `iot_esp32` (firmware), `iot_spring` (backend) i `iot_angular` (frontend).
Zmiana w tym pliku = zmiana we wszystkich trzech repozytoriach.

---

## 1. Identyfikacja

| Pojęcie | Opis |
|---|---|
| `deviceId` | ID urządzenia, `^[a-z0-9-]{1,32}$`, np. `esp32-1`. **= CN certyfikatu klienta = MQTT client id = segment topicu.** |
| `r` | Lokalny numer trasy w urządzeniu (licznik w NVS, od 1). |
| `seq` | Numer próbki w trasie (od 0, rośnie o 1). Po restarcie awaryjnym kontynuowany. |
| `routeId` | ID trasy w bazie, nadawane przez serwer dla pary `(deviceId, r)`. |

### Kiedy urządzenie zaczyna nową trasę (`r + 1`, `seq = 0`)

| Zdarzenie | Nowa trasa? |
|---|---|
| Włączenie zasilania, przycisk RST (`esp_reset_reason()` = `POWERON`, `EXT`, `UNKNOWN`) | **tak** |
| Komenda `new-route` z serwera | **tak** (bez restartu) |
| Brownout, panic, watchdog, restart programowy, wybudzenie z deep sleep | **nie** – ta sama trasa, `seq` kontynuowany |

---

## 2. MQTT v1

Broker: Mosquitto, port **8883**, TLS z certyfikatem klienta (wymagany), `use_identity_as_username`.

| Topic | Nadawca | QoS | Retain | Treść |
|---|---|---|---|---|
| `radmon/v1/{deviceId}/telemetry` | urządzenie | 0 | nie | próbka (2.1) |
| `radmon/v1/{deviceId}/status` | urządzenie (+ Last Will) | 1 | **tak** | stan urządzenia (2.2) |
| `radmon/v1/{deviceId}/event` | urządzenie | 0 | nie | zdarzenia (2.3) |
| `radmon/v1/{deviceId}/cmd` | backend | 1 | nie | komendy (2.4) |

Backend subskrybuje: `radmon/v1/+/telemetry`, `radmon/v1/+/status`, `radmon/v1/+/event`.
Urządzenie subskrybuje: `radmon/v1/{deviceId}/cmd`.

### 2.1 `telemetry` – próbka (1 na sekundę, w rytmie licznika MightyOhm)

```json
{"v":1,"r":42,"seq":1537,"ts":"2026-10-08T19:56:03Z","tsrc":"gnss",
 "fix":true,"lat":52.218269,"lon":21.011198,"sats":9,"hdop":0.9,"spd":4.1,
 "cps":0,"cpm":18,"usv":0.10,"mode":"SLOW"}
```

| Pole | Typ | Wymagane | Opis / zakres |
|---|---|---|---|
| `v` | int | tak | wersja formatu = `1` |
| `r` | int | tak | ≥ 1 |
| `seq` | int | tak | ≥ 0 |
| `ts` | string | nie | czas UTC ISO-8601 z `Z`; brak/`null` → serwer przyjmuje czas odbioru |
| `tsrc` | string | nie | źródło czasu: `gnss`, `net` (sieć GSM), brak → `srv` |
| `fix` | bool | tak | czy pozycja GNSS jest ważna |
| `lat`, `lon` | number | gdy `fix` | −90..90, −180..180; przy `fix=false` pomijane |
| `sats` | int | nie | liczba satelitów użytych w rozwiązaniu |
| `hdop` | number | nie | dokładność (wg modemu) |
| `spd` | number | nie | prędkość wg GNSS |
| `cps` | int | tak | 0..65535 – zliczenia w ostatniej sekundzie |
| `cpm` | int | tak | 0..1000000 – zliczenia/min (uśrednione przez MightyOhm) |
| `usv` | number | tak | 0..1000 µSv/h |
| `mode` | string | tak | `SLOW`, `FAST`, `INST` |

Serwer: próbka z tym samym `(routeId, seq, ts)` jest ignorowana (deduplikacja przy ponownym wysłaniu).

### 2.2 `status` – stan urządzenia (retained)

Po każdym połączeniu z brokerem:
```json
{"online":true,"fw":"2.1.0","r":42,"reset":"POWERON","bat_mv":3980,"bat_pct":70,"csq":18}
```

| Pole | Opis |
|---|---|
| `bat_mv`, `bat_pct` | napięcie i szacowany poziom baterii |
| `csq` | od v2.1, opcjonalne: zasięg GSM z `AT+CSQ`, 0–31; `99` (nieznany) i tryb Wi-Fi → pole pominięte |
Last Will (wysyłany przez broker po zerwaniu połączenia):
```json
{"online":false}
```

### 2.3 `event` – zdarzenia

```json
{"type":"battery","mv":3980,"pct":70,"csq":17}
{"type":"route-started","r":43,"reason":"cmd"}
{"type":"marker","r":42,"seq":1600,"ts":"2026-10-08T20:01:00Z","fix":true,"lat":52.2,"lon":21.0,"label":"button"}
{"type":"error","code":"sensor-timeout","msg":"no data from MightyOhm for 5 s"}
```

`battery` – odpowiedź na komendę `battery`, a od v2.1 także **cyklicznie** co `HEALTH_INTERVAL` (domyślnie 10 min,
`config.h`), gdy urządzenie jest połączone z brokerem; `csq` jak w `status` (pomijane, gdy nieznane).
Backend zapisuje każdy odczyt baterii / zasięgu w historii urządzenia (`device_health`) i sprawdza regułę alertu
`BATTERY_BELOW`.

### 2.4 `cmd` – komendy (backend → urządzenie)

```json
{"cmd":"new-route"}
{"cmd":"battery"}
```

---

## 3. REST API (`/api`, JSON)

> **Wersja 2.1:** obowiązującym kontraktem REST (logowanie wymagane dla wszystkich GET, role ADMIN / VIEWER,
> stronicowanie, statystyki tras, udostępnianie, alerty, zdarzenia, import z SD, SSE `/api/stream`) jest
> [`V2.1-SPEC.md`](V2.1-SPEC.md), sekcja 3. Poniższa tabela opisuje v2.0.

Uwierzytelnianie: sesja (ciasteczko `JSESSIONID`). Ochrona CSRF: ciasteczko `XSRF-TOKEN` → nagłówek
`X-XSRF-TOKEN` przy POST/PUT/PATCH/DELETE (Angular robi to automatycznie przy względnych URL-ach).
Błędy: `application/problem+json` (RFC 9457).

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/auth/login` | – | `{username,password}` → `{username,role}`; 5 nieudanych prób → blokada 15 min (429) |
| POST | `/api/auth/logout` | – | |
| GET | `/api/auth/me` | – | `{username,role}` albo 401 |
| GET | `/api/routes?deviceId=` | – | lista tras (najnowsze pierwsze) |
| GET | `/api/routes/{id}` | – | trasa |
| PATCH | `/api/routes/{id}` | ADMIN | `{name?, description?}` |
| DELETE | `/api/routes/{id}` | ADMIN | trasa z pomiarami i znacznikami |
| GET | `/api/routes/{id}/measurements` | – | pomiary posortowane po czasie (także bez fixu) |
| DELETE | `/api/routes/{id}/measurements/{measurementId}` | ADMIN | |
| GET | `/api/routes/{id}/markers` | – | znaczniki |
| POST | `/api/routes/{id}/markers` | ADMIN | `{label, ts?, lat?, lon?}` |
| DELETE | `/api/routes/{id}/markers/{markerId}` | ADMIN | |
| GET | `/api/devices` | – | urządzenia ze statusem |
| PATCH | `/api/devices/{id}` | ADMIN | `{name}` |
| POST | `/api/devices/{id}/commands` | ADMIN | `{"cmd":"new-route"\|"battery"}` → 202 |
| GET | `/api/actuator/health` | – | stan aplikacji (bez szczegółów) |

### Obiekty

```jsonc
// Route
{"id":12,"deviceId":"esp32-1","deviceRouteNo":42,"name":"esp32-1 #42","description":null,
 "startedAt":"2026-10-08T19:50:00Z","endedAt":"2026-10-08T20:30:00Z","sampleCount":2400}
// Measurement
{"id":991,"seq":1537,"timestamp":"2026-10-08T19:56:03Z","timeSource":"gnss","gpsFix":true,
 "latitude":52.218269,"longitude":21.011198,"satellites":9,"hdop":0.9,"speed":4.1,
 "cps":0,"cpm":18,"usvPerHour":0.10,"mode":"SLOW"}
// Marker
{"id":3,"timestamp":"2026-10-08T20:01:00Z","latitude":52.2,"longitude":21.0,"label":"Am-241","source":"user"}
// Device
{"id":"esp32-1","name":"Miernik 1","online":true,"lastSeenAt":"2026-10-08T20:30:00Z",
 "fwVersion":"2.0.0","currentRouteNo":42,"batteryMv":3980,"batteryPct":70,"batteryAt":"2026-10-08T20:00:00Z"}
```
