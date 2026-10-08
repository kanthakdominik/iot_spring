package com.project.iot_spring.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static com.project.iot_spring.ingest.TelemetryIngestService.utc;

/**
 * Handles "status" (retained, incl. Last Will) and "event" messages (docs/PROTOCOL.md, sections 2.2 and 2.3).
 */
@Slf4j
@Service
public class DeviceEventService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusMessage(Boolean online, String fw, Integer r, String reset, Integer bat_mv, Integer bat_pct) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventMessage(String type, Integer mv, Integer pct, Integer r, String reason, String ts, Boolean fix,
                        Double lat, Double lon, String label, String code, String msg) { }

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final TelemetryIngestService ingestService;

    public DeviceEventService(JdbcClient jdbc, JsonMapper jsonMapper, TelemetryIngestService ingestService) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.ingestService = ingestService;
    }

    @Transactional
    public void handleStatus(String deviceId, String payload, Instant receivedAt) {
        StatusMessage s = read(payload, StatusMessage.class);
        if (s.online() == null) {
            throw new InvalidMessageException("Status without 'online' field");
        }
        if (!s.online()) {
            // Last Will or clean shutdown; do not create unknown devices for it
            jdbc.sql("UPDATE devices SET online = false WHERE id = :id").param("id", deviceId).update();
            log.info("Device {} is offline", deviceId);
            return;
        }

        ingestService.touchDevice(deviceId, s.r(), receivedAt);
        jdbc.sql("""
                        UPDATE devices SET
                            fw_version  = COALESCE(:fw, fw_version),
                            last_reset  = COALESCE(:reset, last_reset),
                            battery_mv  = COALESCE(:mv, battery_mv),
                            battery_pct = COALESCE(:pct, battery_pct),
                            battery_at  = COALESCE(:at, battery_at)
                        WHERE id = :id
                        """)
                .param("fw", s.fw())
                .param("reset", s.reset())
                .param("mv", s.bat_mv())
                .param("pct", s.bat_pct())
                .param("at", s.bat_mv() != null ? utc(receivedAt) : null)
                .param("id", deviceId)
                .update();
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
                jdbc.sql("UPDATE devices SET battery_mv = :mv, battery_pct = :pct, battery_at = :at WHERE id = :id")
                        .param("mv", e.mv())
                        .param("pct", e.pct())
                        .param("at", utc(receivedAt))
                        .param("id", deviceId)
                        .update();
            }
            case "route-started" -> {
                if (e.r() == null || e.r() < 1) {
                    throw new InvalidMessageException("route-started without valid 'r'");
                }
                ingestService.touchDevice(deviceId, e.r(), receivedAt);
                log.info("Device {} started route {} (reason: {})", deviceId, e.r(), e.reason());
            }
            case "marker" -> handleMarker(deviceId, e, receivedAt);
            case "error" -> log.warn("Device {} reported error {}: {}", deviceId, e.code(), e.msg());
            default -> log.debug("Device {} sent unknown event type '{}'", deviceId, e.type());
        }
    }

    private void handleMarker(String deviceId, EventMessage e, Instant receivedAt) {
        if (e.r() == null || e.r() < 1) {
            throw new InvalidMessageException("marker without valid 'r'");
        }
        ingestService.touchDevice(deviceId, e.r(), receivedAt);
        long routeId = ingestService.findOrCreateRoute(deviceId, e.r());
        boolean hasPosition = Boolean.TRUE.equals(e.fix()) && e.lat() != null && e.lon() != null;
        jdbc.sql("""
                        INSERT INTO route_markers (route_id, ts, latitude, longitude, label, source)
                        VALUES (:routeId, :ts, :lat, :lon, :label, 'device')
                        """)
                .param("routeId", routeId)
                .param("ts", utc(parseOr(e.ts(), receivedAt)))
                .param("lat", hasPosition ? e.lat() : null)
                .param("lon", hasPosition ? e.lon() : null)
                .param("label", e.label() == null || e.label().isBlank() ? "marker" : truncate(e.label(), 200))
                .update();
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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
