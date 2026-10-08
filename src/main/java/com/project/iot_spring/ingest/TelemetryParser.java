package com.project.iot_spring.ingest;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Parses and validates telemetry payload v1 (docs/PROTOCOL.md, section 2.1).
 * <p>
 * Invalid messages throw {@link InvalidMessageException}. A missing or implausible device timestamp is
 * not an error: the sample keeps its data and gets the server receive time ({@code timeSource = "srv"}).
 */
@Component
public class TelemetryParser {

    public static final int SUPPORTED_VERSION = 1;
    static final Set<String> MODES = Set.of("SLOW", "FAST", "INST");
    static final Set<String> TIME_SOURCES = Set.of(Telemetry.TIME_GNSS, Telemetry.TIME_NETWORK);

    /** Anything older than this is a clock that was never set (e.g. 1980-01-06 from a cold GNSS). */
    static final Instant OLDEST_PLAUSIBLE_TIME = Instant.parse("2024-01-01T00:00:00Z");
    static final Duration MAX_CLOCK_AHEAD = Duration.ofHours(24);

    private final JsonMapper jsonMapper;

    public TelemetryParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Telemetry parse(String payload, Instant receivedAt) {
        TelemetryMessage m;
        try {
            m = jsonMapper.readValue(payload, TelemetryMessage.class);
        } catch (JacksonException e) {
            throw new InvalidMessageException("Malformed JSON: " + e.getMessage());
        }
        if (m == null) {
            throw new InvalidMessageException("Empty payload");
        }

        if (m.v() == null || m.v() != SUPPORTED_VERSION) {
            throw new InvalidMessageException("Unsupported payload version: " + m.v());
        }
        int routeNo = requireRange(m.r(), "r", 1, Integer.MAX_VALUE);
        int seq = requireRange(m.seq(), "seq", 0, Integer.MAX_VALUE);
        int cps = requireRange(m.cps(), "cps", 0, 65_535);
        int cpm = requireRange(m.cpm(), "cpm", 0, 1_000_000);
        double usv = requireRange(m.usv(), "usv", 0.0, 1_000.0);
        if (m.mode() == null || !MODES.contains(m.mode())) {
            throw new InvalidMessageException("Invalid mode: " + m.mode());
        }
        if (m.fix() == null) {
            throw new InvalidMessageException("Missing field: fix");
        }

        boolean fix = m.fix();
        Double lat = null;
        Double lon = null;
        if (fix) {
            lat = requireRange(m.lat(), "lat", -90.0, 90.0);
            lon = requireRange(m.lon(), "lon", -180.0, 180.0);
            if (lat == 0.0 && lon == 0.0) {
                // the modem reports 0,0 before the first fix
                fix = false;
                lat = null;
                lon = null;
            }
        }

        Instant ts = parseTimestamp(m.ts(), receivedAt);
        String timeSource;
        if (ts == null) {
            ts = receivedAt;
            timeSource = Telemetry.TIME_SERVER;
        } else {
            timeSource = TIME_SOURCES.contains(m.tsrc()) ? m.tsrc() : Telemetry.TIME_NETWORK;
        }

        return new Telemetry(
                routeNo, seq, ts, timeSource, fix, lat, lon,
                m.sats() != null && m.sats() >= 0 && m.sats() <= 255 ? m.sats().shortValue() : null,
                optionalFloat(m.hdop(), 0, 100),
                optionalFloat(m.spd(), 0, 2000),
                cps, cpm, usv, m.mode());
    }

    /** @return the device timestamp, or null when it is missing or not plausible */
    private static Instant parseTimestamp(String ts, Instant receivedAt) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            Instant instant = Instant.parse(ts);
            if (instant.isBefore(OLDEST_PLAUSIBLE_TIME) || instant.isAfter(receivedAt.plus(MAX_CLOCK_AHEAD))) {
                return null;
            }
            return instant;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int requireRange(Integer value, String field, int min, int max) {
        if (value == null) {
            throw new InvalidMessageException("Missing field: " + field);
        }
        if (value < min || value > max) {
            throw new InvalidMessageException("Field " + field + " out of range: " + value);
        }
        return value;
    }

    private static double requireRange(Double value, String field, double min, double max) {
        if (value == null || value.isNaN()) {
            throw new InvalidMessageException("Missing field: " + field);
        }
        if (value < min || value > max) {
            throw new InvalidMessageException("Field " + field + " out of range: " + value);
        }
        return value;
    }

    private static Float optionalFloat(Double value, double min, double max) {
        if (value == null || value.isNaN() || value < min || value > max) {
            return null;
        }
        return value.floatValue();
    }
}
