package com.project.iot_spring.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.project.iot_spring.alert.AlertTrigger;
import com.project.iot_spring.common.Db;
import com.project.iot_spring.event.EventService;
import com.project.iot_spring.event.EventTypes;
import com.project.iot_spring.ingest.TelemetryIngestService.DeviceTouch;
import com.project.iot_spring.ingest.TelemetryIngestService.RouteRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.project.iot_spring.common.Db.utc;

/**
 * Handles "status" (retained, incl. Last Will) and "event" messages (docs/PROTOCOL.md, sections 2.2 and 2.3).
 * <ul>
 *   <li>event log: DEVICE_ONLINE / DEVICE_OFFLINE (only on a real change, the retained status is delivered again
 *       after every backend restart), BATTERY (first reading or a change of at least 5 points), DEVICE_ERROR,
 *       MARKER; ROUTE_STARTED is written when a route is created</li>
 *   <li>every battery / signal reading is stored in {@code device_health}</li>
 *   <li>alert triggers: battery readings and Last Will</li>
 * </ul>
 */
@Slf4j
@Service
public class DeviceEventService {

    /** Minimal change of the battery level (percentage points) that is written to the event log. */
    static final int BATTERY_EVENT_STEP = 5;
    /** AT+CSQ: 99 = not known or not detectable. */
    static final int CSQ_UNKNOWN = 99;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusMessage(Boolean online, String fw, Integer r, String reset, Integer bat_mv, Integer bat_pct,
                         Integer csq) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventMessage(String type, Integer mv, Integer pct, Integer csq, Integer r, String reason, String ts,
                        Boolean fix, Double lat, Double lon, String label, String code, String msg) { }

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final TelemetryIngestService ingestService;
    private final EventService eventService;
    private final ApplicationEventPublisher publisher;

    public DeviceEventService(JdbcClient jdbc, JsonMapper jsonMapper, TelemetryIngestService ingestService,
                              EventService eventService, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.ingestService = ingestService;
        this.eventService = eventService;
        this.publisher = publisher;
    }

    @Transactional
    public void handleStatus(String deviceId, String payload, Instant receivedAt) {
        StatusMessage s = read(payload, StatusMessage.class);
        if (s.online() == null) {
            throw new InvalidMessageException("Status without 'online' field");
        }
        if (!s.online()) {
            // Last Will or clean shutdown; do not create unknown devices for it, react only to a real change
            boolean wentOffline = !jdbc.sql("UPDATE devices SET online = false WHERE id = :id AND online RETURNING id")
                    .param("id", deviceId)
                    .query(String.class)
                    .list()
                    .isEmpty();
            if (wentOffline) {
                log.info("Device {} is offline", deviceId);
                eventService.log(deviceId, null, EventTypes.DEVICE_OFFLINE, EventTypes.WARN,
                        "Device went offline", null);
                ingestService.publishDevice(deviceId);
                publisher.publishEvent(new AlertTrigger.Offline(deviceId, receivedAt));
            }
            return;
        }

        DeviceTouch touch = ingestService.touchDevice(deviceId, s.r(), receivedAt);
        jdbc.sql("""
                        UPDATE devices SET
                            fw_version  = COALESCE(:fw, fw_version),
                            last_reset  = COALESCE(:reset, last_reset)
                        WHERE id = :id
                        """)
                .param("fw", s.fw(), Types.VARCHAR)
                .param("reset", s.reset(), Types.VARCHAR)
                .param("id", deviceId)
                .update();
        if (!Boolean.TRUE.equals(touch.wasOnline())) {
            Map<String, Object> data = new LinkedHashMap<>();
            putIfNotNull(data, "fw", s.fw());
            putIfNotNull(data, "r", s.r());
            putIfNotNull(data, "reset", s.reset());
            eventService.log(deviceId, null, EventTypes.DEVICE_ONLINE, EventTypes.INFO,
                    "Device online" + (s.fw() != null ? " (fw " + s.fw() + ", reset " + s.reset() + ")" : ""),
                    data);
        }
        recordHealth(deviceId, s.bat_mv(), s.bat_pct(), s.csq(), receivedAt);
        ingestService.publishDevice(deviceId);
        log.info("Device {} is online (fw {}, route {}, reset reason {})", deviceId, s.fw(), s.r(), s.reset());
    }

