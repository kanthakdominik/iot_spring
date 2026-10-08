package com.project.iot_spring.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Stores telemetry samples.
 * <ul>
 *   <li>the device row is created on first contact (only devices with a certificate signed by our CA can
 *       connect, and the broker ACL lets each device publish only under its own id)</li>
 *   <li>the route is found or created for the pair (deviceId, routeNo) - the server owns the route id and
 *       never overwrites a name given by the user</li>
 *   <li>a sample with an already stored (route, seq, ts) is ignored, so a device may safely resend data</li>
 * </ul>
 * Plain SQL (JdbcClient) is used on purpose: INSERT ... ON CONFLICT is the simplest way to get idempotent
 * writes into the TimescaleDB hypertable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryIngestService {

    private final JdbcClient jdbc;

    /** @return true when the sample was stored, false when it was a duplicate */
    @Transactional
    public boolean ingest(String deviceId, Telemetry t, Instant receivedAt) {
        touchDevice(deviceId, t.routeNo(), receivedAt);
        long routeId = findOrCreateRoute(deviceId, t.routeNo());

        int inserted = jdbc.sql("""
                        INSERT INTO measurements (route_id, ts, seq, time_source, gps_fix, latitude, longitude,
                                                  satellites, hdop, speed, cps, cpm, usv_per_hr, mode)
                        VALUES (:routeId, :ts, :seq, :timeSource, :gpsFix, :lat, :lon,
                                :sats, :hdop, :speed, :cps, :cpm, :usv, :mode)
                        ON CONFLICT (route_id, seq, ts) DO NOTHING
                        """)
                .param("routeId", routeId)
                .param("ts", utc(t.timestamp()))
                .param("seq", t.seq())
                .param("timeSource", t.timeSource())
                .param("gpsFix", t.gpsFix())
                .param("lat", t.latitude())
                .param("lon", t.longitude())
                .param("sats", t.satellites())
                .param("hdop", t.hdop())
                .param("speed", t.speed())
                .param("cps", t.cps())
                .param("cpm", t.cpm())
                .param("usv", t.usvPerHour())
                .param("mode", t.mode())
                .update();

        if (inserted == 0) {
            log.debug("Duplicate sample ignored: device={} r={} seq={}", deviceId, t.routeNo(), t.seq());
            return false;
        }

        jdbc.sql("""
                        UPDATE routes SET
                            sample_count = sample_count + 1,
                            started_at   = LEAST(started_at, :ts),
                            ended_at     = GREATEST(ended_at, :ts)
                        WHERE id = :routeId
                        """)
                .param("ts", utc(t.timestamp()))
                .param("routeId", routeId)
                .update();
        return true;
    }

    /** Creates the device on first contact and marks it as online / active on the given route. */
    void touchDevice(String deviceId, Integer currentRouteNo, Instant seenAt) {
        jdbc.sql("""
                        INSERT INTO devices (id, name, last_seen_at, online, current_route_no)
                        VALUES (:id, :id, :seenAt, true, :routeNo)
                        ON CONFLICT (id) DO UPDATE SET
                            last_seen_at     = EXCLUDED.last_seen_at,
                            online           = true,
                            current_route_no = COALESCE(EXCLUDED.current_route_no, devices.current_route_no)
                        """)
                .param("id", deviceId)
                .param("seenAt", utc(seenAt))
                .param("routeNo", currentRouteNo)
                .update();
    }

    /** @return database id of the route identified by (deviceId, routeNo), created when missing */
    long findOrCreateRoute(String deviceId, int routeNo) {
        jdbc.sql("""
                        INSERT INTO routes (device_id, device_route_no, name)
                        VALUES (:deviceId, :routeNo, :name)
                        ON CONFLICT (device_id, device_route_no) DO NOTHING
                        """)
                .param("deviceId", deviceId)
                .param("routeNo", routeNo)
                .param("name", defaultRouteName(deviceId, routeNo))
                .update();

        return jdbc.sql("SELECT id FROM routes WHERE device_id = :deviceId AND device_route_no = :routeNo")
                .param("deviceId", deviceId)
                .param("routeNo", routeNo)
                .query(Long.class)
                .single();
    }

    static String defaultRouteName(String deviceId, int routeNo) {
        return deviceId + " #" + routeNo;
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