    @Transactional
    public void handleEvent(String deviceId, String payload, Instant receivedAt) {
        EventMessage e = read(payload, EventMessage.class);
        if (e.type() == null) {
            throw new InvalidMessageException("Event without 'type' field");
        }
        switch (e.type()) {
            case "battery" -> {
                ingestService.touchDevice(deviceId, null, receivedAt);
                recordHealth(deviceId, e.mv(), e.pct(), e.csq(), receivedAt);
                ingestService.publishDevice(deviceId);
            }
            case "route-started" -> {
                if (e.r() == null || e.r() < 1) {
                    throw new InvalidMessageException("route-started without valid 'r'");
                }
                ingestService.touchDevice(deviceId, e.r(), receivedAt);
                ingestService.findOrCreateRoute(deviceId, e.r(), e.reason() != null ? e.reason() : "device");
                ingestService.publishDevice(deviceId);
                log.info("Device {} started route {} (reason: {})", deviceId, e.r(), e.reason());
            }
            case "marker" -> handleMarker(deviceId, e, receivedAt);
            case "error" -> {
                ingestService.touchDevice(deviceId, null, receivedAt);
                log.warn("Device {} reported error {}: {}", deviceId, e.code(), e.msg());
                Map<String, Object> data = new LinkedHashMap<>();
                putIfNotNull(data, "code", e.code());
                putIfNotNull(data, "msg", e.msg());
                eventService.log(deviceId, null, EventTypes.DEVICE_ERROR, EventTypes.ERROR,
                        "Device error " + (e.code() != null ? e.code() : "?") + (e.msg() != null ? ": " + e.msg() : ""),
                        data);
            }
            default -> log.debug("Device {} sent unknown event type '{}'", deviceId, e.type());
        }
    }

    private void handleMarker(String deviceId, EventMessage e, Instant receivedAt) {
        if (e.r() == null || e.r() < 1) {
            throw new InvalidMessageException("marker without valid 'r'");
        }
        ingestService.touchDevice(deviceId, e.r(), receivedAt);
        RouteRef route = ingestService.findOrCreateRoute(deviceId, e.r(), null);
        boolean hasPosition = Boolean.TRUE.equals(e.fix()) && e.lat() != null && e.lon() != null;
        String label = e.label() == null || e.label().isBlank() ? "marker" : truncate(e.label(), 200);
        jdbc.sql("""
                        INSERT INTO route_markers (route_id, ts, latitude, longitude, label, source)
                        VALUES (:routeId, :ts, :lat, :lon, :label, 'device')
                        """)
                .param("routeId", route.id())
                .param("ts", utc(parseOr(e.ts(), receivedAt)))
                .param("lat", hasPosition ? e.lat() : null, Types.DOUBLE)
                .param("lon", hasPosition ? e.lon() : null, Types.DOUBLE)
                .param("label", label)
                .update();
        eventService.log(deviceId, route.id(), EventTypes.MARKER, EventTypes.INFO,
                "Marker '" + label + "' in route #" + e.r(), Map.of("label", label));
    }

    /**
     * Battery and / or GSM signal reading: updates the device, adds a {@code device_health} row, writes a BATTERY
     * event on the first reading or when the level differs by at least {@link #BATTERY_EVENT_STEP} points from the
     * last BATTERY event (so a slow discharge is logged too, without flooding the log) and publishes the
     * BATTERY_BELOW trigger.
     */
    private void recordHealth(String deviceId, Integer mv, Integer pct, Integer rawCsq, Instant at) {
        Integer csq = normalizeCsq(rawCsq);
        Integer batteryPct = pct != null && pct >= 0 && pct <= 100 ? pct : null;
        Integer batteryMv = mv != null && mv >= 0 && mv <= 10_000 ? mv : null;
        if (batteryMv == null && batteryPct == null && csq == null) {
            return;
        }

        if (batteryMv != null || batteryPct != null) {
            jdbc.sql("""
                            UPDATE devices SET battery_mv = :mv, battery_pct = :pct, battery_at = :at
                            WHERE id = :id""")
                    .param("mv", batteryMv, Types.INTEGER)
                    .param("pct", batteryPct, Types.INTEGER)
                    .param("at", utc(at))
                    .param("id", deviceId)
                    .update();
            Integer loggedPct = batteryPct == null ? null : lastLoggedBatteryPct(deviceId);
            if (batteryPct != null && (loggedPct == null || Math.abs(batteryPct - loggedPct) >= BATTERY_EVENT_STEP)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("pct", batteryPct);
                putIfNotNull(data, "mv", batteryMv);
                eventService.log(deviceId, null, EventTypes.BATTERY, EventTypes.INFO,
                        "Battery " + batteryPct + "%" + (batteryMv != null ? " (" + batteryMv + " mV)" : ""), data);
            }
            if (batteryPct != null) {
                publisher.publishEvent(new AlertTrigger.Battery(deviceId, at, batteryPct));
            }
        }
        if (csq != null) {
            jdbc.sql("UPDATE devices SET signal_csq = :csq, signal_at = :at WHERE id = :id")
                    .param("csq", csq)
                    .param("at", utc(at))
                    .param("id", deviceId)
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO device_health (device_id, ts, battery_mv, battery_pct, signal_csq)
                        VALUES (:id, :ts, :mv, :pct, :csq)
                        ON CONFLICT (device_id, ts) DO UPDATE SET
                            battery_mv  = COALESCE(EXCLUDED.battery_mv, device_health.battery_mv),
                            battery_pct = COALESCE(EXCLUDED.battery_pct, device_health.battery_pct),
                            signal_csq  = COALESCE(EXCLUDED.signal_csq, device_health.signal_csq)
                        """)
                .param("id", deviceId)
                .param("ts", utc(at))
                .param("mv", batteryMv, Types.INTEGER)
                .param("pct", batteryPct, Types.INTEGER)
                .param("csq", csq, Types.SMALLINT)
                .update();
    }

    /** Battery level of the last BATTERY event of the device (null = none yet). */
    private Integer lastLoggedBatteryPct(String deviceId) {
        return jdbc.sql("""
                        SELECT (data ->> 'pct')::int AS pct FROM device_events
                        WHERE device_id = :id AND type = :type
                        ORDER BY ts DESC, id DESC
                        LIMIT 1""")
                .param("id", deviceId)
                .param("type", EventTypes.BATTERY)
                .query((rs, n) -> Db.integer(rs, "pct"))
                .list()
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** 0..31 = valid, 99 or anything else = unknown (null). */
    static Integer normalizeCsq(Integer csq) {
        return csq != null && csq >= 0 && csq <= 31 ? csq : null;
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            T value = jsonMapper.readValue(payload, type);
            if (value == null) {
                throw new InvalidMessageException("Empty payload");
            }
            return value;
        } catch (JacksonException ex) {
            throw new InvalidMessageException("Malformed JSON: " + ex.getMessage());
        }
    }

    private static Instant parseOr(String ts, Instant fallback) {
        if (ts == null) {
            return fallback;
        }
        try {
            Instant parsed = Instant.parse(ts);
            return parsed.isBefore(TelemetryParser.OLDEST_PLAUSIBLE_TIME) ? fallback : parsed;
        } catch (DateTimeParseException ex) {
            return fallback;
        }
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
